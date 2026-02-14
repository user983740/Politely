package com.politeai.infrastructure.ai.preprocessing;

import com.politeai.domain.transform.model.LockedSpan;
import com.politeai.domain.transform.model.LockedSpanType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LockedSpanMaskerTest {

    private LockedSpanMasker masker;
    private LockedSpanExtractor extractor;

    @BeforeEach
    void setUp() {
        masker = new LockedSpanMasker();
        extractor = new LockedSpanExtractor();
    }

    @Test
    @DisplayName("mask: 스팬을 타입별 플레이스홀더로 교체")
    void mask_기본() {
        String text = "2024년 2월 4일에 만나요";
        List<LockedSpan> spans = extractor.extract(text);

        String masked = masker.mask(text, spans);
        assertThat(masked).contains("{{DATE_1}}");
        assertThat(masked).doesNotContain("2024년 2월 4일");
        assertThat(masked).contains("에 만나요");
    }

    @Test
    @DisplayName("mask: 여러 스팬 교체")
    void mask_다중_스팬() {
        String text = "150,000원을 010-1234-5678로 보내주세요";
        List<LockedSpan> spans = extractor.extract(text);

        String masked = masker.mask(text, spans);
        for (LockedSpan span : spans) {
            assertThat(masked).contains(span.placeholder());
            assertThat(masked).doesNotContain(span.originalText());
        }
    }

    @Test
    @DisplayName("mask: 빈 스팬 리스트")
    void mask_빈_스팬() {
        String text = "안녕하세요";
        String masked = masker.mask(text, List.of());
        assertThat(masked).isEqualTo(text);
    }

    @Test
    @DisplayName("unmask: 타입별 플레이스홀더를 원본으로 복원")
    void unmask_기본() {
        List<LockedSpan> spans = List.of(
                new LockedSpan(1, "2024년 2월 4일", "{{DATE_1}}", LockedSpanType.DATE, 0, 11)
        );

        String output = "{{DATE_1}}에 방문하겠습니다.";
        LockedSpanMasker.UnmaskResult result = masker.unmask(output, spans);

        assertThat(result.text()).isEqualTo("2024년 2월 4일에 방문하겠습니다.");
        assertThat(result.missingSpans()).isEmpty();
    }

    @Test
    @DisplayName("unmask: 유연한 플레이스홀더 매칭")
    void unmask_유연_매칭() {
        List<LockedSpan> spans = List.of(
                new LockedSpan(1, "150,000원", "{{MONEY_1}}", LockedSpanType.MONEY, 0, 9)
        );

        // LLM이 공백이나 하이픈을 추가한 경우
        String output = "{{ MONEY_1 }}을 보내주세요.";
        LockedSpanMasker.UnmaskResult result = masker.unmask(output, spans);

        assertThat(result.text()).isEqualTo("150,000원을 보내주세요.");
        assertThat(result.missingSpans()).isEmpty();
    }

    @Test
    @DisplayName("unmask: 누락된 스팬 감지")
    void unmask_누락_감지() {
        List<LockedSpan> spans = List.of(
                new LockedSpan(1, "2024년 2월 4일", "{{DATE_1}}", LockedSpanType.DATE, 0, 11),
                new LockedSpan(1, "150,000원", "{{MONEY_1}}", LockedSpanType.MONEY, 13, 22)
        );

        // LLM이 MONEY_1을 누락한 경우
        String output = "{{DATE_1}}에 입금해주세요.";
        LockedSpanMasker.UnmaskResult result = masker.unmask(output, spans);

        assertThat(result.text()).contains("2024년 2월 4일");
        assertThat(result.missingSpans()).hasSize(1);
        assertThat(result.missingSpans().get(0).originalText()).isEqualTo("150,000원");
    }

    @Test
    @DisplayName("unmask: 원본 텍스트가 그대로 있으면 누락으로 안 침")
    void unmask_원본_그대로_존재() {
        List<LockedSpan> spans = List.of(
                new LockedSpan(1, "150,000원", "{{MONEY_1}}", LockedSpanType.MONEY, 0, 9)
        );

        // LLM이 플레이스홀더 대신 원본을 그대로 출력한 경우
        String output = "150,000원을 보내주세요.";
        LockedSpanMasker.UnmaskResult result = masker.unmask(output, spans);

        assertThat(result.missingSpans()).isEmpty();
    }

    @Test
    @DisplayName("라운드트립: extract → mask → unmask 정확성")
    void 라운드트립() {
        String originalText = "2024년 2월 4일 12~3시에 150,000원 입금 부탁드립니다";
        List<LockedSpan> spans = extractor.extract(originalText);

        // Mask
        String masked = masker.mask(originalText, spans);
        assertThat(masked).doesNotContain("2024년 2월 4일");
        assertThat(masked).doesNotContain("150,000원");

        // Simulate LLM keeping placeholders
        String llmOutput = masked.replace("입금 부탁드립니다", "입금해 주시면 감사하겠습니다");

        // Unmask
        LockedSpanMasker.UnmaskResult result = masker.unmask(llmOutput, spans);
        assertThat(result.text()).contains("2024년 2월 4일");
        assertThat(result.text()).contains("150,000원");
        assertThat(result.text()).contains("입금해 주시면 감사하겠습니다");
        assertThat(result.missingSpans()).isEmpty();
    }

    @Test
    @DisplayName("빈 스팬으로 unmask")
    void unmask_빈_스팬() {
        String output = "안녕하세요";
        LockedSpanMasker.UnmaskResult result = masker.unmask(output, List.of());
        assertThat(result.text()).isEqualTo(output);
        assertThat(result.missingSpans()).isEmpty();
    }
}
