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
     * Full transformation: converts the entire original text into a polite version
     * based on persona, contexts, and tone level.
     *
     * @param persona        the target persona (e.g., BOSS, CLIENT)
     * @param contexts       one or more situation contexts
     * @param toneLevel      desired politeness level
     * @param originalText   the original Korean text (max 1000 chars)
     * @param userPrompt     optional additional instructions (max 80 chars, nullable)
     * @return the transformation result
     */
    TransformResult transform(Persona persona,
                              List<SituationContext> contexts,
                              ToneLevel toneLevel,
                              String originalText,
                              String userPrompt);

    /**
     * Partial rewrite: rewrites only the selected portion of text.
     *
     * @param selectedText   the text fragment to rewrite (max 1000 chars)
     * @param fullContext    optional surrounding context (nullable)
     * @param persona        the target persona
     * @param contexts       one or more situation contexts
     * @param toneLevel      desired politeness level
     * @param userPrompt     optional additional instructions (max 80 chars, nullable)
     * @return the transformation result containing only the rewritten fragment
     */
    TransformResult partialRewrite(String selectedText,
                                   String fullContext,
                                   Persona persona,
                                   List<SituationContext> contexts,
                                   ToneLevel toneLevel,
                                   String userPrompt);
}
