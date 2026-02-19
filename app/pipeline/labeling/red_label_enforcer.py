"""Server-side RED label enforcer.

Two-tier classification:
- Confirmed patterns: immediately override to RED (profanity, ability denial, mockery)
- Ambiguous patterns: GREEN→YELLOW upgrade only (soft profanity)

Uses text normalization (whitespace/special char removal) to prevent bypass.
"""

import logging
import re

from app.models.domain import LabeledSegment
from app.models.enums import SegmentLabel, SegmentLabelTier

logger = logging.getLogger(__name__)

# === Confirmed patterns: immediate RED override ===

# Profanity/slurs (matched against normalized text) → AGGRESSION
_PROFANITY = re.compile(r"ㅅㅂ|ㅄ|ㅂㅅ|ㄱㅅㄲ|시발|씨발|병신|개새끼|개세끼|지랄|ㅈㄹ|ㅂㄹ")

# Direct ability denial → PERSONAL_ATTACK
_ABILITY_DENIAL = re.compile(r"그것도\s*못|뇌가\s*있|할\s*줄\s*모르|그것도\s*몰라|무능")

# Sarcastic praise + marker (ㅋㅎ^) → AGGRESSION
_MOCKERY_CERTAIN = re.compile(r"(?:잘|대단|훌륭)\S{0,4}(?:시네요|하시네요|십니다)\s*[ㅋㅎ^]{2,}")

# === Ambiguous patterns: GREEN→YELLOW only ===

# Context-dependent expressions (e.g., "미친" can be exclamation or insult)
_SOFT_PROFANITY = re.compile(r"미친|개같|ㅈㄴ")

_NORMALIZE_RE = re.compile(r"[\s\-_.·!@#$%^&*()]+")


def _normalize(text: str) -> str:
    """Remove whitespace and special characters to prevent bypass."""
    return _NORMALIZE_RE.sub("", text)


def enforce(labeled: list[LabeledSegment]) -> list[LabeledSegment]:
    """Apply server-side RED enforcement rules.

    Confirmed patterns → RED, ambiguous → GREEN→YELLOW only.
    """
    result: list[LabeledSegment] = []

    for ls in labeled:
        # Already RED — no need to enforce
        if ls.label.tier == SegmentLabelTier.RED:
            result.append(ls)
            continue

        text = ls.text
        normalized = _normalize(text)

        # Confirmed: profanity or mockery → AGGRESSION
        if _PROFANITY.search(normalized) or _MOCKERY_CERTAIN.search(text):
            logger.info(
                "[RedLabelEnforcer] Confirmed override %s → AGGRESSION (segment %s)",
                ls.label, ls.segment_id,
            )
            result.append(LabeledSegment(ls.segment_id, SegmentLabel.AGGRESSION, ls.text, ls.start, ls.end))
            continue

        # Confirmed: ability denial → PERSONAL_ATTACK
        if _ABILITY_DENIAL.search(text):
            logger.info(
                "[RedLabelEnforcer] Confirmed override %s → PERSONAL_ATTACK (segment %s)",
                ls.label, ls.segment_id,
            )
            result.append(LabeledSegment(ls.segment_id, SegmentLabel.PERSONAL_ATTACK, ls.text, ls.start, ls.end))
            continue

        # Ambiguous: soft profanity — GREEN→YELLOW upgrade only
        if ls.label.tier == SegmentLabelTier.GREEN and _SOFT_PROFANITY.search(normalized):
            logger.info(
                "[RedLabelEnforcer] Soft upgrade %s → EMOTIONAL (segment %s)",
                ls.label, ls.segment_id,
            )
            result.append(LabeledSegment(ls.segment_id, SegmentLabel.EMOTIONAL, ls.text, ls.start, ls.end))
            continue

        result.append(ls)

    return result
