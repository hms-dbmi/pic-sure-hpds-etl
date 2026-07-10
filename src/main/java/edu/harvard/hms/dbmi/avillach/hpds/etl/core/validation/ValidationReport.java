package edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Accumulates {@link ValidationIssue}s for one validation pass (input or output).
 * This is a mutable builder-style collector during a job run; it serializes cleanly
 * into the JSON report artifact that Jenkins archives.
 *
 * <p>Typical use inside a job:
 * <pre>{@code
 *   report.error("MISSING_COLUMN", "source_id column not found in header");
 *   if (report.hasErrors()) { ... }  // AbstractJob does this for you
 * }</pre>
 */
public class ValidationReport {

    private final String phase; // "input" or "output"
    private final List<ValidationIssue> issues = new ArrayList<>();

    public ValidationReport(String phase) {
        this.phase = phase;
    }

    public String getPhase() {
        return phase;
    }

    public List<ValidationIssue> getIssues() {
        return Collections.unmodifiableList(issues);
    }

    public ValidationReport add(ValidationIssue issue) {
        issues.add(issue);
        return this;
    }

    public ValidationReport error(String code, String message) {
        return add(ValidationIssue.error(code, message));
    }

    public ValidationReport error(String code, String message, String location) {
        return add(ValidationIssue.error(code, message, location));
    }

    public ValidationReport warning(String code, String message) {
        return add(ValidationIssue.warning(code, message));
    }

    public ValidationReport info(String code, String message) {
        return add(ValidationIssue.info(code, message));
    }

    /** Merge another report's issues into this one (useful when composing validators). */
    public ValidationReport merge(ValidationReport other) {
        this.issues.addAll(other.issues);
        return this;
    }

    @JsonIgnore
    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
    }

    @JsonIgnore
    public boolean hasWarnings() {
        return issues.stream().anyMatch(i -> i.severity() == Severity.WARNING);
    }

    /** Counts by severity, surfaced in the report so a run can be summarized at a glance. */
    public Map<String, Long> getCounts() {
        return Map.of(
                "error", issues.stream().filter(i -> i.severity() == Severity.ERROR).count(),
                "warning", issues.stream().filter(i -> i.severity() == Severity.WARNING).count(),
                "info", issues.stream().filter(i -> i.severity() == Severity.INFO).count());
    }
}
