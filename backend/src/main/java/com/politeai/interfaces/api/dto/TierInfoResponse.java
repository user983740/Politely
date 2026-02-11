package com.politeai.interfaces.api.dto;

public record TierInfoResponse(
        String tier,
        int maxTextLength,
        boolean partialRewriteEnabled,
        boolean promptEnabled
) {}
