package edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;

/**
 * Thrown when an input or output {@link ValidationReport} contains errors. Carries
 * the report so JobLauncher can persist it as the run's artifact before exiting with
 * {@link ExitCode#VALIDATION_FAILED}.
 */
public class ValidationFailedException extends EtlException {

    private final transient ValidationReport report;

    public ValidationFailedException(ValidationReport report) {
        super(ExitCode.VALIDATION_FAILED,
                report.getPhase() + " validation failed with " + report.getCounts().get("error") + " error(s)");
        this.report = report;
    }

    public ValidationReport report() {
        return report;
    }
}
