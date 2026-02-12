package com.politeai.interfaces.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.politeai.domain.transform.model.ProTransformResult;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransformResponse(
        String transformedText,
        String analysisContext,
        Map<String, Boolean> checks,
        List<EditEntry> edits,
        List<String> riskFlags
) {
    public record EditEntry(String original, String changed, String reason) {}

    /**
     * Simple constructor for Free tier (no Pro fields).
     */
    public TransformResponse(String transformedText, String analysisContext) {
        this(transformedText, analysisContext, null, null, null);
    }

    /**
     * Convert Pro domain edits to DTO edits.
     */
    public static List<EditEntry> fromDomainEdits(List<ProTransformResult.EditEntry> domainEdits) {
        if (domainEdits == null) return null;
        return domainEdits.stream()
                .map(e -> new EditEntry(e.original(), e.changed(), e.reason()))
                .toList();
    }
}
