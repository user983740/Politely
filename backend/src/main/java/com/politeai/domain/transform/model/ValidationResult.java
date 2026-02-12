package com.politeai.domain.transform.model;

import java.util.List;

/**
 * Result of output validation.
 *
 * @param passed true if no ERROR-level issues were found
 * @param issues list of all validation issues (both ERROR and WARNING)
 */
public record ValidationResult(
        boolean passed,
        List<ValidationIssue> issues
) {
    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
    }

    public List<ValidationIssue> errors() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.ERROR).toList();
    }

    public List<ValidationIssue> warnings() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.WARNING).toList();
    }
}
