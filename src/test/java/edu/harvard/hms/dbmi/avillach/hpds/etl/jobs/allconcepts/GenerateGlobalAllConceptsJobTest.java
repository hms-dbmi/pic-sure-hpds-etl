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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        long id1 = 1L;
        long id2 = 2L;
        String studyId = "phs001412";

        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("STUDY1", studyId, true)));

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU"),
                new Consent(id2, studyId, "2", "HMB")));

        when(participantRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Participant(id1, "SUBJ1", "DBGap"),
                new Participant(id2, "SUBJ2", "DBGap")));

        when(sampleRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Sample(id1, studyId, "SAMP1", "DBGap")));

        String outputPath = tempDir.resolve("output/").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-success");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<IoResolver.IoWriter> writerCaptor = ArgumentCaptor.forClass(IoResolver.IoWriter.class);
        verify(ioResolver).writeOutput(eq(outputPath + "global_AllConcepts.csv"), writerCaptor.capture());

        String csv = invokeWriter(writerCaptor.getValue());
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
        long id1 = 1L;
        String studyId = "phs001412";

        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("STUDY1", studyId, true)));

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "")));

        when(participantRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Participant(id1, "SUBJ1", "DBGap")));

        when(sampleRepository.findByStudyId(studyId)).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-empty-abbrev");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);

        ArgumentCaptor<IoResolver.IoWriter> writerCaptor = ArgumentCaptor.forClass(IoResolver.IoWriter.class);
        verify(ioResolver).writeOutput(eq(outputPath), writerCaptor.capture());

        String csv = invokeWriter(writerCaptor.getValue());
        // 1 consent + 1 participant + 0 samples + 1 study-level + 0 individual (skipped) = 3
        assertThat(csv.split("\n")).hasSize(3);
        assertThat(csv).contains("\"µ_studies_consentsµ" + studyId + "µ\"");
        assertThat(csv).doesNotContain("\"µ_studies_consentsµ" + studyId + "µµ\"");
    }

    @Test
    void fails_validation_when_no_studies_are_ready() {
        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("STUDY1", "phs001412", false)));

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-no-ready");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().hasErrors()).isTrue();
    }

    @Test
    void fails_when_no_rows_generated_across_all_studies() {
        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("STUDY1", "phs001412", true)));

        when(consentRepository.findByStudyId("phs001412")).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-empty-db");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(result.getErrorMessage()).contains("No concept rows were generated");
    }

    @Test
    void fails_with_infrastructure_error_when_database_unreachable() {
        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("STUDY1", "phs001412", true)));

        when(consentRepository.findByStudyId("phs001412"))
                .thenThrow(new InfrastructureException("connection refused"));

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-db-down");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
    }

    @Test
    void fails_validation_when_output_param_missing() {
        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("STUDY1", "phs001412", true)));

        JobResult result = executor.run(job, Map.of(), "test-missing-output");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
    }

    @Test
    void processes_multiple_ready_studies() {
        long id1 = 1L;
        long id2 = 2L;

        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("S1", "phs000001", true),
                ManagedInputRow.of("S2", "phs000002", true),
                ManagedInputRow.of("S3", "phs000003", false)));

        when(consentRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Consent(id1, "phs000001", "1", "GRU")));
        when(consentRepository.findByStudyId("phs000002")).thenReturn(List.of(
                new Consent(id2, "phs000002", "public", "PUB")));

        when(participantRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Participant(id1, "SUBJ1", "DBGap")));
        when(participantRepository.findByStudyId("phs000002")).thenReturn(List.of(
                new Participant(id2, "SUBJ2", "DBGap")));

        when(sampleRepository.findByStudyId("phs000001")).thenReturn(List.of());
        when(sampleRepository.findByStudyId("phs000002")).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-multi");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<IoResolver.IoWriter> writerCaptor = ArgumentCaptor.forClass(IoResolver.IoWriter.class);
        verify(ioResolver).writeOutput(eq(outputPath), writerCaptor.capture());

        String csv = invokeWriter(writerCaptor.getValue());
        assertThat(csv).contains("phs000001");
        assertThat(csv).contains("phs000002");
        assertThat(csv).doesNotContain("phs000003");

        verify(consentRepository, never()).findByStudyId("phs000003");
    }

    /**
     * One participant (hpds id 1) in a parent study and its TOPMed study: its subject id is written
     * once, a sample both studies loaded is written once, and the report says which studies each row
     * applies to. The per-study concepts stay per study.
     */
    @Test
    void writes_each_subject_and_sample_id_once_and_reports_the_studies_it_applies_to() throws Exception {
        String parent = "phs000280";
        String topmed = "phs001211";
        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("ARIC", parent, true),
                ManagedInputRow.of("ARIC_TOPMED", topmed, true)));

        when(consentRepository.findByStudyId(parent)).thenReturn(List.of(
                new Consent(1L, parent, "1", "HMB"), new Consent(2L, parent, "1", "HMB")));
        when(consentRepository.findByStudyId(topmed)).thenReturn(List.of(
                new Consent(1L, topmed, "2", "DS")));

        when(participantRepository.findByStudyId(parent)).thenReturn(List.of(
                new Participant(1L, "SUBJ1", "DBGap"), new Participant(2L, "SUBJ2", "DBGap")));
        when(participantRepository.findByStudyId(topmed)).thenReturn(List.of(
                new Participant(1L, "SUBJ1", "DBGap")));

        when(sampleRepository.findByStudyId(parent)).thenReturn(List.of(
                new Sample(1L, parent, "SRS1", "DBGap"),
                new Sample(1L, parent, "NWD1", "submitted")));
        when(sampleRepository.findByStudyId(topmed)).thenReturn(List.of(
                new Sample(1L, topmed, "NWD1", "submitted"),
                // The same id under both sources in one study counts for that study once.
                new Sample(1L, topmed, "NWD9", "DBGap"),
                new Sample(1L, topmed, "NWD9", "submitted")));

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-shared");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        ArgumentCaptor<IoResolver.IoWriter> writerCaptor = ArgumentCaptor.forClass(IoResolver.IoWriter.class);
        verify(ioResolver).writeOutput(eq(outputPath), writerCaptor.capture());
        List<String> lines = List.of(invokeWriter(writerCaptor.getValue()).split("\n"));

        assertThat(lines.stream().filter(l -> l.contains("µ_source_subject_idµ"))).containsExactly(
                "\"1\",\"µ_source_subject_idµ\",\"\",\"SUBJ1\",\"0\"",
                "\"2\",\"µ_source_subject_idµ\",\"\",\"SUBJ2\",\"0\"");
        assertThat(lines.stream().filter(l -> l.contains("µ_source_sample_idµ"))).containsExactly(
                "\"1\",\"µ_source_sample_idµ\",\"\",\"SRS1\",\"0\"",
                "\"1\",\"µ_source_sample_idµ\",\"\",\"NWD1\",\"0\"",
                "\"1\",\"µ_source_sample_idµ\",\"\",\"NWD9\",\"0\"");
        // Per-study concepts are still one per study.
        assertThat(lines.stream().filter(l -> l.contains("µ_consentsµ"))).containsExactly(
                "\"1\",\"µ_consentsµ\",\"\",\"phs000280.c1\",\"0\"",
                "\"2\",\"µ_consentsµ\",\"\",\"phs000280.c1\",\"0\"",
                "\"1\",\"µ_consentsµ\",\"\",\"phs001211.c2\",\"0\"");

        assertThat(result.getMetrics())
                .containsEntry("subjectIdRows", 2L)
                .containsEntry("subjectIdsByStudyCount", Map.of("1", 1L, "2", 1L))
                .containsEntry("sharedSubjectIdStudySets", Map.of("phs000280+phs001211", 1L))
                .containsEntry("sampleIdRows", 3L)
                .containsEntry("sampleIdsByStudyCount", Map.of("1", 2L, "2", 1L))
                .containsEntry("sharedSampleIdStudySets", Map.of("phs000280+phs001211", 1L))
                .containsEntry("rowsPerStudy", Map.of(parent, 6L, topmed, 3L));
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("SHARED_SUBJECT_IDS") && i.message().startsWith("1 of 2 "))
                .anyMatch(i -> i.code().equals("SHARED_SAMPLE_IDS") && i.message().startsWith("1 of 3 "));

        assertThat(Files.readAllLines(tempDir.resolve("reports")
                .resolve(GenerateGlobalAllConceptsJob.IDENTITY_STUDIES_REPORT))).containsExactly(
                "concept,hpds_id,value,study_count,study_ids",
                "\"µ_source_subject_idµ\",1,\"SUBJ1\",2,\"phs000280;phs001211\"",
                "\"µ_source_subject_idµ\",2,\"SUBJ2\",1,\"phs000280\"",
                "\"µ_source_sample_idµ\",1,\"SRS1\",1,\"phs000280\"",
                "\"µ_source_sample_idµ\",1,\"NWD1\",2,\"phs000280;phs001211\"",
                "\"µ_source_sample_idµ\",1,\"NWD9\",1,\"phs001211\"");
    }

    @Test
    void skips_study_with_no_consents_in_database() {
        long id2 = 2L;

        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("S1", "phs000001", true),
                ManagedInputRow.of("S2", "phs000002", true)));

        when(consentRepository.findByStudyId("phs000001")).thenReturn(List.of());
        when(consentRepository.findByStudyId("phs000002")).thenReturn(List.of(
                new Consent(id2, "phs000002", "1", "GRU")));

        when(participantRepository.findByStudyId("phs000002")).thenReturn(List.of(
                new Participant(id2, "SUBJ2", "DBGap")));
        when(sampleRepository.findByStudyId("phs000002")).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-skip-empty");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.message().contains("phs000001") && i.message().contains("no consents"));

        ArgumentCaptor<IoResolver.IoWriter> writerCaptor = ArgumentCaptor.forClass(IoResolver.IoWriter.class);
        verify(ioResolver).writeOutput(eq(outputPath), writerCaptor.capture());

        String csv = invokeWriter(writerCaptor.getValue());
        assertThat(csv).doesNotContain("phs000001");
        assertThat(csv).contains("phs000002");
    }

    @Test
    void all_fields_are_quoted_in_output() {
        long id1 = 1L;

        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("S1", "phs000001", true)));
        when(consentRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Consent(id1, "phs000001", "1", "GRU")));
        when(participantRepository.findByStudyId("phs000001")).thenReturn(List.of());
        when(sampleRepository.findByStudyId("phs000001")).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        executor.run(job, Map.of("output", outputPath), "test-quoting");

        ArgumentCaptor<IoResolver.IoWriter> writerCaptor = ArgumentCaptor.forClass(IoResolver.IoWriter.class);
        verify(ioResolver).writeOutput(eq(outputPath), writerCaptor.capture());

        String csv = invokeWriter(writerCaptor.getValue());
        for (String line : csv.split("\n")) {
            String[] fields = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (String field : fields) {
                assertThat(field).startsWith("\"").endsWith("\"");
            }
        }
    }

    @Test
    void timestamp_is_always_zero() {
        long id1 = 1L;

        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("S1", "phs000001", true)));
        when(consentRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Consent(id1, "phs000001", "1", "GRU")));
        when(participantRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Participant(id1, "SUBJ1", "DBGap")));
        when(sampleRepository.findByStudyId("phs000001")).thenReturn(List.of());

        String outputPath = tempDir.resolve("output.csv").toString();
        executor.run(job, Map.of("output", outputPath), "test-timestamp");

        ArgumentCaptor<IoResolver.IoWriter> writerCaptor = ArgumentCaptor.forClass(IoResolver.IoWriter.class);
        verify(ioResolver).writeOutput(eq(outputPath), writerCaptor.capture());

        String csv = invokeWriter(writerCaptor.getValue());
        for (String line : csv.split("\n")) {
            assertThat(line).endsWith(",\"0\"");
        }
    }

    @Test
    void output_uri_appends_filename_when_ending_with_slash() {
        long id1 = 1L;

        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("S1", "phs000001", true)));
        when(consentRepository.findByStudyId("phs000001")).thenReturn(List.of(
                new Consent(id1, "phs000001", "1", "GRU")));
        when(participantRepository.findByStudyId("phs000001")).thenReturn(List.of());
        when(sampleRepository.findByStudyId("phs000001")).thenReturn(List.of());

        executor.run(job, Map.of("output", "s3://bucket/output/"), "test-s3-slash");

        verify(ioResolver).writeOutput(eq("s3://bucket/output/global_AllConcepts.csv"), any(IoResolver.IoWriter.class));
    }

    private static String invokeWriter(IoResolver.IoWriter writer) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writer.writeTo(baos);
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void rejects_a_misspelt_allow_empty_flag_before_running() {
        when(managedInputsService.read()).thenReturn(List.of(
                ManagedInputRow.of("STUDY1", "phs001412", true)));

        String outputPath = tempDir.resolve("output.csv").toString();
        JobResult result = executor.run(job,
                Map.of("output", outputPath, "allow-empty", "yes please"), "test-bad-allow-empty");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("BAD_BOOLEAN") && "--allow-empty".equals(i.location()));
    }
}
