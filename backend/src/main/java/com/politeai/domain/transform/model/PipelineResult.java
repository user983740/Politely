package com.politeai.domain.transform.model;

import java.util.List;
import java.util.Map;

/**
 * Final output of the transform pipeline.
 */
public record PipelineResult(
        String transformedText,
        String analysisContext,
        List<ValidationIssue> validationIssues,
        boolean wasRetried,
        Map<String, Boolean> checks,
        List<ProTransformResult.EditEntry> edits,
        List<String> riskFlags
) {
    /**
     * Convenience constructor for Free tier (no Pro fields).
     */
    public PipelineResult(String transformedText, String analysisContext,
                          List<ValidationIssue> validationIssues, boolean wasRetried) {
        this(transformedText, analysisContext, validationIssues, wasRetried, null, null, null);
    }
}
