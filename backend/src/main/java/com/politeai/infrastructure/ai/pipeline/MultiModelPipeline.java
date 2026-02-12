package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.LlmCallResult;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanExtractor;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanMasker;
import com.politeai.infrastructure.ai.preprocessing.TextNormalizer;
import com.politeai.infrastructure.ai.validation.OutputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Multi-model pipeline orchestrator.
 *
 * Pipeline:
 *   preprocess → 4 intermediate models (parallel, 4o-mini) → semantic span merge → Final model (parameterized) → unmask → validate
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
    private static final int ANALYSIS_MAX_TOKENS = 400;
    private static final int DECONSTRUCTION_MAX_TOKENS = 600;
    private static final double ANALYSIS_TEMP = 0.3;

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
     * Result of the shared analysis phase (3 intermediate models: situation+intent, locked expressions, deconstruction).
     */
    public record SharedAnalysisResult(
            String model1Output,
            String model2Output,
            String model4Output,
            long totalPromptTokens,
            long totalCompletionTokens,
            String maskedText,
            List<LockedSpan> lockedSpans
    ) {}

    /**
     * Result of merging regex spans with AI-identified semantic spans.
     */
    public record MergedSpanResult(String remaskedText, List<LockedSpan> allSpans) {}

    /**
     * Pair of system prompt and user message for the final model, with locked spans.
     */
    public record FinalPromptPair(String systemPrompt, String userMessage, List<LockedSpan> lockedSpans) {}

    /**
     * Run only the shared analysis phase (preprocess + 3 intermediate models + semantic span merge).
     * Call this once, then use executeFinal() or buildFinalPrompt() for each A/B variant.
     */
    public SharedAnalysisResult executeSharedAnalysis(Persona persona,
                                                       List<SituationContext> contexts,
                                                       ToneLevel toneLevel,
                                                       String originalText,
                                                       String userPrompt,
                                                       String senderInfo) {
        // 1. Preprocess
        String normalized = textNormalizer.normalize(originalText);
        List<LockedSpan> regexSpans = spanExtractor.extract(normalized);
        String masked = spanMasker.mask(normalized, regexSpans);

        if (!regexSpans.isEmpty()) {
            log.info("[MultiModel] Extracted {} regex locked spans", regexSpans.size());
        }

        // 2. Build intermediate prompts (3 models: situation+intent, locked expressions, deconstruction)
        String m1System = multiPromptBuilder.buildModel1SystemPrompt();
        String m1User = multiPromptBuilder.buildModel1UserMessage(persona, contexts, toneLevel, masked, userPrompt, senderInfo);

        String m2System = multiPromptBuilder.buildModel2SystemPrompt();
        String m2User = multiPromptBuilder.buildModel2UserMessage(persona, masked);

        String m4System = multiPromptBuilder.buildModel4SystemPrompt();
        String m4User = multiPromptBuilder.buildModel4UserMessage(masked);

        // 3. Run 3 intermediate models in parallel
        CompletableFuture<LlmCallResult> f1 = CompletableFuture.supplyAsync(() ->
                aiTransformService.callOpenAIWithModel(ANALYSIS_MODEL, m1System, m1User,
                        ANALYSIS_TEMP, ANALYSIS_MAX_TOKENS, null));

        CompletableFuture<LlmCallResult> f2 = CompletableFuture.supplyAsync(() ->
                aiTransformService.callOpenAIWithModel(ANALYSIS_MODEL, m2System, m2User,
                        ANALYSIS_TEMP, ANALYSIS_MAX_TOKENS, null));

        CompletableFuture<LlmCallResult> f4 = CompletableFuture.supplyAsync(() ->
                aiTransformService.callOpenAIWithModel(ANALYSIS_MODEL, m4System, m4User,
                        ANALYSIS_TEMP, DECONSTRUCTION_MAX_TOKENS, null));

        CompletableFuture.allOf(f1, f2, f4).join();

        LlmCallResult r1 = safeGet(f1, "Model1-SituationIntent");
        LlmCallResult r2 = safeGet(f2, "Model2-LockedExpr");
        LlmCallResult r4 = safeGet(f4, "Model3-Deconstruction");

        long totalPrompt = r1.promptTokens() + r2.promptTokens() + r4.promptTokens();
        long totalCompletion = r1.completionTokens() + r2.completionTokens() + r4.completionTokens();

        // 4. Merge semantic spans from Model 2
        MergedSpanResult mergeResult = mergeSemanticSpans(normalized, regexSpans, r2.content());
        String finalMasked = mergeResult.remaskedText();
        List<LockedSpan> allSpans = mergeResult.allSpans();

        log.info("[MultiModel] Shared analysis complete - total tokens: prompt={}, completion={}, spans: regex={}, total={}",
                totalPrompt, totalCompletion, regexSpans.size(), allSpans.size());

        return new SharedAnalysisResult(
                r1.content(), r2.content(), r4.content(),
                totalPrompt, totalCompletion,
                finalMasked, allSpans
        );
    }

    /**
     * Build the final model prompts without calling the LLM.
     * Used for streaming: caller handles the actual LLM call.
     */
    public FinalPromptPair buildFinalPrompt(SharedAnalysisResult analysis,
                                             Persona persona, List<SituationContext> contexts,
                                             ToneLevel toneLevel, String senderInfo) {
        String finalSystem = multiPromptBuilder.buildFinalSystemPrompt(persona, contexts, toneLevel);
        String finalUser = multiPromptBuilder.buildFinalUserMessage(
                persona, contexts, toneLevel, senderInfo,
                analysis.model1Output(), analysis.model2Output(),
                analysis.model4Output(), analysis.lockedSpans());

        return new FinalPromptPair(finalSystem, finalUser, analysis.lockedSpans());
    }

    /**
     * Run the Final model using shared analysis results.
     */
    public MultiModelPipelineResult executeFinal(String finalModelName,
                                                  SharedAnalysisResult analysis,
                                                  Persona persona,
                                                  List<SituationContext> contexts,
                                                  ToneLevel toneLevel,
                                                  String originalText,
                                                  String senderInfo) {
        // Build Final prompt with intermediate analysis injected
        FinalPromptPair prompt = buildFinalPrompt(analysis, persona, contexts, toneLevel, senderInfo);

        // Call Final model (plain text, no JSON format)
        LlmCallResult finalResult = aiTransformService.callOpenAIWithModel(
                finalModelName, prompt.systemPrompt(), prompt.userMessage(), -1, -1, null);

        // Unmask
        LockedSpanMasker.UnmaskResult unmaskResult = spanMasker.unmask(
                finalResult.content(), prompt.lockedSpans());

        // Validate
        ValidationResult validation = outputValidator.validate(
                unmaskResult.text(), originalText, prompt.lockedSpans(),
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

    /**
     * Merge AI-identified semantic spans (from Model 2) with regex-extracted spans.
     * Finds each semantic expression in the normalized text, skips overlaps with regex spans,
     * then re-indexes and re-masks the text.
     */
    MergedSpanResult mergeSemanticSpans(String normalizedText,
                                         List<LockedSpan> regexSpans,
                                         String model2Output) {
        List<LockedSpan> semanticSpans = parseSemanticSpans(normalizedText, regexSpans, model2Output);

        if (semanticSpans.isEmpty()) {
            return new MergedSpanResult(spanMasker.mask(normalizedText, regexSpans), regexSpans);
        }

        // Combine and sort by position
        List<LockedSpan> allSpans = new ArrayList<>(regexSpans);
        allSpans.addAll(semanticSpans);
        allSpans.sort(Comparator.comparingInt(LockedSpan::startPos));

        // Re-index and re-assign placeholders
        List<LockedSpan> reindexed = new ArrayList<>();
        for (int i = 0; i < allSpans.size(); i++) {
            LockedSpan s = allSpans.get(i);
            reindexed.add(new LockedSpan(i, s.originalText(), "{{LOCKED_" + i + "}}", s.type(), s.startPos(), s.endPos()));
        }

        String remasked = spanMasker.mask(normalizedText, reindexed);
        log.info("[MultiModel] Merged {} semantic spans (total: {})", semanticSpans.size(), reindexed.size());

        return new MergedSpanResult(remasked, reindexed);
    }

    /**
     * Parse Model 2 line-based output ("- expression" per line) and find non-overlapping semantic spans in the text.
     */
    private List<LockedSpan> parseSemanticSpans(String normalizedText,
                                                  List<LockedSpan> regexSpans,
                                                  String model2Output) {
        List<LockedSpan> result = new ArrayList<>();
        if (model2Output == null || model2Output.isBlank() || model2Output.trim().equals("없음")) {
            return result;
        }

        try {
            int nextIndex = regexSpans.size(); // Start indexing after regex spans

            for (String line : model2Output.split("\n")) {
                line = line.trim();
                if (!line.startsWith("- ")) continue;

                String text = line.substring(2).trim();
                if (text.isBlank()) continue;

                // Find position in normalized text
                int pos = normalizedText.indexOf(text);
                if (pos < 0) continue;

                int endPos = pos + text.length();

                // Check overlap with existing regex spans
                if (overlapsAny(pos, endPos, regexSpans)) continue;

                result.add(new LockedSpan(
                        nextIndex++, text,
                        "{{LOCKED_" + (nextIndex - 1) + "}}",
                        LockedSpanType.SEMANTIC, pos, endPos
                ));
            }
        } catch (Exception e) {
            log.warn("[MultiModel] Failed to parse Model 2 semantic spans: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Check if a span [start, end) overlaps with any existing span.
     */
    private boolean overlapsAny(int start, int end, List<LockedSpan> spans) {
        for (LockedSpan span : spans) {
            if (start < span.endPos() && end > span.startPos()) {
                return true;
            }
        }
        return false;
    }

    private LlmCallResult safeGet(CompletableFuture<LlmCallResult> future, String label) {
        try {
            return future.join();
        } catch (Exception e) {
            log.warn("[MultiModel] {} failed, using empty fallback: {}", label, e.getMessage());
            return new LlmCallResult("", null, 0, 0);
        }
    }
}
