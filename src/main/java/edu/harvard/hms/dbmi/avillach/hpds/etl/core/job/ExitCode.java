package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

/**
 * Process exit codes. These form the contract between a job and the orchestrator
 * (Jenkins). A Jenkinsfile stage gates the next stage on the exit status, and can
 * branch on the specific failure category.
 *
 * <p>Keep these stable -- changing a numeric value is a breaking change for any
 * pipeline that switches on it.
 */
public enum ExitCode {

    /** Job completed and all output expectations were met. */
    SUCCESS(0),

    /** Job ran but produced warnings; treated as success by default. */
    SUCCESS_WITH_WARNINGS(0),

    /** Catch-all for an unexpected/unclassified failure. */
    UNKNOWN(1),

    /** Input or output validation failed. The data was not (fully) processed. */
    VALIDATION_FAILED(2),

    /** The input data was reachable but malformed/inconsistent (bad rows, bad types). */
    DATA_ERROR(3),

    /** An external dependency failed: RDS, S3, network, filesystem. Usually retryable. */
    INFRASTRUCTURE_ERROR(4),

    /** The job was misconfigured: a required parameter/credential was missing or invalid. */
    CONFIG_ERROR(5);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean isSuccess() {
        return this == SUCCESS || this == SUCCESS_WITH_WARNINGS;
    }
}
