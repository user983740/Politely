"""SSE streaming service for the transform pipeline.

Runs analysis with progress callbacks, streams final model deltas,
validates output, and sends stats/usage/done events.

Produces exact `event:\ndata:\n\n` format the frontend expects.
"""

import asyncio
import json
import logging
import time
from collections.abc import AsyncGenerator

import google.genai as genai
from google.genai.types import GenerateContentConfig, ThinkingConfig
from openai import AsyncOpenAI

from app.core.config import settings
from app.models.domain import LabeledSegment, LockedSpan, Segment
from app.models.enums import Purpose, SegmentLabelTier, Topic
from app.pipeline import cache_metrics_tracker
from app.pipeline.ai_call_router import call_llm
from app.pipeline.ai_transform_service import AiTransformError
from app.pipeline.gating.situation_analysis_service import SituationAnalysisResult
from app.pipeline.multi_model_pipeline import (
    PipelineProgressCallback,
    build_final_prompt,
    compute_thinking_budget,
    execute_analysis,
)
from app.pipeline.preprocessing import locked_span_masker
from app.pipeline.template.structure_template import StructureTemplate
from app.pipeline.validation import output_validator

logger = logging.getLogger(__name__)

_client: AsyncOpenAI | None = None
_gemini_client: genai.Client | None = None


def _get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        _client = AsyncOpenAI(api_key=settings.openai_api_key)
    return _client


def _get_gemini_client() -> genai.Client:
    global _gemini_client
    if _gemini_client is None:
        _gemini_client = genai.Client(api_key=settings.gemini_api_key)
    return _gemini_client


async def stream_transform(
    original_text: str,
    user_prompt: str | None,
    sender_info: str | None,
    identity_booster_toggle: bool,
    topic: Topic | None,
    purpose: Purpose | None,
    final_max_tokens: int,
) -> AsyncGenerator[dict, None]:
    """Async generator that yields SSE events for the full transform pipeline.

    Event names: phase, spans, maskedText, segments, labels, situationAnalysis,
    processedSegments, templateSelected, delta, retry, validationIssues, stats, usage, done, error
    """
    queue: asyncio.Queue[dict | None] = asyncio.Queue()

    async def push_event(event_name: str, data) -> None:
        await queue.put({"event": event_name, "data": data if isinstance(data, str) else json.dumps(data)})

    # Build progress callback
    class StreamCallback(PipelineProgressCallback):
        async def on_phase(self, phase: str) -> None:
            await push_event("phase", phase)

        async def on_spans_extracted(self, spans: list[LockedSpan], masked_text: str) -> None:
            spans_data = [
                {"placeholder": s.placeholder, "original": s.original_text, "type": s.type.name}
                for s in spans
            ]
            await push_event("spans", spans_data)
            await push_event("maskedText", masked_text)

        async def on_segmented(self, segments: list[Segment]) -> None:
            seg_data = [
                {"id": s.id, "text": s.text, "start": s.start, "end": s.end}
                for s in segments
            ]
            await push_event("segments", seg_data)

        async def on_labeled(self, labeled_segments: list[LabeledSegment]) -> None:
            labels_data = [
                {"segmentId": s.segment_id, "label": s.label.name, "tier": s.label.tier.name, "text": s.text}
                for s in labeled_segments
            ]
            await push_event("labels", labels_data)

        async def on_situation_analysis(self, fired: bool, result: SituationAnalysisResult | None) -> None:
            if fired and result is not None:
                sa_data = {
                    "facts": [{"content": f.content, "source": f.source} for f in result.facts],
                    "intent": result.intent,
                }
                await push_event("situationAnalysis", sa_data)

        async def on_redacted(self, labeled_segments: list[LabeledSegment], red_count: int) -> None:
            seg_data = [
                {
                    "id": ls.segment_id,
                    "tier": ls.label.tier.name,
                    "label": ls.label.name,
                    "text": None if ls.label.tier == SegmentLabelTier.RED else ls.text,
                }
                for ls in labeled_segments
            ]
            await push_event("processedSegments", seg_data)

        async def on_template_selected(self, template: StructureTemplate, metadata_overridden: bool) -> None:
            await push_event("templateSelected", {
                "templateId": template.id,
                "templateName": template.name,
                "metadataOverridden": metadata_overridden,
            })

    async def run_pipeline() -> None:
        try:
            start_time = time.monotonic()

            # 1. Run analysis phase with progress callbacks
            analysis = await execute_analysis(
                original_text,
                user_prompt, sender_info, identity_booster_toggle,
                topic, purpose,
                ai_call_fn=call_llm,
                callback=StreamCallback(),
            )

            # 1b. RAG retrieval (after analysis, before final prompt)
            rag_results = None
            if settings.rag_enabled:
                try:
                    from app.services.transform_app_service import _retrieve_rag

                    await push_event("phase", "rag_retrieving")
                    rag_results = await _retrieve_rag(
                        original_text, analysis,
                    )
                    if rag_results and not rag_results.is_empty():
                        await push_event("ragResults", {
                            "totalHits": rag_results.total_hits(),
                            "categories": {
                                cat: len(getattr(rag_results, cat))
                                for cat in [
                                    "expression_pool", "cushion", "forbidden",
                                    "policy", "example", "domain_context",
                                ]
                                if getattr(rag_results, cat)
                            },
                        })
                except Exception:
                    logger.warning("[Streaming] RAG retrieval failed, continuing without", exc_info=True)

            # 2. Build final prompt
            prompt = build_final_prompt(analysis, sender_info, rag_results=rag_results)

            # 3. Stream final model
            await push_event("phase", "generating")
            final_model = settings.gemini_final_model

            thinking_budget = None
            if final_model.startswith("gemini-"):
                thinking_budget = compute_thinking_budget(
                    analysis.segments, analysis.labeled_segments, len(original_text),
                )

            final_stream_result = await _stream_final_model(
                final_model, prompt.system_prompt, prompt.user_message,
                prompt.locked_spans, final_max_tokens, push_event,
                thinking_budget=thinking_budget,
            )

            # 4. Validate output
            await push_event("phase", "validating")

            yellow_texts = [
                s.text for s in analysis.labeled_segments
                if s.label.tier == SegmentLabelTier.YELLOW
            ]

            validation = output_validator.validate_with_template(
                final_stream_result["unmasked_text"], original_text, prompt.locked_spans,
                final_stream_result["raw_content"], prompt.redaction_map, yellow_texts,
                analysis.chosen_template, analysis.effective_sections, analysis.labeled_segments,
            )

            retry_count = 0
            active_result = final_stream_result

            # Retry once on ERROR only (no WARNING retry for streaming — deltas already sent)
            if not validation.passed:
                logger.warning(
                    "[Streaming] Validation errors: %s, retrying once",
                    [i.message for i in validation.errors()],
                )
                retry_count = 1

                await push_event("retry", "validation_failed")

                locked_span_hint = output_validator.build_locked_span_retry_hint(
                    validation.issues, prompt.locked_spans,
                )
                error_hint = "\n\n[시스템 검증 오류] " + "; ".join(
                    i.message for i in validation.errors()
                )
                retry_user = prompt.user_message + error_hint + locked_span_hint

                retry_thinking = min(1024, thinking_budget * 2) if thinking_budget else None

                active_result = await _stream_final_model(
                    final_model, prompt.system_prompt, retry_user,
                    prompt.locked_spans, final_max_tokens, push_event,
                    thinking_budget=retry_thinking,
                )

                validation = output_validator.validate_with_template(
                    active_result["unmasked_text"], original_text, prompt.locked_spans,
                    active_result["raw_content"], prompt.redaction_map, yellow_texts,
                    analysis.chosen_template, analysis.effective_sections, analysis.labeled_segments,
                )

            # 5. Send validation issues
            issues_data = [
                {
                    "type": issue.type.name,
                    "severity": issue.severity.name,
                    "message": issue.message,
                    "matchedText": issue.matched_text,
                }
                for issue in validation.issues
            ]
            await push_event("validationIssues", issues_data)
            await push_event("phase", "complete")

            # 6. Send stats
            total_latency = int((time.monotonic() - start_time) * 1000)
            await push_event("stats", {
                "segmentCount": len(analysis.segments),
                "greenCount": analysis.green_count,
                "yellowCount": analysis.yellow_count,
                "redCount": analysis.red_count,
                "lockedSpanCount": len(analysis.locked_spans),
                "retryCount": retry_count,
                "identityBoosterFired": analysis.identity_booster_fired,
                "situationAnalysisFired": analysis.situation_analysis_fired,
                "metadataOverridden": analysis.metadata_overridden,
                "chosenTemplateId": analysis.chosen_template_id,
                "latencyMs": total_latency,
                "yellowRecoveryApplied": analysis.yellow_recovery_applied,
                "yellowUpgradeCount": analysis.yellow_upgrade_count,
            })

            # 7. Send usage
            analysis_prompt = analysis.total_analysis_prompt_tokens
            analysis_completion = analysis.total_analysis_completion_tokens
            final_prompt = active_result["prompt_tokens"]
            final_completion = active_result["completion_tokens"]

            analysis_cost = (analysis_prompt * 0.15 + analysis_completion * 0.60) / 1_000_000
            final_cost = (final_prompt * 0.15 + final_completion * 0.60) / 1_000_000
            total_cost = analysis_cost + final_cost

            await push_event("usage", {
                "analysisPromptTokens": analysis_prompt,
                "analysisCompletionTokens": analysis_completion,
                "finalPromptTokens": final_prompt,
                "finalCompletionTokens": final_completion,
                "totalCostUsd": total_cost,
                "monthly": {
                    "mvp": total_cost * 1500,
                    "growth": total_cost * 6000,
                    "mature": total_cost * 20000,
                },
            })

            # 8. Send done
            await push_event("done", active_result["unmasked_text"])

        except AiTransformError as e:
            logger.error("Streaming transform failed: %s", e)
            await push_event("error", str(e))
        except Exception:
            logger.error("Streaming transform failed", exc_info=True)
            await push_event("error", "AI 변환 서비스에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
        finally:
            await queue.put(None)  # sentinel

    # Launch pipeline as background task
    task = asyncio.create_task(run_pipeline())

    # Yield events from queue
    try:
        while True:
            event = await queue.get()
            if event is None:
                break
            yield event
    finally:
        # Ensure the pipeline task completes even if the generator is cancelled
        if not task.done():
            try:
                await task
            except Exception:
                pass


async def stream_text_only(
    original_text: str,
    sender_info: str | None,
    user_prompt: str | None,
    final_max_tokens: int,
) -> AsyncGenerator[dict, None]:
    """Async generator that yields SSE events for the text-only transform pipeline.

    Reuses text_only_pipeline logic with streaming final model via _stream_final_model().
    """
    from app.models.domain import LabelStats
    from app.pipeline.gating import situation_analysis_service
    from app.pipeline.labeling import red_label_enforcer, structure_label_service
    from app.pipeline.preprocessing import locked_span_extractor, text_normalizer
    from app.pipeline.redaction import redaction_service
    from app.pipeline.segmentation import llm_segment_refiner, meaning_segmenter
    from app.pipeline.template.template_registry import TemplateRegistry
    from app.pipeline.text_only_pipeline import (
        _apply_s2_enforcement,
        _build_ordered_segments,
        _build_system_prompt,
        _build_system_prompt_with_cushion,
        _build_user_message,
    )

    queue: asyncio.Queue[dict | None] = asyncio.Queue()

    async def push_event(event_name: str, data) -> None:
        await queue.put({"event": event_name, "data": data if isinstance(data, str) else json.dumps(data)})

    async def run_pipeline() -> None:
        try:
            start_time = time.monotonic()
            total_prompt_tokens = 0
            total_completion_tokens = 0
            registry = TemplateRegistry()

            # 1. Preprocessing
            await push_event("phase", "normalizing")
            normalized = text_normalizer.normalize(original_text)
            spans = locked_span_extractor.extract(normalized)
            masked = locked_span_masker.mask(normalized, spans)

            spans_data = [
                {"placeholder": s.placeholder, "original": s.original_text, "type": s.type.name}
                for s in spans
            ]
            await push_event("spans", spans_data)
            await push_event("maskedText", masked)

            # 2. Parallel: SA + (Segmentation → Labeling)
            await push_event("phase", "situation_analyzing")
            sa_task = asyncio.create_task(
                situation_analysis_service.analyze_text_only(
                    masked, sender_info, call_llm, user_prompt=user_prompt,
                )
            )

            # Segmentation
            await push_event("phase", "segmenting")
            segments = meaning_segmenter.segment(masked)

            seg_data = [
                {"id": s.id, "text": s.text, "start": s.start, "end": s.end}
                for s in segments
            ]
            await push_event("segments", seg_data)

            # Refine long segments
            refine_result = await llm_segment_refiner.refine(segments, masked, call_llm)
            if refine_result.prompt_tokens > 0:
                segments = refine_result.segments
                total_prompt_tokens += refine_result.prompt_tokens
                total_completion_tokens += refine_result.completion_tokens

            # Labeling
            await push_event("phase", "labeling")
            label_result = await structure_label_service.label_text_only(segments, masked, call_llm)
            total_prompt_tokens += label_result.prompt_tokens
            total_completion_tokens += label_result.completion_tokens

            # RED enforcement
            enforced = red_label_enforcer.enforce(label_result.labeled_segments)

            labels_data = [
                {"segmentId": s.segment_id, "label": s.label.name, "tier": s.label.tier.name, "text": s.text}
                for s in enforced
            ]
            await push_event("labels", labels_data)

            # Collect SA result
            try:
                sa_result = await sa_task
            except Exception as e:
                if isinstance(e, AiTransformError):
                    raise
                raise AiTransformError("상황 분석 중 오류가 발생했습니다.") from e

            total_prompt_tokens += sa_result.prompt_tokens
            total_completion_tokens += sa_result.completion_tokens

            if sa_result.facts or sa_result.intent:
                sa_data = {
                    "facts": [{"content": f.content, "source": f.source} for f in sa_result.facts],
                    "intent": sa_result.intent,
                }
                await push_event("situationAnalysis", sa_data)

            # Filter RED-overlapping facts
            sa_result = situation_analysis_service.filter_red_facts(sa_result, masked, enforced)

            # 3. Template: T01 fixed + S2 enforcement
            await push_event("phase", "template_selecting")
            label_stats = LabelStats.from_segments(enforced)
            template = registry.get_default()
            sections = _apply_s2_enforcement(list(template.section_order), label_stats)

            await push_event("templateSelected", {
                "templateId": template.id,
                "templateName": template.name,
                "metadataOverridden": False,
            })

            # 4. Redaction
            await push_event("phase", "redacting")
            redaction = redaction_service.process(enforced)

            seg_redacted = [
                {
                    "id": ls.segment_id,
                    "tier": ls.label.tier.name,
                    "label": ls.label.name,
                    "text": None if ls.label.tier == SegmentLabelTier.RED else ls.text,
                }
                for ls in enforced
            ]
            await push_event("processedSegments", seg_redacted)

            # 4b. Cushion strategy (if YELLOW segments exist)
            cushion_strategy = None
            if any(s.label.tier == SegmentLabelTier.YELLOW for s in enforced):
                await push_event("phase", "cushion_strategizing")
                try:
                    from app.pipeline.cushion.cushion_strategy_service import generate as generate_cushion
                    cushion_strategy = await generate_cushion(
                        sa_result, enforced, template, sections, sender_info, call_llm,
                    )
                    total_prompt_tokens += cushion_strategy.prompt_tokens
                    total_completion_tokens += cushion_strategy.completion_tokens
                    if cushion_strategy.strategies:
                        await push_event("cushionStrategy", {
                            "overallTone": cushion_strategy.overall_tone,
                            "strategies": cushion_strategy.strategies,
                            "transitionNotes": cushion_strategy.transition_notes,
                        })
                except Exception:
                    logger.warning("[StreamTextOnly] Cushion failed, continuing without", exc_info=True)

            # 5. Build Final Prompt
            ordered = _build_ordered_segments(enforced, spans)
            if cushion_strategy and cushion_strategy.strategies:
                system_prompt = _build_system_prompt_with_cushion(template, sections, sa_result, cushion_strategy)
            else:
                system_prompt = _build_system_prompt(template, sections, sa_result)
            user_message = _build_user_message(ordered, spans, sa_result, sender_info, template, sections)

            # 6. Stream Final Model
            await push_event("phase", "generating")
            final_model = settings.gemini_final_model
            thinking_budget = None
            if final_model.startswith("gemini-"):
                thinking_budget = compute_thinking_budget(segments, enforced, len(original_text))

            final_stream_result = await _stream_final_model(
                final_model, system_prompt, user_message,
                spans, final_max_tokens, push_event,
                thinking_budget=thinking_budget,
            )

            # 7. Validate
            await push_event("phase", "validating")
            yellow_texts = [s.text for s in enforced if s.label.tier == SegmentLabelTier.YELLOW]

            validation = output_validator.validate_with_template(
                final_stream_result["unmasked_text"], original_text, spans,
                final_stream_result["raw_content"], redaction.redaction_map, yellow_texts,
                template, sections, enforced,
            )

            retry_count = 0
            active_result = final_stream_result

            if not validation.passed:
                logger.warning(
                    "[StreamTextOnly] Validation errors: %s, retrying once",
                    [i.message for i in validation.errors()],
                )
                retry_count = 1
                await push_event("retry", "validation_failed")

                locked_span_hint = output_validator.build_locked_span_retry_hint(
                    validation.issues, spans,
                )
                error_hint = "\n\n[시스템 검증 오류] " + "; ".join(
                    i.message for i in validation.errors()
                )

                retry_hint = (
                    "\n\n[검증 재시도 지침] 원문에 있던 숫자/날짜는 모두 유지하세요. "
                    "SOFTEN 대상 내용을 삭제하지 말고 재작성하세요. "
                    "S2(내부 확인/점검) 섹션이 있으면 반드시 포함하세요. "
                    "구어체 접속사(어쨌든/아무튼/걍/근데)를 비즈니스 접속사로 대체하세요."
                )
                retry_system = system_prompt + retry_hint
                retry_user = user_message + error_hint + locked_span_hint

                retry_thinking = min(1024, thinking_budget * 2) if thinking_budget else None

                active_result = await _stream_final_model(
                    final_model, retry_system, retry_user,
                    spans, final_max_tokens, push_event,
                    thinking_budget=retry_thinking,
                )

                validation = output_validator.validate_with_template(
                    active_result["unmasked_text"], original_text, spans,
                    active_result["raw_content"], redaction.redaction_map, yellow_texts,
                    template, sections, enforced,
                )

            # 8. Send validation issues
            issues_data = [
                {
                    "type": issue.type.name,
                    "severity": issue.severity.name,
                    "message": issue.message,
                    "matchedText": issue.matched_text,
                }
                for issue in validation.issues
            ]
            await push_event("validationIssues", issues_data)
            await push_event("phase", "complete")

            # 9. Send stats
            total_latency = int((time.monotonic() - start_time) * 1000)
            green_count = sum(1 for s in enforced if s.label.tier == SegmentLabelTier.GREEN)
            yellow_count = redaction.yellow_count
            red_count = redaction.red_count

            await push_event("stats", {
                "segmentCount": len(segments),
                "greenCount": green_count,
                "yellowCount": yellow_count,
                "redCount": red_count,
                "lockedSpanCount": len(spans),
                "retryCount": retry_count,
                "identityBoosterFired": False,
                "situationAnalysisFired": True,
                "metadataOverridden": False,
                "chosenTemplateId": template.id,
                "latencyMs": total_latency,
                "cushionApplied": cushion_strategy is not None and bool(getattr(cushion_strategy, 'strategies', None)),
            })

            # 10. Send usage
            analysis_prompt = total_prompt_tokens
            analysis_completion = total_completion_tokens
            final_prompt = active_result["prompt_tokens"]
            final_completion = active_result["completion_tokens"]

            analysis_cost = (analysis_prompt * 0.15 + analysis_completion * 0.60) / 1_000_000
            final_cost = (final_prompt * 0.15 + final_completion * 0.60) / 1_000_000
            total_cost = analysis_cost + final_cost

            await push_event("usage", {
                "analysisPromptTokens": analysis_prompt,
                "analysisCompletionTokens": analysis_completion,
                "finalPromptTokens": final_prompt,
                "finalCompletionTokens": final_completion,
                "totalCostUsd": total_cost,
                "monthly": {
                    "mvp": total_cost * 1500,
                    "growth": total_cost * 6000,
                    "mature": total_cost * 20000,
                },
            })

            # 11. Send done
            await push_event("done", active_result["unmasked_text"])

        except AiTransformError as e:
            logger.error("Stream text-only transform failed: %s", e)
            await push_event("error", str(e))
        except Exception:
            logger.error("Stream text-only transform failed", exc_info=True)
            await push_event("error", "AI 변환 서비스에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
        finally:
            await queue.put(None)

    task = asyncio.create_task(run_pipeline())

    try:
        while True:
            event = await queue.get()
            if event is None:
                break
            yield event
    finally:
        if not task.done():
            try:
                await task
            except Exception:
                pass


async def stream_text_only_ab(
    original_text: str,
    sender_info: str | None,
    user_prompt: str | None,
    final_max_tokens: int,
) -> AsyncGenerator[dict, None]:
    """A/B test: runs both baseline (A) and cushion-strategy-enhanced (B) transforms.

    Shared analysis phase, then:
    - Variant A streams with delta events (+ cushion generation in parallel)
    - Variant B streams with delta_b events using cushion-enhanced prompt
    """
    from app.models.domain import LabelStats
    from app.pipeline.cushion.cushion_strategy_service import generate as generate_cushion
    from app.pipeline.gating import situation_analysis_service
    from app.pipeline.labeling import red_label_enforcer, structure_label_service
    from app.pipeline.preprocessing import locked_span_extractor, text_normalizer
    from app.pipeline.redaction import redaction_service
    from app.pipeline.segmentation import llm_segment_refiner, meaning_segmenter
    from app.pipeline.template.template_registry import TemplateRegistry
    from app.pipeline.text_only_pipeline import (
        _apply_s2_enforcement,
        _build_ordered_segments,
        _build_system_prompt,
        _build_system_prompt_with_cushion,
        _build_user_message,
    )

    queue: asyncio.Queue[dict | None] = asyncio.Queue()

    async def push_event(event_name: str, data) -> None:
        await queue.put({"event": event_name, "data": data if isinstance(data, str) else json.dumps(data)})

    async def run_pipeline() -> None:
        try:
            start_time = time.monotonic()
            total_prompt_tokens = 0
            total_completion_tokens = 0
            registry = TemplateRegistry()

            # ===== SHARED ANALYSIS (same as stream_text_only steps 1-4) =====

            # 1. Preprocessing
            await push_event("phase", "normalizing")
            normalized = text_normalizer.normalize(original_text)
            spans = locked_span_extractor.extract(normalized)
            masked = locked_span_masker.mask(normalized, spans)

            spans_data = [
                {"placeholder": s.placeholder, "original": s.original_text, "type": s.type.name}
                for s in spans
            ]
            await push_event("spans", spans_data)
            await push_event("maskedText", masked)

            # 2. Parallel: SA + (Segmentation → Labeling)
            await push_event("phase", "situation_analyzing")
            sa_task = asyncio.create_task(
                situation_analysis_service.analyze_text_only(
                    masked, sender_info, call_llm, user_prompt=user_prompt,
                )
            )

            await push_event("phase", "segmenting")
            segments = meaning_segmenter.segment(masked)

            seg_data = [
                {"id": s.id, "text": s.text, "start": s.start, "end": s.end}
                for s in segments
            ]
            await push_event("segments", seg_data)

            refine_result = await llm_segment_refiner.refine(segments, masked, call_llm)
            if refine_result.prompt_tokens > 0:
                segments = refine_result.segments
                total_prompt_tokens += refine_result.prompt_tokens
                total_completion_tokens += refine_result.completion_tokens

            await push_event("phase", "labeling")
            label_result = await structure_label_service.label_text_only(segments, masked, call_llm)
            total_prompt_tokens += label_result.prompt_tokens
            total_completion_tokens += label_result.completion_tokens

            enforced = red_label_enforcer.enforce(label_result.labeled_segments)

            labels_data = [
                {"segmentId": s.segment_id, "label": s.label.name, "tier": s.label.tier.name, "text": s.text}
                for s in enforced
            ]
            await push_event("labels", labels_data)

            try:
                sa_result = await sa_task
            except Exception as e:
                if isinstance(e, AiTransformError):
                    raise
                raise AiTransformError("상황 분석 중 오류가 발생했습니다.") from e

            total_prompt_tokens += sa_result.prompt_tokens
            total_completion_tokens += sa_result.completion_tokens

            if sa_result.facts or sa_result.intent:
                sa_data = {
                    "facts": [{"content": f.content, "source": f.source} for f in sa_result.facts],
                    "intent": sa_result.intent,
                }
                await push_event("situationAnalysis", sa_data)

            sa_result = situation_analysis_service.filter_red_facts(sa_result, masked, enforced)

            await push_event("phase", "template_selecting")
            label_stats = LabelStats.from_segments(enforced)
            template = registry.get_default()
            sections = _apply_s2_enforcement(list(template.section_order), label_stats)

            await push_event("templateSelected", {
                "templateId": template.id,
                "templateName": template.name,
                "metadataOverridden": False,
            })

            await push_event("phase", "redacting")
            redaction = redaction_service.process(enforced)

            seg_redacted = [
                {
                    "id": ls.segment_id,
                    "tier": ls.label.tier.name,
                    "label": ls.label.name,
                    "text": None if ls.label.tier == SegmentLabelTier.RED else ls.text,
                }
                for ls in enforced
            ]
            await push_event("processedSegments", seg_redacted)

            # Build shared prompt parts
            ordered = _build_ordered_segments(enforced, spans)
            system_prompt_a = _build_system_prompt(template, sections, sa_result)
            user_message = _build_user_message(ordered, spans, sa_result, sender_info, template, sections)

            final_model = settings.gemini_final_model
            thinking_budget = None
            if final_model.startswith("gemini-"):
                thinking_budget = compute_thinking_budget(segments, enforced, len(original_text))

            yellow_texts = [s.text for s in enforced if s.label.tier == SegmentLabelTier.YELLOW]

            # ===== VARIANT A + CUSHION GENERATION (parallel) =====

            await push_event("phase", "generating_a")

            # Start cushion generation in parallel with variant A
            cushion_task = asyncio.create_task(
                generate_cushion(
                    sa_result, enforced, template, sections, sender_info, call_llm,
                )
            )

            # Stream variant A
            result_a = await _stream_final_model(
                final_model, system_prompt_a, user_message,
                spans, final_max_tokens, push_event,
                thinking_budget=thinking_budget,
                delta_event_name="delta",
            )

            # Validate A
            validation_a = output_validator.validate_with_template(
                result_a["unmasked_text"], original_text, spans,
                result_a["raw_content"], redaction.redaction_map, yellow_texts,
                template, sections, enforced,
            )

            issues_a = [
                {"type": i.type.name, "severity": i.severity.name, "message": i.message, "matchedText": i.matched_text}
                for i in validation_a.issues
            ]

            await push_event("done_a", result_a["unmasked_text"])
            await push_event("validation_a", issues_a)
            await push_event("stats_a", {
                "finalPromptTokens": result_a["prompt_tokens"],
                "finalCompletionTokens": result_a["completion_tokens"],
            })

            # ===== AWAIT CUSHION STRATEGY =====

            cushion_strategy = await cushion_task
            total_prompt_tokens += cushion_strategy.prompt_tokens
            total_completion_tokens += cushion_strategy.completion_tokens

            if cushion_strategy.strategies:
                await push_event("cushionStrategy", {
                    "overallTone": cushion_strategy.overall_tone,
                    "strategies": cushion_strategy.strategies,
                    "transitionNotes": cushion_strategy.transition_notes,
                })

            # ===== VARIANT B (cushion-enhanced) =====

            await push_event("phase", "generating_b")

            system_prompt_b = _build_system_prompt_with_cushion(
                template, sections, sa_result, cushion_strategy,
            )

            result_b = await _stream_final_model(
                final_model, system_prompt_b, user_message,
                spans, final_max_tokens, push_event,
                thinking_budget=thinking_budget,
                delta_event_name="delta_b",
            )

            # Validate B
            validation_b = output_validator.validate_with_template(
                result_b["unmasked_text"], original_text, spans,
                result_b["raw_content"], redaction.redaction_map, yellow_texts,
                template, sections, enforced,
            )

            issues_b = [
                {"type": i.type.name, "severity": i.severity.name, "message": i.message, "matchedText": i.matched_text}
                for i in validation_b.issues
            ]

            await push_event("done_b", result_b["unmasked_text"])
            await push_event("validation_b", issues_b)
            await push_event("stats_b", {
                "finalPromptTokens": result_b["prompt_tokens"],
                "finalCompletionTokens": result_b["completion_tokens"],
            })

            await push_event("phase", "complete")

            # Stats
            total_latency = int((time.monotonic() - start_time) * 1000)
            green_count = sum(1 for s in enforced if s.label.tier == SegmentLabelTier.GREEN)
            yellow_count = redaction.yellow_count
            red_count = redaction.red_count

            await push_event("stats", {
                "segmentCount": len(segments),
                "greenCount": green_count,
                "yellowCount": yellow_count,
                "redCount": red_count,
                "lockedSpanCount": len(spans),
                "retryCount": 0,
                "identityBoosterFired": False,
                "situationAnalysisFired": True,
                "metadataOverridden": False,
                "chosenTemplateId": template.id,
                "latencyMs": total_latency,
            })

            # Usage
            analysis_prompt = total_prompt_tokens
            analysis_completion = total_completion_tokens
            final_a_prompt = result_a["prompt_tokens"]
            final_a_completion = result_a["completion_tokens"]
            final_b_prompt = result_b["prompt_tokens"]
            final_b_completion = result_b["completion_tokens"]

            total_final_prompt = final_a_prompt + final_b_prompt
            total_final_completion = final_a_completion + final_b_completion

            analysis_cost = (analysis_prompt * 0.15 + analysis_completion * 0.60) / 1_000_000
            final_cost = (total_final_prompt * 0.15 + total_final_completion * 0.60) / 1_000_000
            total_cost = analysis_cost + final_cost

            await push_event("usage", {
                "analysisPromptTokens": analysis_prompt,
                "analysisCompletionTokens": analysis_completion,
                "finalPromptTokens": total_final_prompt,
                "finalCompletionTokens": total_final_completion,
                "totalCostUsd": total_cost,
                "monthly": {
                    "mvp": total_cost * 1500,
                    "growth": total_cost * 6000,
                    "mature": total_cost * 20000,
                },
            })

            # Done with both texts
            await push_event("done", json.dumps({
                "a": result_a["unmasked_text"],
                "b": result_b["unmasked_text"],
            }))

        except AiTransformError as e:
            logger.error("Stream AB transform failed: %s", e)
            await push_event("error", str(e))
        except Exception:
            logger.error("Stream AB transform failed", exc_info=True)
            await push_event("error", "AI 변환 서비스에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
        finally:
            await queue.put(None)

    task = asyncio.create_task(run_pipeline())

    try:
        while True:
            event = await queue.get()
            if event is None:
                break
            yield event
    finally:
        if not task.done():
            try:
                await task
            except Exception:
                pass


async def _stream_gemini_final_model(
    model_name: str,
    system_prompt: str,
    user_message: str,
    locked_spans: list[LockedSpan],
    max_tokens: int,
    push_event,
    *,
    thinking_budget: int | None = None,
    delta_event_name: str = "delta",
) -> dict:
    """Stream Gemini final model deltas and return unmasked text + token usage."""
    client = _get_gemini_client()

    config_kwargs: dict = {
        "system_instruction": system_prompt,
        "temperature": settings.openai_temperature,
        "max_output_tokens": max_tokens,
    }
    if thinking_budget is not None:
        actual_budget = max(512, thinking_budget)
        config_kwargs["thinking_config"] = ThinkingConfig(thinking_budget=actual_budget)

    stream = await client.aio.models.generate_content_stream(
        model=model_name,
        contents=user_message,
        config=GenerateContentConfig(**config_kwargs),
    )

    full_content: list[str] = []
    prompt_tokens = 0
    completion_tokens = 0

    async for chunk in stream:
        if chunk.text:
            full_content.append(chunk.text)
            await push_event(delta_event_name, chunk.text)
        if chunk.usage_metadata:
            prompt_tokens = chunk.usage_metadata.prompt_token_count or 0
            completion_tokens = chunk.usage_metadata.candidates_token_count or 0

    raw_content = "".join(full_content).strip()
    unmask_result = locked_span_masker.unmask(raw_content, locked_spans)

    return {
        "unmasked_text": unmask_result.text,
        "raw_content": raw_content,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
    }


async def _stream_final_model(
    model_name: str,
    system_prompt: str,
    user_message: str,
    locked_spans: list[LockedSpan],
    max_tokens: int,
    push_event,
    *,
    thinking_budget: int | None = None,
    delta_event_name: str = "delta",
) -> dict:
    """Stream the final model deltas and return unmasked text + token usage.

    Returns dict with keys: unmasked_text, raw_content, prompt_tokens, completion_tokens
    Routes to Gemini or OpenAI based on model name prefix.
    """
    if model_name.startswith("gemini-"):
        return await _stream_gemini_final_model(
            model_name, system_prompt, user_message, locked_spans,
            max_tokens, push_event, thinking_budget=thinking_budget,
            delta_event_name=delta_event_name,
        )

    client = _get_client()

    stream = await client.chat.completions.create(
        model=model_name,
        temperature=settings.openai_temperature,
        max_completion_tokens=max_tokens,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_message},
        ],
        stream=True,
        stream_options={"include_usage": True},
    )

    full_content: list[str] = []
    prompt_tokens = 0
    completion_tokens = 0

    async for chunk in stream:
        if chunk.usage:
            logger.info(
                "Streaming token usage - prompt: %d, completion: %d, total: %d",
                chunk.usage.prompt_tokens, chunk.usage.completion_tokens, chunk.usage.total_tokens,
            )
            prompt_tokens = chunk.usage.prompt_tokens
            completion_tokens = chunk.usage.completion_tokens
            cached_tokens = 0
            if chunk.usage.prompt_tokens_details:
                cached_tokens = chunk.usage.prompt_tokens_details.cached_tokens or 0
            cache_metrics_tracker.record_usage(prompt_tokens, cached_tokens)

        if chunk.choices:
            for choice in chunk.choices:
                if choice.delta and choice.delta.content:
                    content = choice.delta.content
                    full_content.append(content)
                    await push_event(delta_event_name, content)

    raw_content = "".join(full_content).strip()
    unmask_result = locked_span_masker.unmask(raw_content, locked_spans)

    return {
        "unmasked_text": unmask_result.text,
        "raw_content": raw_content,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
    }
