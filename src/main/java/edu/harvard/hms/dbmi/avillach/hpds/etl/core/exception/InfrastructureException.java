package edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;

/** An external dependency (RDS, S3, network, filesystem) failed. Usually retryable by Jenkins. */
public class InfrastructureException extends EtlException {

    public InfrastructureException(String message) {
        super(ExitCode.INFRASTRUCTURE_ERROR, message);
    }

    public InfrastructureException(String message, Throwable cause) {
        super(ExitCode.INFRASTRUCTURE_ERROR, message, cause);
    }
}
