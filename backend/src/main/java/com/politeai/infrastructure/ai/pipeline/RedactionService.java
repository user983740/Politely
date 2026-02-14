package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.LabeledSegment;
import com.politeai.domain.transform.model.Segment;
import com.politeai.domain.transform.model.SegmentLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Server-side enforcement of the 3-tier label system.
 *
 * - RED segments → replaced with [REDACTED:LABEL_N] markers. Model never sees original.
 * - YELLOW segments → wrapped with [SOFTEN: original] markers. Model sees original but gets instructions to soften.
 * - GREEN segments → left as-is. Model only adjusts style.
 *
 * Uses position-based replacement (right-to-left) to avoid duplicate-text mis-replacement.
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
     * Process the masked text according to labeled segments using position-based replacement.
     *
     * @param maskedText      the text with {{LOCKED_N}} placeholders
     * @param labeledSegments the labeled segments from StructureLabelService
     * @param segments        the original segments from MeaningSegmenter (with start/end positions)
     * @return processed text with RED=REDACTED, YELLOW=SOFTEN markers, GREEN=unchanged
     */
    public RedactionResult process(String maskedText, List<LabeledSegment> labeledSegments, List<Segment> segments) {
        Map<String, String> redactionMap = new LinkedHashMap<>();
        Map<SegmentLabel, Integer> redCounters = new HashMap<>();
        int redCount = 0;
        int yellowCount = 0;
        int skippedCount = 0;

        // Build segment position lookup by ID
        Map<String, Segment> segmentMap = segments.stream()
                .collect(Collectors.toMap(Segment::id, s -> s, (a, b) -> a));

        // Sort labeled segments by position descending (right-to-left) for safe StringBuilder replacement
        List<LabeledSegment> sorted = labeledSegments.stream()
                .filter(ls -> segmentMap.containsKey(ls.segmentId()))
                .sorted((a, b) -> {
                    Segment sa = segmentMap.get(a.segmentId());
                    Segment sb = segmentMap.get(b.segmentId());
                    return Integer.compare(sb.start(), sa.start());
                })
                .toList();

        StringBuilder sb = new StringBuilder(maskedText);

        for (LabeledSegment ls : sorted) {
            Segment seg = segmentMap.get(ls.segmentId());
            int start = seg.start();
            int end = seg.end();
            SegmentLabel.Tier tier = ls.label().tier();

            // Safety: verify position is within bounds
            if (start < 0 || end > sb.length() || start >= end) {
                log.error("[Redaction] Out-of-bounds segment {} (tier={}): start={}, end={}, textLen={} — skipping (not counted in stats)",
                        ls.segmentId(), tier, start, end, sb.length());
                skippedCount++;
                continue;
            }

            switch (tier) {
                case RED -> {
                    int count = redCounters.merge(ls.label(), 1, Integer::sum);
                    String marker = "[REDACTED:" + ls.label().name() + "_" + count + "]";
                    sb.replace(start, end, marker);
                    redactionMap.put(marker, seg.text());
                    redCount++;
                }
                case YELLOW -> {
                    sb.replace(start, end, "[SOFTEN:" + ls.label().name() + ": " + seg.text() + "]");
                    yellowCount++;
                }
                case GREEN -> {
                    // No modification — left as-is
                }
            }
        }

        if (skippedCount > 0) {
            log.warn("[Redaction] {} segments had out-of-bounds positions and were skipped", skippedCount);
        }

        log.info("[Redaction] RED={}, YELLOW={}, GREEN={}", redCount, yellowCount,
                labeledSegments.size() - redCount - yellowCount);

        return new RedactionResult(sb.toString(), redCount, yellowCount, redactionMap);
    }
}
