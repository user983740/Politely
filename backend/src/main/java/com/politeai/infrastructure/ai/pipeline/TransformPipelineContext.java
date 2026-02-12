package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.domain.user.model.UserTier;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mutable context object passed through pipeline stages.
 * Accumulates results from each stage for the next.
 */
@Data
public class TransformPipelineContext {

    // --- Input ---
    private Persona persona;
    private List<SituationContext> contexts;
    private ToneLevel toneLevel;
    private String originalText;
    private String userPrompt;
    private String senderInfo;
    private UserTier tier;

    // --- Preprocessing ---
    private String normalizedText;
    private List<LockedSpan> lockedSpans = new ArrayList<>();
    private String maskedText;

    // --- Prompt ---
    private String systemPrompt;
    private String userMessage;

    // --- LLM Output ---
    private String rawLlmOutput;

    // --- Unmask ---
    private String unmaskedOutput;
    private List<LockedSpan> missingSpans = new ArrayList<>();

    // --- Validation ---
    private ValidationResult validationResult;
    private boolean retried = false;

    // --- Pro-specific ---
    private String analysisContext;
    private Map<String, Boolean> checks;
    private List<ProTransformResult.EditEntry> edits;
    private List<String> riskFlags;

    /**
     * Build the final PipelineResult from accumulated context.
     */
    public PipelineResult toPipelineResult() {
        List<ValidationIssue> issues = validationResult != null
                ? validationResult.issues()
                : List.of();

        return new PipelineResult(
                unmaskedOutput,
                analysisContext,
                issues,
                retried,
                checks,
                edits,
                riskFlags
        );
    }
}
