package com.politeai.infrastructure.ai.validation;

import com.politeai.domain.transform.model.*;
import com.politeai.domain.transform.model.ValidationIssue.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutputValidatorTest {

    private OutputValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OutputValidator();
    }

    private ValidationResult validate(String output, String originalText, Persona persona) {
        return validator.validate(output, originalText, List.of(), output, persona);
    }

    private ValidationResult validateWithSpans(String output, String originalText,
                                                List<LockedSpan> spans, String maskedOutput, Persona persona) {
        return validator.validate(output, originalText, spans, maskedOutput, persona);
    }

    private ValidationResult validateWithSpansAndRedaction(String output, String originalText,
                                                            List<LockedSpan> spans, String rawLlmOutput,
                                                            Persona persona, Map<String, String> redactionMap) {
        return validator.validate(output, originalText, spans, rawLlmOutput,
                persona, redactionMap, List.of());
    }

    private ValidationResult validateWithYellow(String output, String originalText,
                                                 Persona persona, List<String> yellowTexts) {
        return validator.validate(output, originalText, List.of(), output,
                persona, Map.of(), yellowTexts);
    }

    @Nested
    @DisplayName("규칙 1: 이모지 감지")
    class EmojiTests {
        @Test
        void 이모지_포함시_ERROR() {
            ValidationResult result = validate("안녕하세요 😊", "안녕", Persona.BOSS);
            assertThat(result.passed()).isFalse();
            assertThat(result.errors()).anyMatch(i -> i.type() == ValidationIssueType.EMOJI);
        }

        @Test
        void 이모지_없으면_통과() {
            ValidationResult result = validate("안녕하세요. 잘 부탁드립니다.", "안녕", Persona.BOSS);
            assertThat(result.issues().stream().filter(i -> i.type() == ValidationIssueType.EMOJI).toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("규칙 2: 금지 구문")
    class ForbiddenPhraseTests {
        @Test
        void 메타발언_포함시_ERROR() {
            ValidationResult result = validate("다음과 같이 변환했습니다. 안녕하세요.", "안녕", Persona.BOSS);
            assertThat(result.passed()).isFalse();
            assertThat(result.errors()).anyMatch(i -> i.type() == ValidationIssueType.FORBIDDEN_PHRASE);
        }

        @Test
        void 변환결과_포함시_ERROR() {
            ValidationResult result = validate("변환 결과입니다: 안녕하세요.", "안녕", Persona.BOSS);
            assertThat(result.passed()).isFalse();
        }

        @Test
        void 정상_텍스트는_통과() {
            ValidationResult result = validate("안녕하세요. 건으로 연락드립니다.", "안녕", Persona.BOSS);
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.FORBIDDEN_PHRASE).toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("규칙 3: 환각 숫자/날짜")
    class HallucinatedFactTests {
        @Test
        void 원문에_없는_숫자는_WARNING() {
            // 3자리 이상 숫자 (150000원 등)만 감지 — "2025년" 같은 연도 패턴은 safe context로 허용
            ValidationResult result = validate("금액이 150000원입니다", "만나요", Persona.BOSS);
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.HALLUCINATED_FACT);
        }

        @Test
        void 원문에_있는_숫자는_통과() {
            ValidationResult result = validate("2024년 2월 4일에 만나요", "2024년 2월 4일에 만나요", Persona.BOSS);
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.HALLUCINATED_FACT).toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("규칙 4: 종결어미 반복")
    class EndingRepetitionTests {
        @Test
        void 동일어미_3회_연속_WARNING() {
            String output = "보고하겠습니다.\n확인하겠습니다.\n진행하겠습니다.";
            ValidationResult result = validate(output, "원문", Persona.OTHER);
            // "겠습니다" 3회 연속
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.ENDING_REPETITION);
        }

        @Test
        void 드리겠습니다_3회이상_WARNING() {
            String output = "확인해 드리겠습니다. 그리고 보고 드리겠습니다. 마지막으로 처리해 드리겠습니다.";
            ValidationResult result = validate(output, "원문", Persona.BOSS);
            assertThat(result.warnings()).anyMatch(i ->
                    i.type() == ValidationIssueType.ENDING_REPETITION && i.matchedText().contains("드리겠습니다"));
        }

        @Test
        void 다양한_어미는_통과() {
            String output = "안녕하세요. 건으로 연락드립니다. 확인 부탁드려도 될까요?";
            ValidationResult result = validate(output, "원문", Persona.BOSS);
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.ENDING_REPETITION).toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("규칙 5: 길이 과확장")
    class LengthOverexpansionTests {
        @Test
        void 삼배_초과시_WARNING() {
            String longOriginal = "이거 좀 확인해 주시면 감사하겠습니다 정말 부탁드립니다"; // >= 20자
            int originalLen = longOriginal.length();
            String output = "가".repeat(originalLen * 3 + 1); // > original * 3

            ValidationResult result = validate(output, longOriginal, Persona.BOSS);
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.LENGTH_OVEREXPANSION);
        }

        @Test
        void 짧은_원문에서는_검사안함() {
            String original = "봐주세요"; // 4자 < 20자
            String output = "a".repeat(100);

            ValidationResult result = validate(output, original, Persona.BOSS);
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.LENGTH_OVEREXPANSION).toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("규칙 6: 관점 오류")
    class PerspectiveErrorTests {
        @Test
        void BOSS에게_확인해드리겠습니다는_WARNING() {
            ValidationResult result = validate("확인해 드리겠습니다.", "확인 요청", Persona.BOSS);
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.PERSPECTIVE_ERROR);
        }

        @Test
        void CLIENT에게는_관점오류_검사안함() {
            ValidationResult result = validate("확인해 드리겠습니다.", "확인 요청", Persona.CLIENT);
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.PERSPECTIVE_ERROR).toList()).isEmpty();
        }

        @Test
        void OFFICIAL에게는_관점오류_검사안함() {
            ValidationResult result = validate("처리해 드리겠습니다.", "처리 요청", Persona.OFFICIAL);
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.PERSPECTIVE_ERROR).toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("규칙 7: LockedSpan 누락")
    class LockedSpanMissingTests {
        @Test
        void 플레이스홀더_누락시_ERROR() {
            List<LockedSpan> spans = List.of(
                    new LockedSpan(1, "2024년 2월 4일", "{{DATE_1}}", LockedSpanType.DATE, 0, 11),
                    new LockedSpan(1, "150,000원", "{{MONEY_1}}", LockedSpanType.MONEY, 13, 22)
            );

            // maskedOutput에서 MONEY_1이 누락
            String maskedOutput = "{{DATE_1}}에 입금해 주시면 감사하겠습니다.";
            String output = "2024년 2월 4일에 입금해 주시면 감사하겠습니다.";

            ValidationResult result = validateWithSpans(output, "원문", spans, maskedOutput, Persona.BOSS);
            assertThat(result.passed()).isFalse();
            assertThat(result.errors()).anyMatch(i -> i.type() == ValidationIssueType.LOCKED_SPAN_MISSING);
        }

        @Test
        void 모든_플레이스홀더_존재시_통과() {
            List<LockedSpan> spans = List.of(
                    new LockedSpan(1, "2024년 2월 4일", "{{DATE_1}}", LockedSpanType.DATE, 0, 11)
            );

            String maskedOutput = "{{DATE_1}}에 만나요";
            String output = "2024년 2월 4일에 만나요";

            ValidationResult result = validateWithSpans(output, "원문", spans, maskedOutput, Persona.BOSS);
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.LOCKED_SPAN_MISSING).toList()).isEmpty();
        }

        @Test
        void 원본텍스트가_maskedOutput에_있으면_통과() {
            List<LockedSpan> spans = List.of(
                    new LockedSpan(1, "150,000원", "{{MONEY_1}}", LockedSpanType.MONEY, 0, 9)
            );

            // LLM이 플레이스홀더 대신 원본을 그대로 출력
            String maskedOutput = "150,000원을 보내주세요";
            String output = "150,000원을 보내주세요";

            ValidationResult result = validateWithSpans(output, "원문", spans, maskedOutput, Persona.BOSS);
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.LOCKED_SPAN_MISSING).toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("규칙 9: 원문 숫자 누락")
    class CoreNumberMissingTests {
        @Test
        void 원문_숫자_누락시_WARNING() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "금액을 확인해 주세요.", "금액 150원을 확인하세요",
                    List.of(), "금액을 확인해 주세요.", Persona.BOSS, Map.of());
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.CORE_NUMBER_MISSING);
        }

        @Test
        void 원문_숫자_존재시_통과() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "금액 150원을 확인해 주세요.", "금액 150원을 확인하세요",
                    List.of(), "금액 150원을 확인해 주세요.", Persona.BOSS, Map.of());
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.CORE_NUMBER_MISSING).toList()).isEmpty();
        }

        @Test
        void LockedSpan_숫자는_제외() {
            List<LockedSpan> spans = List.of(
                    new LockedSpan(1, "150,000원", "{{MONEY_1}}", LockedSpanType.MONEY, 3, 12)
            );
            ValidationResult result = validateWithSpansAndRedaction(
                    "금액을 확인해 주세요.", "금액 150,000원을 확인하세요",
                    spans, "금액을 확인해 주세요.", Persona.BOSS, Map.of());
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.CORE_NUMBER_MISSING).toList()).isEmpty();
        }

        @Test
        void 두자리_이하_숫자_무시() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "항목을 확인해 주세요.", "항목 12개를 확인하세요",
                    List.of(), "항목을 확인해 주세요.", Persona.BOSS, Map.of());
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.CORE_NUMBER_MISSING).toList()).isEmpty();
        }

        @Test
        void 쉼표_포함_숫자_정규화후_통과() {
            // 원문 "1,200" → 출력 "1200" → 정규화 후 동일 → 통과
            ValidationResult result = validateWithSpansAndRedaction(
                    "금액 1200원을 확인해 주세요.", "금액 1,200원을 확인하세요",
                    List.of(), "금액 1200원을 확인해 주세요.", Persona.BOSS, Map.of());
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.CORE_NUMBER_MISSING).toList()).isEmpty();
        }

        @Test
        void 쉼표_포함_숫자_누락시_WARNING() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "금액을 확인해 주세요.", "금액 1,200원을 확인하세요",
                    List.of(), "금액을 확인해 주세요.", Persona.BOSS, Map.of());
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.CORE_NUMBER_MISSING);
        }
    }

    @Nested
    @DisplayName("규칙 10: 원문 날짜/시간 누락")
    class CoreDateMissingTests {
        @Test
        void 날짜_누락시_WARNING() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "미팅을 잡아주세요.", "2026-03-01에 미팅을 잡아주세요",
                    List.of(), "미팅을 잡아주세요.", Persona.BOSS, Map.of());
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.CORE_DATE_MISSING);
        }

        @Test
        void 한국어_날짜_누락시_WARNING() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "미팅을 잡아주세요.", "3월 1일에 미팅을 잡아주세요",
                    List.of(), "미팅을 잡아주세요.", Persona.BOSS, Map.of());
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.CORE_DATE_MISSING);
        }

        @Test
        void 시간_누락시_WARNING() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "미팅을 잡아주세요.", "15:30에 미팅을 잡아주세요",
                    List.of(), "미팅을 잡아주세요.", Persona.BOSS, Map.of());
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.CORE_DATE_MISSING);
        }

        @Test
        void 날짜_존재시_통과() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "2026-03-01에 미팅을 잡아주세요.", "2026-03-01에 미팅을 잡아주세요",
                    List.of(), "2026-03-01에 미팅을 잡아주세요.", Persona.BOSS, Map.of());
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.CORE_DATE_MISSING).toList()).isEmpty();
        }

        @Test
        void LockedSpan_날짜는_제외() {
            List<LockedSpan> spans = List.of(
                    new LockedSpan(1, "2026-03-01", "{{DATE_1}}", LockedSpanType.DATE, 0, 10)
            );
            ValidationResult result = validateWithSpansAndRedaction(
                    "미팅을 잡아주세요.", "2026-03-01에 미팅을 잡아주세요",
                    spans, "미팅을 잡아주세요.", Persona.BOSS, Map.of());
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.CORE_DATE_MISSING).toList()).isEmpty();
        }

        @Test
        void 구분자_정규화후_통과() {
            // 원문 "2026.3.1" → 출력 "2026-3-1" → 구분자 정규화 후 통과
            ValidationResult result = validateWithSpansAndRedaction(
                    "2026-3-1에 미팅을 잡아주세요.", "2026.3.1에 미팅을 잡아주세요",
                    List.of(), "2026-3-1에 미팅을 잡아주세요.", Persona.BOSS, Map.of());
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.CORE_DATE_MISSING).toList()).isEmpty();
        }

        @Test
        void 제로패딩_정규화후_통과() {
            // 원문 "2026-03-01" → 출력 "2026-3-1" → 숫자 시퀀스 비교 통과
            ValidationResult result = validateWithSpansAndRedaction(
                    "2026-3-1에 미팅을 잡아주세요.", "2026-03-01에 미팅을 잡아주세요",
                    List.of(), "2026-3-1에 미팅을 잡아주세요.", Persona.BOSS, Map.of());
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.CORE_DATE_MISSING).toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("규칙 11: SOFTEN 내용 소실")
    class SoftenContentDroppedTests {
        @Test
        void YELLOW_내용_완전_소실시_WARNING() {
            // 의미 단어가 하나도 출력에 없으면 WARNING
            ValidationResult result = validateWithYellow(
                    "안녕하세요. 확인 부탁드립니다.",
                    "원문 텍스트입니다",
                    Persona.BOSS,
                    List.of("디자인팀에서 자료를 너무 늦게 줘서 일정이 밀렸습니다")
            );
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.SOFTEN_CONTENT_DROPPED);
        }

        @Test
        void 의미_단어_하나_이상_남아있으면_통과() {
            // "디자인팀" 단어가 출력에 존재
            ValidationResult result = validateWithYellow(
                    "디자인팀 자료 관련 일정 조정이 필요합니다.",
                    "원문 텍스트입니다",
                    Persona.BOSS,
                    List.of("디자인팀에서 자료를 너무 늦게 줘서 일정이 밀렸습니다")
            );
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.SOFTEN_CONTENT_DROPPED).toList()).isEmpty();
        }

        @Test
        void 숫자만_남아있어도_통과() {
            // 세그먼트 내 3자리 숫자가 출력에 존재 (동의어 치환 케이스)
            ValidationResult result = validateWithYellow(
                    "비용이 150만원 발생했습니다.",
                    "원문 텍스트입니다",
                    Persona.BOSS,
                    List.of("서버 장애로 인해 150만원의 손실이 발생했습니다")
            );
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.SOFTEN_CONTENT_DROPPED).toList()).isEmpty();
        }

        @Test
        void 짧은_세그먼트는_검사안함() {
            // 15자 미만 스킵
            ValidationResult result = validateWithYellow(
                    "안녕하세요.",
                    "원문입니다",
                    Persona.BOSS,
                    List.of("짧은 텍스트")  // 5자
            );
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.SOFTEN_CONTENT_DROPPED).toList()).isEmpty();
        }

        @Test
        void 빈_리스트면_검사안함() {
            ValidationResult result = validateWithYellow(
                    "안녕하세요.",
                    "원문입니다",
                    Persona.BOSS,
                    List.of()
            );
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.SOFTEN_CONTENT_DROPPED).toList()).isEmpty();
        }

        @Test
        void 의미_단어_없이_관련없는_출력이면_WARNING() {
            // YELLOW 세그먼트의 핵심 의미 단어("디자인팀","자료","늦게","일정","밀렸습니다")가 전혀 없는 출력
            ValidationResult result = validateWithYellow(
                    "안녕하세요. 감사합니다.",
                    "원문 텍스트입니다",
                    Persona.BOSS,
                    List.of("디자인팀에서 자료가 너무 늦게 와서 일정이 밀렸습니다")
            );
            assertThat(result.warnings()).anyMatch(i -> i.type() == ValidationIssueType.SOFTEN_CONTENT_DROPPED);
        }
    }

    @Nested
    @DisplayName("규칙 8: RED 재유입 + 검열 흔적")
    class RedactedReentryTests {
        @Test
        void 제거된_긴텍스트_재유입시_ERROR() {
            Map<String, String> redactionMap = Map.of(
                    "[REDACTED:AGGRESSION_1]", "솔직히 너무 무능한 거 아닌가요"
            );
            ValidationResult result = validateWithSpansAndRedaction(
                    "솔직히 너무 무능한 거 아닌가요. 확인 부탁드립니다.",
                    "원문", List.of(),
                    "솔직히 너무 무능한 거 아닌가요. 확인 부탁드립니다.",
                    Persona.BOSS, redactionMap);
            assertThat(result.passed()).isFalse();
            assertThat(result.errors()).anyMatch(i -> i.type() == ValidationIssueType.REDACTED_REENTRY);
        }

        @Test
        void 제거된_6자이상_텍스트_재유입시_ERROR() {
            Map<String, String> redactionMap = Map.of(
                    "[REDACTED:AGGRESSION_1]", "무능한놈이야"
            );
            ValidationResult result = validateWithSpansAndRedaction(
                    "무능한놈이야 확인해주세요.",
                    "원문", List.of(),
                    "무능한놈이야 확인해주세요.",
                    Persona.BOSS, redactionMap);
            assertThat(result.passed()).isFalse();
            assertThat(result.errors()).anyMatch(i -> i.type() == ValidationIssueType.REDACTED_REENTRY);
        }

        @Test
        void 정규화후_매칭_공백우회방지() {
            // "무 능 한 놈" (공백 삽입) should still match "무능한놈이야" after normalization
            Map<String, String> redactionMap = Map.of(
                    "[REDACTED:AGGRESSION_1]", "무능한놈이야"
            );
            ValidationResult result = validateWithSpansAndRedaction(
                    "무 능 한 놈 이야 확인해주세요.",
                    "원문", List.of(),
                    "무 능 한 놈 이야 확인해주세요.",
                    Persona.BOSS, redactionMap);
            assertThat(result.passed()).isFalse();
            assertThat(result.errors()).anyMatch(i -> i.type() == ValidationIssueType.REDACTED_REENTRY);
        }

        @Test
        void 짧은_5자이하_텍스트는_검사안함() {
            Map<String, String> redactionMap = Map.of(
                    "[REDACTED:PURE_GRUMBLE_1]", "짜증나"
            );
            ValidationResult result = validateWithSpansAndRedaction(
                    "짜증나 확인 부탁드립니다.",
                    "원문", List.of(),
                    "짜증나 확인 부탁드립니다.",
                    Persona.BOSS, redactionMap);
            // 5자(짜증나) < 6자 threshold → no REDACTED_REENTRY
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.REDACTED_REENTRY).toList()).isEmpty();
        }

        @Test
        void 제거된_내용_미포함시_통과() {
            Map<String, String> redactionMap = Map.of(
                    "[REDACTED:AGGRESSION_1]", "솔직히 무능한 거 아닌가요"
            );
            ValidationResult result = validateWithSpansAndRedaction(
                    "확인 부탁드립니다. 감사합니다.",
                    "원문", List.of(),
                    "확인 부탁드립니다. 감사합니다.",
                    Persona.BOSS, redactionMap);
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.REDACTED_REENTRY).toList()).isEmpty();
        }

        @Test
        void 검열흔적_삭제됨_표시_ERROR() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "확인 부탁드립니다. [삭제됨] 감사합니다.",
                    "원문", List.of(),
                    "확인 부탁드립니다. [삭제됨] 감사합니다.",
                    Persona.BOSS, Map.of());
            assertThat(result.passed()).isFalse();
            assertThat(result.errors()).anyMatch(i -> i.type() == ValidationIssueType.REDACTION_TRACE);
        }

        @Test
        void 검열흔적_삭제된내용_ERROR() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "삭제된 내용은 제외하고 답변드립니다.",
                    "원문", List.of(),
                    "삭제된 내용은 제외하고 답변드립니다.",
                    Persona.BOSS, Map.of());
            assertThat(result.passed()).isFalse();
            assertThat(result.errors()).anyMatch(i -> i.type() == ValidationIssueType.REDACTION_TRACE);
        }

        @Test
        void 검열흔적_없으면_통과() {
            ValidationResult result = validateWithSpansAndRedaction(
                    "안녕하세요. 확인 부탁드립니다.",
                    "원문", List.of(),
                    "안녕하세요. 확인 부탁드립니다.",
                    Persona.BOSS, Map.of());
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.REDACTION_TRACE).toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("종합 검증")
    class IntegrationTests {
        @Test
        void 깨끗한_출력은_모두_통과() {
            String original = "이거 좀 확인해주세요 빨리 좀요";
            String output = "안녕하세요. 해당 건 확인 부탁드려도 될까요? 바쁘신 중에 번거로운 부탁 드려 죄송합니다.";

            ValidationResult result = validate(output, original, Persona.BOSS);
            assertThat(result.passed()).isTrue();
            assertThat(result.errors()).isEmpty();
        }
    }
}
