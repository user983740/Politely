"""OpenAI API wrapper — AsyncOpenAI + token tracking + error classification."""

import logging

from openai import AsyncOpenAI

from app.core.config import settings
from app.models.domain import LlmCallResult
from app.pipeline import cache_metrics_tracker

logger = logging.getLogger(__name__)

_client: AsyncOpenAI | None = None


class AiTransformException(Exception):
    pass


def _get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        _client = AsyncOpenAI(api_key=settings.openai_api_key)
    return _client


async def call_openai_with_model(
    model: str,
    system_prompt: str,
    user_message: str,
    temp: float,
    max_tokens: int,
    analysis_context: str | None,
) -> LlmCallResult:
    """Call OpenAI API with explicit model name and token usage tracking.

    Used by MultiModelPipeline for intermediate and final model calls.
    Matches ai_call_fn signature: (model, system, user, temp, max_tokens, analysis_context) -> LlmCallResult
    """
    actual_temp = settings.openai_temperature if temp < 0 else temp
    actual_max_tokens = settings.openai_max_tokens if max_tokens < 0 else max_tokens

    try:
        client = _get_client()
        completion = await client.chat.completions.create(
            model=model,
            temperature=actual_temp,
            max_completion_tokens=actual_max_tokens,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_message},
            ],
        )

        prompt_tokens = 0
        completion_tokens = 0

        if completion.usage:
            prompt_tokens = completion.usage.prompt_tokens
            completion_tokens = completion.usage.completion_tokens
            logger.info(
                "Token usage [%s] - prompt: %d, completion: %d, total: %d",
                model, prompt_tokens, completion_tokens, completion.usage.total_tokens,
            )
            cached_tokens = 0
            if completion.usage.prompt_tokens_details:
                cached_tokens = completion.usage.prompt_tokens_details.cached_tokens or 0
            cache_metrics_tracker.record_usage(prompt_tokens, cached_tokens)

        content = None
        if completion.choices:
            content = completion.choices[0].message.content

        if not content:
            raise AiTransformException("OpenAI 응답에 내용이 없습니다.")

        return LlmCallResult(
            content=content.strip(),
            analysis_context=analysis_context,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
        )
    except AiTransformException:
        raise
    except Exception as e:
        logger.error("OpenAI API call failed [%s]", model, exc_info=True)
        error_msg = _classify_api_error(e)
        raise AiTransformException(error_msg) from e


def _classify_api_error(e: Exception) -> str:
    name = type(e).__name__
    msg = str(e) if str(e) else ""

    if "Unauthorized" in name or "401" in msg or "Incorrect API key" in msg:
        return "AI 서비스 인증 오류: API 키가 유효하지 않습니다. 서버 설정을 확인해주세요."
    if "RateLimit" in name or "429" in msg or "rate limit" in msg:
        return "AI 서비스 요청 한도 초과: 잠시 후 다시 시도해주세요."
    if "Timeout" in name or "timeout" in msg or "timed out" in msg:
        return "AI 서비스 응답 시간 초과: 잠시 후 다시 시도해주세요."
    if "Connect" in name or "connect" in msg or "network" in msg:
        return "AI 서비스 연결 실패: 네트워크 상태를 확인해주세요."
    return "AI 변환 서비스에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
