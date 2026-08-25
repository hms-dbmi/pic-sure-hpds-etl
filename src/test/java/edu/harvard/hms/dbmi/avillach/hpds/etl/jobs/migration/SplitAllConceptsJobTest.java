package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SplitAllConceptsJobTest {

    @TempDir
    Path tempDir;

    private ConsentRepository consentRepository;
    private SplitAllConceptsJob job;
    private JobExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        consentRepository = mock(ConsentRepository.class);
        IoResolver ioResolver = new IoResolver(null);
        DelimitedReader delimitedReader = new DelimitedReader();
        job = new SplitAllConceptsJob(ioResolver, delimitedReader, consentRepository);

        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(tempDir.resolve("reports").toString());
        Files.createDirectories(tempDir.resolve("reports"));
        executor = new JobExecutor(
                new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    @Test
    void splits_rows_by_consent_code() throws Exception {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        String studyId = "phs001412";

        Path mappingCsv = writeMappingCsv(
                "100," + uuid1 + ",SUBJ1",
                "200," + uuid2 + ",SUBJ2");

        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µphs001412µdemographicsµAGEµ\",\"42\",\"\",\"0\"",
                "\"100\",\"µphs001412µdemographicsµSEXµ\",\"\",\"Male\",\"0\"",
                "\"200\",\"µphs001412µdemographicsµAGEµ\",\"55\",\"\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "1", "GRU"),
                new Consent(uuid2, studyId, "2", "HMB")));

        Path outputDir = tempDir.resolve("output");
        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "FHS",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-split");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        Path c1File = outputDir.resolve("split_allconcepts/phs001412/c1/FHS_allConcepts_c1.csv");
        Path c2File = outputDir.resolve("split_allconcepts/phs001412/c2/FHS_allConcepts_c2.csv");
        assertThat(c1File).exists();
        assertThat(c2File).exists();

        List<String> c1Lines = Files.readAllLines(c1File, StandardCharsets.UTF_8);
        assertThat(c1Lines).hasSize(2);
        assertThat(c1Lines.get(0)).contains(uuid1.toString());
        assertThat(c1Lines.get(0)).contains("µphs001412µdemographicsµAGEµ");
        assertThat(c1Lines.get(1)).contains(uuid1.toString());
        assertThat(c1Lines.get(1)).contains("Male");

        List<String> c2Lines = Files.readAllLines(c2File, StandardCharsets.UTF_8);
        assertThat(c2Lines).hasSize(1);
        assertThat(c2Lines.get(0)).contains(uuid2.toString());
        assertThat(c2Lines.get(0)).contains("55");
    }

    @Test
    void abbreviation_is_uppercased_in_filename() throws Exception {
        UUID uuid1 = UUID.randomUUID();
        String studyId = "phs000999";

        Path mappingCsv = writeMappingCsv("42," + uuid1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"42\",\"µtestµ\",\"\",\"val\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "lowcase",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-upper");

        Path expected = outputDir.resolve("split_allconcepts/phs000999/c1/LOWCASE_allConcepts_c1.csv");
        assertThat(expected).exists();
    }

    @Test
    void old_hpds_ids_are_not_present_in_output() throws Exception {
        UUID uuid1 = UUID.randomUUID();
        String studyId = "phs000111";

        Path mappingCsv = writeMappingCsv("old-legacy-id-999," + uuid1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"old-legacy-id-999\",\"µtestµ\",\"\",\"data\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-no-old-ids");

        Path outFile = outputDir.resolve("split_allconcepts/phs000111/c1/TST_allConcepts_c1.csv");
        String content = Files.readString(outFile, StandardCharsets.UTF_8);
        assertThat(content).doesNotContain("old-legacy-id-999");
        assertThat(content).contains(uuid1.toString());
    }

    @Test
    void warns_on_unmapped_hpds_ids() throws Exception {
        UUID uuid1 = UUID.randomUUID();
        String studyId = "phs000222";

        Path mappingCsv = writeMappingCsv("100," + uuid1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µtestµ\",\"\",\"mapped\",\"0\"",
                "\"999\",\"µtestµ\",\"\",\"unmapped\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-unmapped");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("UNMAPPED_IDS") && i.message().contains("1 row"));
    }

    @Test
    void warns_on_no_consent_for_uuid() throws Exception {
        UUID uuidWithConsent = UUID.randomUUID();
        UUID uuidWithout = UUID.randomUUID();
        String studyId = "phs000333";

        Path mappingCsv = writeMappingCsv(
                "100," + uuidWithConsent + ",S1",
                "200," + uuidWithout + ",S2");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µtestµ\",\"\",\"val1\",\"0\"",
                "\"200\",\"µtestµ\",\"\",\"val2\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuidWithConsent, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-no-consent");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("NO_CONSENT") && i.message().contains("1 row"));
    }

    @Test
    void fails_on_empty_mapping_csv() throws Exception {
        String studyId = "phs000444";

        Path mappingCsv = tempDir.resolve("empty_mapping.csv");
        Files.writeString(mappingCsv, "old_hpds_id,new_uuid,common_dbgap_id\n");

        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µtestµ\",\"\",\"val\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(UUID.randomUUID(), studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-empty-mapping");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
    }

    @Test
    void fails_on_no_consents_in_database() throws Exception {
        String studyId = "phs000555";
        UUID uuid = UUID.randomUUID();

        Path mappingCsv = writeMappingCsv("100," + uuid + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µtestµ\",\"\",\"val\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of());

        Path outputDir = tempDir.resolve("output");
        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-no-consents");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
    }

    @Test
    void fails_on_invalid_study_id() {
        Path dummyFile = tempDir.resolve("dummy.csv");

        JobResult result = executor.run(job, Map.of(
                "study-id", "bad-id",
                "abbreviation", "TST",
                "input", dummyFile.toString(),
                "mapping", dummyFile.toString(),
                "output", tempDir.toString()), "test-bad-id");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("INVALID_STUDY_ID"));
    }

    @Test
    void fails_when_all_rows_unmapped_produces_no_output() throws Exception {
        String studyId = "phs000666";

        Path mappingCsv = writeMappingCsv("999," + UUID.randomUUID() + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µtestµ\",\"\",\"val\",\"0\"",
                "\"200\",\"µtestµ\",\"\",\"val2\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(UUID.randomUUID(), studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-all-unmapped");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("NO_OUTPUT"));
    }

    @Test
    void output_preserves_all_five_columns_quoted() throws Exception {
        UUID uuid = UUID.randomUUID();
        String studyId = "phs000777";

        Path mappingCsv = writeMappingCsv("42," + uuid + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"42\",\"µpathµ\",\"3.14\",\"\",\"1234567890\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-format");

        Path outFile = outputDir.resolve("split_allconcepts/phs000777/c1/TST_allConcepts_c1.csv");
        String line = Files.readAllLines(outFile, StandardCharsets.UTF_8).getFirst();
        assertThat(line).isEqualTo(
                "\"%s\",\"µpathµ\",\"3.14\",\"\",\"1234567890\"".formatted(uuid));
    }

    @Test
    void handles_quotes_in_values() throws Exception {
        UUID uuid = UUID.randomUUID();
        String studyId = "phs000888";

        Path mappingCsv = writeMappingCsv("42," + uuid + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"42\",\"µpathµ\",\"\",\"value with \"\"quotes\"\"\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-quotes");

        Path outFile = outputDir.resolve("split_allconcepts/phs000888/c1/TST_allConcepts_c1.csv");
        String line = Files.readAllLines(outFile, StandardCharsets.UTF_8).getFirst();
        assertThat(line).contains("value with \"\"quotes\"\"");
    }

    @Test
    void multiple_consents_produce_separate_files() throws Exception {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();
        String studyId = "phs000123";

        Path mappingCsv = writeMappingCsv(
                "10," + u1 + ",S1",
                "20," + u2 + ",S2",
                "30," + u3 + ",S3");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"10\",\"µaµ\",\"\",\"a\",\"0\"",
                "\"20\",\"µbµ\",\"\",\"b\",\"0\"",
                "\"30\",\"µcµ\",\"\",\"c\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(u1, studyId, "1", "GRU"),
                new Consent(u2, studyId, "1", "GRU"),
                new Consent(u3, studyId, "2", "HMB")));

        Path outputDir = tempDir.resolve("output");
        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "MUL",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-multi");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        Path c1 = outputDir.resolve("split_allconcepts/phs000123/c1/MUL_allConcepts_c1.csv");
        Path c2 = outputDir.resolve("split_allconcepts/phs000123/c2/MUL_allConcepts_c2.csv");
        assertThat(c1).exists();
        assertThat(c2).exists();
        assertThat(Files.readAllLines(c1)).hasSize(2);
        assertThat(Files.readAllLines(c2)).hasSize(1);
    }

    // --- helpers ---

    private Path writeMappingCsv(String... dataRows) throws Exception {
        Path path = tempDir.resolve("mapping_" + System.nanoTime() + ".csv");
        StringBuilder sb = new StringBuilder("old_hpds_id,new_uuid,common_dbgap_id\n");
        for (String row : dataRows) {
            sb.append(row).append('\n');
        }
        Files.writeString(path, sb.toString());
        return path;
    }

    private Path writeAllConceptsCsv(String... rows) throws Exception {
        Path path = tempDir.resolve("allconcepts_" + System.nanoTime() + ".csv");
        Files.writeString(path, String.join("\n", rows) + "\n");
        return path;
    }
}
