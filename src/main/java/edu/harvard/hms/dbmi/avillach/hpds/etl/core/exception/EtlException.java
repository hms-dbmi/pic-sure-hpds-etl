package edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base for all job failures. Every EtlException carries the {@link ExitCode} the
 * process should exit with, so JobLauncher can translate a thrown exception into a
 * deterministic exit status for Jenkins without an instanceof ladder.
 *
 * <p>Prefer throwing one of the typed subclasses ({@link ConfigException},
 * {@link ValidationFailedException}, {@link DataException},
 * {@link InfrastructureException}) so the failure category is explicit.
 *
 * <p>A job that fails part-way through but has already learned something worth reporting -- a
 * QA job that checked most studies before an external API gave out, say -- attaches it with
 * {@link #withPartialResult}. {@code JobExecutor} copies those metrics and that output validation
 * into the failed run's report, so the report explains the failure as fully as a success.
 */
public class EtlException extends RuntimeException {

    private final ExitCode exitCode;
    private final transient Map<String, Object> partialMetrics = new LinkedHashMap<>();
    private transient ValidationReport partialOutputValidation;

    public EtlException(ExitCode exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public EtlException(ExitCode exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public ExitCode exitCode() {
        return exitCode;
    }

    /**
     * Attaches what the job had established before it failed, for the failed run's report.
     *
     * @param metrics          added to the report's metrics (may be null)
     * @param outputValidation becomes the report's output validation (may be null)
     * @return this exception, so it can be thrown in the same statement
     */
    public EtlException withPartialResult(Map<String, Object> metrics, ValidationReport outputValidation) {
        if (metrics != null) {
            partialMetrics.putAll(metrics);
        }
        partialOutputValidation = outputValidation;
        return this;
    }

    /** Metrics attached with {@link #withPartialResult}; empty if none. */
    public Map<String, Object> partialMetrics() {
        return Collections.unmodifiableMap(partialMetrics);
    }

    /** The output validation attached with {@link #withPartialResult}, or null. */
    public ValidationReport partialOutputValidation() {
        return partialOutputValidation;
    }
}
