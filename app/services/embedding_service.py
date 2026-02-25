import json
import logging

import numpy as np
from openai import AsyncOpenAI

from app.core.config import settings

logger = logging.getLogger(__name__)

_client: AsyncOpenAI | None = None


def _get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        _client = AsyncOpenAI(api_key=settings.openai_api_key)
    return _client


async def embed_text(text: str) -> list[float]:
    """Embed a single text → 1536-dim float list."""
    client = _get_client()
    response = await client.embeddings.create(
        model=settings.rag_embedding_model,
        input=text,
    )
    return response.data[0].embedding


async def embed_batch(texts: list[str]) -> list[list[float]]:
    """Embed multiple texts in one API call → list of 1536-dim float lists."""
    if not texts:
        return []
    client = _get_client()
    response = await client.embeddings.create(
        model=settings.rag_embedding_model,
        input=texts,
    )
    # Sort by index to preserve input order
    sorted_data = sorted(response.data, key=lambda d: d.index)
    return [d.embedding for d in sorted_data]


def embedding_to_json(emb: list[float] | np.ndarray) -> str:
    """float32 downcasting + compact JSON. Prevents cross-platform parsing differences."""
    arr = np.asarray(emb, dtype=np.float32)
    return json.dumps([float(v) for v in arr], separators=(",", ":"))


def json_to_embedding(s: str) -> np.ndarray:
    """JSON string → numpy float32 array."""
    return np.asarray(json.loads(s), dtype=np.float32)
