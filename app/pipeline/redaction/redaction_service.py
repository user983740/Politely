"""Redaction service — counts RED/YELLOW segments and builds redaction map.

With JSON segment format, RedactionService only:
- Counts RED/YELLOW segments
- Builds redactionMap (RED marker → original text) for OutputValidator reentry check

No processedText assembly — Final model receives JSON segments directly.
"""

import logging
from dataclasses import dataclass, field

from app.models.domain import LabeledSegment
from app.models.enums import SegmentLabel, SegmentLabelTier

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class RedactionResult:
    red_count: int
    yellow_count: int
    redaction_map: dict[str, str] = field(default_factory=dict)


def process(labeled_segments: list[LabeledSegment]) -> RedactionResult:
    """Process labeled segments: count tiers and build redaction map for RED segments."""
    redaction_map: dict[str, str] = {}
    red_counters: dict[SegmentLabel, int] = {}
    red_count = 0
    yellow_count = 0

    for ls in labeled_segments:
        tier = ls.label.tier

        if tier == SegmentLabelTier.RED:
            count = red_counters.get(ls.label, 0) + 1
            red_counters[ls.label] = count
            marker = f"[REDACTED:{ls.label.name}_{count}]"
            redaction_map[marker] = ls.text
            red_count += 1
        elif tier == SegmentLabelTier.YELLOW:
            yellow_count += 1
        # GREEN: no-op

    logger.info(
        "[Redaction] RED=%d, YELLOW=%d, GREEN=%d",
        red_count, yellow_count,
        len(labeled_segments) - red_count - yellow_count,
    )

    return RedactionResult(
        red_count=red_count,
        yellow_count=yellow_count,
        redaction_map=redaction_map,
    )
