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
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.AnalysisPhaseResult;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.FinalPromptPair;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanMasker;
import com.politeai.infrastructure.ai.validation.OutputValidator;
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
    private final OutputValidator outputValidator;
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
                                      UserTier tier,
                                      boolean identityBoosterToggle) {
        SseEmitter emitter = new SseEmitter(120_000L);

        executor.execute(() -> {
            try {
                long startTime = System.currentTimeMillis();

                // 1. Run analysis phase (preprocess → segment → label → gating → redact)
                AnalysisPhaseResult analysis = multiModelPipeline.executeAnalysis(
                        persona, contexts, toneLevel, originalText, userPrompt, senderInfo, identityBoosterToggle);

                // 2. Send labels event (3-tier labeled segments)
                List<Map<String, String>> labelsData = analysis.labeledSegments().stream()
                        .map(seg -> {
                            Map<String, String> m = new LinkedHashMap<>();
                            m.put("segmentId", seg.segmentId());
                            m.put("label", seg.label().name());
                            m.put("tier", seg.label().tier().name());
                            m.put("text", seg.text());
                            return m;
                        })
                        .toList();
                emitter.send(SseEmitter.event()
                        .name("labels")
                        .data(objectMapper.writeValueAsString(labelsData)));

                // 3. Send locked spans mapping (enriched with type)
                List<Map<String, String>> spansList = analysis.lockedSpans().stream()
                        .map(s -> Map.of(
                                "placeholder", s.placeholder(),
                                "original", s.originalText(),
                                "type", s.type().name()))
                        .toList();
                emitter.send(SseEmitter.event()
                        .name("spans")
                        .data(objectMapper.writeValueAsString(spansList)));

                // 3a. Send segments event
                List<Map<String, Object>> segmentsData = analysis.segments().stream()
                        .map(seg -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", seg.id());
                            m.put("text", seg.text());
                            m.put("start", seg.start());
                            m.put("end", seg.end());
                            return m;
                        })
                        .toList();
                emitter.send(SseEmitter.event()
                        .name("segments")
                        .data(objectMapper.writeValueAsString(segmentsData)));

                // 3b. Send masked text
                emitter.send(SseEmitter.event()
                        .name("maskedText")
                        .data(analysis.maskedText()));

                // 3c. Send relation/intent (only if fired)
                if (analysis.relationIntentFired() && analysis.relationIntent() != null) {
                    Map<String, String> riData = new LinkedHashMap<>();
                    riData.put("relation", analysis.relationIntent().relation());
                    riData.put("intent", analysis.relationIntent().intent());
                    riData.put("stance", analysis.relationIntent().stance());
                    emitter.send(SseEmitter.event()
                            .name("relationIntent")
                            .data(objectMapper.writeValueAsString(riData)));
                }

                // 3d. Send processed text (after server redaction)
                emitter.send(SseEmitter.event()
                        .name("processedText")
                        .data(analysis.processedText()));

                // 4. Build final prompt
                FinalPromptPair prompt = multiModelPipeline.buildFinalPrompt(
                        analysis, persona, contexts, toneLevel, senderInfo);

                // 5. Stream final model
                FinalStreamResult finalResult = streamFinalModel(model, prompt.systemPrompt(), prompt.userMessage(),
                        prompt.lockedSpans(), emitter);

                // 6. Validate output
                ValidationResult validation = outputValidator.validate(
                        finalResult.unmaskedText, originalText, prompt.lockedSpans(),
                        finalResult.rawContent, persona, prompt.redactionMap());

                int retryCount = 0;
                FinalStreamResult activeResult = finalResult;

                // Retry once on ERROR
                if (!validation.passed()) {
                    log.warn("[Streaming] Validation errors: {}, retrying once",
                            validation.errors().stream().map(ValidationIssue::message).toList());
                    retryCount = 1;

                    String errorHint = "\n\n[시스템 검증 오류] " + String.join("; ",
                            validation.errors().stream().map(ValidationIssue::message).toList());
                    String retryUser = prompt.userMessage() + errorHint;

                    // Re-stream the retry
                    activeResult = streamFinalModel(model, prompt.systemPrompt(), retryUser,
                            prompt.lockedSpans(), emitter);
                }

                // 6a. Send validation issues
                List<Map<String, Object>> issuesData = validation.issues().stream()
                        .map(issue -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("type", issue.type().name());
                            m.put("severity", issue.severity().name());
                            m.put("message", issue.message());
                            m.put("matchedText", issue.matchedText());
                            return m;
                        })
                        .toList();
                emitter.send(SseEmitter.event()
                        .name("validationIssues")
                        .data(objectMapper.writeValueAsString(issuesData)));

                // 7. Send stats event
                long totalLatency = System.currentTimeMillis() - startTime;
                Map<String, Object> statsData = new LinkedHashMap<>();
                statsData.put("segmentCount", analysis.segments().size());
                statsData.put("greenCount", analysis.greenCount());
                statsData.put("yellowCount", analysis.yellowCount());
                statsData.put("redCount", analysis.redCount());
                statsData.put("lockedSpanCount", analysis.lockedSpans().size());
                statsData.put("retryCount", retryCount);
                statsData.put("identityBoosterFired", analysis.identityBoosterFired());
                statsData.put("relationIntentFired", analysis.relationIntentFired());
                statsData.put("latencyMs", totalLatency);
                emitter.send(SseEmitter.event()
                        .name("stats")
                        .data(objectMapper.writeValueAsString(statsData)));

                // 8. Send usage event
                long analysisPrompt = analysis.totalAnalysisPromptTokens();
                long analysisCompletion = analysis.totalAnalysisCompletionTokens();
                long finalPrompt = activeResult.promptTokens;
                long finalCompletion = activeResult.completionTokens;

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

                // 9. Send done event
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(activeResult.unmaskedText));
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

    private record FinalStreamResult(String unmaskedText, String rawContent, long promptTokens, long completionTokens) {}

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

        String rawContent = fullContent.toString().trim();

        // Unmask the full content
        LockedSpanMasker.UnmaskResult unmaskResult = spanMasker.unmask(rawContent, lockedSpans);

        return new FinalStreamResult(unmaskResult.text(), rawContent, finalTokens[0], finalTokens[1]);
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
