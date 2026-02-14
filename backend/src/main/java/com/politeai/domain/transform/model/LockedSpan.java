package com.politeai.domain.transform.model;

/**
 * Value object representing a locked (immutable) span in the original text.
 * These spans must be preserved exactly as-is through the transformation.
 *
 * @param index        1-based per-type-prefix counter for placeholder generation
 * @param originalText the exact original text of this span
 * @param placeholder  the placeholder string, e.g. "{{DATE_1}}", "{{PHONE_2}}"
 * @param type         the type of locked span (DATE, TIME, etc.)
 * @param startPos     start position in the original text
 * @param endPos       end position (exclusive) in the original text
 */
public record LockedSpan(
        int index,
        String originalText,
        String placeholder,
        LockedSpanType type,
        int startPos,
        int endPos
) {}
