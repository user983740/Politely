package com.politeai.infrastructure.ai;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private PromptBuilder promptBuilder;
    private static final String TEST_CORE = "테스트 코어 프롬프트";

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
    }

    @Test
    @DisplayName("동적 블록 조립: 코어 + persona + context + tone 포함")
    void buildDynamicBlocks_동적_조립() {
        String prompt = promptBuilder.buildDynamicBlocks(
                TEST_CORE,
                Persona.BOSS,
                List.of(SituationContext.APOLOGY, SituationContext.SCHEDULE_DELAY),
                ToneLevel.POLITE
        );

        // Core prompt
        assertThat(prompt).contains(TEST_CORE);

        // Persona block
        assertThat(prompt).contains("직장 상사");
        assertThat(prompt).contains("겸양어와 존댓말");

        // Context blocks
        assertThat(prompt).contains("사과");
        assertThat(prompt).contains("일정 지연");

        // Tone block
        assertThat(prompt).contains("공손");
    }

    @Test
    @DisplayName("동적 블록 토큰 수 검증 (합리적 범위 내)")
    void buildDynamicBlocks_토큰_검증() {
        String prompt = promptBuilder.buildDynamicBlocks(
                TEST_CORE,
                Persona.BOSS,
                List.of(SituationContext.REQUEST, SituationContext.APOLOGY, SituationContext.URGING),
                ToneLevel.VERY_POLITE
        );

        int estimatedTokens = (int) (prompt.length() / 1.5);
        assertThat(estimatedTokens).isLessThan(1500);
        assertThat(estimatedTokens).isGreaterThan(50);
    }

    @Test
    @DisplayName("각 Persona에 대해 블록이 존재")
    void 모든_Persona_블록_존재() {
        for (Persona persona : Persona.values()) {
            String prompt = promptBuilder.buildDynamicBlocks(
                    TEST_CORE,
                    persona,
                    List.of(SituationContext.REQUEST),
                    ToneLevel.POLITE
            );
            assertThat(prompt).contains("받는 사람");
        }
    }

    @Test
    @DisplayName("각 ToneLevel에 대해 블록이 존재")
    void 모든_ToneLevel_블록_존재() {
        for (ToneLevel tone : ToneLevel.values()) {
            String prompt = promptBuilder.buildDynamicBlocks(
                    TEST_CORE,
                    Persona.BOSS,
                    List.of(SituationContext.REQUEST),
                    tone
            );
            assertThat(prompt).contains("말투");
        }
    }
}
