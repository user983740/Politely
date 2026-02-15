package com.politeai.domain.transform.model;

public record LabeledSegment(String segmentId, SegmentLabel label, String text, int start, int end) {}
