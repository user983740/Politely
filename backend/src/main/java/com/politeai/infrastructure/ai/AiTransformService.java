package com.politeai.infrastructure.ai;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import com.politeai.domain.transform.model.TransformResult;
import com.politeai.domain.transform.service.TransformService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Stub implementation of TransformService.
 * TODO: Integrate with OpenAI API for real Korean tone/politeness transformation.
 */
@Slf4j
@Service
public class AiTransformService implements TransformService {

    @Override
    public TransformResult transform(Persona persona,
                                     List<SituationContext> contexts,
                                     ToneLevel toneLevel,
                                     String originalText,
                                     String userPrompt) {
        log.info("Transform request - persona: {}, contexts: {}, toneLevel: {}, textLength: {}",
                persona, contexts, toneLevel, originalText.length());

        // TODO: Replace stub with actual OpenAI API call
        String contextNames = contexts.stream()
                .map(Enum::name)
                .collect(Collectors.joining(", "));

        String stubResponse = String.format(
                "[Transformed] persona=%s, contexts=[%s], tone=%s | Original: %s",
                persona, contextNames, toneLevel, originalText);

        return new TransformResult(stubResponse);
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

        // TODO: Replace stub with actual OpenAI API call
        String contextNames = contexts.stream()
                .map(Enum::name)
                .collect(Collectors.joining(", "));

        String stubResponse = String.format(
                "[Rewritten] persona=%s, contexts=[%s], tone=%s | Selected: %s",
                persona, contextNames, toneLevel, selectedText);

        return new TransformResult(stubResponse);
    }
}
