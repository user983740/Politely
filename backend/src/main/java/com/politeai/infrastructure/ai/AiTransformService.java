package com.politeai.infrastructure.ai;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.ResponseFormatJsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * LLM call wrapper. Provides callOpenAIWithModel for pipeline use.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTransformService {

    private final OpenAIClient openAIClient;
    private final CacheMetricsTracker cacheMetrics;

    @Value("${openai.temperature}")
    private double temperature;

    @Value("${openai.max-tokens}")
    private int maxTokens;

    /**
     * OpenAI API call with explicit model name and token usage tracking.
     * Used by MultiModelPipeline for intermediate and final model calls.
     */
    public LlmCallResult callOpenAIWithModel(String modelName, String systemPrompt, String userMessage,
                                              double temp, int maxTok, ResponseFormatJsonObject responseFormat) {
        double actualTemp = temp < 0 ? temperature : temp;
        int actualMaxTok = maxTok < 0 ? maxTokens : maxTok;

        try {
            var builder = ChatCompletionCreateParams.builder()
                    .model(modelName)
                    .temperature(actualTemp)
                    .maxCompletionTokens(actualMaxTok)
                    .addSystemMessage(systemPrompt)
                    .addUserMessage(userMessage);

            if (responseFormat != null) {
                builder.responseFormat(responseFormat);
            }

            ChatCompletion completion = openAIClient.chat().completions().create(builder.build());

            long promptTokens = 0;
            long completionTokens = 0;

            if (completion.usage().isPresent()) {
                var usage = completion.usage().get();
                promptTokens = usage.promptTokens();
                completionTokens = usage.completionTokens();
                log.info("Token usage [{}] - prompt: {}, completion: {}, total: {}",
                        modelName, promptTokens, completionTokens, usage.totalTokens());
                long cachedTokens = usage.promptTokensDetails()
                        .map(d -> d.cachedTokens().orElse(0L))
                        .orElse(0L);
                cacheMetrics.recordUsage(promptTokens, cachedTokens);
            }

            String content = completion.choices().stream()
                    .findFirst()
                    .flatMap(choice -> choice.message().content())
                    .orElseThrow(() -> new AiTransformException("OpenAI 응답에 내용이 없습니다."));

            return new LlmCallResult(content.trim(), null, promptTokens, completionTokens);
        } catch (AiTransformException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI API call failed [{}]", modelName, e);
            throw new AiTransformException("AI 변환 서비스에 일시적인 오류가 발생했습니다.", e);
        }
    }
}
