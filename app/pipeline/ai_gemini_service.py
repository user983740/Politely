"""Google Gemini API wrapper — genai Client + token tracking + error classification."""

import logging

from google import genai
from google.genai.types import GenerateContentConfig, ThinkingConfig

from app.core.config import settings
from app.models.domain import LlmCallResult
from app.pipeline.ai_transform_service import AiTransformError

logger = logging.getLogger(__name__)

_client: genai.Client | None = None


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        if not settings.gemini_api_key:
            raise AiTransformError("Gemini API 키가 설정되지 않았습니다. 서버 설정을 확인해주세요.")
        _client = genai.Client(api_key=settings.gemini_api_key)
    return _client


async def call_gemini(
    model: str,
    system_prompt: str,
    user_message: str,
    temp: float,
    max_tokens: int,
    analysis_context: str | None,
    *,
    thinking_budget: int | None = None,
) -> LlmCallResult:
    """Call Gemini API with explicit model name and token usage tracking.

    Matches ai_call_fn signature: (model, system, user, temp, max_tokens, analysis_context) -> LlmCallResult
    """
    actual_temp = settings.openai_temperature if temp < 0 else temp
    actual_max_tokens = settings.openai_max_tokens if max_tokens < 0 else max_tokens

    try:
        client = _get_client()

        config_kwargs: dict = {
            "system_instruction": system_prompt,
            "temperature": actual_temp,
            "max_output_tokens": actual_max_tokens,
        }
        if thinking_budget is not None:
            config_kwargs["thinking"] = ThinkingConfig(thinking_budget=thinking_budget)

        response = await client.aio.models.generate_content(
            model=model,
            contents=user_message,
            config=GenerateContentConfig(**config_kwargs),
        )

        prompt_tokens = 0
        completion_tokens = 0

        if response.usage_metadata:
            prompt_tokens = response.usage_metadata.prompt_token_count or 0
            completion_tokens = response.usage_metadata.candidates_token_count or 0
            logger.info(
                "Token usage [%s] - prompt: %d, completion: %d, total: %d",
                model, prompt_tokens, completion_tokens,
                (response.usage_metadata.total_token_count or 0),
            )

        content = response.text if response.text else None

        if not content:
            raise AiTransformError("Gemini 응답에 내용이 없습니다.")

        return LlmCallResult(
            content=content.strip(),
            analysis_context=analysis_context,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
        )
    except AiTransformError:
        raise
    except Exception as e:
        logger.error("Gemini API call failed [%s]", model, exc_info=True)
        error_msg = _classify_gemini_error(e)
        raise AiTransformError(error_msg) from e


def _classify_gemini_error(e: Exception) -> str:
    name = type(e).__name__
    msg = str(e) if str(e) else ""

    if "Unauthorized" in name or "401" in msg or "API key" in msg.lower():
        return "AI 서비스 인증 오류: API 키가 유효하지 않습니다. 서버 설정을 확인해주세요."
    if "RateLimit" in name or "429" in msg or "rate limit" in msg.lower() or "quota" in msg.lower():
        return "AI 서비스 요청 한도 초과: 잠시 후 다시 시도해주세요."
    if "Timeout" in name or "timeout" in msg or "timed out" in msg:
        return "AI 서비스 응답 시간 초과: 잠시 후 다시 시도해주세요."
    if "Connect" in name or "connect" in msg or "network" in msg:
        return "AI 서비스 연결 실패: 네트워크 상태를 확인해주세요."
    return "AI 변환 서비스에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
