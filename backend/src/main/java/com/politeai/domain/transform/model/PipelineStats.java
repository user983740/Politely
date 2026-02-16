package com.politeai.domain.transform.model;

public record PipelineStats(
        long analysisPromptTokens,
        long analysisCompletionTokens,
        long finalPromptTokens,
        long finalCompletionTokens,
        int segmentCount,
        int greenCount,
        int yellowCount,
        int redCount,
        int lockedSpanCount,
        int retryCount,
        boolean identityBoosterFired,
        boolean situationAnalysisFired,
        boolean contextGatingFired,
        String chosenTemplateId,
        long totalLatencyMs
) {}
