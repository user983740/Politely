import re
from dataclasses import dataclass

from app.models.domain import LockedSpan
from app.models.enums import LockedSpanType

# --- Pattern definitions in priority order ---

_PATTERNS: list[tuple[re.Pattern, LockedSpanType]] = [
    # 1. Email
    (re.compile(r"[\w]+(?:[.+\-][\w]+)*@[\w]+(?:[\-][\w]+)*(?:\.[a-zA-Z]{2,})+"), LockedSpanType.EMAIL),
    # 2. URL
    (re.compile(r"(?:https?://|www\.)[\w\-.~:/?#\[\]@!$&'()*+,;=%]+[\w/=]"), LockedSpanType.URL),
    # 3. Phone number
    (re.compile(r"0\d{1,2}[\-\.]\d{3,4}[\-\.]\d{4}"), LockedSpanType.PHONE),
    # 4. Account number
    (re.compile(r"\d{2,6}-\d{2,6}-\d{4,12}"), LockedSpanType.ACCOUNT),
    # 5. Korean date
    (re.compile(r"(?:\d{2,4}년\s*)?\d{1,2}월\s*\d{1,2}일|\d{2,4}년\s*\d{1,2}월|\d{4}[./\-]\d{1,2}[./\-]\d{1,2}"), LockedSpanType.DATE),
    # 6. Korean time
    (re.compile(r"(?:오전|오후|새벽|저녁|밤)?\s*\d{1,2}(?:시\s*\d{1,2}분?)?(?:\s*~\s*\d{1,2}(?:시(?:\s*\d{1,2}분?)?)?)?(?:시|분)"), LockedSpanType.TIME),
    # 7. HH:MM
    (re.compile(r"(?:[01]?\d|2[0-3]):\d{2}"), LockedSpanType.TIME_HH_MM),
    # 8. Money
    (re.compile(r"\d[\d,]*(?:\.\d+)?\s*(?:만\s*)?원"), LockedSpanType.MONEY),
    # 9. Numbers with units
    (re.compile(r"\d[\d,]*(?:\.\d+)?\s*(?:자리|개|건|명|장|통|호|층|평|kg|cm|mm|km|%|주|일|개월|년|시간|분|초)"), LockedSpanType.UNIT_NUMBER),
    # 10. Large standalone numbers
    (re.compile(r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d{5,}"), LockedSpanType.LARGE_NUMBER),
    # 11. UUID
    (re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), LockedSpanType.UUID),
    # 12. File path / filename with extension
    (re.compile(
        r"(?:[\w./\\-]+/)?[\w.-]+\.(?:pdf|doc|docx|xls|xlsx|ppt|pptx|csv|txt|md|json|xml|yaml|yml|html|css|js|ts|tsx|jsx|java|py|rb|go|rs|cpp|c|h|hpp|sh|bat|sql|log|zip|tar|gz|rar|7z|png|jpg|jpeg|gif|svg|mp4|mp3|wav|avi|exe|app|msi|dmg|apk|ipa|iso|img|bak|cfg|ini|env|toml|lock|pid)\b",
        re.IGNORECASE,
    ), LockedSpanType.FILE_PATH),
    # 13. Issue/ticket references
    (re.compile(r"#\d{1,6}|[A-Z]{2,10}-\d{1,6}"), LockedSpanType.ISSUE_TICKET),
    # 14. Version numbers
    (re.compile(r"v?\d{1,4}\.\d{1,4}(?:\.\d{1,4})?"), LockedSpanType.VERSION),
    # 15. Quoted text (2-60 chars inside matched quotes)
    (re.compile(r'"([^"]{2,60})"|\'([^\']{2,60})\'|\u201C([^\u201C\u201D]{2,60})\u201D|\u2018([^\u2018\u2019]{2,60})\u2019'), LockedSpanType.QUOTED_TEXT),
    # 16. Identifiers: camelCase (>=5 chars), snake_case (2+ segments), PascalCase with fn()
    (re.compile(r"\b(?:[a-z][a-zA-Z0-9]*[A-Z][a-zA-Z0-9]{2,}|[a-z]+(?:_[a-z]+){1,}|[A-Z][a-z]+(?:[A-Z][a-z]+)+)(?:\(\))?\b"), LockedSpanType.IDENTIFIER),
    # 17. Git commit hashes (7-40 hex chars)
    (re.compile(r"\b[0-9a-f]{7,40}\b"), LockedSpanType.HASH_COMMIT),
]


@dataclass
class _RawMatch:
    start: int
    end: int
    text: str
    type: LockedSpanType


def extract(text: str) -> list[LockedSpan]:
    """Extract all locked spans from the given text.

    Overlapping matches are resolved by keeping the longer match.
    Returns non-overlapping locked spans sorted by start position.
    """
    if not text:
        return []

    # Collect all raw matches
    raw_matches: list[_RawMatch] = []
    for pattern, span_type in _PATTERNS:
        for m in pattern.finditer(text):
            raw_matches.append(_RawMatch(m.start(), m.end(), m.group(), span_type))

    # Sort by start position, then by length descending (longer first)
    raw_matches.sort(key=lambda m: (m.start, -(m.end - m.start)))

    # Remove overlapping matches (keep the longer one)
    resolved = _resolve_overlaps(raw_matches)

    # Convert to LockedSpan with type-specific placeholder
    spans: list[LockedSpan] = []
    prefix_counters: dict[str, int] = {}
    for m in resolved:
        prefix = m.type.placeholder_prefix
        prefix_counters[prefix] = prefix_counters.get(prefix, 0) + 1
        counter = prefix_counters[prefix]
        spans.append(
            LockedSpan(
                index=counter,
                original_text=m.text,
                placeholder=f"{{{{{prefix}_{counter}}}}}",
                type=m.type,
                start_pos=m.start,
                end_pos=m.end,
            )
        )

    return spans


def _resolve_overlaps(sorted_matches: list[_RawMatch]) -> list[_RawMatch]:
    result: list[_RawMatch] = []
    last_end = -1

    for match in sorted_matches:
        if match.start >= last_end:
            result.append(match)
            last_end = match.end
        # else: overlapping or fully contained — skip

    return result
