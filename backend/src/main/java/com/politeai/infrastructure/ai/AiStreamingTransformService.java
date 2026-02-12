package com.politeai.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.politeai.domain.transform.model.*;
import com.politeai.domain.user.model.UserTier;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.FinalPromptPair;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.SharedAnalysisResult;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiStreamingTransformService {

    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final MultiModelPipeline multiModelPipeline;
    private final LockedSpanMasker spanMasker;
    private final CacheMetricsTracker cacheMetrics;
    private final ObjectMapper objectMapper;

    @Value("${openai.model}")
    private String model;

    @Value("${openai.temperature}")
    private double temperature;

    @Value("${openai.max-tokens}")
    private int maxTokens;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public SseEmitter streamTransform(Persona persona,
                                      List<SituationContext> contexts,
                                      ToneLevel toneLevel,
                                      String originalText,
                                      String userPrompt,
                                      String senderInfo,
                                      UserTier tier) {
        SseEmitter emitter = new SseEmitter(120_000L);

        executor.execute(() -> {
            try {
                // 1. Run shared analysis (3 intermediate models in parallel)
                SharedAnalysisResult analysis = multiModelPipeline.executeSharedAnalysis(
                        persona, contexts, toneLevel, originalText, userPrompt, senderInfo);

                // 2. Send intermediate analysis results
                Map<String, String> intermediateData = new LinkedHashMap<>();
                intermediateData.put("model1", analysis.model1Output());
                intermediateData.put("model2", analysis.model2Output());
                intermediateData.put("model4", analysis.model4Output());
                emitter.send(SseEmitter.event()
                        .name("intermediate")
                        .data(objectMapper.writeValueAsString(intermediateData)));

                // 3. Send locked spans mapping
                List<Map<String, String>> spansList = analysis.lockedSpans().stream()
                        .map(s -> Map.of("placeholder", s.placeholder(), "original", s.originalText()))
                        .toList();
                emitter.send(SseEmitter.event()
                        .name("spans")
                        .data(objectMapper.writeValueAsString(spansList)));

                // 4. Build final prompt
                FinalPromptPair prompt = multiModelPipeline.buildFinalPrompt(
                        analysis, persona, contexts, toneLevel, senderInfo);

                // 5. Stream final model (streams deltas, returns unmasked text + token usage)
                FinalStreamResult finalResult = streamFinalModel(model, prompt.systemPrompt(), prompt.userMessage(),
                        prompt.lockedSpans(), emitter);

                // 6. Send usage event (before done)
                long analysisPrompt = analysis.totalPromptTokens();
                long analysisCompletion = analysis.totalCompletionTokens();
                long finalPrompt = finalResult.promptTokens;
                long finalCompletion = finalResult.completionTokens;

                double analysisCost = (analysisPrompt * 0.15 + analysisCompletion * 0.60) / 1_000_000.0;
                double finalCost = (finalPrompt * 0.15 + finalCompletion * 0.60) / 1_000_000.0;
                double totalCost = analysisCost + finalCost;

                Map<String, Object> usageData = new LinkedHashMap<>();
                usageData.put("analysisPromptTokens", analysisPrompt);
                usageData.put("analysisCompletionTokens", analysisCompletion);
                usageData.put("finalPromptTokens", finalPrompt);
                usageData.put("finalCompletionTokens", finalCompletion);
                usageData.put("totalCostUsd", totalCost);
                usageData.put("monthly", Map.of(
                        "mvp", totalCost * 1500,
                        "growth", totalCost * 6000,
                        "mature", totalCost * 20000
                ));

                emitter.send(SseEmitter.event()
                        .name("usage")
                        .data(objectMapper.writeValueAsString(usageData)));

                // 7. Send done event
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(finalResult.unmaskedText));
                emitter.complete();

            } catch (Exception e) {
                log.error("Streaming transform failed", e);
                sendError(emitter, "AI 변환 서비스에 일시적인 오류가 발생했습니다.");
            }
        });

        return emitter;
    }

    public SseEmitter streamPartialRewrite(String selectedText,
                                           String fullContext,
                                           Persona persona,
                                           List<SituationContext> contexts,
                                           ToneLevel toneLevel,
                                           String userPrompt,
                                           String senderInfo,
                                           String analysisContext) {
        SseEmitter emitter = new SseEmitter(120_000L);

        executor.execute(() -> {
            try {
                String systemPrompt = promptBuilder.buildSystemPrompt(persona, contexts, toneLevel);
                String userMessage = promptBuilder.buildPartialRewriteUserMessage(
                        selectedText, fullContext, persona, contexts, toneLevel,
                        userPrompt, senderInfo, analysisContext);

                streamOpenAI(model, systemPrompt, userMessage, emitter);
            } catch (Exception e) {
                log.error("Streaming partial rewrite failed", e);
                sendError(emitter, "AI 변환 서비스에 일시적인 오류가 발생했습니다.");
            }
        });

        return emitter;
    }

    private record FinalStreamResult(String unmaskedText, long promptTokens, long completionTokens) {}

    /**
     * Stream the final model deltas and return unmasked text + token usage.
     * Does NOT send done/complete — caller handles that after sending usage.
     */
    private FinalStreamResult streamFinalModel(String modelName, String systemPrompt, String userMessage,
                                                List<LockedSpan> lockedSpans, SseEmitter emitter) throws IOException {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(modelName)
                .temperature(temperature)
                .maxCompletionTokens(maxTokens)
                .addSystemMessage(systemPrompt)
                .addUserMessage(userMessage)
                .streamOptions(ChatCompletionStreamOptions.builder()
                        .includeUsage(true)
                        .build())
                .build();

        StringBuilder fullContent = new StringBuilder();
        long[] finalTokens = {0, 0}; // [promptTokens, completionTokens]

        try (StreamResponse<ChatCompletionChunk> stream =
                     openAIClient.chat().completions().createStreaming(params)) {

            stream.stream().forEach(chunk -> {
                chunk.usage().ifPresent(usage -> {
                    log.info("Streaming token usage - prompt: {}, completion: {}, total: {}",
                            usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
                    finalTokens[0] = usage.promptTokens();
                    finalTokens[1] = usage.completionTokens();
                    long cachedTokens = usage.promptTokensDetails()
                            .map(d -> d.cachedTokens().orElse(0L))
                            .orElse(0L);
                    cacheMetrics.recordUsage(usage.promptTokens(), cachedTokens);
                });

                chunk.choices().forEach(choice -> {
                    choice.delta().content().ifPresent(content -> {
                        if (!content.isEmpty()) {
                            fullContent.append(content);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("delta")
                                        .data(content));
                            } catch (IOException e) {
                                throw new RuntimeException("SSE send failed", e);
                            }
                        }
                    });
                });
            });
        }

        // Unmask the full content
        LockedSpanMasker.UnmaskResult unmaskResult = spanMasker.unmask(
                fullContent.toString().trim(), lockedSpans);

        return new FinalStreamResult(unmaskResult.text(), finalTokens[0], finalTokens[1]);
    }

    /**
     * Simple streaming without pipeline (used for partial rewrite).
     */
    private void streamOpenAI(String modelName, String systemPrompt, String userMessage,
                               SseEmitter emitter) throws IOException {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(modelName)
                .temperature(temperature)
                .maxCompletionTokens(maxTokens)
                .addSystemMessage(systemPrompt)
                .addUserMessage(userMessage)
                .streamOptions(ChatCompletionStreamOptions.builder()
                        .includeUsage(true)
                        .build())
                .build();

        StringBuilder fullContent = new StringBuilder();

        try (StreamResponse<ChatCompletionChunk> stream =
                     openAIClient.chat().completions().createStreaming(params)) {

            stream.stream().forEach(chunk -> {
                chunk.usage().ifPresent(usage -> {
                    log.info("Streaming token usage - prompt: {}, completion: {}, total: {}",
                            usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
                    long cachedTokens = usage.promptTokensDetails()
                            .map(d -> d.cachedTokens().orElse(0L))
                            .orElse(0L);
                    cacheMetrics.recordUsage(usage.promptTokens(), cachedTokens);
                });

                chunk.choices().forEach(choice -> {
                    choice.delta().content().ifPresent(content -> {
                        if (!content.isEmpty()) {
                            fullContent.append(content);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("delta")
                                        .data(content));
                            } catch (IOException e) {
                                throw new RuntimeException("SSE send failed", e);
                            }
                        }
                    });
                });
            });
        }

        emitter.send(SseEmitter.event()
                .name("done")
                .data(fullContent.toString().trim()));
        emitter.complete();
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(message));
            emitter.complete();
        } catch (IOException e) {
            log.error("Failed to send error SSE event", e);
            emitter.completeWithError(e);
        }
    }
}
