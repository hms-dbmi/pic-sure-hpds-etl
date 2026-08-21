package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateGlobalAllConceptsJobTest {

    @TempDir
    Path tempDir;

    private ManagedInputsService managedInputsService;
    private ConsentRepository consentRepository;
    private ParticipantRepository participantRepository;
    private SampleRepository sampleRepository;
    private IoResolver ioResolver;

    private GenerateGlobalAllConceptsJob job;
    private JobExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        managedInputsService = mock(ManagedInputsService.class);
        consentRepository = mock(ConsentRepository.class);
        participantRepository = mock(ParticipantRepository.class);
        sampleRepository = mock(SampleRepository.class);
        ioResolver = mock(IoResolver.class);

        job = new GenerateGlobalAllConceptsJob(
                managedInputsService, consentRepository, participantRepository, sampleRepository, ioResolver);

        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(tempDir.resolve("reports").toString());
        Files.createDirectories(tempDir.resolve("reports"));
        executor = new JobExecutor(
                new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    @Test
    void succeeds_with_all_concept_types() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        String studyId = "phs001412";

        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("STUDY1", studyId, true)));

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "1", "GRU"),
                new Consent(uuid2, studyId, "2", "HMB")));

        when(participantRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Participant(uuid1, "SUBJ1", "DBGap"),
                new Participant(uuid2, "SUBJ2", "DBGap")));

        when(sampleRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Sample(uuid1, "SAMP1", "DBGap")));

        String outputPath = tempDir.resolve("output/").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-success");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ioResolver).writeOutput(eq(outputPath + "global_AllConcepts.csv"), contentCaptor.capture());

        String csv = new String(contentCaptor.getValue(), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");

        // 2 consents + 2 participants + 1 sample + 2 study-level consents + 2 individual consents = 9
        assertThat(lines).hasSize(9);

        assertThat(csv).contains("\"µ_consentsµ\"");
        assertThat(csv).contains("\"" + studyId + ".c1\"");
        assertThat(csv).contains("\"" + studyId + ".c2\"");
        assertThat(csv).contains("\"µ_source_subject_idµ\"");
        assertThat(csv).contains("\"SUBJ1\"");
        assertThat(csv).contains("\"SUBJ2\"");
        assertThat(csv).contains("\"µ_source_sample_idµ\"");
        assertThat(csv).contains("\"SAMP1\"");
        assertThat(csv).contains("\"µ_studies_consentsµ" + studyId + "µ\"");
        assertThat(csv).contains("\"µ_studies_consentsµ" + studyId + "µGRUµ\"");
        assertThat(csv).contains("\"µ_studies_consentsµ" + studyId + "µHMBµ\"");
        assertThat(csv).contains("\"TRUE\"");

        // All lines have exactly 5 quoted fields
        for (String line : lines) {
            long quoteCount = line.chars().filter(c -> c == '"').count();
            assertThat(quoteCount).isGreaterThanOrEqualTo(10); // 5 fields * 2 quotes each
        }
    }

    @Test
    void skips_individual_consent_path_when_abbreviation_is_empty() {
        UUID uuid1 = UUID.randomUUID();
        String studyId = "phs001412";

        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("STUDY1", studyId, true)));

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "1", "")));

        when(participantRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Participant(uuid1, "SUBJ1", "DBGap")));

        when(sampleRepository.findByStudyId(studyId)).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-empty-abbrev");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ioResolver).writeOutput(eq(outputPath), contentCaptor.capture());

        String csv = new String(contentCaptor.getValue(), StandardCharsets.UTF_8);
        // 1 consent + 1 participant + 0 samples + 1 study-level + 0 individual (skipped) = 3
        assertThat(csv.split("\n")).hasSize(3);
        assertThat(csv).contains("\"µ_studies_consentsµ" + studyId + "µ\"");
        assertThat(csv).doesNotContain("\"µ_studies_consentsµ" + studyId + "µµ\"");
    }

    @Test
    void fails_validation_when_no_studies_are_ready() {
        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("STUDY1", "phs001412", false)));

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-no-ready");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().hasErrors()).isTrue();
    }

    @Test
    void fails_when_no_rows_generated_across_all_studies() {
        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("STUDY1", "phs001412", true)));

        when(consentRepository.findByStudyId("phs001412")).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-empty-db");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(result.getErrorMessage()).contains("No concept rows were generated");
    }

    @Test
    void fails_with_infrastructure_error_when_database_unreachable() {
        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("STUDY1", "phs001412", true)));

        when(consentRepository.findByStudyId("phs001412"))
                .thenThrow(new InfrastructureException("connection refused"));

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-db-down");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
    }

    @Test
    void fails_validation_when_output_param_missing() {
        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("STUDY1", "phs001412", true)));

        JobResult result = executor.run(job, Map.of(), "test-missing-output");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
    }

    @Test
    void processes_multiple_ready_studies() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();

        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("S1", "phs000001", true),
                new ManagedInputRow("S2", "phs000002", true),
                new ManagedInputRow("S3", "phs000003", false)));

        when(consentRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Consent(uuid1, "phs000001", "1", "GRU")));
        when(consentRepository.findByStudyId("phs000002")).thenReturn(List.of(
                new Consent(uuid2, "phs000002", "public", "PUB")));

        when(participantRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Participant(uuid1, "SUBJ1", "DBGap")));
        when(participantRepository.findByStudyId("phs000002")).thenReturn(List.of(
                new Participant(uuid2, "SUBJ2", "DBGap")));

        when(sampleRepository.findByStudyId("phs000001")).thenReturn(List.of());
        when(sampleRepository.findByStudyId("phs000002")).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-multi");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ioResolver).writeOutput(eq(outputPath), contentCaptor.capture());

        String csv = new String(contentCaptor.getValue(), StandardCharsets.UTF_8);
        assertThat(csv).contains("phs000001");
        assertThat(csv).contains("phs000002");
        assertThat(csv).doesNotContain("phs000003");

        verify(consentRepository, never()).findByStudyId("phs000003");
    }

    @Test
    void skips_study_with_no_consents_in_database() {
        UUID uuid2 = UUID.randomUUID();

        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("S1", "phs000001", true),
                new ManagedInputRow("S2", "phs000002", true)));

        when(consentRepository.findByStudyId("phs000001")).thenReturn(List.of());
        when(consentRepository.findByStudyId("phs000002")).thenReturn(List.of(
                new Consent(uuid2, "phs000002", "1", "GRU")));

        when(participantRepository.findByStudyId("phs000002")).thenReturn(List.of(
                new Participant(uuid2, "SUBJ2", "DBGap")));
        when(sampleRepository.findByStudyId("phs000002")).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-skip-empty");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ioResolver).writeOutput(eq(outputPath), contentCaptor.capture());

        String csv = new String(contentCaptor.getValue(), StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain("phs000001");
        assertThat(csv).contains("phs000002");
    }

    @Test
    void all_fields_are_quoted_in_output() {
        UUID uuid1 = UUID.randomUUID();

        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("S1", "phs000001", true)));
        when(consentRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Consent(uuid1, "phs000001", "1", "GRU")));
        when(participantRepository.findByStudyId("phs000001")).thenReturn(List.of());
        when(sampleRepository.findByStudyId("phs000001")).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        executor.run(job, Map.of("output", outputPath), "test-quoting");

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ioResolver).writeOutput(eq(outputPath), contentCaptor.capture());

        String csv = new String(contentCaptor.getValue(), StandardCharsets.UTF_8);
        for (String line : csv.split("\n")) {
            String[] fields = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (String field : fields) {
                assertThat(field).startsWith("\"").endsWith("\"");
            }
        }
    }

    @Test
    void timestamp_is_always_zero() {
        UUID uuid1 = UUID.randomUUID();

        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("S1", "phs000001", true)));
        when(consentRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Consent(uuid1, "phs000001", "1", "GRU")));
        when(participantRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Participant(uuid1, "SUBJ1", "DBGap")));
        when(sampleRepository.findByStudyId("phs000001")).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        executor.run(job, Map.of("output", outputPath), "test-timestamp");

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ioResolver).writeOutput(eq(outputPath), contentCaptor.capture());

        String csv = new String(contentCaptor.getValue(), StandardCharsets.UTF_8);
        for (String line : csv.split("\n")) {
            assertThat(line).endsWith(",\"0\"");
        }
    }

    @Test
    void output_uri_appends_filename_when_ending_with_slash() {
        UUID uuid1 = UUID.randomUUID();

        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("S1", "phs000001", true)));
        when(consentRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Consent(uuid1, "phs000001", "1", "GRU")));
        when(participantRepository.findByStudyId("phs000001")).thenReturn(List.of());
        when(sampleRepository.findByStudyId("phs000001")).thenReturn(List.of());

        executor.run(job, Map.of("output", "s3://bucket/output/"), "test-s3-slash");

        verify(ioResolver).writeOutput(eq("s3://bucket/output/global_AllConcepts.csv"), any(byte[].class));
    }
}
