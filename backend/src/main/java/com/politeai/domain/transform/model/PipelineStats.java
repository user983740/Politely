package com.politeai.domain.transform.model;

public record PipelineStats(
        long structureLabelPromptTokens,
        long structureLabelCompletionTokens,
        long finalPromptTokens,
        long finalCompletionTokens,
        int segmentCount,
        int greenCount,
        int yellowCount,
        int redCount,
        int lockedSpanCount,
        int retryCount,
        boolean identityBoosterFired,
        boolean relationIntentFired,
        boolean contextGatingFired,
        String chosenTemplateId,
        long totalLatencyMs
) {}
