"""Evaluates gating conditions for optional pipeline components."""

import logging
import re

from app.models.domain import LabelStats, LockedSpan
from app.models.enums import Persona, Purpose, SituationContext, ToneLevel, Topic

logger = logging.getLogger(__name__)

_HIGH_FORMALITY_PERSONAS = frozenset({Persona.BOSS, Persona.CLIENT, Persona.OFFICIAL})

# Context gating keyword patterns
_REFUND_KEYWORDS = re.compile(r"환불|취소|반품|결제\s*취소|카드\s*취소|refund|cancel")
_BLAME_REJECTION_KEYWORDS = re.compile(r"책임|귀책|거절|불가|어렵습니다|못합니다|안 됩니다")
_REQUEST_REJECTION_KEYWORDS = re.compile(r"요청|부탁|해 주|거절|불가|어렵")


def should_fire_identity_booster(
    frontend_toggle: bool,
    persona: Persona,
    locked_spans: list[LockedSpan],
    text_length: int,
    min_text_length: int = 80,
    max_locked_spans: int = 1,
) -> bool:
    """Identity Lock Booster ON conditions (OR):
    1. frontendToggle = true
    2. persona ∈ {BOSS, CLIENT, OFFICIAL} AND lockedSpans ≤ 1 AND textLength ≥ 80
    """
    if frontend_toggle:
        logger.info("[Gating] IdentityBooster: ON (frontend toggle)")
        return True

    if (
        persona in _HIGH_FORMALITY_PERSONAS
        and len(locked_spans) <= max_locked_spans
        and text_length >= min_text_length
    ):
        logger.info(
            "[Gating] IdentityBooster: ON (high-formality persona=%s, spans=%d, len=%d)",
            persona, len(locked_spans), text_length,
        )
        return True

    return False


def should_fire_situation_analysis(persona: Persona, text: str) -> bool:
    """Situation Analysis: always ON."""
    logger.info("[Gating] SituationAnalysis: ON (always-on)")
    return True


def should_fire_context_gating(
    persona: Persona,
    contexts: list[SituationContext],
    topic: Topic | None,
    purpose: Purpose | None,
    tone_level: ToneLevel,
    label_stats: LabelStats,
    masked_text: str,
) -> bool:
    """Context Gating LLM trigger conditions (any true):
    1. APOLOGY context but text has blame/rejection/refund keywords
    2. ANNOUNCEMENT context but text has request/rejection patterns
    3. Refund keywords in text but TOPIC != REFUND_CANCEL and PURPOSE != REFUND_REJECTION
    4. All of ACCOUNTABILITY + NEGATIVE_FEEDBACK + EMOTIONAL present in labels
    5. VERY_POLITE + CLIENT/OFFICIAL persona
    """
    # 1. APOLOGY context + blame/rejection/refund keywords
    if SituationContext.APOLOGY in contexts and (
        _BLAME_REJECTION_KEYWORDS.search(masked_text)
        or _REFUND_KEYWORDS.search(masked_text)
    ):
        logger.info("[Gating] ContextGating: ON (APOLOGY context + blame/refund keywords)")
        return True

    # 2. ANNOUNCEMENT context + request/rejection patterns
    if SituationContext.ANNOUNCEMENT in contexts and _REQUEST_REJECTION_KEYWORDS.search(masked_text):
        logger.info("[Gating] ContextGating: ON (ANNOUNCEMENT context + request/rejection keywords)")
        return True

    # 3. Refund keywords but no matching topic/purpose
    if (
        _REFUND_KEYWORDS.search(masked_text)
        and topic != Topic.REFUND_CANCEL
        and purpose != Purpose.REFUND_REJECTION
    ):
        logger.info("[Gating] ContextGating: ON (refund keywords without matching topic/purpose)")
        return True

    # 4. Complex label mix
    if label_stats.has_accountability and label_stats.has_negative_feedback and label_stats.has_emotional:
        logger.info("[Gating] ContextGating: ON (complex label mix: ACCOUNTABILITY+NEGATIVE_FEEDBACK+EMOTIONAL)")
        return True

    # 5. VERY_POLITE + CLIENT/OFFICIAL
    if tone_level == ToneLevel.VERY_POLITE and persona in (Persona.CLIENT, Persona.OFFICIAL):
        logger.info("[Gating] ContextGating: ON (VERY_POLITE + CLIENT/OFFICIAL)")
        return True

    return False
