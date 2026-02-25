import logging

from app.core.config import settings
from app.models.domain import TransformResult
from app.models.enums import Persona, SituationContext, ToneLevel
from app.pipeline.ai_call_router import call_llm
from app.pipeline.multi_model_pipeline import AnalysisPhaseResult, execute_analysis, execute_final

logger = logging.getLogger(__name__)


async def _retrieve_rag(
    original_text: str,
    analysis: AnalysisPhaseResult,
    persona: Persona,
    contexts: list[SituationContext],
    tone_level: ToneLevel,
):
    """Retrieve RAG context for the final prompt. Returns RagResults or None."""
    if not settings.rag_enabled:
        return None

    try:
        from app.services.embedding_service import embed_text
        from app.services.rag_service import rag_index

        if rag_index.size == 0:
            return None

        # Build unified query: original text + intent + persona + contexts
        query_parts = [original_text]
        if analysis.situation_analysis and analysis.situation_analysis.intent:
            query_parts.append(analysis.situation_analysis.intent)
        query_parts.append(persona.value)
        for ctx in contexts:
            query_parts.append(ctx.value)
        query = " ".join(query_parts)

        # Single embedding call
        query_embedding = await embed_text(query)

        # Extract yellow labels from analysis
        yellow_labels = [
            s.label.name for s in analysis.labeled_segments
            if s.label.tier.name == "YELLOW"
        ]

        # Extract section names
        section_names = [s.name for s in analysis.effective_sections]

        # Search all categories
        results = rag_index.search(
            query_embedding,
            original_text,
            persona=persona.value,
            contexts=[ctx.value for ctx in contexts],
            tone_level=tone_level.value,
            sections=section_names,
            yellow_labels=yellow_labels,
        )

        if not results.is_empty():
            logger.info("[RAG] Retrieved %d total hits", results.total_hits())

        return results

    except Exception:
        logger.exception("[RAG] Retrieval failed — continuing without RAG")
        return None


async def transform(
    persona: Persona,
    contexts: list[SituationContext],
    tone_level: ToneLevel,
    original_text: str,
    user_prompt: str | None,
    sender_info: str | None,
) -> TransformResult:
    """Full transform via multi-model pipeline (v2)."""
    validate_transform_request(original_text)

    analysis = await execute_analysis(
        persona, contexts, tone_level, original_text,
        user_prompt, sender_info, False,
        ai_call_fn=call_llm,
    )

    # RAG retrieval (after analysis, before final)
    rag_results = await _retrieve_rag(original_text, analysis, persona, contexts, tone_level)

    result = await execute_final(
        settings.gemini_final_model,
        analysis,
        persona,
        contexts,
        tone_level,
        original_text,
        sender_info,
        settings.openai_max_tokens_paid,
        ai_call_fn=call_llm,
        rag_results=rag_results,
    )

    return TransformResult(transformed_text=result.transformed_text)


def validate_transform_request(original_text: str) -> None:
    max_length = settings.tier_paid_max_text_length
    if len(original_text) > max_length:
        raise ValueError(f"최대 {max_length}자까지 입력할 수 있습니다.")


def get_max_text_length() -> int:
    return settings.tier_paid_max_text_length


def resolve_final_max_tokens() -> int:
    return settings.openai_max_tokens_paid
