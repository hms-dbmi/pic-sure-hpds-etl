package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Template-method base class for jobs. It fixes the lifecycle so every job behaves
 * consistently and a new job only implements the parts that differ:
 *
 * <pre>
 *   1. validate required params      (automatic, from expectations())
 *   2. validateInput(...)            (per job: structural/business input checks)
 *   3. execute(...)                  (per job: the extract/transform/load)
 *   4. validateOutput(...)           (per job: assert the output is correct)
 *   5. report(...)                   (per job, optional: attach metrics)
 * </pre>
 *
 * If step 1 or 2 finds ERROR-level issues, {@code execute} is never called and the run
 * ends with {@link ExitCode#VALIDATION_FAILED}. ERROR-level issues from step 4 also
 * fail the run, but the output was already produced -- treat output validation as a
 * post-condition assertion, not a gate.
 *
 * @param <O> the type produced by {@link #execute} and inspected by {@link #validateOutput}
 */
public abstract class AbstractJob<O> implements Job {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /* ---- Implement these ---- */

    /** Structural/business checks on the input. Add ERROR issues to block execution. */
    protected abstract void validateInput(JobContext ctx, ValidationReport report) throws Exception;

    /** Do the work. Throw a typed EtlException for config/data/infrastructure failures. */
    protected abstract O execute(JobContext ctx) throws Exception;

    /** Post-condition assertions on what was produced. Add ERROR issues to fail the run. */
    protected abstract void validateOutput(O output, JobContext ctx, ValidationReport report) throws Exception;

    /** Optional: attach metrics (row counts, timings) to the report. Default: nothing. */
    protected void report(O output, JobResult.Builder builder) {
        // no-op by default
    }

    /* ---- Fixed lifecycle ---- */

    @Override
    public final JobResult run(JobContext ctx) throws Exception {
        Instant start = Instant.now();
        JobResult.Builder result = JobResult.builder(name(), type(), ctx.runId()).startedAt(start);

        ValidationReport input = new ValidationReport("input");
        validateRequiredParams(ctx, input);
        validateInput(ctx, input);
        result.inputValidation(input);

        if (input.hasErrors()) {
            log.error("Input validation failed for job '{}' with {} error(s)", name(),
                    input.getCounts().get("error"));
            return result.exitCode(ExitCode.VALIDATION_FAILED)
                    .finishedAt(Instant.now())
                    .errorMessage("Input validation failed")
                    .build();
        }

        log.info("Input validated for job '{}'; executing", name());
        O output = execute(ctx);

        ValidationReport outputReport = new ValidationReport("output");
        validateOutput(output, ctx, outputReport);
        result.outputValidation(outputReport);
        report(output, result);

        ExitCode exitCode;
        String error = null;
        if (outputReport.hasErrors()) {
            exitCode = ExitCode.VALIDATION_FAILED;
            error = "Output validation failed";
            log.error("Output validation failed for job '{}' with {} error(s)", name(),
                    outputReport.getCounts().get("error"));
        } else if (input.hasWarnings() || outputReport.hasWarnings()) {
            exitCode = ExitCode.SUCCESS_WITH_WARNINGS;
        } else {
            exitCode = ExitCode.SUCCESS;
        }

        return result.exitCode(exitCode).finishedAt(Instant.now()).errorMessage(error).build();
    }

    /** Auto-checks presence of every {@code required} param declared in {@link #expectations()}. */
    private void validateRequiredParams(JobContext ctx, ValidationReport report) {
        for (ParamSpec spec : expectations().inputs()) {
            if (spec.required() && ctx.get(spec.name()).isEmpty()) {
                report.error("MISSING_PARAM",
                        "Required parameter --" + spec.name() + " is missing. " + spec.description(),
                        "--" + spec.name());
            }
        }
    }
}
