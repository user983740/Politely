package com.politeai.application.transform;

import com.politeai.application.transform.exception.TierRestrictionException;
import com.politeai.domain.transform.model.*;
import com.politeai.domain.transform.service.TransformService;
import com.politeai.domain.user.model.UserTier;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.MultiModelPipelineResult;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.SharedAnalysisResult;
import com.politeai.infrastructure.ai.pipeline.TransformPipeline;
import com.politeai.interfaces.api.dto.ABTestResponse;
import com.politeai.interfaces.api.dto.ABTestResponse.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransformAppService {

    private final TransformService transformService;
    private final TransformPipeline transformPipeline;
    private final MultiModelPipeline multiModelPipeline;

    @Value("${tier.free.max-text-length}")
    private int freeMaxTextLength;

    @Value("${tier.paid.max-text-length}")
    private int paidMaxTextLength;

    /**
     * Full transform via pipeline (preprocess → LLM → postprocess).
     */
    public PipelineResult transform(Persona persona,
                                    List<SituationContext> contexts,
                                    ToneLevel toneLevel,
                                    String originalText,
                                    String userPrompt,
                                    String senderInfo,
                                    UserTier tier) {
        int maxLength = tier == UserTier.PAID ? paidMaxTextLength : freeMaxTextLength;
        if (originalText.length() > maxLength) {
            throw new TierRestrictionException(
                    String.format("프리티어는 최대 %d자까지 입력할 수 있습니다.", freeMaxTextLength));
        }
        if (tier == UserTier.FREE && userPrompt != null && !userPrompt.isBlank()) {
            throw new TierRestrictionException("추가 요청 프롬프트는 프리미엄 기능입니다.");
        }

        return transformPipeline.execute(persona, contexts, toneLevel, originalText, userPrompt, senderInfo, tier);
    }

    public TransformResult partialRewrite(String selectedText,
                                          String fullContext,
                                          Persona persona,
                                          List<SituationContext> contexts,
                                          ToneLevel toneLevel,
                                          String userPrompt,
                                          String senderInfo,
                                          String analysisContext,
                                          UserTier tier) {
        if (tier == UserTier.FREE) {
            throw new TierRestrictionException("부분 재변환은 프리미엄 기능입니다.");
        }

        return transformService.partialRewrite(selectedText, fullContext, persona, contexts, toneLevel, userPrompt, senderInfo, analysisContext);
    }

    public void validateTransformRequest(String originalText, String userPrompt, UserTier tier) {
        int maxLength = tier == UserTier.PAID ? paidMaxTextLength : freeMaxTextLength;
        if (originalText.length() > maxLength) {
            throw new TierRestrictionException(
                    String.format("프리티어는 최대 %d자까지 입력할 수 있습니다.", freeMaxTextLength));
        }
        if (tier == UserTier.FREE && userPrompt != null && !userPrompt.isBlank()) {
            throw new TierRestrictionException("추가 요청 프롬프트는 프리미엄 기능입니다.");
        }
    }

    public void validatePartialRewriteRequest(UserTier tier) {
        if (tier == UserTier.FREE) {
            throw new TierRestrictionException("부분 재변환은 프리미엄 기능입니다.");
        }
    }

    public int getMaxTextLength(UserTier tier) {
        return tier == UserTier.PAID ? paidMaxTextLength : freeMaxTextLength;
    }

    /**
     * A/B Test: run shared analysis once, then Final model with gpt-4o (A) and gpt-4o-mini (B).
     */
    public ABTestResponse abTest(Persona persona,
                                  List<SituationContext> contexts,
                                  ToneLevel toneLevel,
                                  String originalText,
                                  String userPrompt,
                                  String senderInfo,
                                  UserTier tier) {
        validateTransformRequest(originalText, userPrompt, tier);

        long totalStart = System.currentTimeMillis();

        // 1. Shared analysis (3 intermediate models, 1 call)
        long analysisStart = System.currentTimeMillis();
        SharedAnalysisResult analysis = multiModelPipeline.executeSharedAnalysis(
                persona, contexts, toneLevel, originalText, userPrompt, senderInfo);
        long sharedAnalysisDuration = System.currentTimeMillis() - analysisStart;

        // 2. Final A (gpt-4o) and Final B (gpt-4o-mini) in parallel
        CompletableFuture<FinalRunResult> futureA = CompletableFuture.supplyAsync(() ->
                runFinal("gpt-4o", analysis, persona, contexts, toneLevel, originalText, userPrompt, senderInfo));

        CompletableFuture<FinalRunResult> futureB = CompletableFuture.supplyAsync(() ->
                runFinal("gpt-4o-mini", analysis, persona, contexts, toneLevel, originalText, userPrompt, senderInfo));

        CompletableFuture.allOf(futureA, futureB).join();

        FinalRunResult resultA = futureA.join();
        FinalRunResult resultB = futureB.join();

        long totalDuration = System.currentTimeMillis() - totalStart;

        // 3. Cost calculation
        double sharedAnalysisCost = costMini(analysis.totalPromptTokens(), analysis.totalCompletionTokens());

        double finalACost = cost4o(resultA.result != null ? resultA.result.finalPromptTokens() : 0,
                resultA.result != null ? resultA.result.finalCompletionTokens() : 0);
        double finalBCost = costMini(resultB.result != null ? resultB.result.finalPromptTokens() : 0,
                resultB.result != null ? resultB.result.finalCompletionTokens() : 0);

        double testACostTotal = sharedAnalysisCost + finalACost;
        double testBCostTotal = sharedAnalysisCost + finalBCost;
        double thisRequestTotal = sharedAnalysisCost + finalACost + finalBCost;

        // Token usage for entries
        TokenUsage tokenA = resultA.result != null
                ? new TokenUsage(
                        analysis.totalPromptTokens() + resultA.result.finalPromptTokens(),
                        analysis.totalCompletionTokens() + resultA.result.finalCompletionTokens(),
                        analysis.totalPromptTokens() + resultA.result.finalPromptTokens()
                                + analysis.totalCompletionTokens() + resultA.result.finalCompletionTokens(),
                        testACostTotal)
                : null;

        TokenUsage tokenB = resultB.result != null
                ? new TokenUsage(
                        analysis.totalPromptTokens() + resultB.result.finalPromptTokens(),
                        analysis.totalCompletionTokens() + resultB.result.finalCompletionTokens(),
                        analysis.totalPromptTokens() + resultB.result.finalPromptTokens()
                                + analysis.totalCompletionTokens() + resultB.result.finalCompletionTokens(),
                        testBCostTotal)
                : null;

        PipelineEntry entryA = new PipelineEntry(
                "gpt-4o",
                resultA.result != null ? resultA.result.transformedText() : null,
                resultA.durationMs,
                resultA.error,
                tokenA);

        PipelineEntry entryB = new PipelineEntry(
                "gpt-4o-mini",
                resultB.result != null ? resultB.result.transformedText() : null,
                resultB.durationMs,
                resultB.error,
                tokenB);

        CostInfo costInfo = new CostInfo(
                sharedAnalysisCost,
                testACostTotal,
                testBCostTotal,
                thisRequestTotal,
                new MonthlyCostProjection(testACostTotal * 1500, testACostTotal * 6000, testACostTotal * 20000),
                new MonthlyCostProjection(testBCostTotal * 1500, testBCostTotal * 6000, testBCostTotal * 20000)
        );

        return new ABTestResponse(entryA, entryB, sharedAnalysisDuration, totalDuration, costInfo);
    }

    private record FinalRunResult(MultiModelPipelineResult result, long durationMs, String error) {}

    private FinalRunResult runFinal(String modelName, SharedAnalysisResult analysis,
                                     Persona persona, List<SituationContext> contexts,
                                     ToneLevel toneLevel, String originalText,
                                     String userPrompt, String senderInfo) {
        long start = System.currentTimeMillis();
        try {
            MultiModelPipelineResult result = multiModelPipeline.executeFinal(
                    modelName, analysis, persona, contexts, toneLevel, originalText, userPrompt, senderInfo);
            long duration = System.currentTimeMillis() - start;
            return new FinalRunResult(result, duration, null);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[ABTest] Final model {} failed", modelName, e);
            return new FinalRunResult(null, duration, e.getMessage());
        }
    }

    // Cost helpers (per token pricing)
    private static double costMini(long promptTokens, long completionTokens) {
        return (promptTokens * 0.15 + completionTokens * 0.60) / 1_000_000.0;
    }

    private static double cost4o(long promptTokens, long completionTokens) {
        return (promptTokens * 2.50 + completionTokens * 10.0) / 1_000_000.0;
    }
}
