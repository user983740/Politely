"""Tests for output validation rules."""

from app.models.domain import LockedSpan
from app.models.enums import (
    LockedSpanType,
    Severity,
    ValidationIssueType,
)
from app.pipeline.validation.output_validator import validate


def _span(index: int, original: str, placeholder: str, span_type: LockedSpanType) -> LockedSpan:
    return LockedSpan(
        index=index, original_text=original, placeholder=placeholder,
        type=span_type, start_pos=0, end_pos=len(original),
    )


# Rule 1: Emoji


def test_emoji_detection():
    result = validate("안녕하세요 😊", "안녕하세요", None, None)
    errors = [i for i in result.issues if i.type == ValidationIssueType.EMOJI]
    assert len(errors) >= 1
    assert errors[0].severity == Severity.ERROR


# Rule 2: Forbidden phrases


def test_forbidden_phrase_detection():
    result = validate(
        "변환 결과입니다. 좋은 하루 되세요.", "원문", None, None,
    )
    errors = [i for i in result.issues if i.type == ValidationIssueType.FORBIDDEN_PHRASE]
    assert len(errors) >= 1
    assert errors[0].severity == Severity.ERROR


# Rule 3: Hallucinated fact (number not in original)


def test_hallucinated_fact_number():
    result = validate(
        "총 50000원이 청구되었습니다.", "금액을 확인해주세요.", None, None,
    )
    warnings = [i for i in result.issues if i.type == ValidationIssueType.HALLUCINATED_FACT]
    assert len(warnings) >= 1
    assert warnings[0].severity == Severity.WARNING


# Rule 4: Ending repetition (3x same ending)


def test_ending_repetition_detection():
    text = (
        "확인하겠습니다.\n"
        "보고하겠습니다.\n"
        "처리하겠습니다.\n"
        "전달하겠습니다.\n"
        "안내하겠습니다."
    )
    result = validate(text, "원문", None, None)
    warnings = [i for i in result.issues if i.type == ValidationIssueType.ENDING_REPETITION]
    assert len(warnings) >= 1


# Rule 5: Length overexpansion (>3x original)


def test_length_overexpansion():
    original = "이것은 테스트 문장입니다 확인 바랍니다"  # 20 chars
    output = original * 5  # 100 chars > 3x
    result = validate(output, original, None, None)
    warnings = [i for i in result.issues if i.type == ValidationIssueType.LENGTH_OVEREXPANSION]
    assert len(warnings) >= 1
    assert warnings[0].severity == Severity.WARNING


# Rule 6: Perspective error


def test_perspective_error():
    result = validate(
        "확인해 드리겠습니다. 감사합니다.",
        "확인해 주세요", None, None,
    )
    warnings = [i for i in result.issues if i.type == ValidationIssueType.PERSPECTIVE_ERROR]
    assert len(warnings) >= 1
    assert warnings[0].severity == Severity.WARNING


# Rule 7: Locked span missing


def test_locked_span_missing():
    spans = [_span(1, "test@email.com", "{{EMAIL_1}}", LockedSpanType.EMAIL)]
    result = validate(
        "이메일 주소가 없습니다.", "test@email.com으로 연락주세요",
        spans, "변환된 결과입니다.",
    )
    errors = [i for i in result.issues if i.type == ValidationIssueType.LOCKED_SPAN_MISSING]
    assert len(errors) >= 1
    assert errors[0].severity == Severity.ERROR


def test_locked_span_present_in_raw():
    spans = [_span(1, "test@email.com", "{{EMAIL_1}}", LockedSpanType.EMAIL)]
    result = validate(
        "test@email.com으로 연락 부탁드립니다.",
        "test@email.com으로 연락주세요",
        spans, "{{EMAIL_1}}으로 연락 부탁드립니다.",
    )
    errors = [i for i in result.issues if i.type == ValidationIssueType.LOCKED_SPAN_MISSING]
    assert len(errors) == 0


# Rule 8: Redacted reentry


def test_redacted_reentry():
    redaction_map = {"[REDACTED:AGGRESSION_1]": "이 멍청한 놈아 진짜 짜증나네"}
    result = validate(
        "이 멍청한 놈아 진짜 짜증나네 확인 바랍니다.",
        "원문", None, None,
        redaction_map=redaction_map,
    )
    errors = [i for i in result.issues if i.type == ValidationIssueType.REDACTED_REENTRY]
    assert len(errors) >= 1


# Rule 8b: Redaction trace ([삭제됨])


def test_redaction_trace():
    result = validate(
        "내용입니다. [삭제됨] 확인 바랍니다.", "원문", None, None,
    )
    errors = [i for i in result.issues if i.type == ValidationIssueType.REDACTION_TRACE]
    assert len(errors) >= 1
    assert errors[0].severity == Severity.ERROR


# Rule 9: Core number missing


def test_core_number_missing():
    result = validate(
        "금액을 확인해주세요.", "50000원을 입금해주세요.", None, None,
    )
    warnings = [i for i in result.issues if i.type == ValidationIssueType.CORE_NUMBER_MISSING]
    assert len(warnings) >= 1
    assert warnings[0].severity == Severity.WARNING


# Rule 10: Core date missing


def test_core_date_missing():
    result = validate(
        "기한을 확인해주세요.", "2024-03-15까지 제출해주세요.", None, None,
    )
    warnings = [i for i in result.issues if i.type == ValidationIssueType.CORE_DATE_MISSING]
    assert len(warnings) >= 1
    assert warnings[0].severity == Severity.WARNING


# Rule 13: Informal conjunction


def test_informal_conjunction_detected():
    result = validate(
        "어쨌든 확인 부탁드립니다. 아무튼 일정을 조율하겠습니다.",
        "어쨌든 확인해주세요. 아무튼 일정 조율합시다.",
        None, None,
    )
    warnings = [i for i in result.issues if i.type == ValidationIssueType.INFORMAL_CONJUNCTION]
    assert len(warnings) >= 1
    assert warnings[0].severity == Severity.WARNING


def test_informal_conjunction_not_detected_in_clean():
    result = validate(
        "확인 부탁드립니다. 이에 따라 일정을 조율하겠습니다.",
        "확인해주세요.",
        None, None,
    )
    warnings = [i for i in result.issues if i.type == ValidationIssueType.INFORMAL_CONJUNCTION]
    assert len(warnings) == 0


# Clean output passes


def test_clean_output_passes():
    result = validate(
        "보고서 제출 부탁드립니다.",
        "보고서 제출해주세요.",
        None, None,
    )
    assert result.passed is True
    assert result.has_errors() is False


# ValidationResult helpers


def test_validation_result_helpers():
    result = validate("안녕 😊 변환 결과", "안녕", None, None)
    assert result.has_errors() is True
    assert len(result.errors()) >= 1
    assert all(i.severity == Severity.ERROR for i in result.errors())
    assert all(i.severity == Severity.WARNING for i in result.warnings())
