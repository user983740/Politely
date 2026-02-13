package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.LlmCallResult;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanExtractor;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanMasker;
import com.politeai.infrastructure.ai.preprocessing.TextNormalizer;
import com.politeai.infrastructure.ai.segmentation.MeaningSegmenter;
import com.politeai.infrastructure.ai.validation.OutputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Multi-model pipeline orchestrator (v2).
 *
 * Pipeline:
 *   A) normalize → B) extract+mask (enhanced regex) → C) segment (server)
 *   → D) structureLabel (LLM #1) → E?) identityBooster (gating)
 *   → F?) relationIntent (gating) → G) redact (server)
 *   → H) Final LLM #2 → I) unmask → validate → (ERROR: 1 retry)
 *
 * Base case: 2 LLM calls. With gating: up to 4.
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
    private final MeaningSegmenter meaningSegmenter;
    private final StructureLabelService structureLabelService;
    private final RedactionService redactionService;
    private final GatingConditionEvaluator gatingEvaluator;
    private final IdentityLockBooster identityLockBooster;
    private final RelationIntentService relationIntentService;

    /**
     * Pair of system prompt and user message for the final model, with locked spans and redaction map.
     */
    public record FinalPromptPair(String systemPrompt, String userMessage,
                                   List<LockedSpan> lockedSpans,
                                   Map<String, String> redactionMap) {}

    /**
     * Result of the analysis phase.
     */
    public record AnalysisPhaseResult(
            String maskedText,
            List<LockedSpan> lockedSpans,
            List<Segment> segments,
            List<LabeledSegment> labeledSegments,
            String processedText,
            RedactionService.RedactionResult redaction,
            RelationIntentService.RelationIntentResult relationIntent,
            String summaryText,
            long totalAnalysisPromptTokens,
            long totalAnalysisCompletionTokens,
            boolean identityBoosterFired,
            boolean relationIntentFired,
            int greenCount,
            int yellowCount,
            int redCount
    ) {}

    /**
     * Result of the full pipeline execution.
     */
    public record PipelineResult(
            String transformedText,
            List<ValidationIssue> validationIssues,
            PipelineStats stats
    ) {}

    /**
     * Run the analysis phase: preprocess → segment → label → gating → redact.
     */
    public AnalysisPhaseResult executeAnalysis(Persona persona,
                                                List<SituationContext> contexts,
                                                ToneLevel toneLevel,
                                                String originalText,
                                                String userPrompt,
                                                String senderInfo,
                                                boolean identityBoosterToggle) {
        // A) Normalize
        String normalized = textNormalizer.normalize(originalText);

        // B) Extract regex locked spans + mask
        List<LockedSpan> regexSpans = spanExtractor.extract(normalized);
        String masked = spanMasker.mask(normalized, regexSpans);

        if (!regexSpans.isEmpty()) {
            log.info("[Pipeline] Extracted {} regex locked spans", regexSpans.size());
        }

        // Track tokens
        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        boolean boosterFired = false;
        boolean relationFired = false;

        // E?) Identity Booster (gating — before segmentation so new spans are included)
        List<LockedSpan> allSpans = regexSpans;
        if (gatingEvaluator.shouldFireIdentityBooster(identityBoosterToggle, persona, regexSpans, normalized.length())) {
            IdentityLockBooster.BoosterResult boostResult = identityLockBooster.boost(persona, normalized, regexSpans, masked);
            allSpans = boostResult.allSpans();
            masked = boostResult.remaskedText();
            totalPromptTokens += boostResult.promptTokens();
            totalCompletionTokens += boostResult.completionTokens();
            boosterFired = true;
        }

        // C) Segment (server-side, no LLM)
        List<Segment> segments = meaningSegmenter.segment(masked);

        // D) Structure Label (LLM #1)
        StructureLabelService.StructureLabelResult labelResult = structureLabelService.label(
                persona, contexts, toneLevel, userPrompt, senderInfo, segments, masked);
        totalPromptTokens += labelResult.promptTokens();
        totalCompletionTokens += labelResult.completionTokens();

        // F?) Relation/Intent (gating)
        RelationIntentService.RelationIntentResult relationResult = null;
        if (gatingEvaluator.shouldFireRelationIntent(persona, normalized)) {
            relationResult = relationIntentService.analyze(persona, contexts, toneLevel, masked, userPrompt, senderInfo);
            totalPromptTokens += relationResult.promptTokens();
            totalCompletionTokens += relationResult.completionTokens();
            relationFired = true;
        }

        // G) Redact (server enforcement)
        RedactionService.RedactionResult redaction = redactionService.process(masked, labelResult.labeledSegments());

        // Count tiers
        int greenCount = (int) labelResult.labeledSegments().stream()
                .filter(s -> s.label().tier() == SegmentLabel.Tier.GREEN).count();
        int yellowCount = redaction.yellowCount();
        int redCount = redaction.redCount();

        log.info("[Pipeline] Analysis complete — segments={}, GREEN={}, YELLOW={}, RED={}, booster={}, relation={}",
                segments.size(), greenCount, yellowCount, redCount, boosterFired, relationFired);

        return new AnalysisPhaseResult(
                masked, allSpans, segments, labelResult.labeledSegments(),
                redaction.processedText(), redaction,
                relationResult, labelResult.summaryText(),
                totalPromptTokens, totalCompletionTokens,
                boosterFired, relationFired,
                greenCount, yellowCount, redCount
        );
    }

    /**
     * Build the final model prompts from analysis results.
     * Used for streaming: caller handles the actual LLM call.
     */
    public FinalPromptPair buildFinalPrompt(AnalysisPhaseResult analysis,
                                             Persona persona, List<SituationContext> contexts,
                                             ToneLevel toneLevel, String senderInfo) {
        String finalSystem = multiPromptBuilder.buildFinalSystemPrompt(persona, contexts, toneLevel);
        String finalUser = multiPromptBuilder.buildFinalUserMessage(
                persona, contexts, toneLevel, senderInfo,
                analysis.labeledSegments(), analysis.processedText(),
                analysis.lockedSpans(), analysis.relationIntent(), analysis.summaryText());

        return new FinalPromptPair(finalSystem, finalUser, analysis.lockedSpans(),
                analysis.redaction().redactionMap());
    }

    /**
     * Run the Final model using analysis results.
     */
    public PipelineResult executeFinal(String finalModelName,
                                        AnalysisPhaseResult analysis,
                                        Persona persona,
                                        List<SituationContext> contexts,
                                        ToneLevel toneLevel,
                                        String originalText,
                                        String senderInfo) {
        long startTime = System.currentTimeMillis();

        FinalPromptPair prompt = buildFinalPrompt(analysis, persona, contexts, toneLevel, senderInfo);

        // Call Final model (LLM #2)
        LlmCallResult finalResult = aiTransformService.callOpenAIWithModel(
                finalModelName, prompt.systemPrompt(), prompt.userMessage(), -1, -1, null);

        // Unmask
        LockedSpanMasker.UnmaskResult unmaskResult = spanMasker.unmask(
                finalResult.content(), prompt.lockedSpans());

        // Validate (with redaction map for Rule 8)
        ValidationResult validation = outputValidator.validate(
                unmaskResult.text(), originalText, prompt.lockedSpans(),
                finalResult.content(), persona, prompt.redactionMap());

        int retryCount = 0;

        // Retry once on ERROR
        if (!validation.passed()) {
            log.warn("[Pipeline] Final validation errors: {}, retrying once",
                    validation.errors().stream().map(ValidationIssue::message).toList());
            retryCount = 1;

            String errorHint = "\n\n[시스템 검증 오류] " + String.join("; ",
                    validation.errors().stream().map(ValidationIssue::message).toList());
            String retryUser = prompt.userMessage() + errorHint;

            LlmCallResult retryResult = aiTransformService.callOpenAIWithModel(
                    finalModelName, prompt.systemPrompt(), retryUser, -1, -1, null);
            LockedSpanMasker.UnmaskResult retryUnmask = spanMasker.unmask(
                    retryResult.content(), prompt.lockedSpans());
            validation = outputValidator.validate(
                    retryUnmask.text(), originalText, prompt.lockedSpans(),
                    retryResult.content(), persona, prompt.redactionMap());
            unmaskResult = retryUnmask;
            finalResult = retryResult;
        }

        long totalLatency = System.currentTimeMillis() - startTime;

        PipelineStats stats = new PipelineStats(
                analysis.totalAnalysisPromptTokens(),
                analysis.totalAnalysisCompletionTokens(),
                finalResult.promptTokens(),
                finalResult.completionTokens(),
                analysis.segments().size(),
                analysis.greenCount(),
                analysis.yellowCount(),
                analysis.redCount(),
                analysis.lockedSpans().size(),
                retryCount,
                analysis.identityBoosterFired(),
                analysis.relationIntentFired(),
                totalLatency
        );

        return new PipelineResult(unmaskResult.text(), validation.issues(), stats);
    }

    // === Utility ===

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
            log.warn("[Pipeline] {} failed, using empty fallback: {}", label, e.getMessage());
            return new LlmCallResult("", null, 0, 0);
        }
    }
}
