"""Multi-model pipeline orchestrator (v2).

Pipeline:
  [parallel: SituationAnalysis (LLM) + A→G pipeline]
  A) normalize → B) extract+mask (enhanced regex)
  → E?) identityBooster (gating)
  → C) segment (server) → C') refine long segments (LLM, conditional)
  → D) structureLabel (LLM #1) → D') RED enforce
  → join SituationAnalysis → filterRedFacts
  → Template Select
  → G) redact (server)
  → H) Final LLM #2 → I) unmask → validate → (ERROR: 1 retry)

Base case: 3 LLM calls (SituationAnalysis + StructureLabel + Final, +1 if long segments).
With gating: up to 5.
"""

import asyncio
import logging
import re
import time
from dataclasses import dataclass, field

from app.models.domain import (
    LabeledSegment,
    LabelStats,
    LlmCallResult,
    LockedSpan,
    PipelineStats,
    Segment,
    ValidationIssue,
)
from app.models.enums import (
    LockedSpanType,
    Purpose,
    SegmentLabelTier,
    Topic,
    ValidationIssueType,
)
from app.pipeline import multi_model_prompt_builder as prompt_builder_final
from app.pipeline.gating.situation_analysis_service import SituationAnalysisResult
from app.pipeline.preprocessing import locked_span_masker
from app.pipeline.redaction.redaction_service import RedactionResult
from app.pipeline.template.structure_template import StructureSection, StructureTemplate

logger = logging.getLogger(__name__)


# ===== Callback protocol =====


class PipelineProgressCallback:
    """Callback for reporting pipeline progress during analysis.
    Used by streaming service to send real-time SSE events.
    Default implementations are no-ops.
    """

    async def on_phase(self, phase: str) -> None:
        pass

    async def on_spans_extracted(self, spans: list[LockedSpan], masked_text: str) -> None:
        pass

    async def on_segmented(self, segments: list[Segment]) -> None:
        pass

    async def on_labeled(self, labeled_segments: list[LabeledSegment]) -> None:
        pass

    async def on_situation_analysis(self, fired: bool, result: SituationAnalysisResult | None) -> None:
        pass

    async def on_redacted(self, labeled_segments: list[LabeledSegment], red_count: int) -> None:
        pass

    async def on_template_selected(self, template: StructureTemplate, metadata_overridden: bool) -> None:
        pass


# ===== Result dataclasses =====


@dataclass(frozen=True)
class AnalysisPhaseResult:
    masked_text: str
    locked_spans: list[LockedSpan]
    segments: list[Segment]
    labeled_segments: list[LabeledSegment]
    redaction: RedactionResult
    situation_analysis: SituationAnalysisResult | None
    summary_text: str | None
    total_analysis_prompt_tokens: int
    total_analysis_completion_tokens: int
    identity_booster_fired: bool
    situation_analysis_fired: bool
    metadata_overridden: bool
    chosen_template_id: str
    chosen_template: StructureTemplate
    effective_sections: list[StructureSection]
    green_count: int
    yellow_count: int
    red_count: int
    yellow_recovery_applied: bool = False
    yellow_upgrade_count: int = 0


@dataclass(frozen=True)
class FinalPromptPair:
    system_prompt: str
    user_message: str
    locked_spans: list[LockedSpan]
    redaction_map: dict[str, str]


@dataclass(frozen=True)
class PipelineResult:
    transformed_text: str
    validation_issues: list[ValidationIssue] = field(default_factory=list)
    stats: PipelineStats | None = None


# ===== Dedupe key helper =====

_PLACEHOLDER_PATTERN = re.compile(r"\{\{([A-Z_]+)_(\d+)\}\}")


def build_dedupe_key(text: str | None) -> str | None:
    """Generate a deduplication key from segment text.

    1. Replace {{TYPE_N}} placeholders with lowercase "type_n" tokens
    2. Remove whitespace and punctuation
    3. Lowercase
    """
    if not text or not text.strip():
        return None

    def repl(m: re.Match) -> str:
        return m.group(1).lower() + "_" + m.group(2)

    result = _PLACEHOLDER_PATTERN.sub(repl, text)
    result = re.sub(r"[\s\p{P}]" if False else r"[\s!\"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~]", "", result)
    return result.lower()


# ===== Retryable warnings =====

_RETRYABLE_WARNINGS = frozenset({
    ValidationIssueType.CORE_NUMBER_MISSING,
    ValidationIssueType.CORE_DATE_MISSING,
    ValidationIssueType.SOFTEN_CONTENT_DROPPED,
    ValidationIssueType.SECTION_S2_MISSING,
    ValidationIssueType.INFORMAL_CONJUNCTION,
})


# ===== Span merge helper =====


def _merge_and_reindex_spans(
    regex_spans: list[LockedSpan],
    extra_spans: list[LockedSpan],
) -> list[LockedSpan]:
    """Merge regex + semantic spans and re-index with type-specific placeholders."""
    combined = sorted(list(regex_spans) + list(extra_spans), key=lambda s: s.start_pos)
    prefix_counters: dict[str, int] = {}
    reindexed: list[LockedSpan] = []
    for s in combined:
        prefix = s.type.placeholder_prefix
        prefix_counters[prefix] = prefix_counters.get(prefix, 0) + 1
        counter = prefix_counters[prefix]
        reindexed.append(LockedSpan(
            index=counter,
            original_text=s.original_text,
            placeholder=f"{{{{{prefix}_{counter}}}}}",
            type=s.type,
            start_pos=s.start_pos,
            end_pos=s.end_pos,
        ))
    return reindexed


# ===== Dynamic thinking budget =====


def compute_thinking_budget(
    segments: list[Segment],
    labeled_segments: list[LabeledSegment],
    original_text_length: int,
) -> int:
    """Compute dynamic thinking budget based on complexity signals."""
    score = 0
    if len(segments) >= 6:
        score += 1
    yellow = sum(1 for s in labeled_segments if s.label.tier == SegmentLabelTier.YELLOW)
    red = sum(1 for s in labeled_segments if s.label.tier == SegmentLabelTier.RED)
    if yellow >= 2 or red >= 1:
        score += 1
    if original_text_length >= 500:
        score += 1
    if score == 0:
        return 512
    if score <= 2:
        return 768
    return 1024


# ===== Pipeline execution =====


async def execute_analysis(
    original_text: str,
    user_prompt: str | None,
    sender_info: str | None,
    identity_booster_toggle: bool,
    topic: Topic | None = None,
    purpose: Purpose | None = None,
    ai_call_fn=None,
    callback: PipelineProgressCallback | None = None,
) -> AnalysisPhaseResult:
    """Run the analysis phase: preprocess → segment → label → SA → metadata override → template → redact.

    Args:
        ai_call_fn: async function(model, system, user, temp, max_tokens, analysis_context) -> LlmCallResult
        callback: progress callback for SSE streaming
    """
    from app.pipeline.gating import (
        gating_condition_evaluator,
        identity_lock_booster,
        situation_analysis_service,
    )
    from app.pipeline.labeling import red_label_enforcer, structure_label_service
    from app.pipeline.preprocessing import locked_span_extractor, text_normalizer
    from app.pipeline.redaction import redaction_service
    from app.pipeline.segmentation import llm_segment_refiner, meaning_segmenter
    from app.pipeline.template import template_selector
    from app.pipeline.template.template_registry import TemplateRegistry

    if ai_call_fn is None:
        from app.pipeline.ai_call_router import call_llm
        ai_call_fn = call_llm

    if callback is None:
        callback = PipelineProgressCallback()

    registry = TemplateRegistry()

    # A) Normalize
    await callback.on_phase("normalizing")
    normalized = text_normalizer.normalize(original_text)

    # B) Extract regex locked spans + mask
    await callback.on_phase("extracting")
    regex_spans = locked_span_extractor.extract(normalized)
    masked = locked_span_masker.mask(normalized, regex_spans)
    await callback.on_spans_extracted(regex_spans, masked)

    if regex_spans:
        logger.info("[Pipeline] Extracted %d regex locked spans", len(regex_spans))

    # Track tokens
    total_prompt_tokens = 0
    total_completion_tokens = 0
    booster_fired = False
    situation_fired = False
    metadata_overridden = False

    # Launch Situation Analysis async (runs in parallel with the rest)
    should_fire_situation = gating_condition_evaluator.should_fire_situation_analysis(masked)
    situation_task: asyncio.Task | None = None
    if should_fire_situation:
        situation_task = asyncio.create_task(
            situation_analysis_service.analyze_text_only(
                masked, sender_info, ai_call_fn, user_prompt=user_prompt,
            )
        )

    # E?) Identity Booster — launch async (does not block segmentation)
    booster_task: asyncio.Task | None = None
    if gating_condition_evaluator.should_fire_identity_booster(
        identity_booster_toggle, regex_spans, len(normalized),
    ):
        await callback.on_phase("identity_boosting")
        booster_task = asyncio.create_task(
            identity_lock_booster.boost(normalized, regex_spans, masked, ai_call_fn)
        )
    else:
        await callback.on_phase("identity_skipped")

    # C) Segment — uses regex-only masked text (unchanged)
    await callback.on_phase("segmenting")
    segments = meaning_segmenter.segment(masked)

    # C') Refine long segments with LLM (conditional)
    refine_result = await llm_segment_refiner.refine(segments, masked, ai_call_fn)
    if refine_result.prompt_tokens > 0:
        await callback.on_phase("segment_refining")
        segments = refine_result.segments
        total_prompt_tokens += refine_result.prompt_tokens
        total_completion_tokens += refine_result.completion_tokens
    else:
        await callback.on_phase("segment_refining_skipped")
    await callback.on_segmented(segments)

    # D) Structure Label (LLM #1)
    await callback.on_phase("labeling")
    label_result = await structure_label_service.label_text_only(segments, masked, ai_call_fn)
    total_prompt_tokens += label_result.prompt_tokens
    total_completion_tokens += label_result.completion_tokens

    # D') Server-side RED label enforcement
    enforced_labels = red_label_enforcer.enforce(label_result.labeled_segments)
    await callback.on_labeled(enforced_labels)

    # E?) Collect Booster result (labeling complete, merge spans)
    all_spans: list[LockedSpan] = list(regex_spans)
    if booster_task is not None:
        try:
            boost_result = await booster_task
            if boost_result.extra_spans:
                all_spans = _merge_and_reindex_spans(regex_spans, boost_result.extra_spans)
                booster_fired = True
            total_prompt_tokens += boost_result.prompt_tokens
            total_completion_tokens += boost_result.completion_tokens
        except Exception as e:
            logger.warning("[Pipeline] IdentityBooster failed, continuing without: %s", e)
        if booster_fired:
            await callback.on_spans_extracted(all_spans, masked)

    # Collect Situation Analysis result (before template selection for metadata override)
    situation_result: SituationAnalysisResult | None = None
    if should_fire_situation and situation_task is not None:
        await callback.on_phase("situation_analyzing")
        try:
            situation_result = await situation_task
        except Exception as e:
            from app.pipeline.ai_transform_service import AiTransformError
            if isinstance(e, AiTransformError):
                raise
            raise AiTransformError("상황 분석 중 오류가 발생했습니다.") from e

        # Filter RED-overlapping facts
        situation_result = situation_analysis_service.filter_red_facts(
            situation_result, masked, enforced_labels,
        )
        total_prompt_tokens += situation_result.prompt_tokens
        total_completion_tokens += situation_result.completion_tokens
        situation_fired = True
        await callback.on_situation_analysis(True, situation_result)
    else:
        await callback.on_phase("situation_skipped")
        await callback.on_situation_analysis(False, None)

    # Template Selection
    effective_topic = topic
    effective_purpose = purpose
    await callback.on_phase("template_selecting")
    label_stats = LabelStats.from_segments(enforced_labels)
    template_result = template_selector.select_template(
        registry, effective_topic, effective_purpose, label_stats, masked,
    )
    chosen_template = template_result.template
    effective_sections = template_result.effective_sections
    await callback.on_template_selected(chosen_template, metadata_overridden)

    # G) Redact
    await callback.on_phase("redacting")
    redaction = redaction_service.process(enforced_labels)
    await callback.on_redacted(enforced_labels, redaction.red_count)

    # Count tiers
    green_count = sum(1 for s in enforced_labels if s.label.tier == SegmentLabelTier.GREEN)
    yellow_count = redaction.yellow_count
    red_count = redaction.red_count

    logger.info(
        "[Pipeline] Analysis complete — segments=%d, GREEN=%d, YELLOW=%d, RED=%d, "
        "booster=%s, situation=%s, template=%s, override=%s",
        len(segments), green_count, yellow_count, red_count,
        booster_fired, situation_fired, chosen_template.id, metadata_overridden,
    )

    return AnalysisPhaseResult(
        masked_text=masked,
        locked_spans=all_spans,
        segments=segments,
        labeled_segments=enforced_labels,
        redaction=redaction,
        situation_analysis=situation_result,
        summary_text=label_result.summary_text,
        total_analysis_prompt_tokens=total_prompt_tokens,
        total_analysis_completion_tokens=total_completion_tokens,
        identity_booster_fired=booster_fired,
        situation_analysis_fired=situation_fired,
        metadata_overridden=metadata_overridden,
        chosen_template_id=chosen_template.id,
        chosen_template=chosen_template,
        effective_sections=effective_sections,
        green_count=green_count,
        yellow_count=yellow_count,
        red_count=red_count,
        yellow_recovery_applied=label_result.yellow_recovery_applied,
        yellow_upgrade_count=label_result.yellow_upgrade_count,
    )


def build_final_prompt(
    analysis: AnalysisPhaseResult,
    sender_info: str | None,
    rag_results=None,
) -> FinalPromptPair:
    """Build the final model prompts from analysis results.

    Assigns order (by start position) and dedupeKey to each segment.
    """
    # Assign order by start position
    sorted_segments = sorted(analysis.labeled_segments, key=lambda ls: ls.start)

    # Collect booster (SEMANTIC) spans for segment text masking
    booster_spans = [s for s in analysis.locked_spans if s.type == LockedSpanType.SEMANTIC]

    ordered_segments: list[prompt_builder_final.OrderedSegment] = []
    for i, ls in enumerate(sorted_segments):
        is_red = ls.label.tier == SegmentLabelTier.RED
        seg_text = ls.text if not is_red else None

        # Apply booster span placeholders to segment text
        if seg_text and booster_spans:
            for span in booster_spans:
                if ls.start <= span.start_pos < ls.end:
                    seg_text = seg_text.replace(span.original_text, span.placeholder, 1)

        dedupe_key = None if is_red else build_dedupe_key(seg_text)
        must_include = (
            prompt_builder_final.extract_placeholders(seg_text)
            if ls.label.tier == SegmentLabelTier.YELLOW
            else []
        )

        ordered_segments.append(prompt_builder_final.OrderedSegment(
            id=ls.segment_id,
            order=i + 1,
            tier=ls.label.tier.name,
            label=ls.label.name,
            text=seg_text,
            dedupe_key=dedupe_key,
            must_include=must_include,
        ))

    final_system = prompt_builder_final.build_final_system_prompt(
        analysis.chosen_template, analysis.effective_sections,
        rag_results=rag_results,
    )
    final_user = prompt_builder_final.build_final_user_message(
        sender_info,
        ordered_segments, analysis.locked_spans,
        analysis.situation_analysis, analysis.summary_text,
        analysis.chosen_template, analysis.effective_sections,
        rag_results=rag_results,
    )

    return FinalPromptPair(
        system_prompt=final_system,
        user_message=final_user,
        locked_spans=analysis.locked_spans,
        redaction_map=analysis.redaction.redaction_map,
    )


async def execute_final(
    final_model_name: str,
    analysis: AnalysisPhaseResult,
    original_text: str,
    sender_info: str | None,
    max_tokens: int,
    ai_call_fn=None,
    rag_results=None,
) -> PipelineResult:
    """Run the Final model using analysis results."""
    from app.pipeline.validation import output_validator

    if ai_call_fn is None:
        from app.pipeline.ai_call_router import call_llm
        ai_call_fn = call_llm

    start_time = time.monotonic()

    prompt = build_final_prompt(
        analysis, sender_info, rag_results=rag_results,
    )

    # Extract YELLOW segment texts for Rule 11
    yellow_texts = [s.text for s in analysis.labeled_segments if s.label.tier == SegmentLabelTier.YELLOW]

    # Compute thinking budget for Gemini models
    thinking_budget = None
    if final_model_name.startswith("gemini-"):
        thinking_budget = compute_thinking_budget(
            analysis.segments, analysis.labeled_segments, len(original_text),
        )

    # Call Final model (LLM #2)
    final_result: LlmCallResult = await ai_call_fn(
        final_model_name, prompt.system_prompt, prompt.user_message, -1, max_tokens, None,
        thinking_budget=thinking_budget,
    )

    # Unmask
    unmask_result = locked_span_masker.unmask(final_result.content, prompt.locked_spans)

    # Validate (with template info)
    validation = output_validator.validate_with_template(
        unmask_result.text, original_text, prompt.locked_spans,
        final_result.content, prompt.redaction_map, yellow_texts,
        analysis.chosen_template, analysis.effective_sections, analysis.labeled_segments,
    )

    retry_count = 0

    # Retry once on ERROR or retryable WARNING
    has_retryable_warning = any(
        i.severity.value == "WARNING" and i.type in _RETRYABLE_WARNINGS
        for i in validation.issues
    )

    if not validation.passed or has_retryable_warning:
        error_msgs = [i.message for i in validation.errors()]
        retryable_msgs = [
            i.message for i in validation.issues
            if i.severity.value == "WARNING" and i.type in _RETRYABLE_WARNINGS
        ]
        logger.warning(
            "[Pipeline] Final validation issues (errors: %s, retryable warnings: %s), retrying once",
            error_msgs, retryable_msgs,
        )
        retry_count = 1

        retry_hint = (
            "\n\n[검증 재시도 지침] 원문에 있던 숫자/날짜는 모두 유지하세요. "
            "SOFTEN 대상 내용을 삭제하지 말고 재작성하세요. "
            "S2(내부 확인/점검) 섹션이 있으면 반드시 포함하세요. "
            "구어체 접속사(어쨌든/아무튼/걍/근데)를 비즈니스 접속사로 대체하세요."
        )
        retry_system = prompt.system_prompt + retry_hint

        locked_span_hint = output_validator.build_locked_span_retry_hint(
            validation.issues, prompt.locked_spans,
        )

        all_issue_msgs = [
            i.message for i in validation.issues
            if i.severity.value == "ERROR" or i.type in _RETRYABLE_WARNINGS
        ]
        error_hint = "\n\n[시스템 검증 오류] " + "; ".join(all_issue_msgs)
        retry_user = prompt.user_message + error_hint + locked_span_hint

        retry_thinking = None
        if thinking_budget is not None:
            retry_thinking = min(1024, thinking_budget * 2)

        retry_result: LlmCallResult = await ai_call_fn(
            final_model_name, retry_system, retry_user, 0.3, max_tokens, None,
            thinking_budget=retry_thinking,
        )
        retry_unmask = locked_span_masker.unmask(retry_result.content, prompt.locked_spans)
        validation = output_validator.validate_with_template(
            retry_unmask.text, original_text, prompt.locked_spans,
            retry_result.content, prompt.redaction_map, yellow_texts,
            analysis.chosen_template, analysis.effective_sections, analysis.labeled_segments,
        )
        unmask_result = retry_unmask
        final_result = retry_result

    total_latency = int((time.monotonic() - start_time) * 1000)

    stats = PipelineStats(
        analysis_prompt_tokens=analysis.total_analysis_prompt_tokens,
        analysis_completion_tokens=analysis.total_analysis_completion_tokens,
        final_prompt_tokens=final_result.prompt_tokens,
        final_completion_tokens=final_result.completion_tokens,
        segment_count=len(analysis.segments),
        green_count=analysis.green_count,
        yellow_count=analysis.yellow_count,
        red_count=analysis.red_count,
        locked_span_count=len(analysis.locked_spans),
        retry_count=retry_count,
        identity_booster_fired=analysis.identity_booster_fired,
        situation_analysis_fired=analysis.situation_analysis_fired,
        metadata_overridden=analysis.metadata_overridden,
        chosen_template_id=analysis.chosen_template_id,
        total_latency_ms=total_latency,
        yellow_recovery_applied=analysis.yellow_recovery_applied,
        yellow_upgrade_count=analysis.yellow_upgrade_count,
    )

    return PipelineResult(
        transformed_text=unmask_result.text,
        validation_issues=validation.issues,
        stats=stats,
    )
