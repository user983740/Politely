package com.politeai.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.ResponseFormatJsonObject;
import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import com.politeai.domain.transform.model.TransformResult;
import com.politeai.domain.transform.service.TransformService;
import com.politeai.domain.user.model.UserTier;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pure LLM call wrapper. Pipeline logic lives in TransformPipeline.
 * This service exposes raw LLM call methods and implements TransformService
 * for backward compatibility (direct calls without pipeline).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTransformService implements TransformService {

    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final CacheMetricsTracker cacheMetrics;

    @Value("${openai.model}")
    private String model;

    @Value("${openai.temperature}")
    private double temperature;

    @Value("${openai.max-tokens}")
    private int maxTokens;

    @PostConstruct
    void logSystemPromptInfo() {
        String systemPrompt = promptBuilder.getSystemPrompt();
        int estimatedTokens = (int) (systemPrompt.length() / 1.5);
        log.info("Core system prompt length: {} chars, estimated ~{} tokens", systemPrompt.length(), estimatedTokens);
    }

    /**
     * Direct transform without pipeline (used for backward compatibility).
     * For pipeline-based transform, use TransformPipeline.execute() instead.
     */
    @Override
    public TransformResult transform(Persona persona,
                                     List<SituationContext> contexts,
                                     ToneLevel toneLevel,
                                     String originalText,
                                     String userPrompt,
                                     String senderInfo,
                                     UserTier tier) {
        log.info("Direct transform (no pipeline) - persona: {}, contexts: {}, toneLevel: {}, textLength: {}, tier: {}",
                persona, contexts, toneLevel, originalText.length(), tier);

        String systemPrompt = promptBuilder.buildSystemPrompt(persona, contexts, toneLevel);
        String userMessage = promptBuilder.buildTransformUserMessage(
                persona, contexts, toneLevel, originalText, userPrompt, senderInfo);
        return callOpenAI(systemPrompt, userMessage, -1, -1, null);
    }

    @Override
    public TransformResult partialRewrite(String selectedText,
                                          String fullContext,
                                          Persona persona,
                                          List<SituationContext> contexts,
                                          ToneLevel toneLevel,
                                          String userPrompt,
                                          String senderInfo,
                                          String analysisContext) {
        log.info("Partial rewrite - persona: {}, contexts: {}, toneLevel: {}, selectedTextLength: {}, hasAnalysis: {}",
                persona, contexts, toneLevel, selectedText.length(), analysisContext != null);

        String systemPrompt = promptBuilder.buildSystemPrompt(persona, contexts, toneLevel);
        String userMessage = promptBuilder.buildPartialRewriteUserMessage(
                selectedText, fullContext, persona, contexts, toneLevel, userPrompt, senderInfo, analysisContext);

        return callOpenAI(systemPrompt, userMessage, -1, -1, null);
    }

    /**
     * Raw OpenAI API call. Used by TransformPipeline and internally.
     *
     * @param systemPrompt   system prompt
     * @param userMessage    user message
     * @param temp           temperature (-1 to use default)
     * @param maxTok         max tokens (-1 to use default)
     * @param responseFormat JSON format (null for text)
     */
    public TransformResult callOpenAI(String systemPrompt, String userMessage,
                                      double temp, int maxTok, ResponseFormatJsonObject responseFormat) {
        double actualTemp = temp < 0 ? temperature : temp;
        int actualMaxTok = maxTok < 0 ? maxTokens : maxTok;

        try {
            var builder = ChatCompletionCreateParams.builder()
                    .model(model)
                    .temperature(actualTemp)
                    .maxCompletionTokens(actualMaxTok)
                    .addSystemMessage(systemPrompt)
                    .addUserMessage(userMessage);

            if (responseFormat != null) {
                builder.responseFormat(responseFormat);
            }

            ChatCompletion completion = openAIClient.chat().completions().create(builder.build());

            completion.usage().ifPresent(usage -> {
                log.info("Token usage - prompt: {}, completion: {}, total: {}",
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
                long cachedTokens = usage.promptTokensDetails()
                        .map(d -> d.cachedTokens().orElse(0L))
                        .orElse(0L);
                cacheMetrics.recordUsage(usage.promptTokens(), cachedTokens);
            });

            String content = completion.choices().stream()
                    .findFirst()
                    .flatMap(choice -> choice.message().content())
                    .orElseThrow(() -> new AiTransformException("OpenAI 응답에 내용이 없습니다."));

            return new TransformResult(content.trim(), null);
        } catch (AiTransformException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI API call failed", e);
            throw new AiTransformException("AI 변환 서비스에 일시적인 오류가 발생했습니다.", e);
        }
    }

    /**
     * Pro JSON 1-pass call: calls OpenAI with JSON response format and parses the result.
     */
    public TransformResult callOpenAIForPro(String systemPrompt, String userMessage) {
        TransformResult rawResult = callOpenAI(
                systemPrompt, userMessage, -1, -1,
                ResponseFormatJsonObject.builder().build());

        return parseProJsonResponse(rawResult.getTransformedText());
    }

    private TransformResult parseProJsonResponse(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            String analysis = node.has("analysis") ? node.get("analysis").asText() : null;
            String result = node.has("result") ? node.get("result").asText() : content;
            return new TransformResult(result.trim(), analysis);
        } catch (Exception e) {
            log.warn("Failed to parse Pro JSON response, using raw content as result", e);
            return new TransformResult(content.trim(), null);
        }
    }

    /**
     * OpenAI API call that returns token usage alongside content.
     * Uses the default model from config.
     */
    public LlmCallResult callOpenAIWithUsage(String systemPrompt, String userMessage,
                                              double temp, int maxTok, ResponseFormatJsonObject responseFormat) {
        return callOpenAIWithModel(model, systemPrompt, userMessage, temp, maxTok, responseFormat);
    }

    /**
     * OpenAI API call with explicit model name and token usage tracking.
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
