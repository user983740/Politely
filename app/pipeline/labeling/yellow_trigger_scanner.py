"""Server-side regex scanner for all-GREEN yellow recovery.

When ALL segments (4+) are labeled GREEN, this scanner checks for
Korean patterns that strongly suggest YELLOW-worthy content was missed.
If found, it upgrades up to MAX_UPGRADES GREEN segments to YELLOW,
avoiding an expensive LLM diversity retry.

4 pattern categories:
  1. Blame + generalization (매번/맨날/항상/도대체 + recipient reference)
  2. Direct emotional expression (답답/화가/짜증/열받/미치겠/환장)
  3. Speculation / assertion (틀림없이/확실히/아마/같다/듯/분명)
  4. Defensive structure ("내 탓 하려는"/"말해두는데"/"난 ~했고")
"""

import re
from dataclasses import dataclass

from app.models.domain import LabeledSegment, Segment
from app.models.enums import SegmentLabel, SegmentLabelTier

SCORE_THRESHOLD = 2
MAX_UPGRADES = 2


@dataclass(frozen=True)
class YellowUpgrade:
    segment_id: str
    new_label: SegmentLabel
    reason: str
    score: int


# ---------------------------------------------------------------------------
# Pattern definitions
# ---------------------------------------------------------------------------

# Each category: (recommended_label, strong_patterns (+2), soft_patterns (+1))
_CategoryDef = tuple[SegmentLabel, list[re.Pattern], list[re.Pattern]]

_RECIPIENT_RE = re.compile(r"(?:상대|님|너희|귀사|담당)")
_GENERALIZER_RE = re.compile(r"(?:매번|맨날|항상|도대체)")

_CATEGORIES: list[tuple[str, _CategoryDef]] = [
    # 1. Blame + generalization
    (
        "blame_generalization",
        (
            SegmentLabel.ACCOUNTABILITY,
            # Strong: generalizer + recipient reference in same segment
            [],  # handled via compound check below
            # Soft: generalizer alone
            [],  # handled via compound check below
        ),
    ),
    # 2. Direct emotional expression
    (
        "emotional_expression",
        (
            SegmentLabel.EMOTIONAL,
            [re.compile(r"(?:답답|화가|짜증|열받|미치겠|환장)")],
            [re.compile(r"(?:정말|너무)")],
        ),
    ),
    # 3. Speculation / assertion
    (
        "speculation",
        (
            SegmentLabel.EXCESS_DETAIL,
            [re.compile(r"(?:틀림없이|확실히)")],
            [re.compile(r"(?:아마|것\s*같다|것\s*같아|같다|듯\b|분명)")],
        ),
    ),
    # 4. Defensive structure
    (
        "defense",
        (
            SegmentLabel.SELF_JUSTIFICATION,
            [re.compile(r"(?:내\s*탓\s*하려|말해\s*두는데)")],
            [re.compile(r"(?:난\s.*했고|최선을\s*다했|제\s*잘못도\s*있지만)")],
        ),
    ),
]


def _score_blame_generalization(text: str) -> tuple[int, str]:
    """Special compound scorer for blame + generalization category."""
    score = 0
    reasons: list[str] = []

    has_generalizer = bool(_GENERALIZER_RE.search(text))
    has_recipient = bool(_RECIPIENT_RE.search(text))

    if has_generalizer and has_recipient:
        score += 2
        reasons.append("generalizer+recipient(strong)")
    elif has_generalizer:
        score += 1
        reasons.append("generalizer(soft)")

    return score, "+".join(reasons) if reasons else ""


def _score_category(text: str, cat_def: _CategoryDef) -> tuple[int, str]:
    """Score a segment against a single category's patterns."""
    _, strong_patterns, soft_patterns = cat_def
    score = 0
    reasons: list[str] = []

    for pat in strong_patterns:
        if pat.search(text):
            score += 2
            reasons.append(f"strong:{pat.pattern}")
            break  # one strong hit is enough per category

    for pat in soft_patterns:
        if pat.search(text):
            score += 1
            reasons.append(f"soft:{pat.pattern}")
            break  # one soft hit is enough per category

    return score, "+".join(reasons) if reasons else ""


def scan_yellow_triggers(
    segments: list[Segment],
    labeled_segments: list[LabeledSegment],
) -> list[YellowUpgrade]:
    """Scan GREEN segments for YELLOW-worthy patterns.

    Only called when ALL segments are GREEN and there are 4+ segments.
    Returns up to MAX_UPGRADES upgrade recommendations (score >= SCORE_THRESHOLD).
    """
    # Build lookup: segment_id -> LabeledSegment
    label_map = {ls.segment_id: ls for ls in labeled_segments}

    candidates: list[YellowUpgrade] = []

    for seg in segments:
        ls = label_map.get(seg.id)
        if ls is None or ls.label.tier != SegmentLabelTier.GREEN:
            continue

        text = seg.text
        total_score = 0
        all_reasons: list[str] = []
        best_label: SegmentLabel | None = None
        best_label_score = 0

        # Score blame+generalization (special compound logic)
        blame_score, blame_reason = _score_blame_generalization(text)
        if blame_score > 0:
            total_score += blame_score
            all_reasons.append(f"blame({blame_reason})")
            if blame_score > best_label_score:
                best_label_score = blame_score
                # Choose label based on whether there's recipient reference
                if _RECIPIENT_RE.search(text):
                    best_label = SegmentLabel.ACCOUNTABILITY
                else:
                    best_label = SegmentLabel.NEGATIVE_FEEDBACK

        # Score remaining categories
        for cat_name, cat_def in _CATEGORIES[1:]:  # skip blame (index 0)
            cat_score, cat_reason = _score_category(text, cat_def)
            if cat_score > 0:
                total_score += cat_score
                all_reasons.append(f"{cat_name}({cat_reason})")
                if cat_score > best_label_score:
                    best_label_score = cat_score
                    best_label = cat_def[0]

        if total_score >= SCORE_THRESHOLD and best_label is not None:
            candidates.append(YellowUpgrade(
                segment_id=seg.id,
                new_label=best_label,
                reason="; ".join(all_reasons),
                score=total_score,
            ))

    # Sort by score descending, take top MAX_UPGRADES
    candidates.sort(key=lambda c: c.score, reverse=True)
    return candidates[:MAX_UPGRADES]
