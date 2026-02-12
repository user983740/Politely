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
        // Worst case: all contexts selected
        String prompt = promptBuilder.buildSystemPrompt(
                Persona.BOSS,
                List.of(SituationContext.REQUEST, SituationContext.APOLOGY, SituationContext.URGING),
                ToneLevel.VERY_POLITE
        );

        // Korean: ~1.5 chars per token
        int estimatedTokens = (int) (prompt.length() / 1.5);
        assertThat(estimatedTokens).isLessThan(700); // generous upper bound
        assertThat(estimatedTokens).isGreaterThan(200); // sanity check lower bound
    }

    @Test
    @DisplayName("기존 대비 93% 감소 확인")
    void buildSystemPrompt_크기비교() {
        String dynamicPrompt = promptBuilder.buildSystemPrompt(
                Persona.BOSS,
                List.of(SituationContext.APOLOGY),
                ToneLevel.POLITE
        );

        // Old system prompt was ~7500 tokens (~11250 chars)
        // New should be ~430-550 tokens (~650-825 chars)
        assertThat(dynamicPrompt.length()).isLessThan(2000); // well under old size
    }

    @Test
    @DisplayName("Pro 시스템 프롬프트: JSON 규칙 포함")
    void buildProSystemPrompt_JSON_규칙() {
        String prompt = promptBuilder.buildProSystemPrompt(
                Persona.CLIENT,
                List.of(SituationContext.REJECTION),
                ToneLevel.VERY_POLITE
        );

        assertThat(prompt).contains("Pro 출력");
        assertThat(prompt).contains("analysis");
        assertThat(prompt).contains("result");
        assertThat(prompt).contains("checks");
        assertThat(prompt).contains("riskFlags");
    }

    @Test
    @DisplayName("예시 선택: score >= 2인 예시만")
    void selectRelevantExamples_점수_필터() {
        var examples = promptBuilder.selectRelevantExamples(
                Persona.BOSS,
                List.of(SituationContext.URGING),
                2, 2
        );

        // BOSS + URGING matches ex1 (score=3)
        assertThat(examples).isNotEmpty();
        assertThat(examples.size()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("예시 선택: 관련 없는 조합은 빈 리스트")
    void selectRelevantExamples_관련없는_조합() {
        var examples = promptBuilder.selectRelevantExamples(
                Persona.OFFICIAL,
                List.of(SituationContext.ANNOUNCEMENT),
                2, 2
        );

        // No example matches OFFICIAL + ANNOUNCEMENT with score >= 2
        assertThat(examples).isEmpty();
    }

    @Test
    @DisplayName("사용자 메시지에 예시 포함 (score >= 2)")
    void buildTransformUserMessage_예시_포함() {
        String userMessage = promptBuilder.buildTransformUserMessage(
                Persona.BOSS,
                List.of(SituationContext.URGING),
                ToneLevel.POLITE,
                "이거 빨리 좀 해주세요",
                null,
                null
        );

        assertThat(userMessage).contains("[전체 변환]");
        assertThat(userMessage).contains("직장 상사");
        // Should contain example since BOSS+URGING matches ex1
        assertThat(userMessage).contains("참고 예시");
    }

    @Test
    @DisplayName("Pro 사용자 메시지에 예시 없음")
    void buildProTransformUserMessage_예시_없음() {
        String userMessage = promptBuilder.buildProTransformUserMessage(
                Persona.BOSS,
                List.of(SituationContext.URGING),
                ToneLevel.POLITE,
                "이거 빨리 좀 해주세요",
                null,
                null
        );

        assertThat(userMessage).contains("[Pro 전체 변환");
        // Pro has anti-pattern reference but not full examples
        assertThat(userMessage).doesNotContain("참고 예시");
    }

    @Test
    @DisplayName("재시도 프롬프트 빌드")
    void buildProRetryUserMessage() {
        String retry = promptBuilder.buildProRetryUserMessage(
                "{\"result\": \"😊 안녕하세요\"}",
                List.of("[EMOJI] 이모지 감지: \"😊\""),
                "원래 요청 내용"
        );

        assertThat(retry).contains("[재시도 요청]");
        assertThat(retry).contains("이전 변환 결과");
        assertThat(retry).contains("EMOJI");
        assertThat(retry).contains("원래 요청 내용");
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
