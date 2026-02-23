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
from app.models.enums import Persona, Purpose, SegmentLabelTier, SituationContext, ToneLevel, Topic
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
    persona: Persona,
    contexts: list[SituationContext],
    tone_level: ToneLevel,
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
                persona, contexts, tone_level, original_text,
                user_prompt, sender_info, identity_booster_toggle,
                topic, purpose,
                ai_call_fn=call_llm,
                callback=StreamCallback(),
            )

            # 2. Build final prompt
            prompt = build_final_prompt(analysis, persona, contexts, tone_level, sender_info)

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
                final_stream_result["raw_content"], persona, prompt.redaction_map, yellow_texts,
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
                    active_result["raw_content"], persona, prompt.redaction_map, yellow_texts,
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


async def _stream_gemini_final_model(
    model_name: str,
    system_prompt: str,
    user_message: str,
    locked_spans: list[LockedSpan],
    max_tokens: int,
    push_event,
    *,
    thinking_budget: int | None = None,
) -> dict:
    """Stream Gemini final model deltas and return unmasked text + token usage."""
    client = _get_gemini_client()

    config_kwargs: dict = {
        "system_instruction": system_prompt,
        "temperature": settings.openai_temperature,
        "max_output_tokens": max_tokens,
    }
    if thinking_budget is not None:
        config_kwargs["thinking_config"] = ThinkingConfig(thinking_budget=thinking_budget)

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
            await push_event("delta", chunk.text)
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
) -> dict:
    """Stream the final model deltas and return unmasked text + token usage.

    Returns dict with keys: unmasked_text, raw_content, prompt_tokens, completion_tokens
    Routes to Gemini or OpenAI based on model name prefix.
    """
    if model_name.startswith("gemini-"):
        return await _stream_gemini_final_model(
            model_name, system_prompt, user_message, locked_spans,
            max_tokens, push_event, thinking_budget=thinking_budget,
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
                    await push_event("delta", content)

    raw_content = "".join(full_content).strip()
    unmask_result = locked_span_masker.unmask(raw_content, locked_spans)

    return {
        "unmasked_text": unmask_result.text,
        "raw_content": raw_content,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
    }
