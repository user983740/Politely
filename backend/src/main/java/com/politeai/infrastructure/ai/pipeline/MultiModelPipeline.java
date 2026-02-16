package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.infrastructure.ai.AiTransformException;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.LlmCallResult;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanExtractor;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanMasker;
import com.politeai.infrastructure.ai.preprocessing.TextNormalizer;
import com.politeai.infrastructure.ai.segmentation.LlmSegmentRefiner;
import com.politeai.infrastructure.ai.segmentation.MeaningSegmenter;
import com.politeai.infrastructure.ai.pipeline.template.*;
import com.politeai.infrastructure.ai.validation.OutputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Multi-model pipeline orchestrator (v2).
 *
 * Pipeline:
 *   [parallel: SituationAnalysis (LLM) + A→G pipeline]
 *   A) normalize → B) extract+mask (enhanced regex)
 *   → E?) identityBooster (gating)
 *   → C) segment (server) → C') refine long segments (LLM, conditional)
 *   → D) structureLabel (LLM #1) → D') RED enforce
 *   → Template Select → Context Gating LLM (optional)
 *   → G) redact (server)
 *   → join SituationAnalysis → filterRedFacts
 *   → H) Final LLM #2 → I) unmask → validate → (ERROR: 1 retry)
 *
 * Base case: 3 LLM calls (SituationAnalysis + StructureLabel + Final, +1 if long segments). With gating: up to 6.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiModelPipeline {

    /**
     * Callback for reporting pipeline progress during analysis.
     * Used by streaming service to send real-time SSE events.
     */
    public interface PipelineProgressCallback {
        void onPhase(String phase) throws Exception;
        void onSpansExtracted(List<LockedSpan> spans, String maskedText) throws Exception;
        void onSegmented(List<Segment> segments) throws Exception;
        void onLabeled(List<LabeledSegment> labeledSegments) throws Exception;
        void onSituationAnalysis(boolean fired, SituationAnalysisService.SituationAnalysisResult result) throws Exception;
        void onRedacted(List<LabeledSegment> labeledSegments, int redCount) throws Exception;
        void onTemplateSelected(StructureTemplate template, boolean gatingFired) throws Exception;

        static PipelineProgressCallback noop() {
            return new PipelineProgressCallback() {
                @Override public void onPhase(String phase) {}
                @Override public void onSpansExtracted(List<LockedSpan> spans, String maskedText) {}
                @Override public void onSegmented(List<Segment> segments) {}
                @Override public void onLabeled(List<LabeledSegment> labeledSegments) {}
                @Override public void onSituationAnalysis(boolean fired, SituationAnalysisService.SituationAnalysisResult result) {}
                @Override public void onRedacted(List<LabeledSegment> labeledSegments, int redCount) {}
                @Override public void onTemplateSelected(StructureTemplate template, boolean gatingFired) {}
            };
        }
    }

    private final TextNormalizer textNormalizer;
    private final LockedSpanExtractor spanExtractor;
    private final LockedSpanMasker spanMasker;
    private final OutputValidator outputValidator;
    private final MultiModelPromptBuilder multiPromptBuilder;
    private final AiTransformService aiTransformService;
    private final MeaningSegmenter meaningSegmenter;
    private final LlmSegmentRefiner llmSegmentRefiner;
    private final StructureLabelService structureLabelService;
    private final RedLabelEnforcer redLabelEnforcer;
    private final RedactionService redactionService;
    private final GatingConditionEvaluator gatingEvaluator;
    private final IdentityLockBooster identityLockBooster;
    private final SituationAnalysisService situationAnalysisService;
    private final TemplateSelector templateSelector;
    private final ContextGatingService contextGatingService;

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
            RedactionService.RedactionResult redaction,
            SituationAnalysisService.SituationAnalysisResult situationAnalysis,
            String summaryText,
            long totalAnalysisPromptTokens,
            long totalAnalysisCompletionTokens,
            boolean identityBoosterFired,
            boolean situationAnalysisFired,
            boolean contextGatingFired,
            String chosenTemplateId,
            StructureTemplate chosenTemplate,
            List<StructureSection> effectiveSections,
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

    // --- dedupeKey helpers ---

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([A-Z_]+)_(\\d+)\\}\\}");

    /**
     * Generate a deduplication key from segment text:
     * 1. Replace {{TYPE_N}} placeholders with lowercase "type_n" tokens
     * 2. Remove whitespace and punctuation
     * 3. Lowercase
     */
    static String buildDedupeKey(String text) {
        if (text == null || text.isBlank()) return null;
        StringBuffer sb = new StringBuffer();
        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        while (m.find()) {
            m.appendReplacement(sb, m.group(1).toLowerCase() + "_" + m.group(2));
        }
        m.appendTail(sb);
        return sb.toString()
                .replaceAll("[\\s\\p{Punct}]", "")
                .toLowerCase();
    }

    /**
     * Run the analysis phase (non-streaming, no progress callback).
     */
    public AnalysisPhaseResult executeAnalysis(Persona persona,
                                                List<SituationContext> contexts,
                                                ToneLevel toneLevel,
                                                String originalText,
                                                String userPrompt,
                                                String senderInfo,
                                                boolean identityBoosterToggle) {
        try {
            return executeAnalysis(persona, contexts, toneLevel, originalText,
                    userPrompt, senderInfo, identityBoosterToggle, null, null, PipelineProgressCallback.noop());
        } catch (AiTransformException e) {
            throw e;
        } catch (Exception e) {
            if (e.getCause() instanceof AiTransformException ate) {
                throw ate;
            }
            throw new AiTransformException("파이프라인 분석 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * Run the analysis phase with progress callback: preprocess → segment → label → template → gating → redact.
     * Each step reports progress via the callback for real-time SSE streaming.
     */
    public AnalysisPhaseResult executeAnalysis(Persona persona,
                                                List<SituationContext> contexts,
                                                ToneLevel toneLevel,
                                                String originalText,
                                                String userPrompt,
                                                String senderInfo,
                                                boolean identityBoosterToggle,
                                                Topic topic,
                                                Purpose purpose,
                                                PipelineProgressCallback callback) throws Exception {
        // A) Normalize
        callback.onPhase("normalizing");
        String normalized = textNormalizer.normalize(originalText);

        // B) Extract regex locked spans + mask
        callback.onPhase("extracting");
        List<LockedSpan> regexSpans = spanExtractor.extract(normalized);
        String masked = spanMasker.mask(normalized, regexSpans);
        callback.onSpansExtracted(regexSpans, masked);

        if (!regexSpans.isEmpty()) {
            log.info("[Pipeline] Extracted {} regex locked spans", regexSpans.size());
        }

        // Track tokens
        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        boolean boosterFired = false;
        boolean situationFired = false;
        boolean contextGatingFired = false;

        // Launch Situation Analysis async (runs in parallel with the rest of the pipeline)
        boolean shouldFireSituation = gatingEvaluator.shouldFireSituationAnalysis(persona, masked);
        CompletableFuture<SituationAnalysisService.SituationAnalysisResult> situationFuture = null;
        if (shouldFireSituation) {
            final String maskedForSituation = masked;
            situationFuture = CompletableFuture.supplyAsync(() ->
                    situationAnalysisService.analyze(persona, contexts, toneLevel, maskedForSituation, userPrompt, senderInfo));
        }

        // E?) Identity Booster (gating — before segmentation so new spans are included)
        List<LockedSpan> allSpans = regexSpans;
        if (gatingEvaluator.shouldFireIdentityBooster(identityBoosterToggle, persona, regexSpans, normalized.length())) {
            callback.onPhase("identity_boosting");
            IdentityLockBooster.BoosterResult boostResult = identityLockBooster.boost(persona, normalized, regexSpans, masked);
            allSpans = boostResult.allSpans();
            masked = boostResult.remaskedText();
            totalPromptTokens += boostResult.promptTokens();
            totalCompletionTokens += boostResult.completionTokens();
            boosterFired = true;
            callback.onSpansExtracted(allSpans, masked); // Re-send updated spans
        } else {
            callback.onPhase("identity_skipped");
        }

        // C) Segment (server-side, rule-based)
        callback.onPhase("segmenting");
        List<Segment> segments = meaningSegmenter.segment(masked);

        // C') Refine long segments with LLM (conditional — only if segments > threshold exist)
        LlmSegmentRefiner.RefineResult refineResult = llmSegmentRefiner.refine(segments, masked);
        boolean segmentRefinerFired = refineResult.promptTokens() > 0;
        if (segmentRefinerFired) {
            callback.onPhase("segment_refining");
            segments = refineResult.segments();
            totalPromptTokens += refineResult.promptTokens();
            totalCompletionTokens += refineResult.completionTokens();
        } else {
            callback.onPhase("segment_refining_skipped");
        }
        callback.onSegmented(segments);

        // D) Structure Label (LLM #1)
        callback.onPhase("labeling");

        // D runs on main thread
        StructureLabelService.StructureLabelResult labelResult = structureLabelService.label(
                persona, contexts, toneLevel, userPrompt, senderInfo, segments, masked);
        totalPromptTokens += labelResult.promptTokens();
        totalCompletionTokens += labelResult.completionTokens();

        // D') Server-side RED label enforcement (pattern-based override after LLM labeling)
        List<LabeledSegment> enforcedLabels = redLabelEnforcer.enforce(labelResult.labeledSegments());
        callback.onLabeled(enforcedLabels);

        // [NEW] Template Selection (after RED enforcement, before gating)
        callback.onPhase("template_selecting");
        LabelStats labelStats = LabelStats.from(enforcedLabels);
        TemplateSelector.TemplateSelectionResult templateResult = templateSelector.selectTemplate(
                persona, contexts, topic, purpose, labelStats, masked);

        StructureTemplate chosenTemplate = templateResult.template();
        List<StructureSection> effectiveSections = templateResult.effectiveSections();

        // [NEW?] Context Gating (optional LLM)
        if (gatingEvaluator.shouldFireContextGating(persona, contexts, topic, purpose, toneLevel, labelStats, masked)) {
            callback.onPhase("context_gating");
            ContextGatingService.ContextGatingResult gating = contextGatingService.evaluate(
                    persona, contexts, topic, purpose, toneLevel, masked, enforcedLabels);
            totalPromptTokens += gating.promptTokens();
            totalCompletionTokens += gating.completionTokens();
            contextGatingFired = true;

            if (gating.meetsThreshold()) {
                // Re-select template with overridden metadata
                Topic effectiveTopic = gating.inferredTopic() != null ? gating.inferredTopic() : topic;
                Purpose effectivePurpose = gating.inferredPurpose() != null ? gating.inferredPurpose() : purpose;
                List<SituationContext> effectiveContexts = gating.inferredPrimaryContext() != null
                        ? List.of(gating.inferredPrimaryContext()) : contexts;

                TemplateSelector.TemplateSelectionResult overridden = templateSelector.selectTemplate(
                        persona, effectiveContexts, effectiveTopic, effectivePurpose, labelStats, masked);
                chosenTemplate = overridden.template();
                effectiveSections = overridden.effectiveSections();
                log.info("[Pipeline] Context gating overrode template: {} → {} (confidence={})",
                        templateResult.template().id(), chosenTemplate.id(), gating.confidence());
            }
        } else {
            callback.onPhase("context_gating_skipped");
        }
        callback.onTemplateSelected(chosenTemplate, contextGatingFired);

        // G) Redact (count tiers + build redactionMap — no processedText assembly)
        callback.onPhase("redacting");
        RedactionService.RedactionResult redaction = redactionService.process(enforcedLabels);
        callback.onRedacted(enforcedLabels, redaction.redCount());

        // Collect Situation Analysis result (already completed or near-completion from parallel execution)
        SituationAnalysisService.SituationAnalysisResult situationResult = null;
        if (shouldFireSituation) {
            callback.onPhase("situation_analyzing");
            try {
                situationResult = situationFuture.join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof AiTransformException ate) throw ate;
                if (cause instanceof RuntimeException re) throw re;
                throw new AiTransformException("상황 분석 중 오류가 발생했습니다.", cause);
            }
            // Filter RED-overlapping facts
            situationResult = situationAnalysisService.filterRedFacts(situationResult, masked, enforcedLabels);
            totalPromptTokens += situationResult.promptTokens();
            totalCompletionTokens += situationResult.completionTokens();
            situationFired = true;
            callback.onSituationAnalysis(true, situationResult);
        } else {
            callback.onPhase("situation_skipped");
            callback.onSituationAnalysis(false, null);
        }

        // Count tiers
        int greenCount = (int) enforcedLabels.stream()
                .filter(s -> s.label().tier() == SegmentLabel.Tier.GREEN).count();
        int yellowCount = redaction.yellowCount();
        int redCount = redaction.redCount();

        log.info("[Pipeline] Analysis complete — segments={}, GREEN={}, YELLOW={}, RED={}, booster={}, situation={}, template={}, gating={}",
                segments.size(), greenCount, yellowCount, redCount, boosterFired, situationFired,
                chosenTemplate.id(), contextGatingFired);

        return new AnalysisPhaseResult(
                masked, allSpans, segments, enforcedLabels,
                redaction,
                situationResult, labelResult.summaryText(),
                totalPromptTokens, totalCompletionTokens,
                boosterFired, situationFired, contextGatingFired,
                chosenTemplate.id(), chosenTemplate, effectiveSections,
                greenCount, yellowCount, redCount
        );
    }

    /**
     * Build the final model prompts from analysis results.
     * Assigns order (by start position) and dedupeKey to each segment.
     * Used for streaming: caller handles the actual LLM call.
     */
    public FinalPromptPair buildFinalPrompt(AnalysisPhaseResult analysis,
                                             Persona persona, List<SituationContext> contexts,
                                             ToneLevel toneLevel, String senderInfo) {
        // Assign order by start position
        List<LabeledSegment> sorted = analysis.labeledSegments().stream()
                .sorted(Comparator.comparingInt(LabeledSegment::start))
                .toList();

        List<MultiModelPromptBuilder.OrderedSegment> orderedSegments = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            LabeledSegment ls = sorted.get(i);
            boolean isRed = ls.label().tier() == SegmentLabel.Tier.RED;
            String dedupeKey = isRed ? null : buildDedupeKey(ls.text());
            // Extract mustInclude placeholders for YELLOW segments
            List<String> mustInclude = (ls.label().tier() == SegmentLabel.Tier.YELLOW)
                    ? MultiModelPromptBuilder.extractPlaceholders(ls.text())
                    : List.of();
            orderedSegments.add(new MultiModelPromptBuilder.OrderedSegment(
                    ls.segmentId(), i + 1, ls.label().tier().name(), ls.label().name(),
                    isRed ? null : ls.text(),
                    dedupeKey,
                    mustInclude
            ));
        }

        String finalSystem = multiPromptBuilder.buildFinalSystemPrompt(
                persona, contexts, toneLevel, analysis.chosenTemplate(), analysis.effectiveSections());
        String finalUser = multiPromptBuilder.buildFinalUserMessage(
                persona, contexts, toneLevel, senderInfo,
                orderedSegments,
                analysis.lockedSpans(), analysis.situationAnalysis(), analysis.summaryText(),
                analysis.chosenTemplate(), analysis.effectiveSections());

        return new FinalPromptPair(finalSystem, finalUser, analysis.lockedSpans(),
                analysis.redaction().redactionMap());
    }

    private static final Set<ValidationIssueType> RETRYABLE_WARNINGS = Set.of(
            ValidationIssueType.CORE_NUMBER_MISSING,
            ValidationIssueType.CORE_DATE_MISSING,
            ValidationIssueType.SOFTEN_CONTENT_DROPPED,
            ValidationIssueType.SECTION_S2_MISSING
    );

    /**
     * Run the Final model using analysis results.
     */
    public PipelineResult executeFinal(String finalModelName,
                                        AnalysisPhaseResult analysis,
                                        Persona persona,
                                        List<SituationContext> contexts,
                                        ToneLevel toneLevel,
                                        String originalText,
                                        String senderInfo,
                                        int maxTokens) {
        long startTime = System.currentTimeMillis();

        FinalPromptPair prompt = buildFinalPrompt(analysis, persona, contexts, toneLevel, senderInfo);

        // Extract YELLOW segment texts for Rule 11
        List<String> yellowTexts = analysis.labeledSegments().stream()
                .filter(s -> s.label().tier() == SegmentLabel.Tier.YELLOW)
                .map(LabeledSegment::text)
                .toList();

        // Call Final model (LLM #2)
        LlmCallResult finalResult = aiTransformService.callOpenAIWithModel(
                finalModelName, prompt.systemPrompt(), prompt.userMessage(), -1, maxTokens, null);

        // Unmask
        LockedSpanMasker.UnmaskResult unmaskResult = spanMasker.unmask(
                finalResult.content(), prompt.lockedSpans());

        // Validate (with template info)
        ValidationResult validation = outputValidator.validate(
                unmaskResult.text(), originalText, prompt.lockedSpans(),
                finalResult.content(), persona, prompt.redactionMap(), yellowTexts,
                analysis.chosenTemplate(), analysis.effectiveSections(), analysis.labeledSegments());

        int retryCount = 0;

        // Retry once on ERROR or retryable WARNING
        boolean hasRetryableWarning = validation.issues().stream()
                .anyMatch(i -> i.severity() == ValidationIssue.Severity.WARNING
                        && RETRYABLE_WARNINGS.contains(i.type()));

        if (!validation.passed() || hasRetryableWarning) {
            log.warn("[Pipeline] Final validation issues (errors: {}, retryable warnings: {}), retrying once",
                    validation.errors().stream().map(ValidationIssue::message).toList(),
                    validation.issues().stream()
                            .filter(i -> i.severity() == ValidationIssue.Severity.WARNING
                                    && RETRYABLE_WARNINGS.contains(i.type()))
                            .map(ValidationIssue::message).toList());
            retryCount = 1;

            // Retry hint in system prompt
            String retryHint = "\n\n[검증 재시도 지침] 원문에 있던 숫자/날짜는 모두 유지하세요. SOFTEN 대상 내용을 삭제하지 말고 재작성하세요. S2(내부 확인/점검) 섹션이 있으면 반드시 포함하세요.";
            String retrySystemPrompt = prompt.systemPrompt() + retryHint;

            // Build specific locked span hint if applicable
            String lockedSpanHint = outputValidator.buildLockedSpanRetryHint(
                    validation.issues(), prompt.lockedSpans());

            String errorHint = "\n\n[시스템 검증 오류] " + String.join("; ",
                    validation.issues().stream()
                            .filter(i -> i.severity() == ValidationIssue.Severity.ERROR
                                    || RETRYABLE_WARNINGS.contains(i.type()))
                            .map(ValidationIssue::message).toList());
            String retryUser = prompt.userMessage() + errorHint + lockedSpanHint;

            // Retry with lower temperature (0.3) for more conservative output
            LlmCallResult retryResult = aiTransformService.callOpenAIWithModel(
                    finalModelName, retrySystemPrompt, retryUser, 0.3, maxTokens, null);
            LockedSpanMasker.UnmaskResult retryUnmask = spanMasker.unmask(
                    retryResult.content(), prompt.lockedSpans());
            validation = outputValidator.validate(
                    retryUnmask.text(), originalText, prompt.lockedSpans(),
                    retryResult.content(), persona, prompt.redactionMap(), yellowTexts,
                    analysis.chosenTemplate(), analysis.effectiveSections(), analysis.labeledSegments());
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
                analysis.situationAnalysisFired(),
                analysis.contextGatingFired(),
                analysis.chosenTemplateId(),
                totalLatency
        );

        return new PipelineResult(unmaskResult.text(), validation.issues(), stats);
    }
}
