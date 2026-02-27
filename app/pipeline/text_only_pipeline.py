"""Text-only pipeline orchestrator.

Lightweight pipeline for keyboard/extension use. Input: original text only (+ optional sender).
No metadata (persona/contexts/tone). SA intent guides the final transform.
Template fixed to T01_GENERAL, tone fixed to POLITE.

Flow:
  原文 → preprocess → parallel(SA + segmentation→labeling) → T01 template → redact
  → build final prompt (SA intent based) → Final LLM → validate → (retry) → unmask

LLM calls: 2~3 (SA + Label + Final, Refiner conditional).
"""

import asyncio
import logging
import time

from app.core.config import settings
from app.models.domain import (
    LabeledSegment,
    LabelStats,
    LlmCallResult,
    LockedSpan,
    PipelineStats,
)
from app.models.enums import (
    LockedSpanType,
    Persona,
    SegmentLabelTier,
)
from app.pipeline import multi_model_prompt_builder as prompt_builder_final
from app.pipeline.gating.situation_analysis_service import SituationAnalysisResult
from app.pipeline.multi_model_pipeline import (
    _RETRYABLE_WARNINGS,
    PipelineResult,
    build_dedupe_key,
    compute_thinking_budget,
)
from app.pipeline.preprocessing import locked_span_masker
from app.pipeline.template.structure_template import StructureSection, StructureTemplate

logger = logging.getLogger(__name__)


async def execute(
    original_text: str,
    sender_info: str | None = None,
    user_prompt: str | None = None,
    ai_call_fn=None,
) -> PipelineResult:
    """Text-only transform pipeline. No metadata; SA intent drives the transform."""
    from app.pipeline.gating import situation_analysis_service
    from app.pipeline.labeling import red_label_enforcer, structure_label_service
    from app.pipeline.preprocessing import locked_span_extractor, text_normalizer
    from app.pipeline.redaction import redaction_service
    from app.pipeline.segmentation import llm_segment_refiner, meaning_segmenter
    from app.pipeline.template.template_registry import TemplateRegistry
    from app.pipeline.validation import output_validator

    if ai_call_fn is None:
        from app.pipeline.ai_call_router import call_llm
        ai_call_fn = call_llm

    registry = TemplateRegistry()
    start_time = time.monotonic()
    total_prompt_tokens = 0
    total_completion_tokens = 0

    # 1. Preprocessing (reuse)
    normalized = text_normalizer.normalize(original_text)
    spans = locked_span_extractor.extract(normalized)
    masked = locked_span_masker.mask(normalized, spans)

    if spans:
        logger.info("[TextOnlyPipeline] Extracted %d locked spans", len(spans))

    # 2. Parallel: SA + (Segmentation → Labeling)
    sa_task = asyncio.create_task(
        situation_analysis_service.analyze_text_only(
            masked, sender_info, ai_call_fn, user_prompt=user_prompt,
        )
    )

    # Segmentation
    segments = meaning_segmenter.segment(masked)

    # Refine long segments (conditional)
    refine_result = await llm_segment_refiner.refine(segments, masked, ai_call_fn)
    if refine_result.prompt_tokens > 0:
        segments = refine_result.segments
        total_prompt_tokens += refine_result.prompt_tokens
        total_completion_tokens += refine_result.completion_tokens

    # Labeling (text-only: no metadata)
    label_result = await structure_label_service.label_text_only(segments, masked, ai_call_fn)
    total_prompt_tokens += label_result.prompt_tokens
    total_completion_tokens += label_result.completion_tokens

    # RED enforcement
    enforced = red_label_enforcer.enforce(label_result.labeled_segments)

    # Collect SA result
    try:
        sa_result = await sa_task
    except Exception as e:
        from app.pipeline.ai_transform_service import AiTransformError
        if isinstance(e, AiTransformError):
            raise
        raise AiTransformError("상황 분석 중 오류가 발생했습니다.") from e

    total_prompt_tokens += sa_result.prompt_tokens
    total_completion_tokens += sa_result.completion_tokens

    # Filter RED-overlapping facts
    sa_result = situation_analysis_service.filter_red_facts(sa_result, masked, enforced)

    # 3. Template: T01 fixed + S2 enforcement
    label_stats = LabelStats.from_segments(enforced)
    template = registry.get_default()  # T01_GENERAL
    sections = _apply_s2_enforcement(list(template.section_order), label_stats)

    # 4. Redaction
    redaction = redaction_service.process(enforced)

    # 4b. Cushion strategy
    cushion_strategy = None
    if any(s.label.tier == SegmentLabelTier.YELLOW for s in enforced):
        try:
            from app.pipeline.cushion.cushion_strategy_service import generate as generate_cushion
            cushion_strategy = await generate_cushion(
                sa_result, enforced, template, sections, sender_info, ai_call_fn,
            )
            total_prompt_tokens += cushion_strategy.prompt_tokens
            total_completion_tokens += cushion_strategy.completion_tokens
        except Exception:
            logger.warning("[TextOnlyPipeline] Cushion failed, continuing without", exc_info=True)

    # 5. Build Final Prompt (SA intent based)
    ordered = _build_ordered_segments(enforced, spans)
    if cushion_strategy and cushion_strategy.strategies:
        system_prompt = _build_system_prompt_with_cushion(template, sections, sa_result, cushion_strategy)
    else:
        system_prompt = _build_system_prompt(template, sections, sa_result)
    user_message = _build_user_message(ordered, spans, sa_result, sender_info, template, sections)

    # 6. Final LLM → Validate → (Retry) → Unmask
    final_model = settings.gemini_final_model
    max_tokens = settings.openai_max_tokens_paid
    thinking_budget = compute_thinking_budget(segments, enforced, len(original_text))

    final_result: LlmCallResult = await ai_call_fn(
        final_model, system_prompt, user_message, -1, max_tokens, None,
        thinking_budget=thinking_budget,
    )

    # Unmask
    unmask_result = locked_span_masker.unmask(final_result.content, spans)

    # Validate (persona=OTHER since receiver is unknown)
    yellow_texts = [s.text for s in enforced if s.label.tier == SegmentLabelTier.YELLOW]
    validation = output_validator.validate_with_template(
        unmask_result.text, original_text, spans,
        final_result.content, Persona.OTHER, redaction.redaction_map, yellow_texts,
        template, sections, enforced,
    )

    retry_count = 0
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
            "[TextOnlyPipeline] Validation issues (errors: %s, retryable warnings: %s), retrying once",
            error_msgs, retryable_msgs,
        )
        retry_count = 1

        retry_hint = (
            "\n\n[검증 재시도 지침] 원문에 있던 숫자/날짜는 모두 유지하세요. "
            "SOFTEN 대상 내용을 삭제하지 말고 재작성하세요. "
            "S2(내부 확인/점검) 섹션이 있으면 반드시 포함하세요. "
            "구어체 접속사(어쨌든/아무튼/걍/근데)를 비즈니스 접속사로 대체하세요."
        )
        retry_system = system_prompt + retry_hint

        locked_span_hint = output_validator.build_locked_span_retry_hint(
            validation.issues, spans,
        )

        all_issue_msgs = [
            i.message for i in validation.issues
            if i.severity.value == "ERROR" or i.type in _RETRYABLE_WARNINGS
        ]
        error_hint = "\n\n[시스템 검증 오류] " + "; ".join(all_issue_msgs)
        retry_user = user_message + error_hint + locked_span_hint

        retry_thinking = None
        if thinking_budget is not None:
            retry_thinking = min(1024, thinking_budget * 2)

        retry_result: LlmCallResult = await ai_call_fn(
            final_model, retry_system, retry_user, 0.3, max_tokens, None,
            thinking_budget=retry_thinking,
        )
        retry_unmask = locked_span_masker.unmask(retry_result.content, spans)
        validation = output_validator.validate_with_template(
            retry_unmask.text, original_text, spans,
            retry_result.content, Persona.OTHER, redaction.redaction_map, yellow_texts,
            template, sections, enforced,
        )
        unmask_result = retry_unmask
        final_result = retry_result

    total_latency = int((time.monotonic() - start_time) * 1000)

    green_count = sum(1 for s in enforced if s.label.tier == SegmentLabelTier.GREEN)
    yellow_count = redaction.yellow_count
    red_count = redaction.red_count

    stats = PipelineStats(
        analysis_prompt_tokens=total_prompt_tokens,
        analysis_completion_tokens=total_completion_tokens,
        final_prompt_tokens=final_result.prompt_tokens,
        final_completion_tokens=final_result.completion_tokens,
        segment_count=len(segments),
        green_count=green_count,
        yellow_count=yellow_count,
        red_count=red_count,
        locked_span_count=len(spans),
        retry_count=retry_count,
        identity_booster_fired=False,
        situation_analysis_fired=True,
        metadata_overridden=False,
        chosen_template_id=template.id,
        total_latency_ms=total_latency,
    )

    logger.info(
        "[TextOnlyPipeline] Complete — segments=%d, GREEN=%d, YELLOW=%d, RED=%d, "
        "template=%s, latency=%dms",
        len(segments), green_count, yellow_count, red_count,
        template.id, total_latency,
    )

    return PipelineResult(
        transformed_text=unmask_result.text,
        validation_issues=validation.issues,
        stats=stats,
    )


# ===== Internal helpers =====


def _apply_s2_enforcement(
    sections: list[StructureSection],
    label_stats: LabelStats,
) -> list[StructureSection]:
    """Inject S2 if ACCOUNTABILITY or NEGATIVE_FEEDBACK present."""
    if (
        (label_stats.has_accountability or label_stats.has_negative_feedback)
        and StructureSection.S2_OUR_EFFORT not in sections
    ):
        insert_idx = -1
        if StructureSection.S1_ACKNOWLEDGE in sections:
            insert_idx = sections.index(StructureSection.S1_ACKNOWLEDGE)
        elif StructureSection.S0_GREETING in sections:
            insert_idx = sections.index(StructureSection.S0_GREETING)
        sections.insert(insert_idx + 1, StructureSection.S2_OUR_EFFORT)
    return sections


def _build_ordered_segments(
    labeled_segments: list[LabeledSegment],
    locked_spans: list[LockedSpan],
) -> list[prompt_builder_final.OrderedSegment]:
    """Sort segments by start pos, build OrderedSegment with dedupeKey/mustInclude."""
    sorted_segments = sorted(labeled_segments, key=lambda ls: ls.start)
    booster_spans = [s for s in locked_spans if s.type == LockedSpanType.SEMANTIC]

    ordered: list[prompt_builder_final.OrderedSegment] = []
    for i, ls in enumerate(sorted_segments):
        is_red = ls.label.tier == SegmentLabelTier.RED
        seg_text = ls.text if not is_red else None

        # Apply booster span placeholders (unlikely in text-only, but handle)
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

        ordered.append(prompt_builder_final.OrderedSegment(
            id=ls.segment_id,
            order=i + 1,
            tier=ls.label.tier.name,
            label=ls.label.name,
            text=seg_text,
            dedupe_key=dedupe_key,
            must_include=must_include,
        ))

    return ordered


def _build_system_prompt(
    template: StructureTemplate,
    sections: list[StructureSection],
    sa_result: SituationAnalysisResult,
) -> str:
    """Build system prompt: CORE + template sections + SA intent block + POLITE tone."""
    parts: list[str] = [prompt_builder_final.FINAL_CORE_SYSTEM_PROMPT]

    # Template section block (reuse)
    parts.append(prompt_builder_final._build_template_section_block(template, sections))

    # SA intent block (replaces persona + context blocks)
    if sa_result.intent:
        parts.append(
            "\n\n## 화자 목적\n"
            f"{sa_result.intent}\n"
            "위 목적을 중심으로, 사실은 정확히 전달하고 감정은 간접적으로 표현하며 "
            "수신자를 배려하는 자연스러운 비즈니스 톤으로 변환하세요."
        )

    # Fixed POLITE tone block (no persona/context blocks)
    parts.append(
        "\n\n## 말투: 공손 — 표준 비즈니스 존댓말. 자연스럽고 과하지 않은 정중함."
    )

    return "".join(parts)


def _build_system_prompt_with_cushion(
    template: StructureTemplate,
    sections: list[StructureSection],
    sa_result: SituationAnalysisResult,
    cushion_strategy,
) -> str:
    """Build system prompt with cushion strategy block appended."""
    from app.pipeline.cushion.cushion_strategy_service import CushionStrategy

    base = _build_system_prompt(template, sections, sa_result)
    if not isinstance(cushion_strategy, CushionStrategy) or not cushion_strategy.strategies:
        return base
    return base + _format_cushion_block(cushion_strategy)


def _format_cushion_block(cushion_strategy) -> str:
    """Format cushion strategy as a system prompt block."""
    parts: list[str] = ["\n\n## 쿠션 전략 (사전 분석 기반 — 반드시 적용)"]
    if cushion_strategy.overall_tone:
        parts.append(f"\n전체 톤: {cushion_strategy.overall_tone}")

    parts.append("\n\n### YELLOW 세그먼트별 쿠션 지침")
    for s in cushion_strategy.strategies:
        seg_id = s.get("segment_id", "?")
        label = s.get("label", "?")
        approach = s.get("approach", "")
        phrase = s.get("cushion_phrase", "")
        avoid = s.get("avoid", "")
        parts.append(f"\n**{seg_id} ({label})**:")
        parts.append(f'\n  접근: {approach}')
        parts.append(f'\n  쿠션: "{phrase}"')
        parts.append(f'\n  금지: {avoid}')

    if cushion_strategy.transition_notes:
        parts.append(f"\n\n전환 힌트: {cushion_strategy.transition_notes}")

    parts.append(
        "\n\n### 쿠션 적용 규칙"
        "\n- 위 쿠션 전략을 YELLOW 세그먼트 재작성 시 반드시 반영"
        "\n- 쿠션은 YELLOW 시작부에 자연스럽게 삽입. 쿠션이 본문보다 길면 안 됨"
        "\n- 종결어미 반복 금지: 쿠션 문장과 직후 사실 문장의 어미가 동일하면 변형"
        '\n- "습니다" 연속 3회 금지. 중간 문장을 명사형/연결형으로 전환'
    )
    return "".join(parts)


def _build_user_message(
    ordered_segments: list[prompt_builder_final.OrderedSegment],
    locked_spans: list[LockedSpan],
    sa_result: SituationAnalysisResult,
    sender_info: str | None,
    template: StructureTemplate,
    sections: list[StructureSection],
) -> str:
    """Build user message: SA facts/intent + JSON wrapper (no receiver/context/tone in meta)."""
    parts: list[str] = []

    # SA facts/intent
    if sa_result.facts or sa_result.intent:
        parts.append("--- 상황 분석 ---\n")
        if sa_result.facts:
            parts.append("사실:\n")
            for fact in sa_result.facts:
                parts.append(f"- {fact.content}")
                if fact.source:
                    parts.append(f' (원문: "{fact.source}")')
                parts.append("\n")
        if sa_result.intent:
            parts.append(f"의도: {sa_result.intent}\n")
        parts.append("\n")

    # JSON wrapper
    _esc = prompt_builder_final._escape_json
    sections_str = ",".join(s.name for s in sections)

    parts.append("```json\n")
    parts.append("{\n")

    # meta (minimal: tone + sender? + template + sections)
    parts.append("  \"meta\": {\n")
    parts.append('    "tone": "공손"')
    if sender_info and sender_info.strip():
        parts.append(f',\n    "sender": "{_esc(sender_info)}"')
    parts.append(f',\n    "template": "{_esc(template.id)}"')
    parts.append(f',\n    "sections": "{_esc(sections_str)}"')
    parts.append("\n  },\n")

    # segments
    parts.append('  "segments": [\n')
    for i, seg in enumerate(ordered_segments):
        parts.append(f'    {{"id":"{seg.id}"')
        parts.append(f',"order":{seg.order}')
        parts.append(f',"tier":"{seg.tier}"')
        parts.append(f',"label":"{seg.label}"')
        if seg.text is not None:
            parts.append(f',"text":"{_esc(seg.text)}"')
            parts.append(f',"dedupeKey":"{_esc(seg.dedupe_key or "")}"')
            if seg.must_include:
                joined = ",".join(f'"{_esc(p)}"' for p in seg.must_include)
                parts.append(f',"mustInclude":[{joined}]')
        else:
            parts.append(',"text":null,"dedupeKey":null')
        parts.append("}")
        if i < len(ordered_segments) - 1:
            parts.append(",")
        parts.append("\n")
    parts.append("  ],\n")

    # placeholders
    parts.append('  "placeholders": {')
    if locked_spans:
        parts.append("\n")
        for i, span in enumerate(locked_spans):
            parts.append(f'    "{span.placeholder}": "{_esc(span.original_text)}"')
            if i < len(locked_spans) - 1:
                parts.append(",")
            parts.append("\n")
        parts.append("  ")
    parts.append("}\n")

    parts.append("}\n")
    parts.append("```\n")

    return "".join(parts)
