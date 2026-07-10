package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.template;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.Severity;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.JobTestSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TemplateJob}. Pure -- no Spring context, no DB, no network --
 * because the job only reads a local file. This is the pattern for testing any job's
 * validation/transform logic quickly.
 *
 * <p>To add a new case: build a fixture with {@link JobTestSupport#tempFile}, run the
 * job, and assert on the {@link JobResult}. Cover success AND each failure mode.
 */
class TemplateJobTest {

    private final TemplateJob job = new TemplateJob(new IoResolver(null), new DelimitedReader());

    @Test
    void succeeds_and_counts_distinct_keys() throws Exception {
        String input = JobTestSupport.tempFile("data.csv",
                "source_id,source\n1,dbgap\n2,dbgap\n2,dbgap\n");

        JobResult result = job.run(JobTestSupport.context(job.name(),
                Map.of("input", input, "key-column", "source_id")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("rows", 3L)
                .containsEntry("distinctKeys", 2L)
                .containsEntry("blankKeys", 0L);
    }

    @Test
    void warns_on_blank_key_values() throws Exception {
        String input = JobTestSupport.tempFile("data.csv",
                "source_id,source\n1,dbgap\n,dbgap\n");

        JobResult result = job.run(JobTestSupport.context(job.name(),
                Map.of("input", input, "key-column", "source_id")));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("BLANK_KEYS") && i.severity() == Severity.WARNING);
    }

    @Test
    void fails_validation_when_required_param_missing() throws Exception {
        JobResult result = job.run(JobTestSupport.context(job.name(), Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("MISSING_PARAM"));
    }

    @Test
    void fails_validation_on_bad_delimiter() throws Exception {
        String input = JobTestSupport.tempFile("data.csv", "source_id,source\n1,dbgap\n");

        JobResult result = job.run(JobTestSupport.context(job.name(),
                Map.of("input", input, "delimiter", "semicolon")));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("BAD_DELIMITER"));
    }

    @Test
    void fails_output_validation_on_empty_input() throws Exception {
        String input = JobTestSupport.tempFile("empty.csv", "source_id,source\n");

        JobResult result = job.run(JobTestSupport.context(job.name(), Map.of("input", input)));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("EMPTY_INPUT"));
    }
}
