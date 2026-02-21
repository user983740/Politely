"""Evaluates gating conditions for optional pipeline components."""

import logging

from app.models.domain import LockedSpan
from app.models.enums import Persona

logger = logging.getLogger(__name__)

_HIGH_FORMALITY_PERSONAS = frozenset({Persona.BOSS, Persona.CLIENT, Persona.OFFICIAL})


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
