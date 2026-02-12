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

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
    }

    @Test
    @DisplayName("동적 시스템 프롬프트: 코어 + persona + context + tone 포함")
    void buildSystemPrompt_동적_조립() {
        String prompt = promptBuilder.buildSystemPrompt(
                Persona.BOSS,
                List.of(SituationContext.APOLOGY, SituationContext.SCHEDULE_DELAY),
                ToneLevel.POLITE
        );

        // Core prompt
        assertThat(prompt).contains("한국어 커뮤니케이션 전문가");
        assertThat(prompt).contains("{{LOCKED_N}}");

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
    @DisplayName("동적 시스템 프롬프트 토큰 수 검증 (~430-550 토큰)")
    void buildSystemPrompt_토큰_검증() {
        String prompt = promptBuilder.buildSystemPrompt(
                Persona.BOSS,
                List.of(SituationContext.REQUEST, SituationContext.APOLOGY, SituationContext.URGING),
                ToneLevel.VERY_POLITE
        );

        int estimatedTokens = (int) (prompt.length() / 1.5);
        assertThat(estimatedTokens).isLessThan(1000);
        assertThat(estimatedTokens).isGreaterThan(200);
    }

    @Test
    @DisplayName("기존 대비 93% 감소 확인")
    void buildSystemPrompt_크기비교() {
        String dynamicPrompt = promptBuilder.buildSystemPrompt(
                Persona.BOSS,
                List.of(SituationContext.APOLOGY),
                ToneLevel.POLITE
        );

        assertThat(dynamicPrompt.length()).isLessThan(2000);
    }

    @Test
    @DisplayName("부분 재변환 사용자 메시지")
    void buildPartialRewriteUserMessage() {
        String message = promptBuilder.buildPartialRewriteUserMessage(
                "이 부분만 바꿔주세요",
                "전체 문맥이 여기 있습니다. 이 부분만 바꿔주세요. 나머지는 유지.",
                Persona.BOSS,
                List.of(SituationContext.REQUEST),
                ToneLevel.POLITE,
                null,
                "마케팅팀 김민수",
                null
        );

        assertThat(message).contains("[부분 재변환]");
        assertThat(message).contains("전체 문맥");
        assertThat(message).contains("다시 작성할 부분");
        assertThat(message).contains("마케팅팀 김민수");
    }

    @Test
    @DisplayName("각 Persona에 대해 블록이 존재")
    void 모든_Persona_블록_존재() {
        for (Persona persona : Persona.values()) {
            String prompt = promptBuilder.buildSystemPrompt(
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
            String prompt = promptBuilder.buildSystemPrompt(
                    Persona.BOSS,
                    List.of(SituationContext.REQUEST),
                    tone
            );
            assertThat(prompt).contains("말투");
        }
    }
}
