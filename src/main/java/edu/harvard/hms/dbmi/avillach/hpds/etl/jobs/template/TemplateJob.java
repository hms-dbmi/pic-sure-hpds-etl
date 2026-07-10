package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.template;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * COPY-ME TEMPLATE. This is the reference "plug and play" job: copy this class into a
 * new package, rename it, and fill in the five hooks. Registering it is automatic --
 * the {@code @Component} annotation plus the {@link AbstractJob} lifecycle means the
 * new job is immediately runnable via {@code --job=<name>} with no wiring to edit.
 *
 * <p>What this example does (so it is runnable and testable on its own): it reads a
 * delimited file and reports row counts and, optionally, the distinct-value count of a
 * chosen key column. It touches no database, so it doubles as a smoke test.
 *
 * <p>The five hooks, in the order the lifecycle calls them:
 * <ol>
 *   <li>{@code expectations()} -- declare params &amp; outputs (drives auto-validation + --help)</li>
 *   <li>{@code validateInput()} -- cheap pre-checks before doing work</li>
 *   <li>{@code execute()} -- the actual extract/transform/load</li>
 *   <li>{@code validateOutput()} -- assert the result is correct (post-condition)</li>
 *   <li>{@code report()} -- attach metrics for the JSON report Jenkins archives</li>
 * </ol>
 */
@Component
public class TemplateJob extends AbstractJob<TemplateJob.Output> {

    private final IoResolver io;
    private final DelimitedReader delimitedReader;

    public TemplateJob(IoResolver io, DelimitedReader delimitedReader) {
        this.io = io;
        this.delimitedReader = delimitedReader;
    }

    @Override
    public String name() {
        return "template";
    }

    @Override
    public JobType type() {
        // Permanent vs. temporary migration. New long-lived jobs use PERMANENT.
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(
                        ParamSpec.required("input", "Delimited file to read (local path or s3:// URI)",
                                "s3://my-bucket/incoming/data.csv"),
                        ParamSpec.optional("delimiter", "Field delimiter: 'comma' or 'tab'", "comma"),
                        ParamSpec.optional("key-column", "Column whose distinct values should be counted",
                                "source_id")),
                List.of("Row count and distinct key-column count in the run report (no data is written)"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        // Required-param presence is checked automatically from expectations(). Add only
        // the cheap, business-specific checks here. Anything requiring I/O that you would
        // do anyway belongs in execute().
        String delimiter = ctx.get("delimiter", "comma");
        if (!delimiter.equals("comma") && !delimiter.equals("tab")) {
            report.error("BAD_DELIMITER", "delimiter must be 'comma' or 'tab', got: " + delimiter, "--delimiter");
        }
    }

    @Override
    protected Output execute(JobContext ctx) {
        String input = ctx.require("input");
        char sep = "tab".equals(ctx.get("delimiter", "comma")) ? DelimitedReader.TAB : DelimitedReader.COMMA;
        String keyColumn = ctx.get("key-column").orElse(null);

        long rows = 0;
        long blankKeys = 0;
        Set<String> distinctKeys = new HashSet<>();

        // stream.close() closes the underlying InputStream via its onClose hook.
        InputStream in = io.openInput(input);
        try (Stream<Map<String, String>> stream = delimitedReader.stream(in, sep)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                rows++;
                if (keyColumn != null) {
                    String v = row.get(keyColumn);
                    if (v == null || v.isBlank()) {
                        blankKeys++;
                    } else {
                        distinctKeys.add(v);
                    }
                }
            }
        }
        log.info("Read {} row(s); {} distinct '{}' value(s)", rows, distinctKeys.size(), keyColumn);
        return new Output(rows, distinctKeys.size(), blankKeys, keyColumn);
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        // Post-conditions: assert what MUST be true about the result.
        if (output.rows() == 0) {
            report.error("EMPTY_INPUT", "Input contained no data rows");
        }
        if (output.keyColumn() != null && output.blankKeys() > 0) {
            report.warning("BLANK_KEYS", output.blankKeys() + " row(s) had a blank '"
                    + output.keyColumn() + "'");
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("rows", output.rows())
                .metric("distinctKeys", output.distinctKeys())
                .metric("blankKeys", output.blankKeys());
    }

    /** Immutable result of {@link #execute}; inspected by {@link #validateOutput}/{@link #report}. */
    public record Output(long rows, long distinctKeys, long blankKeys, String keyColumn) {
    }
}
