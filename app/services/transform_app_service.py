from app.core.config import settings
from app.models.domain import TransformResult
from app.models.enums import Persona, SituationContext, ToneLevel
from app.pipeline.ai_call_router import call_llm
from app.pipeline.multi_model_pipeline import execute_analysis, execute_final


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
