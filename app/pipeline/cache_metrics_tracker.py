"""Token cache metrics tracking for OpenAI prompt caching."""

import logging
import threading

logger = logging.getLogger(__name__)

_lock = threading.Lock()
_total_requests = 0
_cache_hit_requests = 0
_total_prompt_tokens = 0
_total_cached_tokens = 0


def record_usage(prompt_tokens: int, cached_tokens: int) -> None:
    global _total_requests, _cache_hit_requests, _total_prompt_tokens, _total_cached_tokens
    with _lock:
        _total_requests += 1
        _total_prompt_tokens += prompt_tokens
        if cached_tokens > 0:
            _cache_hit_requests += 1
            _total_cached_tokens += cached_tokens

    cache_ratio = (cached_tokens / prompt_tokens * 100) if prompt_tokens > 0 else 0
    logger.info(
        "Cache metrics - request #%d: promptTokens=%d, cachedTokens=%d, cacheRatio=%.1f%%, "
        "cumulative: totalRequests=%d, cacheHitRate=%.1f%%, tokenCacheRate=%.1f%%",
        _total_requests, prompt_tokens, cached_tokens, cache_ratio,
        _total_requests, get_cache_hit_rate(), get_token_cache_rate(),
    )


def get_cache_hit_rate() -> float:
    with _lock:
        return (_cache_hit_requests / _total_requests * 100) if _total_requests > 0 else 0


def get_token_cache_rate() -> float:
    with _lock:
        return (_total_cached_tokens / _total_prompt_tokens * 100) if _total_prompt_tokens > 0 else 0
