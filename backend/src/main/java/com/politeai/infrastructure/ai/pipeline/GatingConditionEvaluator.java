package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.LockedSpan;
import com.politeai.domain.transform.model.Persona;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates gating conditions for optional pipeline components.
 */
@Slf4j
@Component
public class GatingConditionEvaluator {

    private static final Set<Persona> HIGH_FORMALITY_PERSONAS = Set.of(
            Persona.BOSS, Persona.CLIENT, Persona.OFFICIAL
    );

    private static final Pattern TRANSITION_WORD_PATTERN = Pattern.compile(
            "(?:그리고|그런데|하지만|그래서|따라서|그러나|또한|게다가|반면|한편|더불어|아울러|결국|즉|다만|단)\\s"
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

        if (HIGH_FORMALITY_PERSONAS.contains(persona) && lockedSpans.size() <= 1 && textLength >= 80) {
            log.info("[Gating] IdentityBooster: ON (high-formality persona={}, spans={}, len={})",
                    persona, lockedSpans.size(), textLength);
            return true;
        }

        return false;
    }

    /**
     * Relation/Intent ON conditions (OR):
     *   1. persona = OTHER (ambiguous)
     *   2. textLength > 600 OR transitionWords > 8
     */
    public boolean shouldFireRelationIntent(Persona persona, String text) {
        if (persona == Persona.OTHER) {
            log.info("[Gating] RelationIntent: ON (persona=OTHER)");
            return true;
        }

        if (text.length() > 600) {
            log.info("[Gating] RelationIntent: ON (textLength={})", text.length());
            return true;
        }

        int transitionCount = countTransitionWords(text);
        if (transitionCount > 8) {
            log.info("[Gating] RelationIntent: ON (transitionWords={})", transitionCount);
            return true;
        }

        return false;
    }

    private int countTransitionWords(String text) {
        Matcher m = TRANSITION_WORD_PATTERN.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }
}
