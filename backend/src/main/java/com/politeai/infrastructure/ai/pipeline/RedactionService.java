package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.LabeledSegment;
import com.politeai.domain.transform.model.SegmentLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Server-side enforcement of the 3-tier label system.
 *
 * With JSON segment format, RedactionService only:
 * - Counts RED/YELLOW segments
 * - Builds redactionMap (RED marker → original text) for OutputValidator reentry check
 *
 * No processedText assembly — Final model receives JSON segments directly.
 */
@Slf4j
@Component
public class RedactionService {

    public record RedactionResult(
            int redCount,
            int yellowCount,
            Map<String, String> redactionMap
    ) {}

    /**
     * Process labeled segments: count tiers and build redaction map for RED segments.
     *
     * @param labeledSegments the labeled segments from StructureLabelService + RedLabelEnforcer
     * @return counts and redaction map
     */
    public RedactionResult process(List<LabeledSegment> labeledSegments) {
        Map<String, String> redactionMap = new LinkedHashMap<>();
        Map<SegmentLabel, Integer> redCounters = new HashMap<>();
        int redCount = 0;
        int yellowCount = 0;

        for (LabeledSegment ls : labeledSegments) {
            SegmentLabel.Tier tier = ls.label().tier();

            switch (tier) {
                case RED -> {
                    int count = redCounters.merge(ls.label(), 1, Integer::sum);
                    String marker = "[REDACTED:" + ls.label().name() + "_" + count + "]";
                    redactionMap.put(marker, ls.text());
                    redCount++;
                }
                case YELLOW -> yellowCount++;
                case GREEN -> { /* no-op */ }
            }
        }

        log.info("[Redaction] RED={}, YELLOW={}, GREEN={}", redCount, yellowCount,
                labeledSegments.size() - redCount - yellowCount);

        return new RedactionResult(redCount, yellowCount, redactionMap);
    }
}
