package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.fencemapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.fencemapping.FenceMappingValidator;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static edu.harvard.hms.dbmi.avillach.hpds.etl.support.FenceMappingFixtures.file;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.support.FenceMappingFixtures.legacyEntry;
import static org.assertj.core.api.Assertions.assertThat;

class ValidateFenceMappingJobTest {

    @TempDir
    Path tempDir;

    private JobExecutor executor;
    private ValidateFenceMappingJob job;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        job = new ValidateFenceMappingJob(new IoResolver(null), new FenceMappingValidator(mapper));
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(tempDir.resolve("reports").toString());
        Files.createDirectories(tempDir.resolve("reports"));
        executor = new JobExecutor(new ReportWriter(mapper), properties);
    }

    private JobResult run(String content) throws Exception {
        Path f = tempDir.resolve("fence_mapping.json");
        Files.writeString(f, content);
        return executor.run(job, Map.of("input", f.toString()), "t");
    }

    @Test
    void a_valid_file_exits_zero() throws Exception {
        JobResult r = run(file(legacyEntry("phs123456", "c1", "parent", "/a"),
                legacyEntry("phs999999", "", "public", "/b")));

        assertThat(r.getExitCode().code()).isZero();
        assertThat(r.getMetrics()).containsEntry("entries", 2).containsEntry("publicEntries", 1L);
    }

    @Test
    void any_error_exits_non_zero_with_the_validation_exit_code() throws Exception {
        ObjectNode bad = legacyEntry("phs123456", "c1", "parent", "/a");
        bad.put("surprise", true);

        JobResult r = run(file(bad));

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(r.getExitCode().code()).isEqualTo(2);
        assertThat(r.getOutputValidation().getIssues()).anyMatch(i -> i.code().equals("FENCE_SCHEMA"));
    }

    @Test
    void a_cross_entry_error_alone_also_fails() throws Exception {
        JobResult r = run(file(legacyEntry("phs123456", "c1", "parent", "/a"),
                legacyEntry("phs999999", "c1", "parent", "/a")));

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
    }

    @Test
    void a_missing_file_is_a_config_error() {
        JobResult r = executor.run(job, Map.of("input", tempDir.resolve("nope.json").toString()), "t");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
    }

    @Test
    void a_missing_input_param_fails_validation() {
        assertThat(executor.run(job, Map.of(), "t").getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
    }
}
