"""Rule-based post-processing validator for LLM output.

Checks 12 rules and returns validation results.
"""

import logging
import re

from app.models.domain import LabeledSegment, LockedSpan, ValidationIssue, ValidationResult
from app.models.enums import (
    Persona,
    SegmentLabel,
    Severity,
    ValidationIssueType,
)
from app.pipeline.template.structure_template import StructureSection, StructureTemplate

logger = logging.getLogger(__name__)

# Rule 1: Emoji detection
_EMOJI_PATTERN = re.compile(
    "["
    "\U0001F600-\U0001F64F"  # Emoticons
    "\U0001F300-\U0001F5FF"  # Misc Symbols and Pictographs
    "\U0001F680-\U0001F6FF"  # Transport and Map
    "\U0001F1E0-\U0001F1FF"  # Flags
    "\U0000FE00-\U0000FE0F"  # Variation Selectors
    "\U0001F3FB-\U0001F3FF"  # Skin tone modifiers
    "\U0000200D"             # ZWJ
    "\U0001F900-\U0001F9FF"  # Supplemental Symbols
    "\U0001FA00-\U0001FA6F"  # Chess Symbols
    "\U0001FA70-\U0001FAFF"  # Symbols Extended-A
    "\U00002600-\U000026FF"  # Misc symbols
    "\U00002700-\U000027BF"  # Dingbats
    "\U0000231A-\U0000231B"  # Watch, Hourglass
    "\U000023E9-\U000023F3"  # Media controls
    "\U000023F8-\U000023FA"  # Media controls
    "\U000025AA-\U000025AB"  # Squares
    "\U000025B6\U000025C0"   # Play buttons
    "\U000025FB-\U000025FE"  # Squares
    "\U00002614-\U00002615"  # Umbrella, Hot Beverage
    "\U00002648-\U00002653"  # Zodiac
    "\U0000267F\U00002693"   # Wheelchair, Anchor
    "\U000026A1\U000026AA-\U000026AB"
    "\U000026BD-\U000026BE"
    "\U000026C4-\U000026C5"
    "\U000026CE-\U000026D4"
    "\U000026EA\U000026F2-\U000026F3"
    "\U000026F5\U000026FA\U000026FD"
    "\U00002934-\U00002935"
    "\U00002B05-\U00002B07"
    "\U00002B1B-\U00002B1C"
    "\U00002B50\U00002B55"
    "\U00003030\U0000303D\U00003297\U00003299"
    "]"
)

# Rule 2: Forbidden phrases (LLM meta-commentary)
_FORBIDDEN_PHRASES = [
    "변환 결과",
    "다음과 같이",
    "도움이 되셨으면",
    "변환해 드리겠",
    "아래와 같이",
    "다음은 변환",
    "변환된 텍스트",
    "이렇게 변환",
    "존댓말로 바꾸",
    "다듬어 보았",
]

# Rule 4: Korean sentence endings
_ENDING_PATTERN = re.compile(
    r"[가-힣]*?(드리겠습니다|겠습니다|드립니다|할게요|합니다|됩니다|됩니까|십시오|습니다|니다|세요|에요|해요|예요|네요|군요|는데요|거든요|잖아요|지요|죠|요)[.!?]?\s*$",
    re.MULTILINE,
)
_DEURIGET_PATTERN = re.compile(r"드리겠습니다")

# Rule 6: Perspective error hints
_PERSPECTIVE_PHRASES = [
    "확인해 드리겠습니다",
    "접수되었습니다",
    "처리해 드리겠습니다",
    "안내해 드리겠습니다",
    "도와드리겠습니다",
    "답변드리겠습니다",
    "알려드리겠습니다",
    "연락드리겠습니다",
    "보내드리겠습니다",
    "전달드리겠습니다",
    "안내 드리겠습니다",
    "처리 드리겠습니다",
]

# Rule 9: Core number pattern
_CORE_NUMBER_PATTERN = re.compile(r"\d{1,3}(?:,\d{3})+|\d{3,}")

# Rule 3: Safe number context
_SAFE_NUMBER_CONTEXT = re.compile(r"\d{2,4}년|제\d+|\d+호|\d+층|\d+차|\d+번째")

# Rule 3: Korean spelled-out large numbers
_KOREAN_NUMBER_PATTERN = re.compile(
    r"(?:약\s*)?(?:\d+)?(?:십|백|천|만|억|조)\s*(?:십|백|천|만|억|조)?\s*(?:원|명|개|건|일|시간|분|배)"
)

# Rule 10: Date/time patterns
_DATE_PATTERNS = [
    re.compile(r"\d{4}[./-]\d{1,2}([./-]\d{1,2})?"),
    re.compile(r"\d{1,2}월\s*\d{1,2}일"),
    re.compile(r"\d{1,2}:\d{2}"),
]

# Rule 11: Stopwords
_STOPWORDS = frozenset({
    "은", "는", "이", "가", "을", "를", "에", "의", "와", "과",
    "로", "도", "만", "까지", "부터", "에서", "처럼", "보다",
    "그리고", "하지만", "또한", "그래서", "그런데", "따라서",
    "문제", "확인", "요청", "부분", "경우", "상황", "내용",
    "것", "수", "등", "및", "위해", "대해", "통해",
})

_KOREAN_WORD = re.compile(r"[가-힣]{2,}")

# Rule 12: S2 effort pattern
_S2_EFFORT_PATTERN = re.compile(r"확인|점검|검토|살펴|조사|파악|내부.*결과|담당.*확인|로그.*기준")

# Rule 8: Censorship trace phrases
_CENSORSHIP_TRACES = [
    "[삭제됨]", "[REDACTED", "삭제된 내용", "제거된 부분", "삭제된 부분",
    "일부 내용을 삭제", "부적절한 내용이 제거",
]

# Rule 5
_MAX_ABSOLUTE_OUTPUT_LENGTH = 6000


def validate(
    final_text: str,
    original_text: str,
    spans: list[LockedSpan] | None,
    raw_llm_output: str | None,
    persona: Persona,
    redaction_map: dict[str, str] | None = None,
    yellow_segment_texts: list[str] | None = None,
) -> ValidationResult:
    """Validate the LLM output against all 11 rules."""
    if redaction_map is None:
        redaction_map = {}
    if yellow_segment_texts is None:
        yellow_segment_texts = []

    issues: list[ValidationIssue] = []

    _check_emoji(final_text, issues)
    _check_forbidden_phrases(final_text, issues)
    _check_hallucinated_facts(final_text, original_text, spans, issues)
    _check_ending_repetition(final_text, issues)
    _check_length_overexpansion(final_text, original_text, issues)
    _check_perspective_error(final_text, persona, issues)
    _check_locked_span_missing(raw_llm_output, final_text, spans, issues)
    _check_redacted_reentry(final_text, raw_llm_output, redaction_map, issues)
    _check_core_number_missing(final_text, original_text, spans, issues)
    _check_core_date_missing(final_text, original_text, spans, issues)
    _check_soften_content_dropped(final_text, yellow_segment_texts, issues)

    passed = not any(i.severity == Severity.ERROR for i in issues)

    if issues:
        logger.info(
            "Validation completed: %d issues (%d errors, %d warnings)",
            len(issues),
            sum(1 for i in issues if i.severity == Severity.ERROR),
            sum(1 for i in issues if i.severity == Severity.WARNING),
        )

    return ValidationResult(passed=passed, issues=issues)


def validate_with_template(
    final_text: str,
    original_text: str,
    spans: list[LockedSpan] | None,
    raw_llm_output: str | None,
    persona: Persona,
    redaction_map: dict[str, str] | None,
    yellow_segment_texts: list[str] | None,
    template: StructureTemplate | None,
    effective_sections: list[StructureSection] | None,
    labeled_segments: list[LabeledSegment] | None,
) -> ValidationResult:
    """Validate with template-aware S2 presence check."""
    base_result = validate(
        final_text, original_text, spans, raw_llm_output,
        persona, redaction_map, yellow_segment_texts,
    )

    all_issues = list(base_result.issues)
    _check_section_s2_missing(final_text, effective_sections, labeled_segments, all_issues)

    passed = not any(i.severity == Severity.ERROR for i in all_issues)
    return ValidationResult(passed=passed, issues=all_issues)


def build_locked_span_retry_hint(
    issues: list[ValidationIssue],
    locked_spans: list[LockedSpan] | None,
) -> str:
    """Build a specific retry hint for LOCKED_SPAN_MISSING errors."""
    missing_span_issues = [
        i for i in issues
        if i.type == ValidationIssueType.LOCKED_SPAN_MISSING and i.severity == Severity.ERROR
    ]

    if not missing_span_issues or not locked_spans:
        return ""

    missing_placeholders = {
        i.matched_text for i in missing_span_issues if i.matched_text is not None
    }

    parts = ["\n\n[고정 표현 누락 오류] 다음 고정 표현이 출력에 반드시 포함되어야 합니다:\n"]
    for span in locked_spans:
        if span.placeholder in missing_placeholders:
            parts.append(f"- {span.placeholder} → \"{span.original_text}\"\n")
    parts.append("위 플레이스홀더를 변환 결과에 반드시 자연스럽게 포함하세요. 절대 누락하지 마세요.")

    return "".join(parts)


# ===== Rule implementations =====


def _check_emoji(output: str, issues: list[ValidationIssue]) -> None:
    for m in _EMOJI_PATTERN.finditer(output):
        issues.append(ValidationIssue(
            type=ValidationIssueType.EMOJI,
            severity=Severity.ERROR,
            message=f'이모지 감지: "{m.group()}"',
            matched_text=m.group(),
        ))


def _check_forbidden_phrases(output: str, issues: list[ValidationIssue]) -> None:
    for phrase in _FORBIDDEN_PHRASES:
        if phrase in output:
            issues.append(ValidationIssue(
                type=ValidationIssueType.FORBIDDEN_PHRASE,
                severity=Severity.ERROR,
                message=f'금지 구문 감지: "{phrase}"',
                matched_text=phrase,
            ))


def _check_hallucinated_facts(
    output: str, original_text: str, spans: list[LockedSpan] | None, issues: list[ValidationIssue],
) -> None:
    # Check 1: Numeric patterns (3+ digits)
    number_pattern = re.compile(r"\d{3,}")
    for m in number_pattern.finditer(output):
        found = m.group()

        exists_in_original = found in original_text
        exists_in_spans = spans is not None and any(found in s.original_text for s in spans)
        if exists_in_original or exists_in_spans:
            continue

        # Skip safe contextual patterns
        ctx_start = max(0, m.start() - 2)
        ctx_end = min(len(output), m.end() + 3)
        context = output[ctx_start:ctx_end]
        if _SAFE_NUMBER_CONTEXT.search(context):
            continue

        issues.append(ValidationIssue(
            type=ValidationIssueType.HALLUCINATED_FACT,
            severity=Severity.WARNING,
            message=f'원문에 없는 숫자/날짜 감지: "{found}"',
            matched_text=found,
        ))

    # Check 2: Korean spelled-out numbers
    for m in _KOREAN_NUMBER_PATTERN.finditer(output):
        found = m.group()
        if found not in original_text:
            core = re.sub(r"\s+", "", found).lstrip("약")
            if core not in original_text.replace(" ", ""):
                issues.append(ValidationIssue(
                    type=ValidationIssueType.HALLUCINATED_FACT,
                    severity=Severity.WARNING,
                    message=f'원문에 없는 한국어 수량 표현 감지: "{found}"',
                    matched_text=found,
                ))


def _check_ending_repetition(output: str, issues: list[ValidationIssue]) -> None:
    endings: list[str] = []
    for m in _ENDING_PATTERN.finditer(output):
        endings.append(m.group(1))

    # Check 3 consecutive same endings
    for i in range(len(endings) - 2):
        if endings[i] == endings[i + 1] == endings[i + 2]:
            issues.append(ValidationIssue(
                type=ValidationIssueType.ENDING_REPETITION,
                severity=Severity.WARNING,
                message=f'동일 종결어미 3회 연속: "{endings[i]}"',
                matched_text=endings[i],
            ))
            break

    # Check "드리겠습니다" frequency
    count = len(_DEURIGET_PATTERN.findall(output))
    if count >= 3:
        issues.append(ValidationIssue(
            type=ValidationIssueType.ENDING_REPETITION,
            severity=Severity.WARNING,
            message=f'"드리겠습니다" {count}회 사용 (3회 이상)',
            matched_text="드리겠습니다",
        ))


def _check_length_overexpansion(output: str, original_text: str, issues: list[ValidationIssue]) -> None:
    if len(original_text) >= 20 and len(output) > len(original_text) * 3:
        issues.append(ValidationIssue(
            type=ValidationIssueType.LENGTH_OVEREXPANSION,
            severity=Severity.WARNING,
            message=(
                f"출력 길이 과확장: 입력 {len(original_text)}자 → 출력 {len(output)}자"
                f" ({len(output) / len(original_text):.1f}배)"
            ),
        ))

    if len(output) > _MAX_ABSOLUTE_OUTPUT_LENGTH:
        issues.append(ValidationIssue(
            type=ValidationIssueType.LENGTH_OVEREXPANSION,
            severity=Severity.WARNING,
            message=f"출력 길이 절대 상한 초과: {len(output)}자 (상한: {_MAX_ABSOLUTE_OUTPUT_LENGTH}자)",
        ))


def _check_perspective_error(output: str, persona: Persona, issues: list[ValidationIssue]) -> None:
    if persona in (Persona.CLIENT, Persona.OFFICIAL):
        return

    for phrase in _PERSPECTIVE_PHRASES:
        if phrase in output:
            issues.append(ValidationIssue(
                type=ValidationIssueType.PERSPECTIVE_ERROR,
                severity=Severity.WARNING,
                message=f'관점 오류 힌트: "{phrase}" (받는 사람이 {persona}일 때 부적절)',
                matched_text=phrase,
            ))


def _check_locked_span_missing(
    raw_llm_output: str | None, final_text: str | None,
    spans: list[LockedSpan] | None, issues: list[ValidationIssue],
) -> None:
    if not spans or raw_llm_output is None:
        return

    for span in spans:
        # Check exact placeholder
        if span.placeholder in raw_llm_output:
            continue

        # Check flexible placeholder pattern
        prefix = span.type.placeholder_prefix
        flexible = re.compile(rf"\{{\{{\s*{prefix}[-_]?{span.index}\s*\}}\}}")
        if flexible.search(raw_llm_output):
            continue

        # Check if original text appears in raw output
        if span.original_text in raw_llm_output:
            continue

        # Final fallback: check in final output
        if final_text and span.original_text in final_text:
            continue

        issues.append(ValidationIssue(
            type=ValidationIssueType.LOCKED_SPAN_MISSING,
            severity=Severity.ERROR,
            message=f'LockedSpan 누락: {span.placeholder} ("{span.original_text}")',
            matched_text=span.placeholder,
        ))


def _check_redacted_reentry(
    final_text: str, raw_llm_output: str | None,
    redaction_map: dict[str, str], issues: list[ValidationIssue],
) -> None:
    # Check 1: Original redacted text (>=6 chars) should not appear in output
    if redaction_map:
        normalized_output = _normalize_for_reentry(final_text)
        for marker, original_text in redaction_map.items():
            if len(original_text) >= 6:
                normalized_original = _normalize_for_reentry(original_text)
                if len(normalized_original) >= 4 and normalized_original in normalized_output:
                    issues.append(ValidationIssue(
                        type=ValidationIssueType.REDACTED_REENTRY,
                        severity=Severity.ERROR,
                        message=f'제거된 내용 재유입: "{original_text[:30]}..."',
                        matched_text=marker,
                    ))

        # Check 1b: Semantic keyword reentry
        for marker, original_text in redaction_map.items():
            keywords = _extract_meaning_words(original_text)
            distinctive_keywords = [w for w in keywords if len(w) >= 3 and w not in _STOPWORDS]
            if len(distinctive_keywords) >= 2:
                match_count = sum(1 for w in distinctive_keywords if w in final_text)
                if match_count >= 2:
                    issues.append(ValidationIssue(
                        type=ValidationIssueType.REDACTED_REENTRY,
                        severity=Severity.WARNING,
                        message=(
                            f'제거된 내용 의미적 재유입 의심: '
                            f'"{original_text[:30]}..." '
                            f'(키워드 {match_count}개 일치)'
                        ),
                        matched_text=marker,
                    ))

    # Check 2: Censorship trace phrases in output
    for trace in _CENSORSHIP_TRACES:
        if trace in final_text:
            issues.append(ValidationIssue(
                type=ValidationIssueType.REDACTION_TRACE,
                severity=Severity.ERROR,
                message=f'검열 흔적 문구 감지: "{trace}"',
                matched_text=trace,
            ))


def _check_core_number_missing(
    final_text: str, original_text: str,
    spans: list[LockedSpan] | None, issues: list[ValidationIssue],
) -> None:
    locked_numbers = _collect_locked_numbers(spans, _CORE_NUMBER_PATTERN)

    for m in _CORE_NUMBER_PATTERN.finditer(original_text):
        number = m.group()
        normalized = number.replace(",", "")
        if normalized in locked_numbers:
            continue

        # Check if output contains the number
        if number in final_text or normalized in final_text:
            continue
        if normalized in final_text.replace(",", ""):
            continue

        # Extended context check
        ctx_start = max(0, m.start() - 8)
        ctx_end = min(len(original_text), m.end() + 8)
        if _SAFE_NUMBER_CONTEXT.search(original_text[ctx_start:ctx_end]):
            continue

        issues.append(ValidationIssue(
            type=ValidationIssueType.CORE_NUMBER_MISSING,
            severity=Severity.WARNING,
            message=f'원문 숫자 누락: "{number}"',
            matched_text=number,
        ))


def _check_core_date_missing(
    final_text: str, original_text: str,
    spans: list[LockedSpan] | None, issues: list[ValidationIssue],
) -> None:
    locked_texts = _collect_locked_texts(spans)

    for pattern in _DATE_PATTERNS:
        for m in pattern.finditer(original_text):
            date_str = m.group()
            if any(date_str in s for s in locked_texts):
                continue

            # 1st: exact match
            if date_str in final_text:
                continue

            # 2nd: separator normalization
            normalized_date = re.sub(r"[./-]", "-", date_str)
            normalized_output = re.sub(r"[./-]", "-", final_text)
            if normalized_date in normalized_output:
                continue

            # 3rd: numeric sequence comparison
            date_nums = _extract_date_numbers(date_str)
            numeric_match = False
            for out_m in pattern.finditer(final_text):
                if _extract_date_numbers(out_m.group()) == date_nums:
                    numeric_match = True
                    break
            if numeric_match:
                continue

            issues.append(ValidationIssue(
                type=ValidationIssueType.CORE_DATE_MISSING,
                severity=Severity.WARNING,
                message=f'원문 날짜/시간 누락: "{date_str}"',
                matched_text=date_str,
            ))


def _check_soften_content_dropped(
    final_text: str, yellow_segment_texts: list[str], issues: list[ValidationIssue],
) -> None:
    if not yellow_segment_texts:
        return

    for seg_text in yellow_segment_texts:
        if len(seg_text) < 15:
            continue

        meaning_words = _extract_meaning_words(seg_text)
        if len(meaning_words) < 2:
            continue

        # Pass condition 1: meaning word match
        has_word_match = any(
            _contains_with_particle_variation(final_text, word)
            for word in meaning_words
        )
        if has_word_match:
            continue

        # Pass condition 2: core number match
        has_number_match = any(
            m.group() in final_text
            for m in re.finditer(r"\d{3,}", seg_text)
        )
        if has_number_match:
            continue

        issues.append(ValidationIssue(
            type=ValidationIssueType.SOFTEN_CONTENT_DROPPED,
            severity=Severity.WARNING,
            message=f'SOFTEN 대상 내용 완전 소실: "{seg_text[:30]}..."',
        ))


def _check_section_s2_missing(
    final_text: str,
    effective_sections: list[StructureSection] | None,
    labeled_segments: list[LabeledSegment] | None,
    issues: list[ValidationIssue],
) -> None:
    if effective_sections is None or labeled_segments is None:
        return

    if StructureSection.S2_OUR_EFFORT not in effective_sections:
        return

    has_relevant_labels = any(
        ls.label in (SegmentLabel.ACCOUNTABILITY, SegmentLabel.NEGATIVE_FEEDBACK)
        for ls in labeled_segments
    )
    if not has_relevant_labels:
        return

    if not _S2_EFFORT_PATTERN.search(final_text):
        issues.append(ValidationIssue(
            type=ValidationIssueType.SECTION_S2_MISSING,
            severity=Severity.WARNING,
            message="S2(내부 확인/점검) 섹션 누락: 템플릿에 포함되어 있으나 출력에 확인/점검 표현 없음",
        ))


# ===== Helper functions =====


def _normalize_for_reentry(text: str) -> str:
    return re.sub(r"[^가-힣a-zA-Z0-9]", "", text)


def _collect_locked_numbers(spans: list[LockedSpan] | None, number_pattern: re.Pattern) -> set[str]:
    result: set[str] = set()
    if not spans:
        return result
    for span in spans:
        for m in number_pattern.finditer(span.original_text):
            result.add(m.group().replace(",", ""))
    return result


def _collect_locked_texts(spans: list[LockedSpan] | None) -> set[str]:
    if not spans:
        return set()
    return {s.original_text for s in spans}


def _extract_date_numbers(date_str: str) -> list[int]:
    return [int(m.group()) for m in re.finditer(r"\d+", date_str)]


def _contains_with_particle_variation(text: str, word: str) -> bool:
    """Check if text contains the word, accounting for Korean particle variations."""
    if word in text:
        return True
    for length in range(len(word) - 1, max(1, len(word) - 3), -1):
        if word[:length] in text:
            return True
    return False


def _extract_meaning_words(text: str) -> list[str]:
    words: list[str] = []
    for m in _KOREAN_WORD.finditer(text):
        word = m.group()
        if word not in _STOPWORDS:
            words.append(word)
    return words
