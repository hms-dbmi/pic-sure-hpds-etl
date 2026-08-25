package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.EtlException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ValidationFailedException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Runs a single job end-to-end and guarantees three things regardless of outcome:
 * <ol>
 *   <li>logs carry the job name + run id (MDC), so a Jenkins console is traceable;</li>
 *   <li>any failure is mapped to a deterministic {@link ExitCode};</li>
 *   <li>a report artifact is always written.</li>
 * </ol>
 * This is the single choke point through which both the CLI launcher and the pipeline
 * runner invoke jobs, so error handling/logging/reporting is uniform and jobs stay
 * free of that boilerplate.
 */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final ReportWriter reportWriter;
    private final EtlProperties properties;

    public JobExecutor(ReportWriter reportWriter, EtlProperties properties) {
        this.reportWriter = reportWriter;
        this.properties = properties;
    }

    public JobResult run(Job job, Map<String, String> params, String runId) {
        Path reportsDir = Path.of(properties.getReports().getDir());
        JobContext ctx = new JobContext(job.name(), runId, reportsDir, params);

        MDC.put("job", job.name());
        MDC.put("runId", runId);
        Instant start = Instant.now();
        try {
            log.info("Starting job '{}' ({}) run '{}'", job.name(), job.type(), runId);
            JobResult result = safelyRun(job, ctx, start);
            reportWriter.write(reportsDir, result);
            if (result.isSuccess()) {
                log.info("Job '{}' completed: {} ({} ms)", job.name(), result.getExitCode(),
                        result.getDurationMillis());
            } else {
                log.error("Job '{}' failed: {} - {}", job.name(), result.getExitCode(),
                        result.getErrorMessage());
            }
            return result;
        } finally {
            MDC.remove("job");
            MDC.remove("runId");
        }
    }

    /** Translates thrown exceptions into a failed {@link JobResult} with the right exit code. */
    private JobResult safelyRun(Job job, JobContext ctx, Instant start) {
        try {
            return job.run(ctx);
        } catch (ValidationFailedException e) {
            // Carries the populated report so failures are as informative as successes.
            JobResult.Builder b = JobResult.builder(job.name(), job.type(), ctx.runId())
                    .startedAt(start).finishedAt(Instant.now())
                    .exitCode(e.exitCode()).errorMessage(e.getMessage());
            if ("input".equals(e.report().getPhase())) {
                b.inputValidation(e.report());
            } else {
                b.outputValidation(e.report());
            }
            return b.build();
        } catch (EtlException e) {
            log.error("Job '{}' raised {}: {}", job.name(), e.getClass().getSimpleName(), e.getMessage(), e);
            return failed(job, ctx, start, e.exitCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Job '{}' raised an unexpected error", job.name(), e);
            return failed(job, ctx, start, ExitCode.UNKNOWN,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private JobResult failed(Job job, JobContext ctx, Instant start, ExitCode code, String message) {
        return JobResult.builder(job.name(), job.type(), ctx.runId())
                .startedAt(start).finishedAt(Instant.now())
                .exitCode(code).errorMessage(message).build();
    }
}
