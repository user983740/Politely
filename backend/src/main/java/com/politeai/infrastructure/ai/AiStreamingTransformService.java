package com.politeai.infrastructure.ai;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.politeai.domain.transform.model.*;
import com.politeai.domain.user.model.UserTier;
import com.politeai.infrastructure.ai.pipeline.TransformPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiStreamingTransformService {

    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final TransformPipeline transformPipeline;
    private final CacheMetricsTracker cacheMetrics;

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
                // Unified: full JSON pipeline for all tiers, then send SSE events
                PipelineResult result = transformPipeline.execute(
                        persona, contexts, toneLevel, originalText, userPrompt, senderInfo, tier);

                if (result.analysisContext() != null) {
                    emitter.send(SseEmitter.event()
                            .name("analysis")
                            .data(result.analysisContext()));
                }
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(result.transformedText()));
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

                streamOpenAI(systemPrompt, userMessage, emitter);
            } catch (Exception e) {
                log.error("Streaming partial rewrite failed", e);
                sendError(emitter, "AI 변환 서비스에 일시적인 오류가 발생했습니다.");
            }
        });

        return emitter;
    }

    /**
     * Simple streaming without pipeline (used for partial rewrite).
     */
    private void streamOpenAI(String systemPrompt, String userMessage, SseEmitter emitter) throws IOException {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model)
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
