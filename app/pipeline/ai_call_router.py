"""Model-name-based LLM call router.

Routes to Gemini or OpenAI based on model name prefix.
"""

from app.models.domain import LlmCallResult


async def call_llm(
    model: str,
    system_prompt: str,
    user_message: str,
    temp: float,
    max_tokens: int,
    analysis_context: str | None,
    *,
    thinking_budget: int | None = None,
) -> LlmCallResult:
    """Route LLM call to the appropriate provider based on model name prefix."""
    if model.startswith("gemini-"):
        from app.pipeline.ai_gemini_service import call_gemini

        return await call_gemini(
            model, system_prompt, user_message, temp, max_tokens, analysis_context,
            thinking_budget=thinking_budget,
        )
    else:
        from app.pipeline.ai_transform_service import call_openai_with_model

        return await call_openai_with_model(
            model, system_prompt, user_message, temp, max_tokens, analysis_context,
        )
