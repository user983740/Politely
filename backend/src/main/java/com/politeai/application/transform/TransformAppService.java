package com.politeai.application.transform;

import com.politeai.application.transform.exception.TierRestrictionException;
import com.politeai.domain.transform.model.*;
import com.politeai.domain.transform.service.TransformService;
import com.politeai.domain.user.model.UserTier;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.AnalysisPhaseResult;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.PipelineResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransformAppService {

    private final TransformService transformService;
    private final MultiModelPipeline multiModelPipeline;

    @Value("${tier.free.max-text-length}")
    private int freeMaxTextLength;

    @Value("${tier.paid.max-text-length}")
    private int paidMaxTextLength;

    @Value("${openai.model}")
    private String model;

    /**
     * Full transform via multi-model pipeline (v2: preprocess → segment → label → redact → final → unmask → validate).
     */
    public TransformResult transform(Persona persona,
                                     List<SituationContext> contexts,
                                     ToneLevel toneLevel,
                                     String originalText,
                                     String userPrompt,
                                     String senderInfo,
                                     UserTier tier) {
        validateTransformRequest(originalText, userPrompt, tier);

        AnalysisPhaseResult analysis = multiModelPipeline.executeAnalysis(
                persona, contexts, toneLevel, originalText, userPrompt, senderInfo, false);

        PipelineResult result = multiModelPipeline.executeFinal(
                model, analysis, persona, contexts, toneLevel, originalText, senderInfo);

        return new TransformResult(result.transformedText(), null);
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
}
