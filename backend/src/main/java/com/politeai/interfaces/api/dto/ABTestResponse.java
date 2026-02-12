package com.politeai.interfaces.api.dto;

public record ABTestResponse(
        PipelineEntry testA,
        PipelineEntry testB,
        long sharedAnalysisDurationMs,
        long totalDurationMs,
        CostInfo costInfo
) {
    public record PipelineEntry(
            String label,
            String transformedText,
            long finalModelDurationMs,
            String error,
            TokenUsage tokenUsage
    ) {}

    public record TokenUsage(
            long promptTokens,
            long completionTokens,
            long totalTokens,
            double estimatedCostUsd
    ) {}

    public record CostInfo(
            double sharedAnalysisCostUsd,
            double testACostUsd,
            double testBCostUsd,
            double thisRequestTotalUsd,
            MonthlyCostProjection monthlyA,
            MonthlyCostProjection monthlyB
    ) {}

    public record MonthlyCostProjection(
            double mvpInitial,
            double growth,
            double mature
    ) {}
}
