"""Precision-first 7-stage hierarchical text segmenter. No LLM calls.

Pipeline:
  1. Strong structural boundaries (confidence: 1.0)
     - blank lines, explicit separators (---/===/___), bullets, numbered lists
  2. Korean sentence endings (confidence: 0.95)
     - ~120 patterns + connective suffix suppression
  3. Weak punctuation boundaries (confidence: 0.9)
     - .!?;...-- followed by space or line end
  4. Length-based safety split (confidence: 0.85)
     - 250 char segments split at nearest weak boundary, postposition avoidance
  5. Enumeration detection (confidence: 0.9)
     - comma lists, delimiter lists, parallel ~go structure (120+ chars only)
  6. Discourse marker split (confidence: 0.88)
     - ~39 markers, sentence-start only, 150+ chars only, compound exclusion
  7. Over-segmentation merge
     - 3+ consecutive <5 char segments merged, placeholder boundary protection
"""

import logging
import re

import regex  # PyPI package — supports variable-width lookbehind

from app.core.config import settings
from app.models.domain import Segment

logger = logging.getLogger(__name__)

# ── Internal types ──


class _SplitUnit:
    __slots__ = ("text", "start", "end", "confidence")

    def __init__(self, text: str, start: int, end: int, confidence: float):
        self.text = text
        self.start = start
        self.end = end
        self.confidence = confidence


class _ProtectedRange:
    __slots__ = ("start", "end", "type")

    def __init__(self, start: int, end: int, ptype: str):
        self.start = start
        self.end = end
        self.type = ptype


# ── Constants ──

_MIN_SEGMENT_LENGTH = 5
_MIN_SHORT_CONSECUTIVE = 3

# ── Patterns ──

_PLACEHOLDER_PATTERN = re.compile(r"\{\{[A-Z]+_\d+\}\}")

# Stage 1: Strong boundaries
_BLANK_LINE = re.compile(r"\n\n+")
_EXPLICIT_SEPARATOR = re.compile(r"(?:^|\n)[-=_]{3,}\s*(?:\n|$)", re.MULTILINE)
_BULLET = regex.compile(r"(?<=\n)(?:[-*\u2022]\s)")
_NUMBERED_LIST = regex.compile(r"(?<=\n)(?:\d{1,3}[.)]\s|[\u2460-\u2473]\s?)")

# Stage 2: Korean sentence endings (using `regex` for variable-width lookbehind)
_ENDING_FORMAL = regex.compile(
    r"(?<=겠습니다|하십시오|겠습니까|"
    r"습니다|입니다|됩니다|합니다|답니다|랍니다|십니다|"
    r"습니까|입니까|됩니까|합니까|십니까|십시오"
    r")(?:\s+|[.!?\u2026~;]\s*)"
)
_ENDING_POLITE = regex.compile(
    r"(?<=는데요|거든요|잖아요|니까요|라서요|던가요|텐데요|다고요|라고요|냐고요|자고요|은데요|던데요|"
    r"세요|에요|해요|예요|네요|군요|지요|어요|아요|게요|래요|나요|가요|고요|서요|걸요|대요|까요|셔요|구요"
    r")(?:\s+|[.!?\u2026~;]\s*)"
)
_ENDING_CASUAL = regex.compile(
    r"(?<=[았었했됐갔왔봤줬났겠셨]어|같어|않아|없어|있어|못해|"
    r"[았었했됐겠셨]지|"
    r"거든|잖아|는데|인데|한데|은데|던데|텐데|더라|니까|"
    r"할래|할게|갈게|볼게|줄게|을래|을게|을걸|"
    r"하자|해라|해봐|구나|구먼|이야|거야|건데|"
    r"다며|다더라|그치|시죠|던가"
    r")(?:\s+|[.!?\u2026~;]\s*)"
)
_ENDING_NARRATIVE = regex.compile(
    r"(?<=하게|하네|하세|"
    r"[했됐봤왔갔줬났]음|같음|있음|없음|아님|맞음|모름|드림|올림|알림|바람|나름|받음|보냄|"
    r"[했됐봤왔갔줬났겠]다|있다|없다|같다|한다|된다|간다|온다|는다|"
    r"됨|임|함|"
    r"죠|ㅋㅋ|ㅎㅎ|ㅠㅠ|ㅜㅜ"
    r")(?:\s+|[.!?\u2026~;]\s*)"
)
_KOREAN_ENDING_PATTERNS = [_ENDING_FORMAL, _ENDING_POLITE, _ENDING_CASUAL, _ENDING_NARRATIVE]

# Ambiguous endings that can be connective
_AMBIGUOUS_ENDINGS = frozenset(["는데", "인데", "한데", "은데", "던데", "텐데", "니까", "거든", "고", "건데"])

# Discourse markers
_DISCOURSE_MARKERS_SET = frozenset([
    "그리고", "또한", "게다가", "더구나", "심지어",
    "그런데", "근데", "하지만", "그러나", "그래도", "반면", "한편", "오히려", "그렇지만",
    "그래서", "그러므로", "결국", "그러니까", "그러니", "결과적으로",
    "그러면", "그럼", "그렇다면", "만약", "만일", "아니면",
    "아무튼", "어쨌든", "어쨌거나", "그나저나", "암튼",
    "마지막으로", "끝으로", "첫째", "둘째", "셋째",
    "결론적으로", "왜냐하면", "왜냐면",
])

# Stage 3: Weak punctuation
_WEAK_BOUNDARY = regex.compile(
    r"(?<=[.!?;])\s+|(?<=[.!?;])$|(?<=\u2026)\s*|(?<=\.{3})\s*|(?<=[\u2014\u2013])\s*",
    regex.MULTILINE,
)

# Stage 4: Korean postpositions
_POSTPOSITIONS = frozenset([
    "은", "는", "이", "가", "을", "를", "에", "의", "와", "과",
    "로", "도", "만", "까지", "부터", "에서", "처럼", "보다",
    "마다", "밖에", "조차", "든지", "이나", "에게", "한테", "께",
])

# Stage 5: Enumeration patterns
_COMMA_LIST = re.compile(r",\s*")
_DELIMITER_LIST = re.compile(r"[/\u00B7|]\s*")
_PARALLEL_GO = regex.compile(r"(?<=[가-힣])고\s+(?=[가-힣])")

# Stage 6: Discourse markers (sentence-start only)
_DISCOURSE_MARKER_ALTERNATIVES = (
    "그리고|또한|게다가|더구나|심지어|"
    "그런데|근데|하지만|그러나|그래도|반면|한편|오히려|그렇지만|"
    "그래서|그러므로|결국|그러니까|그러니|결과적으로|"
    "그러면|그럼|그렇다면|만약|만일|아니면|"
    "아무튼|어쨌든|어쨌거나|그나저나|암튼|"
    "마지막으로|끝으로|첫째|둘째|셋째|"
    "결론적으로|왜냐하면|왜냐면"
)
_DISCOURSE_MARKER_SPLIT = regex.compile(
    r"(?<=(?:[.!?;\u2026]\s)|(?:\n))(?=(?:" + _DISCOURSE_MARKER_ALTERNATIVES + r")\s)"
)

# Compound suffixes that should NOT be split
_COMPOUND_SUFFIXES = frozenset(["그런데도", "그래서인지", "그러나마나", "하지만서도", "그래도역시"])

# Parenthetical and quoted ranges
_PAREN_PATTERN = re.compile(r"\([^)]*\)")
_QUOTE_PATTERN = re.compile(r'"[^"]*"|\'[^\']*\'|\u201C[^\u201D]*\u201D|\u2018[^\u2019]*\u2019')


# ── Public API ──


def segment(masked_text: str) -> list[Segment]:
    """Segment the masked text into meaning units."""
    if not masked_text or not masked_text.strip():
        return []

    max_segment_length = settings.segmenter_max_segment_length
    discourse_marker_min_length = settings.segmenter_discourse_marker_min_length
    enumeration_min_length = settings.segmenter_enumeration_min_length

    protected_ranges = _collect_protected_ranges(masked_text)

    # Start with a single SplitUnit spanning the entire text
    units = [_SplitUnit(masked_text, 0, len(masked_text), 1.0)]

    # Stage 1: Strong structural boundaries
    units = _split_strong_boundaries(units, protected_ranges)

    # Stage 2: Korean sentence endings (with connective suppression)
    units = _split_korean_endings(units, protected_ranges)

    # Stage 3: Weak punctuation boundaries
    units = _apply_split_pattern(units, _WEAK_BOUNDARY, protected_ranges, 0.9, False)

    # Stage 4: Length-based safety split
    units = _force_split_long(units, protected_ranges, max_segment_length)

    # Stage 5: Enumeration detection
    units = _split_enumerations(units, protected_ranges, enumeration_min_length)

    # Stage 6: Discourse markers (length-restricted)
    units = _split_discourse_markers(units, protected_ranges, discourse_marker_min_length)

    # Stage 7: Merge over-segmented runs
    units = _merge_short_units(units)

    # Convert to Segment list
    segments = [Segment(id=f"T{i + 1}", text=u.text, start=u.start, end=u.end) for i, u in enumerate(units)]

    avg_conf = sum(u.confidence for u in units) / len(units) if units else 1.0
    min_conf = min((u.confidence for u in units), default=1.0)
    logger.info(
        "[Segmenter] %d segments from %d chars — avg confidence=%.2f, min=%.2f",
        len(segments), len(masked_text), avg_conf, min_conf,
    )

    return segments


# ── Protected range collection ──


def _collect_protected_ranges(text: str) -> list[_ProtectedRange]:
    ranges: list[_ProtectedRange] = []

    for m in _PLACEHOLDER_PATTERN.finditer(text):
        ranges.append(_ProtectedRange(m.start(), m.end(), "PLACEHOLDER"))

    for m in _PAREN_PATTERN.finditer(text):
        if not _overlaps_placeholder(m.start(), m.end(), ranges):
            ranges.append(_ProtectedRange(m.start(), m.end(), "PARENTHETICAL"))

    for m in _QUOTE_PATTERN.finditer(text):
        if not _overlaps_placeholder(m.start(), m.end(), ranges):
            ranges.append(_ProtectedRange(m.start(), m.end(), "QUOTED"))

    return ranges


def _overlaps_placeholder(start: int, end: int, ranges: list[_ProtectedRange]) -> bool:
    return any(r.type == "PLACEHOLDER" and start < r.end and end > r.start for r in ranges)


def _is_in_protected(global_pos: int, ranges: list[_ProtectedRange], strong_boundary: bool) -> bool:
    for r in ranges:
        if r.start <= global_pos < r.end:
            if r.type == "PLACEHOLDER":
                return True
            if not strong_boundary:
                return True
    return False


# ── Stage 1: Strong structural boundaries ──


def _split_strong_boundaries(units: list[_SplitUnit], protected_ranges: list[_ProtectedRange]) -> list[_SplitUnit]:
    result = units
    for pattern in [_BLANK_LINE, _EXPLICIT_SEPARATOR, _BULLET, _NUMBERED_LIST]:
        result = _apply_split_pattern(result, pattern, protected_ranges, 1.0, True)
    return result


# ── Stage 2: Korean sentence endings ──


def _split_korean_endings(units: list[_SplitUnit], protected_ranges: list[_ProtectedRange]) -> list[_SplitUnit]:
    current = units
    for ending_pattern in _KOREAN_ENDING_PATTERNS:
        current = _apply_split_pattern_with_connective_filter(current, ending_pattern, protected_ranges, 0.95)
    return current


def _apply_split_pattern_with_connective_filter(
    units: list[_SplitUnit],
    pattern: regex.Pattern,
    protected_ranges: list[_ProtectedRange],
    stage_confidence: float,
) -> list[_SplitUnit]:
    result: list[_SplitUnit] = []

    for unit in units:
        if len(unit.text) < 3:
            result.append(unit)
            continue

        split_points: list[tuple[int, int]] = []
        last_end = 0

        for m in pattern.finditer(unit.text):
            global_pos = unit.start + m.start()
            if _is_in_protected(global_pos, protected_ranges, False):
                continue

            ending_text = _extract_ending_before(unit.text, m.start())
            text_len_before = m.start() - last_end

            if ending_text in _AMBIGUOUS_ENDINGS:
                if not _should_split_ambiguous_ending(unit.text, m.end(), text_len_before):
                    continue

            split_points.append((m.start(), m.end()))
            last_end = m.end()

        if not split_points:
            result.append(unit)
        else:
            prev_end = 0
            for sp_start, sp_end in split_points:
                sub = unit.text[prev_end:sp_start].strip()
                if sub:
                    sub_start = unit.start + _find_substring_start(unit.text, prev_end, sub)
                    result.append(_SplitUnit(
                        sub, sub_start, sub_start + len(sub),
                        min(unit.confidence, stage_confidence),
                    ))
                prev_end = sp_end
            tail = unit.text[prev_end:].strip()
            if tail:
                tail_start = unit.start + _find_substring_start(unit.text, prev_end, tail)
                result.append(_SplitUnit(
                    tail, tail_start, tail_start + len(tail),
                    min(unit.confidence, stage_confidence),
                ))

    return result


def _extract_ending_before(text: str, match_start: int) -> str:
    for length in (3, 2, 1):
        if match_start >= length:
            candidate = text[match_start - length:match_start]
            if candidate in _AMBIGUOUS_ENDINGS:
                return candidate
    return ""


def _should_split_ambiguous_ending(chunk_text: str, after_match_end: int, text_len_before: int) -> bool:
    if text_len_before > 250:
        return True

    remaining = chunk_text[after_match_end:].strip()

    if not remaining:
        return True

    for marker in _DISCOURSE_MARKERS_SET:
        if remaining.startswith(marker + " ") or remaining.startswith(marker + "\n") or remaining == marker:
            return True

    return False


# ── Stage 4: Length-based safety split ──


def _force_split_long(
    units: list[_SplitUnit], protected_ranges: list[_ProtectedRange], max_segment_length: int
) -> list[_SplitUnit]:
    current = list(units)

    for _ in range(5):
        result: list[_SplitUnit] = []
        did_split = False

        for unit in current:
            if len(unit.text) <= max_segment_length:
                result.append(unit)
                continue

            chunk = unit.text
            mid = len(chunk) // 2
            best_split = -1
            best_dist = float("inf")

            search_start = max(10, mid - 60)
            search_end = min(len(chunk) - 5, mid + 60)

            for i in range(search_start, search_end):
                c = chunk[i]
                if c in (" ", ",", "\n") and not _is_in_protected(unit.start + i, protected_ranges, False):
                    if _is_after_postposition(chunk, i):
                        continue
                    dist = abs(i - mid)
                    if dist < best_dist:
                        best_dist = dist
                        best_split = i + 1

            # Retry without postposition avoidance
            if best_split < 0:
                for i in range(search_start, search_end):
                    c = chunk[i]
                    if c in (" ", ",", "\n") and not _is_in_protected(unit.start + i, protected_ranges, False):
                        dist = abs(i - mid)
                        if dist < best_dist:
                            best_dist = dist
                            best_split = i + 1

            if best_split > 0:
                left = chunk[:best_split].strip()
                right = chunk[best_split:].strip()
                if left:
                    left_start = unit.start + _find_substring_start(chunk, 0, left)
                    result.append(_SplitUnit(left, left_start, left_start + len(left), min(unit.confidence, 0.85)))
                if right:
                    right_start = unit.start + _find_substring_start(chunk, best_split, right)
                    result.append(_SplitUnit(right, right_start, right_start + len(right), min(unit.confidence, 0.85)))
                did_split = True
            else:
                result.append(unit)

        current = result
        if not did_split:
            break

    return current


def _is_after_postposition(chunk: str, split_pos: int) -> bool:
    for length in (3, 2, 1):
        start = split_pos - length
        if start < 0:
            continue
        candidate = chunk[start:split_pos]
        if candidate in _POSTPOSITIONS:
            return True
    return False


# ── Stage 5: Enumeration detection ──


def _split_enumerations(
    units: list[_SplitUnit], protected_ranges: list[_ProtectedRange], enumeration_min_length: int
) -> list[_SplitUnit]:
    result: list[_SplitUnit] = []

    for unit in units:
        if len(unit.text) <= enumeration_min_length:
            result.append(unit)
            continue

        for delimiter_pattern in [_COMMA_LIST, _DELIMITER_LIST, _PARALLEL_GO]:
            split = _try_split_by_delimiter(unit, delimiter_pattern, protected_ranges, 3, 15)
            if split is not None:
                result.extend(split)
                break
        else:
            result.append(unit)

    return result


def _try_split_by_delimiter(
    unit: _SplitUnit,
    delimiter: re.Pattern,
    protected_ranges: list[_ProtectedRange],
    min_parts: int,
    min_part_length: int,
) -> list[_SplitUnit] | None:
    text = unit.text
    match_positions: list[tuple[int, int]] = []

    for m in delimiter.finditer(text):
        global_pos = unit.start + m.start()
        if not _is_in_protected(global_pos, protected_ranges, False):
            match_positions.append((m.start(), m.end()))

    if len(match_positions) < min_parts - 1:
        return None

    parts: list[_SplitUnit] = []
    prev_end = 0
    for mp_start, mp_end in match_positions:
        part = text[prev_end:mp_start].strip()
        if part:
            part_start = unit.start + _find_substring_start(text, prev_end, part)
            parts.append(_SplitUnit(part, part_start, part_start + len(part), min(unit.confidence, 0.9)))
        prev_end = mp_end

    tail = text[prev_end:].strip()
    if tail:
        tail_start = unit.start + _find_substring_start(text, prev_end, tail)
        parts.append(_SplitUnit(tail, tail_start, tail_start + len(tail), min(unit.confidence, 0.9)))

    if len(parts) < min_parts:
        return None

    for p in parts:
        if len(p.text) < min_part_length:
            return None

    return parts


# ── Stage 6: Discourse markers ──


def _split_discourse_markers(
    units: list[_SplitUnit], protected_ranges: list[_ProtectedRange], discourse_marker_min_length: int
) -> list[_SplitUnit]:
    result: list[_SplitUnit] = []

    for unit in units:
        if len(unit.text) <= discourse_marker_min_length:
            result.append(unit)
            continue

        split_points: list[int] = []
        for m in _DISCOURSE_MARKER_SPLIT.finditer(unit.text):
            global_pos = unit.start + m.start()
            if _is_in_protected(global_pos, protected_ranges, False):
                continue

            remaining = unit.text[m.end():]
            if _is_compound_marker(remaining):
                continue
            if len(remaining.strip()) <= 4:
                continue

            split_points.append(m.end())

        if not split_points:
            result.append(unit)
        else:
            prev_end = 0
            for sp in split_points:
                sub = unit.text[prev_end:sp].strip()
                if sub:
                    sub_start = unit.start + _find_substring_start(unit.text, prev_end, sub)
                    result.append(_SplitUnit(sub, sub_start, sub_start + len(sub), min(unit.confidence, 0.88)))
                prev_end = sp
            tail = unit.text[prev_end:].strip()
            if tail:
                tail_start = unit.start + _find_substring_start(unit.text, prev_end, tail)
                result.append(_SplitUnit(tail, tail_start, tail_start + len(tail), min(unit.confidence, 0.88)))

    return result


def _is_compound_marker(remaining: str) -> bool:
    trimmed = remaining.strip()
    for compound in _COMPOUND_SUFFIXES:
        if trimmed.startswith(compound):
            return True
    for marker in _DISCOURSE_MARKERS_SET:
        if trimmed.startswith(marker) and len(trimmed) > len(marker):
            next_char = trimmed[len(marker)]
            if next_char not in (" ", "\n") and _is_hangul(next_char):
                return True
    return False


def _is_hangul(c: str) -> bool:
    return ("\uAC00" <= c <= "\uD7A3") or ("\u3131" <= c <= "\u318E")


# ── Stage 7: Merge short segments ──


def _merge_short_units(units: list[_SplitUnit]) -> list[_SplitUnit]:
    if len(units) <= 1:
        return units

    result: list[_SplitUnit] = []
    i = 0
    while i < len(units):
        short_start = i
        while i < len(units) and len(units[i].text) < _MIN_SEGMENT_LENGTH:
            i += 1
        short_count = i - short_start

        if short_count >= _MIN_SHORT_CONSECUTIVE:
            groups = _group_by_placeholder_boundary(units, short_start, i)
            for group in groups:
                if len(group) >= _MIN_SHORT_CONSECUTIVE:
                    result.append(_merge_group(group))
                else:
                    result.extend(group)
        else:
            for j in range(short_start, i):
                result.append(units[j])

        if i < len(units):
            result.append(units[i])
            i += 1

    return result


def _group_by_placeholder_boundary(
    units: list[_SplitUnit], from_idx: int, to_idx: int
) -> list[list[_SplitUnit]]:
    groups: list[list[_SplitUnit]] = []
    current: list[_SplitUnit] = []

    for i in range(from_idx, to_idx):
        unit = units[i]
        contains_placeholder = bool(_PLACEHOLDER_PATTERN.search(unit.text))

        if contains_placeholder and current:
            groups.append(current)
            current = []

        current.append(unit)

        if contains_placeholder:
            groups.append(current)
            current = []

    if current:
        groups.append(current)

    return groups


def _merge_group(group: list[_SplitUnit]) -> _SplitUnit:
    start = group[0].start
    end = group[-1].end
    text = " ".join(u.text for u in group)
    min_conf = min(u.confidence for u in group)
    return _SplitUnit(text, start, end, min_conf)


# ── Generic split utility ──


def _apply_split_pattern(
    units: list[_SplitUnit],
    pattern: re.Pattern,
    protected_ranges: list[_ProtectedRange],
    stage_confidence: float,
    is_strong_boundary: bool,
) -> list[_SplitUnit]:
    result: list[_SplitUnit] = []

    for unit in units:
        if len(unit.text) < 3:
            result.append(unit)
            continue

        last_end = 0
        split = False

        for m in pattern.finditer(unit.text):
            global_pos = unit.start + m.start()
            if _is_in_protected(global_pos, protected_ranges, is_strong_boundary):
                continue

            sub = unit.text[last_end:m.start()].strip()
            if sub:
                sub_start = unit.start + _find_substring_start(unit.text, last_end, sub)
                result.append(_SplitUnit(sub, sub_start, sub_start + len(sub), min(unit.confidence, stage_confidence)))
                split = True
            last_end = m.end()

        if split:
            tail = unit.text[last_end:].strip()
            if tail:
                tail_start = unit.start + _find_substring_start(unit.text, last_end, tail)
                result.append(_SplitUnit(
                    tail, tail_start, tail_start + len(tail),
                    min(unit.confidence, stage_confidence),
                ))
        else:
            result.append(unit)

    return result


# ── Position helpers ──


def _find_substring_start(parent: str, search_from: int, trimmed: str) -> int:
    pos = parent.find(trimmed, search_from)
    return pos if pos >= 0 else search_from
