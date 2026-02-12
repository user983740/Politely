package com.politeai.domain.transform.model;

/**
 * Individual validation issue found in the LLM output.
 *
 * @param type        the type of validation issue
 * @param severity    ERROR triggers retry (Pro only), WARNING is logged only
 * @param message     human-readable description of the issue
 * @param matchedText the specific text that triggered this issue (nullable)
 */
public record ValidationIssue(
        ValidationIssueType type,
        Severity severity,
        String message,
        String matchedText
) {
    public enum Severity {
        ERROR,
        WARNING
    }
}
