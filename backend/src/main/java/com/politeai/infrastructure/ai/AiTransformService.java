package com.politeai.infrastructure.ai;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import com.politeai.domain.transform.model.TransformResult;
import com.politeai.domain.transform.service.TransformService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTransformService implements TransformService {

    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;

    @Value("${openai.model}")
    private String model;

    @Value("${openai.temperature}")
    private double temperature;

    @Value("${openai.max-tokens}")
    private int maxTokens;

    @Override
    public TransformResult transform(Persona persona,
                                     List<SituationContext> contexts,
                                     ToneLevel toneLevel,
                                     String originalText,
                                     String userPrompt) {
        log.info("Transform request - persona: {}, contexts: {}, toneLevel: {}, textLength: {}",
                persona, contexts, toneLevel, originalText.length());

        String userMessage = promptBuilder.buildTransformUserMessage(
                persona, contexts, toneLevel, originalText, userPrompt);

        return callOpenAI(userMessage);
    }

    @Override
    public TransformResult partialRewrite(String selectedText,
                                          String fullContext,
                                          Persona persona,
                                          List<SituationContext> contexts,
                                          ToneLevel toneLevel,
                                          String userPrompt) {
        log.info("Partial rewrite request - persona: {}, contexts: {}, toneLevel: {}, selectedTextLength: {}",
                persona, contexts, toneLevel, selectedText.length());

        String userMessage = promptBuilder.buildPartialRewriteUserMessage(
                selectedText, fullContext, persona, contexts, toneLevel, userPrompt);

        return callOpenAI(userMessage);
    }

    private TransformResult callOpenAI(String userMessage) {
        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(model)
                    .temperature(temperature)
                    .maxCompletionTokens(maxTokens)
                    .addSystemMessage(promptBuilder.getSystemPrompt())
                    .addUserMessage(userMessage)
                    .build();

            ChatCompletion completion = openAIClient.chat().completions().create(params);

            // Log token usage for cache monitoring
            completion.usage().ifPresent(usage -> {
                log.info("Token usage - prompt: {}, completion: {}, total: {}",
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
                usage.promptTokensDetails().ifPresent(details ->
                        log.info("Prompt token details - cachedTokens: {}", details.cachedTokens()));
            });

            String content = completion.choices().stream()
                    .findFirst()
                    .flatMap(choice -> choice.message().content())
                    .orElseThrow(() -> new AiTransformException("OpenAI 응답에 내용이 없습니다."));

            return new TransformResult(content.trim());
        } catch (AiTransformException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI API call failed", e);
            throw new AiTransformException("AI 변환 서비스에 일시적인 오류가 발생했습니다.", e);
        }
    }
}
