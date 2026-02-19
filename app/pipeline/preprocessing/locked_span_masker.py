import logging
import re
from dataclasses import dataclass

from app.models.domain import LockedSpan

logger = logging.getLogger(__name__)

# Flexible pattern for matching type-specific placeholders in LLM output
# Handles variations: {{DATE_1}}, {{ DATE_1 }}, {{DATE-1}}, etc.
_PLACEHOLDER_PATTERN = re.compile(r"\{\{\s*([A-Z]+)[-_](\d+)\s*\}\}")


@dataclass
class UnmaskResult:
    text: str
    missing_spans: list[LockedSpan]


def mask(text: str, spans: list[LockedSpan]) -> str:
    """Replace locked spans in the original text with their placeholders.

    Spans must be sorted by start_pos ascending.
    """
    if not spans:
        return text

    parts: list[str] = []
    last_end = 0

    for span in spans:
        parts.append(text[last_end:span.start_pos])
        parts.append(span.placeholder)
        last_end = span.end_pos

    parts.append(text[last_end:])
    return "".join(parts)


def unmask(output: str, spans: list[LockedSpan]) -> UnmaskResult:
    """Restore placeholders in the LLM output with their original text.

    Uses flexible matching to handle minor LLM variations in placeholder format.
    """
    if not spans:
        return UnmaskResult(text=output, missing_spans=[])

    # Build placeholder -> span map
    span_map: dict[str, LockedSpan] = {}
    for span in spans:
        span_map[span.placeholder] = span

    restored: set[str] = set()

    def replace_fn(m: re.Match) -> str:
        prefix = m.group(1)
        counter = m.group(2)
        canonical = f"{{{{{prefix}_{counter}}}}}"
        span = span_map.get(canonical)
        if span is not None:
            restored.add(canonical)
            return span.original_text
        logger.warning("LockedSpan placeholder %s not found in span map", canonical)
        return m.group(0)

    result = _PLACEHOLDER_PATTERN.sub(replace_fn, output)

    # Check for missing spans
    missing_spans: list[LockedSpan] = []
    for span in spans:
        if span.placeholder not in restored:
            logger.warning(
                "LockedSpan missing in output: placeholder=%s, type=%s, text='%s'",
                span.placeholder,
                span.type,
                span.original_text,
            )
            if span.original_text not in result:
                missing_spans.append(span)
            else:
                logger.info(
                    "LockedSpan %s found as verbatim text in output (LLM preserved without placeholder)",
                    span.placeholder,
                )

    return UnmaskResult(text=result, missing_spans=missing_spans)
