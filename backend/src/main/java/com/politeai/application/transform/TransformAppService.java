package com.politeai.application.transform;

import com.politeai.domain.transform.model.*;
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

    private final MultiModelPipeline multiModelPipeline;

    @Value("${tier.paid.max-text-length}")
    private int maxTextLength;

    @Value("${openai.model}")
    private String model;

    @Value("${openai.max-tokens}")
    private int defaultMaxTokens;

    @Value("${openai.max-tokens-paid}")
    private int paidMaxTokens;

    /**
     * Full transform via multi-model pipeline (v2: preprocess → segment → label → redact → final → unmask → validate).
     */
    public TransformResult transform(Persona persona,
                                     List<SituationContext> contexts,
                                     ToneLevel toneLevel,
                                     String originalText,
                                     String userPrompt,
                                     String senderInfo) {
        validateTransformRequest(originalText);

        AnalysisPhaseResult analysis = multiModelPipeline.executeAnalysis(
                persona, contexts, toneLevel, originalText, userPrompt, senderInfo, false);

        PipelineResult result = multiModelPipeline.executeFinal(
                model, analysis, persona, contexts, toneLevel, originalText, senderInfo, paidMaxTokens);

        return new TransformResult(result.transformedText(), null);
    }

    public void validateTransformRequest(String originalText) {
        if (originalText.length() > maxTextLength) {
            throw new IllegalArgumentException(
                    String.format("최대 %d자까지 입력할 수 있습니다.", maxTextLength));
        }
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public int resolveFinalMaxTokens() {
        // Currently all users are treated as PAID
        return paidMaxTokens;
    }
}
