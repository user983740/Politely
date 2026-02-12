package com.politeai.infrastructure.ai.validation;

import com.politeai.domain.transform.model.*;
import com.politeai.domain.transform.model.ValidationIssue.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

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
            ValidationResult result = validate("2025년 3월 15일에 만나요", "만나요", Persona.BOSS);
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
                    new LockedSpan(0, "2024년 2월 4일", "{{LOCKED_0}}", LockedSpanType.DATE, 0, 11),
                    new LockedSpan(1, "150,000원", "{{LOCKED_1}}", LockedSpanType.MONEY, 13, 22)
            );

            // maskedOutput에서 LOCKED_1이 누락
            String maskedOutput = "{{LOCKED_0}}에 입금해 주시면 감사하겠습니다.";
            String output = "2024년 2월 4일에 입금해 주시면 감사하겠습니다.";

            ValidationResult result = validateWithSpans(output, "원문", spans, maskedOutput, Persona.BOSS);
            assertThat(result.passed()).isFalse();
            assertThat(result.errors()).anyMatch(i -> i.type() == ValidationIssueType.LOCKED_SPAN_MISSING);
        }

        @Test
        void 모든_플레이스홀더_존재시_통과() {
            List<LockedSpan> spans = List.of(
                    new LockedSpan(0, "2024년 2월 4일", "{{LOCKED_0}}", LockedSpanType.DATE, 0, 11)
            );

            String maskedOutput = "{{LOCKED_0}}에 만나요";
            String output = "2024년 2월 4일에 만나요";

            ValidationResult result = validateWithSpans(output, "원문", spans, maskedOutput, Persona.BOSS);
            assertThat(result.issues().stream()
                    .filter(i -> i.type() == ValidationIssueType.LOCKED_SPAN_MISSING).toList()).isEmpty();
        }

        @Test
        void 원본텍스트가_maskedOutput에_있으면_통과() {
            List<LockedSpan> spans = List.of(
                    new LockedSpan(0, "150,000원", "{{LOCKED_0}}", LockedSpanType.MONEY, 0, 9)
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
