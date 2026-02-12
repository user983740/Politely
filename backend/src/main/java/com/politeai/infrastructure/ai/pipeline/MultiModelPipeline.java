package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.domain.user.model.UserTier;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.LlmCallResult;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanExtractor;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanMasker;
import com.politeai.infrastructure.ai.preprocessing.TextNormalizer;
import com.politeai.infrastructure.ai.validation.OutputValidator;
import com.openai.models.ResponseFormatJsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Multi-model pipeline orchestrator.
 *
 * Pipeline:
 *   preprocess → 3 intermediate models (parallel, 4o-mini) → Final model (parameterized) → unmask → validate
 *
 * The intermediate analysis is shared; only the Final model differs between A/B tests.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiModelPipeline {

    private final TextNormalizer textNormalizer;
    private final LockedSpanExtractor spanExtractor;
    private final LockedSpanMasker spanMasker;
    private final OutputValidator outputValidator;
    private final MultiModelPromptBuilder multiPromptBuilder;
    private final AiTransformService aiTransformService;

    private static final String ANALYSIS_MODEL = "gpt-4o-mini";
    private static final int ANALYSIS_MAX_TOKENS = 300;
    private static final double ANALYSIS_TEMP = 0.3;
    private static final ResponseFormatJsonObject JSON_FORMAT = ResponseFormatJsonObject.builder().build();

    /**
     * Result of the multi-model pipeline execution, including token usage.
     */
    public record MultiModelPipelineResult(
            String transformedText,
            List<ValidationIssue> validationIssues,
            long analysisPromptTokens,
            long analysisCompletionTokens,
            long finalPromptTokens,
            long finalCompletionTokens
    ) {}

    /**
     * Result of only the shared analysis phase (3 intermediate models).
     */
    public record SharedAnalysisResult(
            String model1Output,
            String model2Output,
            String model3Output,
            long totalPromptTokens,
            long totalCompletionTokens,
            String maskedText,
            List<LockedSpan> lockedSpans
    ) {}

    /**
     * Run only the shared analysis phase (preprocess + 3 intermediate models).
     * Call this once, then use executeFinal() for each A/B variant.
     */
    public SharedAnalysisResult executeSharedAnalysis(Persona persona,
                                                       List<SituationContext> contexts,
                                                       ToneLevel toneLevel,
                                                       String originalText,
                                                       String userPrompt,
                                                       String senderInfo) {
        // 1. Preprocess
        String normalized = textNormalizer.normalize(originalText);
        List<LockedSpan> spans = spanExtractor.extract(normalized);
        String masked = spanMasker.mask(normalized, spans);

        if (!spans.isEmpty()) {
            log.info("[MultiModel] Extracted {} locked spans", spans.size());
        }

        // 2. Build intermediate prompts
        String m1System = multiPromptBuilder.buildModel1SystemPrompt();
        String m1User = multiPromptBuilder.buildModel1UserMessage(persona, contexts, toneLevel, masked, userPrompt, senderInfo);

        String m2System = multiPromptBuilder.buildModel2SystemPrompt();
        String m2User = multiPromptBuilder.buildModel2UserMessage(persona, masked);

        String m3System = multiPromptBuilder.buildModel3SystemPrompt();
        String m3User = multiPromptBuilder.buildModel3UserMessage(persona, contexts, toneLevel, masked);

        // 3. Run 3 intermediate models in parallel
        CompletableFuture<LlmCallResult> f1 = CompletableFuture.supplyAsync(() ->
                aiTransformService.callOpenAIWithModel(ANALYSIS_MODEL, m1System, m1User,
                        ANALYSIS_TEMP, ANALYSIS_MAX_TOKENS, JSON_FORMAT));

        CompletableFuture<LlmCallResult> f2 = CompletableFuture.supplyAsync(() ->
                aiTransformService.callOpenAIWithModel(ANALYSIS_MODEL, m2System, m2User,
                        ANALYSIS_TEMP, ANALYSIS_MAX_TOKENS, JSON_FORMAT));

        CompletableFuture<LlmCallResult> f3 = CompletableFuture.supplyAsync(() ->
                aiTransformService.callOpenAIWithModel(ANALYSIS_MODEL, m3System, m3User,
                        ANALYSIS_TEMP, ANALYSIS_MAX_TOKENS, JSON_FORMAT));

        CompletableFuture.allOf(f1, f2, f3).join();

        LlmCallResult r1 = safeGet(f1, "Model1");
        LlmCallResult r2 = safeGet(f2, "Model2");
        LlmCallResult r3 = safeGet(f3, "Model3");

        long totalPrompt = r1.promptTokens() + r2.promptTokens() + r3.promptTokens();
        long totalCompletion = r1.completionTokens() + r2.completionTokens() + r3.completionTokens();

        log.info("[MultiModel] Shared analysis complete - total tokens: prompt={}, completion={}",
                totalPrompt, totalCompletion);

        return new SharedAnalysisResult(
                r1.content(), r2.content(), r3.content(),
                totalPrompt, totalCompletion,
                masked, spans
        );
    }

    /**
     * Run the Final model using shared analysis results.
     *
     * @param finalModelName  the model to use for final transform (e.g. "gpt-4o" or "gpt-4o-mini")
     * @param analysis        shared analysis result from executeSharedAnalysis()
     * @param persona         target persona
     * @param contexts        situation contexts
     * @param toneLevel       tone level
     * @param originalText    original text (for validation)
     * @param userPrompt      user prompt
     * @param senderInfo      sender info
     * @return pipeline result with token usage
     */
    public MultiModelPipelineResult executeFinal(String finalModelName,
                                                  SharedAnalysisResult analysis,
                                                  Persona persona,
                                                  List<SituationContext> contexts,
                                                  ToneLevel toneLevel,
                                                  String originalText,
                                                  String userPrompt,
                                                  String senderInfo) {
        // Build Final prompt with intermediate analysis injected
        String finalSystem = multiPromptBuilder.buildFinalSystemPrompt(persona, contexts, toneLevel);
        String finalUser = multiPromptBuilder.buildFinalUserMessage(
                persona, contexts, toneLevel, analysis.maskedText(),
                userPrompt, senderInfo,
                analysis.model1Output(), analysis.model2Output(), analysis.model3Output());

        // Call Final model (plain text, no JSON format)
        LlmCallResult finalResult = aiTransformService.callOpenAIWithModel(
                finalModelName, finalSystem, finalUser, -1, -1, null);

        // Unmask
        LockedSpanMasker.UnmaskResult unmaskResult = spanMasker.unmask(
                finalResult.content(), analysis.lockedSpans());

        // Validate
        ValidationResult validation = outputValidator.validate(
                unmaskResult.text(), originalText, analysis.lockedSpans(),
                finalResult.content(), persona);

        if (!validation.passed()) {
            log.warn("[MultiModel] Final [{}] validation errors: {}", finalModelName,
                    validation.errors().stream().map(ValidationIssue::message).toList());
        }

        return new MultiModelPipelineResult(
                unmaskResult.text(),
                validation.issues(),
                analysis.totalPromptTokens(),
                analysis.totalCompletionTokens(),
                finalResult.promptTokens(),
                finalResult.completionTokens()
        );
    }

    private LlmCallResult safeGet(CompletableFuture<LlmCallResult> future, String label) {
        try {
            return future.join();
        } catch (Exception e) {
            log.warn("[MultiModel] {} failed, using empty fallback: {}", label, e.getMessage());
            return new LlmCallResult("{}", null, 0, 0);
        }
    }
}
