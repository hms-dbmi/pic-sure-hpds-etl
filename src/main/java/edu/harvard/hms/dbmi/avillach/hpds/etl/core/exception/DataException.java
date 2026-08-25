package edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;

/** The input was reachable but malformed or internally inconsistent. Not usually retryable. */
public class DataException extends EtlException {

    public DataException(String message) {
        super(ExitCode.DATA_ERROR, message);
    }

    public DataException(String message, Throwable cause) {
        super(ExitCode.DATA_ERROR, message, cause);
    }
}
