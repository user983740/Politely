package com.politeai.infrastructure.ai.pipeline.template;

import com.politeai.domain.transform.model.LabeledSegment;
import com.politeai.domain.transform.model.SegmentLabel;

import java.util.List;

public record LabelStats(
    int greenCount,
    int yellowCount,
    int redCount,
    boolean hasAccountability,
    boolean hasNegativeFeedback,
    boolean hasEmotional,
    boolean hasSelfJustification,
    boolean hasAggression
) {
    public static LabelStats from(List<LabeledSegment> segments) {
        int green = 0, yellow = 0, red = 0;
        boolean accountability = false, negativeFeedback = false, emotional = false,
                selfJustification = false, aggression = false;

        for (LabeledSegment seg : segments) {
            switch (seg.label().tier()) {
                case GREEN -> green++;
                case YELLOW -> yellow++;
                case RED -> red++;
            }
            switch (seg.label()) {
                case ACCOUNTABILITY -> accountability = true;
                case NEGATIVE_FEEDBACK -> negativeFeedback = true;
                case EMOTIONAL -> emotional = true;
                case SELF_JUSTIFICATION -> selfJustification = true;
                case AGGRESSION -> aggression = true;
                default -> {}
            }
        }

        return new LabelStats(green, yellow, red,
                accountability, negativeFeedback, emotional, selfJustification, aggression);
    }
}
