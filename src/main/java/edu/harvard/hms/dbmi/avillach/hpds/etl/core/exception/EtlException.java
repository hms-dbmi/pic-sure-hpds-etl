package edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;

/**
 * Base for all job failures. Every EtlException carries the {@link ExitCode} the
 * process should exit with, so JobLauncher can translate a thrown exception into a
 * deterministic exit status for Jenkins without an instanceof ladder.
 *
 * <p>Prefer throwing one of the typed subclasses ({@link ConfigException},
 * {@link ValidationFailedException}, {@link DataException},
 * {@link InfrastructureException}) so the failure category is explicit.
 */
public class EtlException extends RuntimeException {

    private final ExitCode exitCode;

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
}
