package com.politeai.domain.transform.model;

import java.util.List;
import java.util.Map;

/**
 * Value object for Pro JSON 1-pass response parsed from the LLM.
 */
public record ProTransformResult(
        String analysis,
        String result,
        Map<String, Boolean> checks,
        List<EditEntry> edits,
        List<String> riskFlags
) {
    public record EditEntry(String original, String changed, String reason) {}
}
