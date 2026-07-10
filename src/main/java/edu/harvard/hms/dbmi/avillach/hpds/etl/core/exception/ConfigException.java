package edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;

/** A required parameter, credential, or setting was missing or invalid. Not retryable. */
public class ConfigException extends EtlException {

    public ConfigException(String message) {
        super(ExitCode.CONFIG_ERROR, message);
    }

    public ConfigException(String message, Throwable cause) {
        super(ExitCode.CONFIG_ERROR, message, cause);
    }
}
