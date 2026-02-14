package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.LockedSpan;
import com.politeai.domain.transform.model.Persona;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${gating.identity-booster.min-text-length:80}")
    private int identityBoosterMinTextLength;

    @Value("${gating.identity-booster.max-locked-spans:1}")
    private int identityBoosterMaxLockedSpans;

    @Value("${gating.relation-intent.min-text-length:600}")
    private int relationIntentMinTextLength;

    @Value("${gating.relation-intent.min-transition-words:8}")
    private int relationIntentMinTransitionWords;

    private static final Set<Persona> HIGH_FORMALITY_PERSONAS = Set.of(
            Persona.BOSS, Persona.CLIENT, Persona.OFFICIAL
    );

    // ~109 transition word patterns — synced with MeaningSegmenter
    // Matches at text start (^) or after whitespace/punctuation
    private static final Pattern TRANSITION_WORD_PATTERN = Pattern.compile(
            "(?:^|(?<=\\s|[,;]))(?:" +
                    // 나열/추가
                    "그리고|또한|게다가|더불어|아울러|더구나|심지어|나아가|마찬가지로|" +
                    // 대조/양보
                    "그런데|근데|하지만|그러나|그래도|반면|한편|오히려|대신|그렇지만|그럼에도|반대로|역시|차라리|하긴|그나마|" +
                    // 인과/결과
                    "그래서|따라서|그러므로|결국|그러니까|그러니|결과적으로|덕분에|그니까|그래갖고|" +
                    // 조건/가정
                    "그러면|그럼|그렇다면|만약|만일|아니면|혹시|가령|설령|설사|또는|혹은|설마|" +
                    // 전환/화제
                    "아무튼|어쨌든|어쨌거나|아무래도|그나저나|어차피|하다못해|암튼|됐고|" +
                    // 부연/예시
                    "즉|다만|사실|물론|솔직히|참고로|예컨대|이를테면|말하자면|소위|이른바|요는|" +
                    // 순서/시간
                    "우선|먼저|다음으로|마지막으로|이후|이어서|동시에|앞서|첫째|둘째|셋째|끝으로|그다음|그전에|나중에|마침내|드디어|그러다가|향후|처음에|" +
                    // 강조
                    "특히|무엇보다|더욱이|과연|" +
                    // 태도/확신
                    "확실히|분명|아마|당연히|" +
                    // 요약/이유
                    "결론적으로|요약하면|종합하면|한마디로|왜냐하면|왜냐면|덧붙여|" +
                    // 비즈니스
                    "추가로|별도로|거듭|" +
                    // 구어체
                    "있잖아|있잖아요" +
            ")\\s", Pattern.MULTILINE
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
     * Relation/Intent ON conditions (OR):
     *   1. persona = OTHER (ambiguous)
     *   2. textLength > 600 OR transitionWords > 8
     */
    public boolean shouldFireRelationIntent(Persona persona, String text) {
        if (persona == Persona.OTHER) {
            log.info("[Gating] RelationIntent: ON (persona=OTHER)");
            return true;
        }

        if (text.length() > relationIntentMinTextLength) {
            log.info("[Gating] RelationIntent: ON (textLength={})", text.length());
            return true;
        }

        int transitionCount = countTransitionWords(text);
        if (transitionCount > relationIntentMinTransitionWords) {
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
