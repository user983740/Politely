package com.politeai.infrastructure.ai.preprocessing;

import com.politeai.domain.transform.model.LockedSpan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks locked spans with placeholders before sending to LLM,
 * and unmasks (restores) them in the LLM output.
 */
@Slf4j
@Component
public class LockedSpanMasker {

    // Flexible pattern for matching placeholders in LLM output
    // Handles variations: {{LOCKED_0}}, {{ LOCKED_0 }}, {{LOCKED-0}}, etc.
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "\\{\\{\\s*LOCKED[_\\-]?(\\d+)\\s*\\}\\}"
    );

    /**
     * Replace locked spans in the original text with their placeholders.
     * Spans must be sorted by startPos ascending.
     *
     * @param text  the original text
     * @param spans the extracted locked spans (sorted by position)
     * @return text with locked spans replaced by placeholders
     */
    public String mask(String text, List<LockedSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;

        for (LockedSpan span : spans) {
            // Append text before this span
            sb.append(text, lastEnd, span.startPos());
            // Append placeholder
            sb.append(span.placeholder());
            lastEnd = span.endPos();
        }

        // Append remaining text
        sb.append(text, lastEnd, text.length());

        return sb.toString();
    }

    /**
     * Restore placeholders in the LLM output with their original text.
     * Uses flexible matching to handle minor LLM variations in placeholder format.
     *
     * @param output the LLM output text containing placeholders
     * @param spans  the original locked spans
     * @return text with placeholders restored to original values, and count of missing spans
     */
    public UnmaskResult unmask(String output, List<LockedSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return new UnmaskResult(output, List.of());
        }

        String result = output;
        boolean[] restored = new boolean[spans.size()];

        // Replace all placeholders found in the output
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 0 && index < spans.size()) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(spans.get(index).originalText()));
                restored[index] = true;
            }
        }
        matcher.appendTail(sb);
        result = sb.toString();

        // Check for missing spans
        List<LockedSpan> missingSpans = new java.util.ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            if (!restored[i]) {
                LockedSpan span = spans.get(i);
                log.warn("LockedSpan missing in output: index={}, type={}, text='{}'",
                        span.index(), span.type(), span.originalText());

                // Check if the original text appears verbatim in the output (LLM may have expanded it)
                if (!result.contains(span.originalText())) {
                    missingSpans.add(span);
                } else {
                    log.info("LockedSpan {} found as verbatim text in output (LLM preserved without placeholder)", i);
                }
            }
        }

        return new UnmaskResult(result, missingSpans);
    }

    /**
     * Result of the unmask operation.
     *
     * @param text         the unmasked text
     * @param missingSpans spans whose placeholders were not found and original text is also absent
     */
    public record UnmaskResult(String text, List<LockedSpan> missingSpans) {}
}
