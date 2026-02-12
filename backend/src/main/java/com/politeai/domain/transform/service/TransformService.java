package com.politeai.domain.transform.service;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import com.politeai.domain.transform.model.TransformResult;

import java.util.List;

/**
 * Domain service interface for Korean text tone/politeness transformation.
 */
public interface TransformService {

    /**
     * Partial rewrite: rewrites only the selected portion of text.
     */
    TransformResult partialRewrite(String selectedText,
                                   String fullContext,
                                   Persona persona,
                                   List<SituationContext> contexts,
                                   ToneLevel toneLevel,
                                   String userPrompt,
                                   String senderInfo,
                                   String analysisContext);
}
