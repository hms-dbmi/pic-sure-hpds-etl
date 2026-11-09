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
        long id1 = 1L;
        long id2 = 2L;
        String studyId = "phs001412";

        Path mappingCsv = writeMappingCsv(
                "100," + id1 + ",SUBJ1",
                "200," + id2 + ",SUBJ2");

        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µphs001412µdemographicsµAGEµ\",\"42\",\"\",\"0\"",
                "\"100\",\"µphs001412µdemographicsµSEXµ\",\"\",\"Male\",\"0\"",
                "\"200\",\"µphs001412µdemographicsµAGEµ\",\"55\",\"\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU"),
                new Consent(id2, studyId, "2", "HMB")));

        Path outputDir = tempDir.resolve("output");
        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "FHS",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-split");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        Path c1File = outputDir.resolve("phs001412/allConcepts/c1/phs001412_allConcepts_c1.csv");
        Path c2File = outputDir.resolve("phs001412/allConcepts/c2/phs001412_allConcepts_c2.csv");
        assertThat(c1File).exists();
        assertThat(c2File).exists();

        List<String> c1Lines = Files.readAllLines(c1File, StandardCharsets.UTF_8);
        assertThat(c1Lines).hasSize(2);
        assertThat(c1Lines.get(0)).contains(String.valueOf(id1));
        assertThat(c1Lines.get(0)).contains("µphs001412µdemographicsµAGEµ");
        assertThat(c1Lines.get(1)).contains(String.valueOf(id1));
        assertThat(c1Lines.get(1)).contains("Male");

        List<String> c2Lines = Files.readAllLines(c2File, StandardCharsets.UTF_8);
        assertThat(c2Lines).hasSize(1);
        assertThat(c2Lines.get(0)).contains(String.valueOf(id2));
        assertThat(c2Lines.get(0)).contains("55");

        // c1 has two rows for one participant: rows and participants are counted separately.
        assertThat(result.getMetrics().get("rowsPerConsent")).isEqualTo(Map.of("1", 2L, "2", 1L));
        assertThat(result.getMetrics().get("participantsPerConsent")).isEqualTo(Map.of("1", 1L, "2", 1L));
    }

    @Test
    void abbreviation_is_uppercased_in_filename() throws Exception {
        long id1 = 1L;
        String studyId = "phs000999";

        Path mappingCsv = writeMappingCsv("42," + id1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"42\",\"µphs000999µtestµ\",\"\",\"val\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "lowcase",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-upper");

        Path expected = outputDir.resolve("phs000999/allConcepts/c1/phs000999_allConcepts_c1.csv");
        assertThat(expected).exists();
    }

    @Test
    void old_hpds_ids_are_not_present_in_output() throws Exception {
        long id1 = 1L;
        String studyId = "phs000111";

        Path mappingCsv = writeMappingCsv("old-legacy-id-999," + id1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"old-legacy-id-999\",\"µphs000111µtestµ\",\"\",\"data\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-no-old-ids");

        Path outFile = outputDir.resolve("phs000111/allConcepts/c1/phs000111_allConcepts_c1.csv");
        String content = Files.readString(outFile, StandardCharsets.UTF_8);
        assertThat(content).doesNotContain("old-legacy-id-999");
        assertThat(content).contains(String.valueOf(id1));
    }

    @Test
    void warns_on_unmapped_hpds_ids() throws Exception {
        long id1 = 1L;
        String studyId = "phs000222";

        Path mappingCsv = writeMappingCsv("100," + id1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µphs000222µtestµ\",\"\",\"mapped\",\"0\"",
                "\"999\",\"µphs000222µtestµ\",\"\",\"unmapped\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU")));

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
    void warns_on_no_consent_for_hpds_id() throws Exception {
        long idWithConsent = 1L;
        long idWithout = 2L;
        String studyId = "phs000333";

        Path mappingCsv = writeMappingCsv(
                "100," + idWithConsent + ",S1",
                "200," + idWithout + ",S2");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µphs000333µtestµ\",\"\",\"val1\",\"0\"",
                "\"200\",\"µphs000333µtestµ\",\"\",\"val2\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(idWithConsent, studyId, "1", "GRU")));

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
        Files.writeString(mappingCsv, "old_hpds_id,new_hpds_id,common_dbgap_id\n");

        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µphs000444µtestµ\",\"\",\"val\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(99L, studyId, "1", "GRU")));

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
        long id1 = 1L;

        Path mappingCsv = writeMappingCsv("100," + id1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µphs000555µtestµ\",\"\",\"val\",\"0\"");

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
                .anyMatch(i -> i.code().equals("BAD_STUDY_ID"));
    }

    @Test
    void fails_when_all_rows_unmapped_produces_no_output() throws Exception {
        String studyId = "phs000666";

        Path mappingCsv = writeMappingCsv("999,99,S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µphs000666µtestµ\",\"\",\"val\",\"0\"",
                "\"200\",\"µphs000666µtestµ\",\"\",\"val2\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(99L, studyId, "1", "GRU")));

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
        long id1 = 1L;
        String studyId = "phs000777";

        Path mappingCsv = writeMappingCsv("42," + id1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"42\",\"µphs000777µpathµ\",\"3.14\",\"\",\"1234567890\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-format");

        Path outFile = outputDir.resolve("phs000777/allConcepts/c1/phs000777_allConcepts_c1.csv");
        String line = Files.readAllLines(outFile, StandardCharsets.UTF_8).getFirst();
        assertThat(line).isEqualTo(
                "\"%s\",\"µphs000777µpathµ\",\"3.14\",\"\",\"1234567890\"".formatted(id1));
    }

    @Test
    void handles_quotes_in_values() throws Exception {
        long id1 = 1L;
        String studyId = "phs000888";

        Path mappingCsv = writeMappingCsv("42," + id1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"42\",\"µphs000888µpathµ\",\"\",\"value with \"\"quotes\"\"\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-quotes");

        Path outFile = outputDir.resolve("phs000888/allConcepts/c1/phs000888_allConcepts_c1.csv");
        String line = Files.readAllLines(outFile, StandardCharsets.UTF_8).getFirst();
        assertThat(line).contains("value with \"\"quotes\"\"");
    }

    @Test
    void multiple_consents_produce_separate_files() throws Exception {
        long id1 = 1L;
        long id2 = 2L;
        long id3 = 3L;
        String studyId = "phs000123";

        Path mappingCsv = writeMappingCsv(
                "10," + id1 + ",S1",
                "20," + id2 + ",S2",
                "30," + id3 + ",S3");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"10\",\"µphs000123µaµ\",\"\",\"a\",\"0\"",
                "\"20\",\"µphs000123µbµ\",\"\",\"b\",\"0\"",
                "\"30\",\"µphs000123µcµ\",\"\",\"c\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU"),
                new Consent(id2, studyId, "1", "GRU"),
                new Consent(id3, studyId, "2", "HMB")));

        Path outputDir = tempDir.resolve("output");
        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "MUL",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-multi");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        Path c1 = outputDir.resolve("phs000123/allConcepts/c1/phs000123_allConcepts_c1.csv");
        Path c2 = outputDir.resolve("phs000123/allConcepts/c2/phs000123_allConcepts_c2.csv");
        assertThat(c1).exists();
        assertThat(c2).exists();
        assertThat(Files.readAllLines(c1)).hasSize(2);
        assertThat(Files.readAllLines(c2)).hasSize(1);
    }

    // --- helpers ---

    private Path writeMappingCsv(String... dataRows) throws Exception {
        Path path = tempDir.resolve("mapping_" + System.nanoTime() + ".csv");
        StringBuilder sb = new StringBuilder("old_hpds_id,new_hpds_id,common_dbgap_id\n");
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

    @Test
    void counts_malformed_rows_and_attaches_the_unmapped_sample() throws Exception {
        long id1 = 1L;
        String studyId = "phs000777";

        Path mappingCsv = writeMappingCsv("100," + id1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µphs000777µtestµ\",\"\",\"mapped\",\"0\"",
                "\"999\",\"µphs000777µtestµ\",\"\",\"unmapped\",\"0\"",
                "\"100\",\"µphs000777µtestµ\",\"\",\"short\"",
                "\"\",\"µphs000777µtestµ\",\"\",\"blank id\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU")));

        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "TST",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", tempDir.resolve("output").toString()), "test-malformed");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getMetrics()).containsEntry("malformedRows", 2L);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("MALFORMED_ROWS") && i.message().startsWith("2 row(s)"))
                .anyMatch(i -> i.code().equals("UNMAPPED_IDS")
                        && i.context().get("firstOldHpdsIds").equals(List.of("999"))
                        && i.context().get("count").equals(1L));
    }

    @Test
    void drops_rows_whose_concept_belongs_to_another_study() throws Exception {
        long id1 = 1L;
        String studyId = "phs001211";

        Path mappingCsv = writeMappingCsv("100," + id1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv(
                "\"100\",\"µphs001211µageµ\",\"42\",\"\",\"0\"",
                "\"100\",\"µphs000280µbmiµ\",\"27\",\"\",\"0\"",
                "\"100\",\"µphs000280µsexµ\",\"\",\"M\",\"0\"",
                "\"100\",\"µDCC Harmonized data setµheightµ\",\"170\",\"\",\"0\"",
                // An unmapped id with a foreign concept counts as foreign, not unmapped.
                "\"999\",\"µphs000280µbmiµ\",\"30\",\"\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU")));

        Path outputDir = tempDir.resolve("output");
        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "ARIC",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", outputDir.toString()), "test-foreign");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(Files.readAllLines(outputDir.resolve("phs001211/allConcepts/c1/phs001211_allConcepts_c1.csv")))
                .containsExactly("\"1\",\"µphs001211µageµ\",\"42\",\"\",\"0\"");
        assertThat(result.getMetrics())
                .containsEntry("totalRows", 5L)
                .containsEntry("foreignConceptRows", 4L)
                .containsEntry("unmappedIds", 0L)
                .containsEntry("rowsPerConsent", Map.of("1", 1L));
        assertThat(result.getMetrics().get("foreignFirstNodes"))
                .isEqualTo(Map.of("phs000280", 3L, "DCC Harmonized data set", 1L));
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("FOREIGN_CONCEPTS")
                        && i.message().startsWith("4 row(s) (80.0%)")
                        && i.context().get("count").equals(4L)
                        && ((List<?>) i.context().get("sampleConceptPaths")).size() == 3);
    }

    @Test
    void fails_when_every_row_belongs_to_another_study() throws Exception {
        long id1 = 1L;
        String studyId = "phs001211";

        Path mappingCsv = writeMappingCsv("100," + id1 + ",S1");
        Path allConceptsCsv = writeAllConceptsCsv("\"100\",\"µphs000280µbmiµ\",\"27\",\"\",\"0\"");

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(id1, studyId, "1", "GRU")));

        JobResult result = executor.run(job, Map.of(
                "study-id", studyId,
                "abbreviation", "ARIC",
                "input", allConceptsCsv.toString(),
                "mapping", mappingCsv.toString(),
                "output", tempDir.resolve("output").toString()), "test-all-foreign");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("NO_OUTPUT"))
                .anyMatch(i -> i.code().equals("FOREIGN_CONCEPTS"));
    }

    @Test
    void first_node_follows_the_paths_own_delimiter() {
        assertThat(SplitAllConceptsJob.firstNode("µphs000123µageµ")).isEqualTo("phs000123");
        assertThat(SplitAllConceptsJob.firstNode("\\phs000123\\age\\")).isEqualTo("phs000123");
        assertThat(SplitAllConceptsJob.firstNode("/phs000123/age/")).isEqualTo("phs000123");
        assertThat(SplitAllConceptsJob.firstNode("µphs000123")).isEqualTo("phs000123");
        // A µ path is split only on µ: a backslash inside a node is part of it.
        assertThat(SplitAllConceptsJob.firstNode("µa\\bµcµ")).isEqualTo("a\\b");
        // No leading delimiter: no first node, so it can match no study.
        assertThat(SplitAllConceptsJob.firstNode("phs000123µageµ")).isEqualTo("phs000123µageµ");
        assertThat(SplitAllConceptsJob.firstNode("")).isEmpty();
    }

    @Test
    void only_an_exact_first_node_belongs_to_the_study() {
        // A longer id or a versioned id is another node, not this study's.
        assertThat(SplitAllConceptsJob.firstNode("µphs0001234µxµ")).isNotEqualTo("phs000123");
        assertThat(SplitAllConceptsJob.firstNode("µphs000123.v1µxµ")).isNotEqualTo("phs000123");
    }

    @Test
    void reports_the_largest_foreign_nodes_and_sums_the_rest() {
        Map<String, Long> counts = new java.util.HashMap<>();
        for (int i = 0; i < SplitAllConceptsJob.FOREIGN_NODES_REPORTED + 2; i++) {
            counts.put("n" + i, (long) i + 1);
        }

        Map<String, Long> top = SplitAllConceptsJob.topCounts(counts);

        assertThat(top).hasSize(SplitAllConceptsJob.FOREIGN_NODES_REPORTED + 1);
        assertThat(top.keySet().iterator().next()).isEqualTo("n" + (SplitAllConceptsJob.FOREIGN_NODES_REPORTED + 1));
        assertThat(top).containsEntry(SplitAllConceptsJob.OTHER_NODES, 1L + 2L);
    }
}
