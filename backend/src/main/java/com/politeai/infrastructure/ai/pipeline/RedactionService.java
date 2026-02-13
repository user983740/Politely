package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.LabeledSegment;
import com.politeai.domain.transform.model.SegmentLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side enforcement of the 3-tier label system.
 *
 * - RED segments → replaced with [REDACTED:LABEL_N] markers. Model never sees original.
 * - YELLOW segments → wrapped with [SOFTEN: original] markers. Model sees original but gets instructions to soften.
 * - GREEN segments → left as-is. Model only adjusts style.
 */
@Slf4j
@Component
public class RedactionService {

    public record RedactionResult(
            String processedText,
            int redCount,
            int yellowCount,
            Map<String, String> redactionMap
    ) {}

    /**
     * Process the masked text according to labeled segments.
     *
     * @param maskedText      the text with {{LOCKED_N}} placeholders
     * @param labeledSegments the labeled segments from StructureLabelService
     * @return processed text with RED=REDACTED, YELLOW=SOFTEN markers, GREEN=unchanged
     */
    public RedactionResult process(String maskedText, List<LabeledSegment> labeledSegments) {
        String processedText = maskedText;
        Map<String, String> redactionMap = new LinkedHashMap<>();
        Map<SegmentLabel, Integer> redCounters = new HashMap<>();
        int redCount = 0;
        int yellowCount = 0;

        // Sort segments by text length descending to avoid partial replacements
        List<LabeledSegment> sorted = labeledSegments.stream()
                .sorted((a, b) -> Integer.compare(b.text().length(), a.text().length()))
                .toList();

        for (LabeledSegment seg : sorted) {
            SegmentLabel.Tier tier = seg.label().tier();

            switch (tier) {
                case RED -> {
                    int count = redCounters.merge(seg.label(), 1, Integer::sum);
                    String marker = "[REDACTED:" + seg.label().name() + "_" + count + "]";
                    if (processedText.contains(seg.text())) {
                        processedText = processedText.replace(seg.text(), marker);
                        redactionMap.put(marker, seg.text());
                        redCount++;
                    }
                }
                case YELLOW -> {
                    if (processedText.contains(seg.text())) {
                        processedText = processedText.replace(seg.text(), "[SOFTEN: " + seg.text() + "]");
                        yellowCount++;
                    }
                }
                case GREEN -> {
                    // No modification — left as-is
                }
            }
        }

        log.info("[Redaction] RED={}, YELLOW={}, GREEN={}", redCount, yellowCount,
                labeledSegments.size() - redCount - yellowCount);

        return new RedactionResult(processedText, redCount, yellowCount, redactionMap);
    }
}
