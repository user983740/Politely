package com.politeai.application.transform;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import com.politeai.domain.transform.model.TransformResult;
import com.politeai.domain.transform.service.TransformService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Application service that orchestrates the transformation use case.
 * Delegates actual transformation logic to the domain TransformService implementation.
 */
@Service
@RequiredArgsConstructor
public class TransformAppService {

    private final TransformService transformService;

    public TransformResult transform(Persona persona,
                                     List<SituationContext> contexts,
                                     ToneLevel toneLevel,
                                     String originalText,
                                     String userPrompt) {
        return transformService.transform(persona, contexts, toneLevel, originalText, userPrompt);
    }

    public TransformResult partialRewrite(String selectedText,
                                          String fullContext,
                                          Persona persona,
                                          List<SituationContext> contexts,
                                          ToneLevel toneLevel,
                                          String userPrompt) {
        return transformService.partialRewrite(selectedText, fullContext, persona, contexts, toneLevel, userPrompt);
    }
}
