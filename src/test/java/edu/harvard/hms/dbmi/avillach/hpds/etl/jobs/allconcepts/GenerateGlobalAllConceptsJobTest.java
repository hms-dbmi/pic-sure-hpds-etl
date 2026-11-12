package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.GlobalAllConceptsRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Input validation, before the DB is touched. What the job writes is tested against a real Postgres
 * in {@link GenerateGlobalAllConceptsJobIT}.
 */
class GenerateGlobalAllConceptsJobTest {

    @TempDir
    Path tempDir;

    private ManagedInputsService managedInputsService;
    private GlobalAllConceptsRepository repository;
    private GenerateGlobalAllConceptsJob job;
    private JobExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        managedInputsService = mock(ManagedInputsService.class);
        repository = mock(GlobalAllConceptsRepository.class);
        job = new GenerateGlobalAllConceptsJob(managedInputsService, repository, mock(IoResolver.class));

        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(tempDir.resolve("reports").toString());
        Files.createDirectories(tempDir.resolve("reports"));
        executor = new JobExecutor(
                new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    @Test
    void fails_validation_when_no_studies_are_ready() {
        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("STUDY1", "phs001412", false)));

        JobResult result = executor.run(job, Map.of("output", tempDir.resolve("o.csv").toString()), "test-no-ready");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().hasErrors()).isTrue();
        verify(repository, never()).open(any());
    }

    @Test
    void fails_validation_when_output_param_missing() {
        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("STUDY1", "phs001412", true)));

        JobResult result = executor.run(job, Map.of(), "test-missing-output");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
    }

    @Test
    void rejects_a_misspelt_allow_empty_flag_before_running() {
        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("STUDY1", "phs001412", true)));

        JobResult result = executor.run(job,
                Map.of("output", tempDir.resolve("o.csv").toString(), "allow-empty", "yes please"),
                "test-bad-allow-empty");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("BAD_BOOLEAN") && "--allow-empty".equals(i.location()));
    }
}
