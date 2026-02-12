package com.politeai.infrastructure.ai.preprocessing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizerTest {

    private TextNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new TextNormalizer();
    }

    @Test
    @DisplayName("null과 빈 문자열 처리")
    void null_빈문자열() {
        assertThat(normalizer.normalize(null)).isNull();
        assertThat(normalizer.normalize("")).isEmpty();
    }

    @Test
    @DisplayName("보이지 않는 문자 제거")
    void 보이지않는_문자_제거() {
        String input = "안녕\u200B하세요\uFEFF";
        assertThat(normalizer.normalize(input)).isEqualTo("안녕하세요");
    }

    @Test
    @DisplayName("제어 문자 제거")
    void 제어문자_제거() {
        String input = "안녕\u0001하세요\u0007";
        assertThat(normalizer.normalize(input)).isEqualTo("안녕하세요");
    }

    @Test
    @DisplayName("\\r\\n → \\n 정규화")
    void 줄바꿈_정규화() {
        String input = "첫째줄\r\n둘째줄\r셋째줄";
        assertThat(normalizer.normalize(input)).isEqualTo("첫째줄\n둘째줄\n셋째줄");
    }

    @Test
    @DisplayName("연속 공백 축소")
    void 연속공백_축소() {
        String input = "안녕   하세요    반갑습니다";
        assertThat(normalizer.normalize(input)).isEqualTo("안녕 하세요 반갑습니다");
    }

    @Test
    @DisplayName("탭 → 단일 공백")
    void 탭_축소() {
        String input = "안녕\t\t하세요";
        assertThat(normalizer.normalize(input)).isEqualTo("안녕 하세요");
    }

    @Test
    @DisplayName("과도한 줄바꿈 축소 (3개 이상 → 2개)")
    void 과도한_줄바꿈_축소() {
        String input = "첫째줄\n\n\n\n둘째줄";
        assertThat(normalizer.normalize(input)).isEqualTo("첫째줄\n\n둘째줄");
    }

    @Test
    @DisplayName("앞뒤 공백 제거")
    void 트림() {
        String input = "  안녕하세요  ";
        assertThat(normalizer.normalize(input)).isEqualTo("안녕하세요");
    }

    @Test
    @DisplayName("정상 텍스트는 변경 없음")
    void 정상_텍스트_유지() {
        String input = "안녕하세요. 오늘 2월 4일 미팅 관련 연락드립니다.";
        assertThat(normalizer.normalize(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("유니코드 NFC 정규화")
    void NFC_정규화() {
        // 'ㅎ' + 'ㅏ' + 'ㄴ' (decomposed) → '한' (composed)
        String decomposed = "\u1112\u1161\u11AB"; // 한 (decomposed)
        String composed = "한";
        assertThat(normalizer.normalize(decomposed)).isEqualTo(composed);
    }
}
