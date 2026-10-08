package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.Severity;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AllConceptsDataGeneratorJobTest {

    @TempDir
    Path tempDir;

    private static final String STUDY_ID = "phs001412";

    private IoResolver ioResolver;
    private ConsentRepository consentRepository;
    private ParticipantRepository participantRepository;

    private AllConceptsDataGeneratorJob job;
    private JobExecutor executor;

    private long id1;
    private long id2;

    @BeforeEach
    void setUp() throws Exception {
        ioResolver = mock(IoResolver.class);
        consentRepository = mock(ConsentRepository.class);
        participantRepository = mock(ParticipantRepository.class);

        job = new AllConceptsDataGeneratorJob(
                ioResolver, new DelimitedReader(), consentRepository, participantRepository);

        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(tempDir.resolve("reports").toString());
        Files.createDirectories(tempDir.resolve("reports"));
        executor = new JobExecutor(
                new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);

        id1 = 1L;
        id2 = 2L;
    }

    private Map<String, String> params(String outputDir) {
        return Map.of(
                "study-id", STUDY_ID,
                "data-dir", tempDir.resolve("data/").toString(),
                "mapping", tempDir.resolve("mapping.csv").toString(),
                "output", outputDir,
                "skip-analysis", "true");
    }

    private void setupStudyData() {
        when(consentRepository.findByStudyId(STUDY_ID)).thenReturn(List.of(
                new Consent(id1, STUDY_ID, "1", "GRU"),
                new Consent(id2, STUDY_ID, "2", "HMB")));

        when(participantRepository.findByStudyId(STUDY_ID)).thenReturn(List.of(
                new Participant(id1, "SUBJ001", "DBGap"),
                new Participant(id2, "SUBJ002", "DBGap")));
    }

    private void setupMappingFile(String mappingContent) {
        InputStream mappingStream = new ByteArrayInputStream(
                mappingContent.getBytes(StandardCharsets.UTF_8));
        String mappingPath = tempDir.resolve("mapping.csv").toString();
        when(ioResolver.openInput(mappingPath)).thenReturn(mappingStream);
    }

    private void setupDataFile(String fileName, String content) {
        String dataPath = tempDir.resolve("data/").toString() + "/" + fileName;
        when(ioResolver.exists(dataPath)).thenReturn(true);
        when(ioResolver.openInput(dataPath)).thenReturn(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void generates_per_consent_output_files_with_text_data() {
        setupStudyData();

        setupMappingFile("\"datafile.csv:1\",\"µStudyµAgeµ\",\"\",\"TEXT\",\"\"\n");

        setupDataFile("datafile.csv",
                "patient_id,age\n"
                        + "SUBJ001,25\n"
                        + "SUBJ002,30\n");

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, params(outputDir), "test-text");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ioResolver, org.mockito.Mockito.atLeastOnce())
                .writeOutput(uriCaptor.capture(), contentCaptor.capture());

        List<String> uris = uriCaptor.getAllValues();
        assertThat(uris).anyMatch(u -> u.contains(STUDY_ID + "_allConcepts_c1.csv"));
        assertThat(uris).anyMatch(u -> u.contains(STUDY_ID + "_allConcepts_c2.csv"));

        for (int i = 0; i < uris.size(); i++) {
            String csv = new String(contentCaptor.getAllValues().get(i), StandardCharsets.UTF_8);
            if (uris.get(i).contains("_allConcepts_c1.csv")) {
                assertThat(csv).contains("\"" + String.valueOf(id1) + "\"");
                assertThat(csv).contains("\"µStudyµAgeµ\"");
                assertThat(csv).contains("\"25\"");
            }
            if (uris.get(i).contains("_allConcepts_c2.csv")) {
                assertThat(csv).contains("\"" + String.valueOf(id2) + "\"");
                assertThat(csv).contains("\"30\"");
            }
        }
    }

    @Test
    void generates_numeric_rows_for_numeric_mappings() {
        setupStudyData();

        setupMappingFile("\"datafile.csv:1\",\"µStudyµBMIµ\",\"\",\"NUMERIC\",\"\"\n");

        setupDataFile("datafile.csv",
                "patient_id,bmi\n"
                        + "SUBJ001,24.5\n"
                        + "SUBJ002,not_a_number\n");

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, params(outputDir), "test-numeric");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ioResolver, org.mockito.Mockito.atLeastOnce())
                .writeOutput(uriCaptor.capture(), contentCaptor.capture());

        for (int i = 0; i < uriCaptor.getAllValues().size(); i++) {
            String csv = new String(contentCaptor.getAllValues().get(i), StandardCharsets.UTF_8);
            if (uriCaptor.getAllValues().get(i).contains("_allConcepts_c1.csv")) {
                // numeric value in the numeric column (3rd), non-numeric column (4th) empty
                assertThat(csv).contains("\"24.5\"");
                String[] lines = csv.trim().split("\n");
                assertThat(lines).hasSize(1);
            }
        }
    }

    @Test
    void skips_null_equivalent_values() {
        setupStudyData();

        setupMappingFile("\"datafile.csv:1\",\"µStudyµValµ\",\"\",\"TEXT\",\"\"\n");

        setupDataFile("datafile.csv",
                "patient_id,val\n"
                        + "SUBJ001,null\n"
                        + "SUBJ002,NA\n");

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, params(outputDir), "test-nulls");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(result.getErrorMessage()).contains("No concept rows were generated");
    }

    @Test
    void skips_rows_with_unmapped_patients_and_warns() {
        when(consentRepository.findByStudyId(STUDY_ID)).thenReturn(List.of(
                new Consent(id1, STUDY_ID, "1", "GRU")));
        when(participantRepository.findByStudyId(STUDY_ID)).thenReturn(List.of(
                new Participant(id1, "SUBJ001", "DBGap")));

        setupMappingFile("\"datafile.csv:1\",\"µStudyµValµ\",\"\",\"TEXT\",\"\"\n");

        setupDataFile("datafile.csv",
                "patient_id,val\n"
                        + "SUBJ001,hello\n"
                        + "UNKNOWN,world\n");

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, params(outputDir), "test-unmapped");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("UNMAPPED_PATIENTS"));
    }

    @Test
    void fails_when_no_consents_for_study() {
        when(consentRepository.findByStudyId(STUDY_ID)).thenReturn(List.of());
        when(participantRepository.findByStudyId(STUDY_ID)).thenReturn(List.of(
                new Participant(id1, "SUBJ001", "DBGap")));

        setupMappingFile("\"datafile.csv:1\",\"µStudyµValµ\",\"\",\"TEXT\",\"\"\n");

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, params(outputDir), "test-no-consents");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(result.getErrorMessage()).contains("No consents found");
    }

    @Test
    void fails_when_no_participants_for_study() {
        when(consentRepository.findByStudyId(STUDY_ID)).thenReturn(List.of(
                new Consent(id1, STUDY_ID, "1", "GRU")));
        when(participantRepository.findByStudyId(STUDY_ID)).thenReturn(List.of());

        setupMappingFile("\"datafile.csv:1\",\"µStudyµValµ\",\"\",\"TEXT\",\"\"\n");

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, params(outputDir), "test-no-participants");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(result.getErrorMessage()).contains("No participants found");
    }

    @Test
    void fails_validation_on_bad_study_id() {
        when(consentRepository.findByStudyId("badid")).thenReturn(List.of());

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, Map.of(
                "study-id", "badid",
                "data-dir", tempDir.resolve("data/").toString(),
                "mapping", tempDir.resolve("mapping.csv").toString(),
                "output", outputDir), "test-bad-study");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("BAD_STUDY_ID"));
    }

    @Test
    void fails_validation_when_required_params_missing() {
        JobResult result = executor.run(job, Map.of(), "test-missing-params");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
    }

    @Test
    void handles_multiple_mappings_for_same_file() {
        setupStudyData();

        setupMappingFile(
                "\"datafile.csv:1\",\"µStudyµAgeµ\",\"\",\"TEXT\",\"\"\n"
                        + "\"datafile.csv:2\",\"µStudyµWeightµ\",\"\",\"TEXT\",\"\"\n");

        setupDataFile("datafile.csv",
                "patient_id,age,weight\n"
                        + "SUBJ001,25,70\n"
                        + "SUBJ002,30,80\n");

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, params(outputDir), "test-multi-mapping");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(ioResolver, org.mockito.Mockito.atLeastOnce())
                .writeOutput(uriCaptor.capture(), contentCaptor.capture());

        for (int i = 0; i < uriCaptor.getAllValues().size(); i++) {
            String csv = new String(contentCaptor.getAllValues().get(i), StandardCharsets.UTF_8);
            if (uriCaptor.getAllValues().get(i).contains("_allConcepts_c1.csv")) {
                assertThat(csv).contains("\"µStudyµAgeµ\"");
                assertThat(csv).contains("\"µStudyµWeightµ\"");
            }
        }
    }

    @Test
    void skips_missing_data_files_gracefully() {
        setupStudyData();

        setupMappingFile("\"missing.csv:1\",\"µStudyµValµ\",\"\",\"TEXT\",\"\"\n"
                + "\"present.csv:1\",\"µStudyµOtherµ\",\"\",\"TEXT\",\"\"\n");

        String dataDir = tempDir.resolve("data/").toString() + "/";
        when(ioResolver.exists(dataDir + "missing.csv")).thenReturn(false);
        setupDataFile("present.csv",
                "patient_id,val\n"
                        + "SUBJ001,hello\n");

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, params(outputDir), "test-missing-file");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
    }

    @Test
    void data_type_analysis_promotes_text_when_non_numeric_found() {
        setupStudyData();

        setupMappingFile("\"datafile.csv:1\",\"µStudyµValµ\",\"\",\"NUMERIC\",\"\"\n");

        String dataDir = tempDir.resolve("data/").toString() + "/";
        String fileUri = dataDir + "datafile.csv";
        when(ioResolver.exists(fileUri)).thenReturn(true);
        when(ioResolver.openInput(fileUri)).thenReturn(
                new ByteArrayInputStream("patient_id,val\nSUBJ001,hello\nSUBJ002,42\n"
                        .getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream("patient_id,val\nSUBJ001,hello\nSUBJ002,42\n"
                        .getBytes(StandardCharsets.UTF_8)));

        String mappingPath = tempDir.resolve("mapping.csv").toString();
        when(ioResolver.openInput(mappingPath)).thenReturn(
                new ByteArrayInputStream("\"datafile.csv:1\",\"µStudyµValµ\",\"\",\"NUMERIC\",\"\"\n"
                        .getBytes(StandardCharsets.UTF_8)));

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, Map.of(
                "study-id", STUDY_ID,
                "data-dir", tempDir.resolve("data/").toString(),
                "mapping", mappingPath,
                "output", outputDir), "test-analysis");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(ioResolver, org.mockito.Mockito.atLeastOnce())
                .writeOutput(uriCaptor.capture(), contentCaptor.capture());

        for (int i = 0; i < uriCaptor.getAllValues().size(); i++) {
            String csv = new String(contentCaptor.getAllValues().get(i), StandardCharsets.UTF_8);
            if (uriCaptor.getAllValues().get(i).contains("_allConcepts_c1.csv")) {
                // "hello" is now TEXT since analysis detected non-numeric values
                assertThat(csv).contains("\"hello\"");
            }
        }
    }

    @Test
    void all_output_fields_are_quoted_with_timestamp_zero() {
        setupStudyData();

        setupMappingFile("\"datafile.csv:1\",\"µStudyµValµ\",\"\",\"TEXT\",\"\"\n");

        setupDataFile("datafile.csv",
                "patient_id,val\n"
                        + "SUBJ001,hello\n");

        String outputDir = tempDir.resolve("output/").toString();
        executor.run(job, params(outputDir), "test-format");

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(ioResolver, org.mockito.Mockito.atLeastOnce())
                .writeOutput(uriCaptor.capture(), contentCaptor.capture());

        for (int i = 0; i < uriCaptor.getAllValues().size(); i++) {
            if (!uriCaptor.getAllValues().get(i).contains("allConcepts")) continue;
            String csv = new String(contentCaptor.getAllValues().get(i), StandardCharsets.UTF_8);
            for (String line : csv.split("\n")) {
                assertThat(line).endsWith(",\"0\"");
                long quoteCount = line.chars().filter(c -> c == '"').count();
                assertThat(quoteCount).isGreaterThanOrEqualTo(10);
            }
        }
    }

    @Test
    void removes_own_file_for_consent_groups_that_produced_no_rows() {
        setupStudyData();
        setupMappingFile("\"datafile.csv:1\",\"µStudyµAgeµ\",\"\",\"TEXT\",\"\"\n");
        // Only SUBJ001 (c1) has data this run, so c2 writes nothing.
        setupDataFile("datafile.csv", "patient_id,age\nSUBJ001,25\n");

        String outputDir = tempDir.resolve("output").toString();
        String studyDir = outputDir + "/" + STUDY_ID + "/allConcepts/";
        // Last run left files in c1, c2, and c3 (a group the SSTR reload has since removed).
        when(ioResolver.listDirectoryNames(studyDir)).thenReturn(List.of("c1", "c2", "c3", "notes"));
        String staleC2 = studyDir + "c2/" + STUDY_ID + "_allConcepts_c2.csv";
        String staleC3 = studyDir + "c3/" + STUDY_ID + "_allConcepts_c3.csv";
        when(ioResolver.exists(staleC2)).thenReturn(true);
        when(ioResolver.exists(staleC3)).thenReturn(true);

        JobResult result = executor.run(job, params(outputDir), "test-stale");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        verify(ioResolver).delete(staleC2);
        verify(ioResolver).delete(staleC3);
        // The group written this run is overwritten, never deleted; non-consent folders are ignored.
        verify(ioResolver, never()).delete(studyDir + "c1/" + STUDY_ID + "_allConcepts_c1.csv");
        verify(ioResolver, never()).delete(startsWith(studyDir + "notes/"));
        assertThat(result.getMetrics().get("staleFilesRemoved")).isEqualTo(List.of(staleC2, staleC3));
    }

    @Test
    void stale_cleanup_leaves_other_sources_files_alone() {
        setupStudyData();
        setupMappingFile("\"datafile.csv:1\",\"µStudyµAgeµ\",\"\",\"TEXT\",\"\"\n");
        setupDataFile("datafile.csv", "patient_id,age\nSUBJ001,25\n");

        String outputDir = tempDir.resolve("output").toString();
        String studyDir = outputDir + "/" + STUDY_ID + "/allConcepts/";
        // c2 holds only another source's file, not this job's.
        when(ioResolver.listDirectoryNames(studyDir)).thenReturn(List.of("c1", "c2"));

        JobResult result = executor.run(job, params(outputDir), "test-other-sources");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        verify(ioResolver, never()).delete(any());
    }

    @Test
    void reports_rows_it_had_to_drop() {
        setupStudyData();

        setupMappingFile("\"datafile.csv:1\",\"µStudyµValµ\",\"\",\"TEXT\",\"\"\n"
                + "\"missing.csv:1\",\"µStudyµGoneµ\",\"\",\"TEXT\",\"\"\n"
                + "\"no-column-index\",\"µStudyµBadµ\",\"\",\"TEXT\",\"\"\n");

        when(ioResolver.exists(tempDir.resolve("data/").toString() + "/missing.csv")).thenReturn(false);
        setupDataFile("datafile.csv",
                "patient_id,val\n"
                        + "SUBJ001,hello\n"
                        + "SUBJ002,too,many\n");

        String outputDir = tempDir.resolve("output/").toString();
        JobResult result = executor.run(job, params(outputDir), "test-dropped-rows");

        // Only the malformed data row loses data; the other two are surfaced without failing the run.
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getMetrics())
                .containsEntry("malformedRows", 1L)
                .containsEntry("mappingRowsDropped", 1L);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("MALFORMED_ROWS") && i.severity() == Severity.WARNING)
                .anyMatch(i -> i.code().equals("MISSING_DATA_FILE") && i.severity() == Severity.INFO
                        && i.message().contains("missing.csv"))
                .anyMatch(i -> i.code().equals("DROPPED_MAPPING_ROWS") && i.severity() == Severity.INFO);
    }

    @Test
    void rejects_a_misspelt_skip_analysis_flag() {
        Map<String, String> params = new java.util.HashMap<>(params(tempDir.resolve("output/").toString()));
        params.put("skip-analysis", "maybe");

        JobResult result = executor.run(job, params, "test-bad-skip-analysis");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("BAD_BOOLEAN") && "--skip-analysis".equals(i.location()));
    }
}
