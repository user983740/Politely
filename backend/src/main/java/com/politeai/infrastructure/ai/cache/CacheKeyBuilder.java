package com.politeai.infrastructure.ai.cache;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds deterministic SHA-256 cache keys from normalized transform inputs.
 * Actual cache storage (Caffeine etc.) is out of scope — this only generates the key.
 */
@Component
public class CacheKeyBuilder {

    /**
     * Build a SHA-256 cache key from the transform parameters.
     *
     * @param persona        the target persona
     * @param contexts       situation contexts (will be sorted for determinism)
     * @param toneLevel      desired tone level
     * @param normalizedText the normalized input text
     * @param userPrompt     optional user prompt (nullable)
     * @param senderInfo     optional sender info (nullable)
     * @return hex-encoded SHA-256 hash
     */
    public String buildKey(Persona persona,
                           List<SituationContext> contexts,
                           ToneLevel toneLevel,
                           String normalizedText,
                           String userPrompt,
                           String senderInfo) {
        String sortedContexts = contexts.stream()
                .sorted()
                .map(Enum::name)
                .collect(Collectors.joining(","));

        String raw = persona.name() + "|"
                + sortedContexts + "|"
                + toneLevel.name() + "|"
                + normalizedText + "|"
                + (userPrompt != null ? userPrompt : "") + "|"
                + (senderInfo != null ? senderInfo : "");

        return sha256(raw);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
