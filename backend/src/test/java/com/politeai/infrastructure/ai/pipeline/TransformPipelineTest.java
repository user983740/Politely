package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.domain.user.model.UserTier;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.PromptBuilder;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanExtractor;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanMasker;
import com.politeai.infrastructure.ai.preprocessing.TextNormalizer;
import com.politeai.infrastructure.ai.validation.OutputValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransformPipelineTest {

    @Mock
    private AiTransformService aiTransformService;

    private TransformPipeline pipeline;

    @BeforeEach
    void setUp() {
        TextNormalizer textNormalizer = new TextNormalizer();
        LockedSpanExtractor spanExtractor = new LockedSpanExtractor();
        LockedSpanMasker spanMasker = new LockedSpanMasker();
        OutputValidator outputValidator = new OutputValidator();
        PromptBuilder promptBuilder = new PromptBuilder();

        pipeline = new TransformPipeline(
                textNormalizer, spanExtractor, spanMasker,
                outputValidator, promptBuilder, aiTransformService
        );
    }

    @Test
    @DisplayName("Free 파이프라인: 전처리 → LLM → unmask → 검증")
    void free_파이프라인_기본() {
        // Mock LLM to return text with placeholder preserved
        when(aiTransformService.callOpenAI(anyString(), anyString(), anyDouble(), anyInt(), any()))
                .thenReturn(new TransformResult(
                        "{{LOCKED_0}}에 방문하겠습니다. 확인 부탁드립니다.", null));

        PipelineResult result = pipeline.execute(
                Persona.BOSS,
                List.of(SituationContext.REQUEST),
                ToneLevel.POLITE,
                "2024년 2월 4일에 방문 예정입니다",
                null,
                null,
                UserTier.FREE
        );

        assertThat(result.transformedText()).contains("2024년 2월 4일");
        assertThat(result.transformedText()).doesNotContain("{{LOCKED_");
        assertThat(result.analysisContext()).isNull();
        assertThat(result.wasRetried()).isFalse();
    }

    @Test
    @DisplayName("Free 파이프라인: 플레이스홀더 없는 텍스트도 정상 처리")
    void free_파이프라인_스팬없음() {
        when(aiTransformService.callOpenAI(anyString(), anyString(), anyDouble(), anyInt(), any()))
                .thenReturn(new TransformResult(
                        "안녕하세요. 확인 부탁드립니다.", null));

        PipelineResult result = pipeline.execute(
                Persona.BOSS,
                List.of(SituationContext.REQUEST),
                ToneLevel.POLITE,
                "이거 좀 봐주세요",
                null,
                null,
                UserTier.FREE
        );

        assertThat(result.transformedText()).isEqualTo("안녕하세요. 확인 부탁드립니다.");
    }

    @Test
    @DisplayName("Free 파이프라인: 이모지 포함 결과에 WARNING/ERROR 기록")
    void free_파이프라인_이모지_검증() {
        when(aiTransformService.callOpenAI(anyString(), anyString(), anyDouble(), anyInt(), any()))
                .thenReturn(new TransformResult("안녕하세요 😊 잘 부탁드립니다.", null));

        PipelineResult result = pipeline.execute(
                Persona.BOSS,
                List.of(SituationContext.REQUEST),
                ToneLevel.POLITE,
                "이거 좀 봐주세요",
                null,
                null,
                UserTier.FREE
        );

        // Free tier: no retry, but validation issues are recorded
        assertThat(result.validationIssues()).isNotEmpty();
        assertThat(result.validationIssues().stream()
                .anyMatch(i -> i.type() == ValidationIssueType.EMOJI)).isTrue();
    }

    @Test
    @DisplayName("Pro 파이프라인: JSON 파싱 + unmask")
    void pro_파이프라인_기본() {
        when(aiTransformService.callOpenAIForPro(anyString(), anyString()))
                .thenReturn(new TransformResult(
                        "{{LOCKED_0}}에 방문 예정입니다. 확인 부탁드리겠습니다.",
                        "상황 분석: 방문 요청 건"));

        PipelineResult result = pipeline.execute(
                Persona.BOSS,
                List.of(SituationContext.REQUEST),
                ToneLevel.POLITE,
                "2024년 2월 4일에 방문할게요",
                null,
                null,
                UserTier.PAID
        );

        assertThat(result.transformedText()).contains("2024년 2월 4일");
        assertThat(result.analysisContext()).isEqualTo("상황 분석: 방문 요청 건");
        assertThat(result.wasRetried()).isFalse();
    }

    @Test
    @DisplayName("preprocess: 정규화 + 스팬 추출 + 마스킹")
    void preprocess_통합() {
        TransformPipelineContext ctx = pipeline.preprocess(
                Persona.BOSS,
                List.of(SituationContext.REQUEST),
                ToneLevel.POLITE,
                "2024년 2월 4일  12~3시에   150,000원 입금",  // 연속 공백 포함
                null,
                null,
                UserTier.FREE
        );

        // Normalized (spaces collapsed)
        assertThat(ctx.getNormalizedText()).doesNotContain("  ");

        // Locked spans extracted
        assertThat(ctx.getLockedSpans()).isNotEmpty();
        assertThat(ctx.getLockedSpans().stream().map(LockedSpan::type).toList())
                .contains(LockedSpanType.DATE, LockedSpanType.TIME, LockedSpanType.MONEY);

        // Masked text has placeholders
        assertThat(ctx.getMaskedText()).contains("{{LOCKED_");
        assertThat(ctx.getMaskedText()).doesNotContain("2024년 2월 4일");

        // System prompt is dynamic (contains persona/context/tone)
        assertThat(ctx.getSystemPrompt()).contains("직장 상사");
        assertThat(ctx.getSystemPrompt()).contains("요청");
        assertThat(ctx.getSystemPrompt()).contains("공손");
    }

    @Test
    @DisplayName("postProcessStreaming: unmask + 검증")
    void postProcessStreaming_통합() {
        TransformPipelineContext ctx = pipeline.preprocess(
                Persona.BOSS,
                List.of(SituationContext.REQUEST),
                ToneLevel.POLITE,
                "2024년 2월 4일에 방문합니다",
                null,
                null,
                UserTier.FREE
        );

        // Simulate streaming output with placeholder
        String rawOutput = "{{LOCKED_0}}에 방문하겠습니다. 확인 부탁드립니다.";
        PipelineResult result = pipeline.postProcessStreaming(ctx, rawOutput);

        assertThat(result.transformedText()).contains("2024년 2월 4일");
        assertThat(result.transformedText()).doesNotContain("{{LOCKED_");
    }
}
