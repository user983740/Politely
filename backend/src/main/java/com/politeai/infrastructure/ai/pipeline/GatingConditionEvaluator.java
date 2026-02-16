package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.infrastructure.ai.pipeline.template.LabelStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evaluates gating conditions for optional pipeline components.
 */
@Slf4j
@Component
public class GatingConditionEvaluator {

    @Value("${gating.identity-booster.min-text-length:80}")
    private int identityBoosterMinTextLength;

    @Value("${gating.identity-booster.max-locked-spans:1}")
    private int identityBoosterMaxLockedSpans;

    private static final Set<Persona> HIGH_FORMALITY_PERSONAS = Set.of(
            Persona.BOSS, Persona.CLIENT, Persona.OFFICIAL
    );

    /**
     * Identity Lock Booster ON conditions (OR):
     *   1. identityBoosterToggle = true (from frontend)
     *   2. persona ∈ {BOSS, CLIENT, OFFICIAL} AND lockedSpans ≤ 1 AND textLength ≥ 80
     */
    public boolean shouldFireIdentityBooster(boolean frontendToggle, Persona persona,
                                              List<LockedSpan> lockedSpans, int textLength) {
        if (frontendToggle) {
            log.info("[Gating] IdentityBooster: ON (frontend toggle)");
            return true;
        }

        if (HIGH_FORMALITY_PERSONAS.contains(persona) && lockedSpans.size() <= identityBoosterMaxLockedSpans && textLength >= identityBoosterMinTextLength) {
            log.info("[Gating] IdentityBooster: ON (high-formality persona={}, spans={}, len={})",
                    persona, lockedSpans.size(), textLength);
            return true;
        }

        return false;
    }

    /**
     * Situation Analysis: always ON.
     * Cost ~$0.0002/call, runs in parallel with segmentation.
     * Provides structured facts + intent context to Final model, reducing hallucination.
     */
    public boolean shouldFireSituationAnalysis(Persona persona, String text) {
        log.info("[Gating] SituationAnalysis: ON (always-on)");
        return true;
    }

    // Context gating keyword patterns
    private static final Pattern REFUND_KEYWORDS = Pattern.compile(
            "환불|취소|반품|결제\\s*취소|카드\\s*취소|refund|cancel"
    );
    private static final Pattern BLAME_REJECTION_KEYWORDS = Pattern.compile(
            "책임|귀책|거절|불가|어렵습니다|못합니다|안 됩니다"
    );
    private static final Pattern REQUEST_REJECTION_KEYWORDS = Pattern.compile(
            "요청|부탁|해 주|거절|불가|어렵"
    );

    /**
     * Context Gating LLM trigger conditions (any true):
     * 1. APOLOGY context but text has blame/rejection/refund keywords
     * 2. ANNOUNCEMENT context but text has request/rejection patterns
     * 3. Refund keywords in text but TOPIC != REFUND_CANCEL and PURPOSE != REFUND_REJECTION
     * 4. All of ACCOUNTABILITY + NEGATIVE_FEEDBACK + EMOTIONAL present in labels
     * 5. VERY_POLITE + CLIENT/OFFICIAL persona (risk of mismatch)
     */
    public boolean shouldFireContextGating(Persona persona, List<SituationContext> contexts,
                                            Topic topic, Purpose purpose, ToneLevel toneLevel,
                                            LabelStats labelStats, String maskedText) {
        // 1. APOLOGY context + blame/rejection/refund keywords
        if (contexts.contains(SituationContext.APOLOGY)
                && (BLAME_REJECTION_KEYWORDS.matcher(maskedText).find()
                    || REFUND_KEYWORDS.matcher(maskedText).find())) {
            log.info("[Gating] ContextGating: ON (APOLOGY context + blame/refund keywords)");
            return true;
        }

        // 2. ANNOUNCEMENT context + request/rejection patterns
        if (contexts.contains(SituationContext.ANNOUNCEMENT)
                && REQUEST_REJECTION_KEYWORDS.matcher(maskedText).find()) {
            log.info("[Gating] ContextGating: ON (ANNOUNCEMENT context + request/rejection keywords)");
            return true;
        }

        // 3. Refund keywords but no matching topic/purpose
        if (REFUND_KEYWORDS.matcher(maskedText).find()
                && topic != Topic.REFUND_CANCEL
                && purpose != Purpose.REFUND_REJECTION) {
            log.info("[Gating] ContextGating: ON (refund keywords without matching topic/purpose)");
            return true;
        }

        // 4. Complex label mix: ACCOUNTABILITY + NEGATIVE_FEEDBACK + EMOTIONAL all present
        if (labelStats.hasAccountability() && labelStats.hasNegativeFeedback() && labelStats.hasEmotional()) {
            log.info("[Gating] ContextGating: ON (complex label mix: ACCOUNTABILITY+NEGATIVE_FEEDBACK+EMOTIONAL)");
            return true;
        }

        // 5. VERY_POLITE + CLIENT/OFFICIAL
        if (toneLevel == ToneLevel.VERY_POLITE
                && (persona == Persona.CLIENT || persona == Persona.OFFICIAL)) {
            log.info("[Gating] ContextGating: ON (VERY_POLITE + CLIENT/OFFICIAL)");
            return true;
        }

        return false;
    }
}
