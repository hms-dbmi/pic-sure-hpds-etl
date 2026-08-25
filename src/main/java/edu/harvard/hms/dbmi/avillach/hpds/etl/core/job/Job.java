package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

/**
 * A runnable unit of work selected at runtime via {@code --job=<name>}.
 *
 * <p>Most jobs should extend {@link AbstractJob}, which implements this interface as
 * a template method (validate input &rarr; execute &rarr; validate output &rarr; report)
 * so a new job only fills in the blanks. Implement this interface directly only when
 * you need a fundamentally different lifecycle.
 */
public interface Job {

    /** Unique job id used on the command line ({@code --job=<name>}). Kebab-case by convention. */
    String name();

    /** Whether this is a permanent ingestion job or a temporary migration. */
    JobType type();

    /** The declared input/output contract, used for auto-validation and documentation. */
    JobExpectations expectations();

    /**
     * Run the job for one invocation.
     *
     * @return the outcome; a non-success {@link ExitCode} indicates the job failed
     *         in a recoverable, reportable way (e.g. validation)
     * @throws Exception for hard failures (config/data/infrastructure); JobLauncher
     *         maps these to an {@link ExitCode} for the process
     */
    JobResult run(JobContext ctx) throws Exception;
}
