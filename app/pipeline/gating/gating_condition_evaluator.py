"""Evaluates gating conditions for optional pipeline components."""

import logging

from app.models.domain import LockedSpan

logger = logging.getLogger(__name__)


def should_fire_identity_booster(
    frontend_toggle: bool,
    locked_spans: list[LockedSpan],
    text_length: int,
    min_text_length: int = 80,
    max_locked_spans: int = 1,
) -> bool:
    """Identity Lock Booster ON condition: frontendToggle = true."""
    if frontend_toggle:
        logger.info("[Gating] IdentityBooster: ON (frontend toggle)")
        return True

    return False


def should_fire_situation_analysis(text: str) -> bool:
    """Situation Analysis: always ON."""
    logger.info("[Gating] SituationAnalysis: ON (always-on)")
    return True
