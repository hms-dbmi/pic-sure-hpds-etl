package edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single finding produced during input or output validation.
 *
 * @param severity how serious the finding is
 * @param code     a stable, machine-readable code (e.g. "MISSING_COLUMN") so pipelines
 *                 and dashboards can aggregate issues without parsing free text
 * @param message  human-readable description
 * @param location optional pointer to where it occurred (e.g. "row 42", "column source_id")
 * @param context  optional structured detail (e.g. {"expected":"UUID","actual":"abc"}); stored as
 *                 an unmodifiable copy in the given order. Null values (and a null map) are
 *                 dropped rather than rejected, so an absent detail never turns a finding
 *                 into a NullPointerException
 */
public record ValidationIssue(
        Severity severity,
        String code,
        String message,
        String location,
        Map<String, Object> context) {

    public ValidationIssue {
        context = copyWithoutNulls(context);
    }

    private static Map<String, Object> copyWithoutNulls(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> nonNull = new LinkedHashMap<>();
        context.forEach((k, v) -> {
            if (k != null && v != null) {
                nonNull.put(k, v);
            }
        });
        return Collections.unmodifiableMap(nonNull);
    }

    public static ValidationIssue error(String code, String message) {
        return new ValidationIssue(Severity.ERROR, code, message, null, Map.of());
    }

    public static ValidationIssue error(String code, String message, String location) {
        return new ValidationIssue(Severity.ERROR, code, message, location, Map.of());
    }

    public static ValidationIssue error(String code, String message, String location, Map<String, Object> context) {
        return new ValidationIssue(Severity.ERROR, code, message, location, context);
    }

    public static ValidationIssue warning(String code, String message) {
        return new ValidationIssue(Severity.WARNING, code, message, null, Map.of());
    }

    public static ValidationIssue warning(String code, String message, Map<String, Object> context) {
        return new ValidationIssue(Severity.WARNING, code, message, null, context);
    }

    public static ValidationIssue info(String code, String message) {
        return new ValidationIssue(Severity.INFO, code, message, null, Map.of());
    }
}
