package com.politeai.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.politeai.domain.transform.model.*;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.AnalysisPhaseResult;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.FinalPromptPair;
import com.politeai.infrastructure.ai.pipeline.MultiModelPipeline.PipelineProgressCallback;
import com.politeai.infrastructure.ai.pipeline.RelationIntentService;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanMasker;
import com.politeai.infrastructure.ai.validation.OutputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiStreamingTransformService {

    private final OpenAIClient openAIClient;
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

    private final ExecutorService executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2
    );

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public SseEmitter streamTransform(Persona persona,
                                      List<SituationContext> contexts,
                                      ToneLevel toneLevel,
                                      String originalText,
                                      String userPrompt,
                                      String senderInfo,
                                      boolean identityBoosterToggle,
                                      int finalMaxTokens) {
        SseEmitter emitter = new SseEmitter(120_000L);

        executor.execute(() -> {
            try {
                long startTime = System.currentTimeMillis();

                // Create progress callback that sends SSE events in real-time
                PipelineProgressCallback progressCallback = new PipelineProgressCallback() {
                    @Override
                    public void onPhase(String phase) throws Exception {
                        emitter.send(SseEmitter.event().name("phase").data(phase));
                    }

                    @Override
                    public void onSpansExtracted(List<LockedSpan> spans, String maskedText) throws Exception {
                        List<Map<String, String>> spansList = spans.stream()
                                .map(s -> Map.of(
                                        "placeholder", s.placeholder(),
                                        "original", s.originalText(),
                                        "type", s.type().name()))
                                .toList();
                        emitter.send(SseEmitter.event()
                                .name("spans")
                                .data(objectMapper.writeValueAsString(spansList)));
                        emitter.send(SseEmitter.event()
                                .name("maskedText")
                                .data(maskedText));
                    }

                    @Override
                    public void onSegmented(List<Segment> segments) throws Exception {
                        List<Map<String, Object>> segmentsData = segments.stream()
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
                    }

                    @Override
                    public void onLabeled(List<LabeledSegment> labeledSegments) throws Exception {
                        List<Map<String, String>> labelsData = labeledSegments.stream()
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
                    }

                    @Override
                    public void onRelationIntent(boolean fired, RelationIntentService.RelationIntentResult result) throws Exception {
                        if (fired && result != null) {
                            Map<String, String> riData = new LinkedHashMap<>();
                            riData.put("relation", result.relation());
                            riData.put("intent", result.intent());
                            riData.put("stance", result.stance());
                            emitter.send(SseEmitter.event()
                                    .name("relationIntent")
                                    .data(objectMapper.writeValueAsString(riData)));
                        }
                    }

                    @Override
                    public void onRedacted(String processedText) throws Exception {
                        emitter.send(SseEmitter.event()
                                .name("processedText")
                                .data(processedText));
                    }
                };

                // 1. Run analysis phase with real-time progress callbacks
                AnalysisPhaseResult analysis = multiModelPipeline.executeAnalysis(
                        persona, contexts, toneLevel, originalText, userPrompt, senderInfo,
                        identityBoosterToggle, progressCallback);

                // 2. Build final prompt
                FinalPromptPair prompt = multiModelPipeline.buildFinalPrompt(
                        analysis, persona, contexts, toneLevel, senderInfo);

                // 3. Stream final model
                emitter.send(SseEmitter.event().name("phase").data("generating"));
                FinalStreamResult finalResult = streamFinalModel(model, prompt.systemPrompt(), prompt.userMessage(),
                        prompt.lockedSpans(), finalMaxTokens, emitter);

                // 4. Validate output
                emitter.send(SseEmitter.event().name("phase").data("validating"));
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

                    // Signal client to discard previous delta output before retry
                    emitter.send(SseEmitter.event().name("retry").data("validation_failed"));

                    String errorHint = "\n\n[시스템 검증 오류] " + String.join("; ",
                            validation.errors().stream().map(ValidationIssue::message).toList());
                    String retryUser = prompt.userMessage() + errorHint;

                    // Re-stream the retry
                    activeResult = streamFinalModel(model, prompt.systemPrompt(), retryUser,
                            prompt.lockedSpans(), finalMaxTokens, emitter);

                    // Re-validate the retry result
                    validation = outputValidator.validate(
                            activeResult.unmaskedText, originalText, prompt.lockedSpans(),
                            activeResult.rawContent, persona, prompt.redactionMap());
                }

                // 6a. Send validation issues (use safeSend to handle client disconnect gracefully)
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
                if (!safeSend(emitter, "validationIssues", objectMapper.writeValueAsString(issuesData))) return;
                if (!safeSend(emitter, "phase", "complete")) return;

                // 5. Send stats event
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
                if (!safeSend(emitter, "stats", objectMapper.writeValueAsString(statsData))) return;

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
                if (!safeSend(emitter, "usage", objectMapper.writeValueAsString(usageData))) return;

                // 9. Send done event
                if (safeSend(emitter, "done", activeResult.unmaskedText)) {
                    emitter.complete();
                }

            } catch (AiTransformException e) {
                log.error("Streaming transform failed: {}", e.getMessage());
                sendError(emitter, e.getMessage());
            } catch (Exception e) {
                log.error("Streaming transform failed", e);
                sendError(emitter, "AI 변환 서비스에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
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
                                                List<LockedSpan> lockedSpans, int maxTok, SseEmitter emitter) throws IOException {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(modelName)
                .temperature(temperature)
                .maxCompletionTokens(maxTok)
                .addSystemMessage(systemPrompt)
                .addUserMessage(userMessage)
                .streamOptions(ChatCompletionStreamOptions.builder()
                        .includeUsage(true)
                        .build())
                .build();

        StringBuilder fullContent = new StringBuilder();
        long[] finalTokens = {0, 0}; // [promptTokens, completionTokens]
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);

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
                            if (!clientDisconnected.get()) {
                                try {
                                    emitter.send(SseEmitter.event()
                                            .name("delta")
                                            .data(content));
                                } catch (IOException e) {
                                    // Client disconnected — stop sending but keep consuming stream
                                    clientDisconnected.set(true);
                                    log.warn("Client disconnected during streaming, continuing to consume OpenAI stream");
                                }
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
     * Send an SSE event, returning false if the client has disconnected.
     * Prevents cascading IOExceptions when the client drops mid-stream.
     */
    private boolean safeSend(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException e) {
            log.debug("Client disconnected, skipping remaining SSE events (failed on '{}')", eventName);
            return false;
        }
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
