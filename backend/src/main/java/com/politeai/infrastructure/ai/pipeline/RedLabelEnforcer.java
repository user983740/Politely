package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.LabeledSegment;
import com.politeai.domain.transform.model.SegmentLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Server-side RED label enforcer.
 *
 * Two-tier classification:
 * - Confirmed patterns: immediately override to RED (profanity, ability denial, mockery)
 * - Ambiguous patterns: GREEN→YELLOW upgrade only (soft profanity)
 *
 * Uses text normalization (whitespace/special char removal) to prevent bypass.
 */
@Slf4j
@Component
public class RedLabelEnforcer {

    // === Confirmed patterns: immediate RED override ===

    // Profanity/slurs (matched against normalized text) → AGGRESSION
    private static final Pattern PROFANITY = Pattern.compile(
            "ㅅㅂ|ㅄ|ㅂㅅ|ㄱㅅㄲ|시발|씨발|병신|개새끼|개세끼|지랄|ㅈㄹ|ㅂㄹ"
    );

    // Direct ability denial → PERSONAL_ATTACK
    private static final Pattern ABILITY_DENIAL = Pattern.compile(
            "그것도\\s*못|뇌가\\s*있|할\\s*줄\\s*모르|그것도\\s*몰라|무능"
    );

    // Sarcastic praise + marker (ㅋㅎ^) → AGGRESSION
    private static final Pattern MOCKERY_CERTAIN = Pattern.compile(
            "(?:잘|대단|훌륭)\\S{0,4}(?:시네요|하시네요|십니다)\\s*[ㅋㅎ^]{2,}"
    );

    // === Ambiguous patterns: GREEN→YELLOW only ===

    // Context-dependent expressions (e.g., "미친" can be exclamation or insult)
    private static final Pattern SOFT_PROFANITY = Pattern.compile(
            "미친|개같|ㅈㄴ"
    );

    /**
     * Normalize text by removing whitespace and special characters to prevent bypass
     * (e.g., "ㅅ ㅂ", "시-발" → "ㅅㅂ", "시발").
     */
    private String normalize(String text) {
        return text.replaceAll("[\\s\\-_.·!@#$%^&*()]+", "");
    }

    /**
     * Apply server-side RED enforcement rules.
     *
     * @param labeled LLM-labeled segments
     * @return enforced segments (confirmed patterns → RED, ambiguous → GREEN→YELLOW only)
     */
    public List<LabeledSegment> enforce(List<LabeledSegment> labeled) {
        return labeled.stream().map(ls -> {
            // Already RED — no need to enforce
            if (ls.label().tier() == SegmentLabel.Tier.RED) return ls;

            String text = ls.text();
            String normalized = normalize(text);

            // Confirmed: profanity or mockery → AGGRESSION
            if (PROFANITY.matcher(normalized).find() || MOCKERY_CERTAIN.matcher(text).find()) {
                log.info("[RedLabelEnforcer] Confirmed override {} → AGGRESSION (segment {})",
                        ls.label(), ls.segmentId());
                return new LabeledSegment(ls.segmentId(), SegmentLabel.AGGRESSION, ls.text(), ls.start(), ls.end());
            }

            // Confirmed: ability denial → PERSONAL_ATTACK
            if (ABILITY_DENIAL.matcher(text).find()) {
                log.info("[RedLabelEnforcer] Confirmed override {} → PERSONAL_ATTACK (segment {})",
                        ls.label(), ls.segmentId());
                return new LabeledSegment(ls.segmentId(), SegmentLabel.PERSONAL_ATTACK, ls.text(), ls.start(), ls.end());
            }

            // Ambiguous: soft profanity — GREEN→YELLOW upgrade only (never RED override)
            if (ls.label().tier() == SegmentLabel.Tier.GREEN && SOFT_PROFANITY.matcher(normalized).find()) {
                log.info("[RedLabelEnforcer] Soft upgrade {} → EMOTIONAL (segment {})",
                        ls.label(), ls.segmentId());
                return new LabeledSegment(ls.segmentId(), SegmentLabel.EMOTIONAL, ls.text(), ls.start(), ls.end());
            }

            return ls;
        }).toList();
    }
}
