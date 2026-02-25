from __future__ import annotations

import logging
from dataclasses import dataclass, field

import numpy as np
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.db import rag_repository
from app.models.rag import RagCategory, RagEntry, parse_csv_filter
from app.services.embedding_service import json_to_embedding

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Category configuration
# ---------------------------------------------------------------------------

CATEGORY_CONFIG: dict[str, dict] = {
    RagCategory.EXPRESSION_POOL: {"threshold": 0.78, "top_k": 5, "fallback_top_k": 1},
    RagCategory.CUSHION: {"threshold": 0.78, "top_k": 3, "fallback_top_k": 1},
    RagCategory.FORBIDDEN: {"threshold": 0.72, "top_k": 3, "fallback_top_k": 0},
    RagCategory.POLICY: {"threshold": 0.82, "top_k": 3, "fallback_top_k": 0},
    RagCategory.EXAMPLE: {"threshold": 0.80, "top_k": 2, "fallback_top_k": 1},
    RagCategory.DOMAIN_CONTEXT: {"threshold": 0.82, "top_k": 2, "fallback_top_k": 0},
}


# ---------------------------------------------------------------------------
# Cached entry for pre-parsed metadata
# ---------------------------------------------------------------------------


@dataclass
class _CachedEntry:
    entry: RagEntry
    personas: frozenset[str]
    contexts: frozenset[str]
    tone_levels: frozenset[str]
    sections: frozenset[str]
    yellow_labels: frozenset[str]
    trigger_phrases: list[str]


# ---------------------------------------------------------------------------
# Search results
# ---------------------------------------------------------------------------


@dataclass
class RagSearchHit:
    content: str
    score: float
    category: str
    original_text: str | None = None
    alternative: str | None = None
    used_fallback: bool = False


@dataclass
class RagResults:
    expression_pool: list[RagSearchHit] = field(default_factory=list)
    cushion: list[RagSearchHit] = field(default_factory=list)
    forbidden: list[RagSearchHit] = field(default_factory=list)
    policy: list[RagSearchHit] = field(default_factory=list)
    example: list[RagSearchHit] = field(default_factory=list)
    domain_context: list[RagSearchHit] = field(default_factory=list)

    def is_empty(self) -> bool:
        return not any([
            self.expression_pool, self.cushion, self.forbidden,
            self.policy, self.example, self.domain_context,
        ])

    def total_hits(self) -> int:
        return sum(len(getattr(self, cat.value)) for cat in RagCategory)


# ---------------------------------------------------------------------------
# RAG Index — in-memory vector search
# ---------------------------------------------------------------------------


class RagIndex:
    """In-memory RAG index with numpy cosine similarity search."""

    def __init__(self) -> None:
        self._cached: list[_CachedEntry] = []
        self._embeddings: np.ndarray = np.empty((0, 1536), dtype=np.float32)

    @property
    def size(self) -> int:
        return len(self._cached)

    def load(self, entries: list[RagEntry]) -> int:
        """Build index from DB entries. Returns count of loaded entries."""
        cached: list[_CachedEntry] = []
        embedding_list: list[np.ndarray] = []

        for entry in entries:
            if not entry.embedding_blob:
                continue
            try:
                emb = json_to_embedding(entry.embedding_blob)
            except Exception:
                logger.warning("Skipping entry %d: invalid embedding", entry.id)
                continue

            # Parse trigger_phrases for forbidden category
            triggers: list[str] = []
            if entry.trigger_phrases:
                triggers = [
                    t.strip().casefold()
                    for t in entry.trigger_phrases.split(",")
                    if len(t.strip()) >= 3  # ignore short tokens to prevent false positives
                ]

            cached.append(_CachedEntry(
                entry=entry,
                personas=parse_csv_filter(entry.personas),
                contexts=parse_csv_filter(entry.contexts),
                tone_levels=parse_csv_filter(entry.tone_levels),
                sections=parse_csv_filter(entry.sections),
                yellow_labels=parse_csv_filter(entry.yellow_labels),
                trigger_phrases=triggers,
            ))
            embedding_list.append(emb)

        if embedding_list:
            matrix = np.stack(embedding_list)
            # L2 normalize for cosine similarity via dot product
            norms = np.linalg.norm(matrix, axis=1, keepdims=True)
            norms[norms == 0] = 1.0
            self._embeddings = (matrix / norms).astype(np.float32)
        else:
            self._embeddings = np.empty((0, 1536), dtype=np.float32)

        self._cached = cached
        return len(cached)

    async def reload(self, session: AsyncSession) -> int:
        """Hot reload — atomic swap to prevent race conditions."""
        entries = await rag_repository.find_all_enabled(session)
        count = self.load(entries)
        logger.info("RAG index reloaded: %d entries", count)
        return count

    def search(
        self,
        query_embedding: list[float],
        original_text: str,
        *,
        persona: str | None = None,
        contexts: list[str] | None = None,
        tone_level: str | None = None,
        sections: list[str] | None = None,
        yellow_labels: list[str] | None = None,
    ) -> RagResults:
        """Search all categories and return aggregated results."""
        if self.size == 0:
            return RagResults()

        # Normalize query embedding
        q = np.asarray(query_embedding, dtype=np.float32)
        q_norm = np.linalg.norm(q)
        if q_norm > 0:
            q = q / q_norm

        results = RagResults()
        for cat in RagCategory:
            config = CATEGORY_CONFIG[cat]
            hits = self._search_category(
                q, original_text, cat.value,
                config=config,
                persona=persona,
                contexts=contexts,
                tone_level=tone_level,
                sections=sections,
                yellow_labels=yellow_labels,
            )
            setattr(results, cat.value, hits)

        if logger.isEnabledFor(logging.DEBUG):
            for cat in RagCategory:
                cat_hits = getattr(results, cat.value)
                if cat_hits:
                    scores = [h.score for h in cat_hits]
                    fb = any(h.used_fallback for h in cat_hits)
                    logger.debug(
                        "RAG %s: %d hits, avg=%.3f, max=%.3f%s",
                        cat.value, len(cat_hits),
                        sum(scores) / len(scores), max(scores),
                        " [fallback]" if fb else "",
                    )

        return results

    def _search_category(
        self,
        query_vec: np.ndarray,
        original_text: str,
        category: str,
        *,
        config: dict,
        persona: str | None,
        contexts: list[str] | None,
        tone_level: str | None,
        sections: list[str] | None,
        yellow_labels: list[str] | None,
    ) -> list[RagSearchHit]:
        threshold = config["threshold"]
        top_k = config["top_k"]
        fallback_top_k = config["fallback_top_k"]

        # Step 1: Boolean mask — category + metadata pre-filter
        candidate_indices: list[int] = []
        for i, ce in enumerate(self._cached):
            if ce.entry.category != category:
                continue
            if not self._matches_filters(ce, persona, contexts, tone_level, sections, yellow_labels):
                continue
            candidate_indices.append(i)

        # Step 2: For forbidden, also find trigger_phrases matches (lexical)
        trigger_indices: set[int] = set()
        if category == RagCategory.FORBIDDEN:
            normalized_text = " ".join(original_text.casefold().split())
            for i, ce in enumerate(self._cached):
                if ce.entry.category != RagCategory.FORBIDDEN:
                    continue
                for trigger in ce.trigger_phrases:
                    if trigger in normalized_text:
                        trigger_indices.add(i)
                        break

        if not candidate_indices and not trigger_indices:
            return []

        # Step 3: Cosine similarity for vector candidates
        scored: list[tuple[int, float]] = []
        if candidate_indices:
            idx_arr = np.array(candidate_indices)
            candidate_embs = self._embeddings[idx_arr]
            similarities = candidate_embs @ query_vec  # dot product of L2-normalized vectors
            for j, idx in enumerate(candidate_indices):
                scored.append((idx, float(similarities[j])))

        # Add trigger matches with score=1.0 (guaranteed match)
        existing_indices = {s[0] for s in scored}
        for idx in trigger_indices:
            if idx not in existing_indices:
                scored.append((idx, 1.0))

        # Sort by score descending
        scored.sort(key=lambda x: x[1], reverse=True)

        # Step 4: Take top_k*3 candidates for MMR dedup
        pre_candidates = scored[:top_k * 3]

        # Step 5: Apply threshold filter
        above_threshold = [(idx, score) for idx, score in pre_candidates if score >= threshold]

        # Step 6: Simple MMR deduplication on subset
        mmr_threshold = settings.rag_mmr_duplicate_threshold
        selected = self._apply_mmr(above_threshold, top_k, mmr_threshold)

        # Step 7: Fallback — if 0 results and fallback_top_k > 0
        used_fallback = False
        if not selected and fallback_top_k > 0 and pre_candidates:
            selected = pre_candidates[:fallback_top_k]
            used_fallback = True

        # Build result hits
        return [
            RagSearchHit(
                content=self._cached[idx].entry.content,
                score=score,
                category=category,
                original_text=self._cached[idx].entry.original_text,
                alternative=self._cached[idx].entry.alternative,
                used_fallback=used_fallback,
            )
            for idx, score in selected
        ]

    def _matches_filters(
        self,
        ce: _CachedEntry,
        persona: str | None,
        contexts: list[str] | None,
        tone_level: str | None,
        sections: list[str] | None,
        yellow_labels: list[str] | None,
    ) -> bool:
        """Check if cached entry matches the given filters. Empty filter = match all."""
        if ce.personas and persona and persona.upper() not in ce.personas:
            return False
        if ce.contexts and contexts:
            query_contexts = frozenset(c.upper() for c in contexts)
            if not ce.contexts & query_contexts:
                return False
        if ce.tone_levels and tone_level and tone_level.upper() not in ce.tone_levels:
            return False
        if ce.sections and sections:
            query_sections = frozenset(s.upper() for s in sections)
            if not ce.sections & query_sections:
                return False
        if ce.yellow_labels and yellow_labels:
            query_labels = frozenset(l.upper() for l in yellow_labels)  # noqa: E741
            if not ce.yellow_labels & query_labels:
                return False
        return True

    def _apply_mmr(
        self,
        candidates: list[tuple[int, float]],
        top_k: int,
        mmr_threshold: float,
    ) -> list[tuple[int, float]]:
        """Simple MMR: remove near-duplicate results from top candidates."""
        if not candidates:
            return []
        selected: list[tuple[int, float]] = []
        for idx, score in candidates:
            if len(selected) >= top_k:
                break
            # Check similarity against already selected
            is_duplicate = False
            for sel_idx, _ in selected:
                sim = float(self._embeddings[idx] @ self._embeddings[sel_idx])
                if sim > mmr_threshold:
                    is_duplicate = True
                    break
            if not is_duplicate:
                selected.append((idx, score))
        return selected


# ---------------------------------------------------------------------------
# Global singleton
# ---------------------------------------------------------------------------

rag_index = RagIndex()
