package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.domain.user.model.UserTier;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.PromptBuilder;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanExtractor;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanMasker;
import com.politeai.infrastructure.ai.preprocessing.TextNormalizer;
import com.politeai.infrastructure.ai.validation.OutputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Orchestrates the full transform pipeline (unified for all tiers):
 * <p>
 * preprocess → prompt (JSON rules) → LLM JSON 1-pass → parse → unmask → validate → retry? → done
 * </p>
 * Tier differences are feature restrictions (input length, userPrompt, partial rewrite),
 * not pipeline quality.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransformPipeline {

    private final TextNormalizer textNormalizer;
    private final LockedSpanExtractor spanExtractor;
    private final LockedSpanMasker spanMasker;
    private final OutputValidator outputValidator;
    private final PromptBuilder promptBuilder;
    private final AiTransformService aiTransformService;

    /**
     * Execute the full pipeline (unified JSON 1-pass for all tiers).
     */
    public PipelineResult execute(Persona persona,
                                  List<SituationContext> contexts,
                                  ToneLevel toneLevel,
                                  String originalText,
                                  String userPrompt,
                                  String senderInfo,
                                  UserTier tier) {
        TransformPipelineContext ctx = new TransformPipelineContext();
        ctx.setPersona(persona);
        ctx.setContexts(contexts);
        ctx.setToneLevel(toneLevel);
        ctx.setOriginalText(originalText);
        ctx.setUserPrompt(userPrompt);
        ctx.setSenderInfo(senderInfo);
        ctx.setTier(tier);

        // 1. Preprocess
        preprocess(ctx);

        // 2. Build prompts
        buildPrompts(ctx);

        // 3. Call LLM (unified JSON pipeline for all tiers)
        executePipeline(ctx);

        return ctx.toPipelineResult();
    }

    /**
     * Preprocess the input text: normalize → extract locked spans → mask.
     * Returns the context for use by streaming pipeline.
     */
    public TransformPipelineContext preprocess(Persona persona,
                                               List<SituationContext> contexts,
                                               ToneLevel toneLevel,
                                               String originalText,
                                               String userPrompt,
                                               String senderInfo,
                                               UserTier tier) {
        TransformPipelineContext ctx = new TransformPipelineContext();
        ctx.setPersona(persona);
        ctx.setContexts(contexts);
        ctx.setToneLevel(toneLevel);
        ctx.setOriginalText(originalText);
        ctx.setUserPrompt(userPrompt);
        ctx.setSenderInfo(senderInfo);
        ctx.setTier(tier);

        preprocess(ctx);
        buildPrompts(ctx);

        return ctx;
    }

    /**
     * Post-process streaming output: unmask + validate.
     * Called by AiStreamingTransformService after stream completes.
     */
    public PipelineResult postProcessStreaming(TransformPipelineContext ctx, String rawOutput) {
        ctx.setRawLlmOutput(rawOutput);

        // Unmask
        LockedSpanMasker.UnmaskResult unmaskResult = spanMasker.unmask(rawOutput, ctx.getLockedSpans());
        ctx.setUnmaskedOutput(unmaskResult.text());
        ctx.setMissingSpans(unmaskResult.missingSpans());

        // Validate
        ValidationResult validation = outputValidator.validate(
                unmaskResult.text(),
                ctx.getOriginalText(),
                ctx.getLockedSpans(),
                rawOutput,
                ctx.getPersona()
        );
        ctx.setValidationResult(validation);

        if (!validation.passed()) {
            log.warn("Free streaming output has validation errors (no retry): {}",
                    validation.errors().stream().map(ValidationIssue::message).toList());
        }
        if (!validation.warnings().isEmpty()) {
            log.info("Free streaming output warnings: {}",
                    validation.warnings().stream().map(ValidationIssue::message).toList());
        }

        return ctx.toPipelineResult();
    }

    // ===== Internal methods =====

    private void preprocess(TransformPipelineContext ctx) {
        // 1. Normalize
        String normalized = textNormalizer.normalize(ctx.getOriginalText());
        ctx.setNormalizedText(normalized);

        // 2. Extract locked spans
        List<LockedSpan> spans = spanExtractor.extract(normalized);
        ctx.setLockedSpans(spans);

        if (!spans.isEmpty()) {
            log.info("Extracted {} locked spans: {}", spans.size(),
                    spans.stream().map(s -> s.type() + ":\"" + s.originalText() + "\"").toList());
        }

        // 3. Mask
        String masked = spanMasker.mask(normalized, spans);
        ctx.setMaskedText(masked);
    }

    private void buildPrompts(TransformPipelineContext ctx) {
        // Unified: always use JSON rules + no examples (JSON 1-pass is sufficient)
        ctx.setSystemPrompt(promptBuilder.buildProSystemPrompt(
                ctx.getPersona(), ctx.getContexts(), ctx.getToneLevel()));
        ctx.setUserMessage(promptBuilder.buildProTransformUserMessage(
                ctx.getPersona(), ctx.getContexts(), ctx.getToneLevel(),
                ctx.getMaskedText(), ctx.getUserPrompt(), ctx.getSenderInfo()));

        // Log estimated token count
        int estimatedTokens = (int) (ctx.getSystemPrompt().length() / 1.5);
        log.info("System prompt: {} chars, ~{} tokens (dynamic)", ctx.getSystemPrompt().length(), estimatedTokens);
    }

    private void executePipeline(TransformPipelineContext ctx) {
        // Unified JSON 1-pass for all tiers
        TransformResult result = aiTransformService.callOpenAIForPro(
                ctx.getSystemPrompt(), ctx.getUserMessage());

        processJsonResult(ctx, result);

        // Validate
        ValidationResult validation = outputValidator.validate(
                ctx.getUnmaskedOutput(), ctx.getOriginalText(),
                ctx.getLockedSpans(), ctx.getRawLlmOutput(),
                ctx.getPersona());
        ctx.setValidationResult(validation);

        // Retry once if ERROR-level issues
        if (validation.hasErrors() && !ctx.isRetried()) {
            log.info("Pipeline validation failed, retrying once. Errors: {}",
                    validation.errors().stream().map(ValidationIssue::message).toList());

            ctx.setRetried(true);

            List<String> issueDescriptions = validation.errors().stream()
                    .map(i -> "[" + i.type() + "] " + i.message())
                    .toList();

            String retryMessage = promptBuilder.buildProRetryUserMessage(
                    ctx.getRawLlmOutput(), issueDescriptions, ctx.getUserMessage());

            TransformResult retryResult = aiTransformService.callOpenAIForPro(
                    ctx.getSystemPrompt(), retryMessage);

            processJsonResult(ctx, retryResult);

            // Re-validate (accept result regardless)
            ValidationResult retryValidation = outputValidator.validate(
                    ctx.getUnmaskedOutput(), ctx.getOriginalText(),
                    ctx.getLockedSpans(), ctx.getRawLlmOutput(),
                    ctx.getPersona());
            ctx.setValidationResult(retryValidation);

            if (retryValidation.hasErrors()) {
                log.warn("Pipeline retry still has errors (accepting result): {}",
                        retryValidation.errors().stream().map(ValidationIssue::message).toList());
            }
        }
    }

    private void processJsonResult(TransformPipelineContext ctx, TransformResult result) {
        ctx.setRawLlmOutput(result.getTransformedText());
        ctx.setAnalysisContext(result.getAnalysisContext());

        // Unmask the result text
        LockedSpanMasker.UnmaskResult unmaskResult = spanMasker.unmask(
                result.getTransformedText(), ctx.getLockedSpans());
        ctx.setUnmaskedOutput(unmaskResult.text());
        ctx.setMissingSpans(unmaskResult.missingSpans());
    }
}
