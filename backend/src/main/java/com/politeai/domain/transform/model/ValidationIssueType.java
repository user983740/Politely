package com.politeai.domain.transform.model;

public enum ValidationIssueType {
    EMOJI,
    FORBIDDEN_PHRASE,
    HALLUCINATED_FACT,
    ENDING_REPETITION,
    LENGTH_OVEREXPANSION,
    PERSPECTIVE_ERROR,
    LOCKED_SPAN_MISSING
}
