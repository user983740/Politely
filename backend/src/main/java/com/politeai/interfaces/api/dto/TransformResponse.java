package com.politeai.interfaces.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransformResponse(
        String transformedText,
        String analysisContext
) {}
