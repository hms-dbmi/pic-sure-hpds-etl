package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a failed run's report keeps. */
class JobExecutorTest {

    @TempDir
    Path reports;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private JobExecutor executor() {
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(reports.toString());
        return new JobExecutor(new ReportWriter(mapper), properties);
    }

    private static Job failing(RuntimeException e) {
        return new Job() {
            public String name() { return "failing"; }
            public JobType type() { return JobType.PERMANENT; }
            public JobExpectations expectations() { return JobExpectations.of(List.of(), List.of()); }
            public JobResult run(JobContext ctx) { throw e; }
        };
    }

    @Test
    void a_failure_with_a_partial_result_keeps_its_metrics_and_issues_in_the_report() throws Exception {
        ValidationReport partial = new ValidationReport("output");
        partial.error("API_DOWN", "phs000001 was not checked");
        RuntimeException e = new InfrastructureException("API down")
                .withPartialResult(Map.of("checked", 3), partial);

        JobResult r = executor().run(failing(e), Map.of(), "t");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        JsonNode json = mapper.readTree(reports.resolve("failing-t.json").toFile());
        assertThat(json.at("/metrics/checked").asInt()).isEqualTo(3);
        assertThat(json.at("/outputValidation/issues/0/code").asText()).isEqualTo("API_DOWN");
        assertThat(json.get("errorMessage").asText()).isEqualTo("API down");
    }

    @Test
    void a_plain_failure_still_reports_no_metrics() throws Exception {
        JobResult r = executor().run(failing(new InfrastructureException("S3 down")), Map.of(), "t");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(r.getMetrics()).isEmpty();
        assertThat(r.getOutputValidation()).isNull();
    }
}
