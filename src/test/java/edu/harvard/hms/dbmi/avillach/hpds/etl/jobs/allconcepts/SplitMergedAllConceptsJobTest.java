package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.fencemapping.FenceMappingValidator;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static edu.harvard.hms.dbmi.avillach.hpds.etl.support.FenceMappingFixtures.file;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.support.FenceMappingFixtures.legacyEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class SplitMergedAllConceptsJobTest {

    private static final String CONSENTS = "\\_consents\\";

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private SplitMergedAllConceptsJob job;
    private JobExecutor executor;
    private Path out;

    @BeforeEach
    void setUp() throws Exception {
        job = new SplitMergedAllConceptsJob(new IoResolver(null), new DelimitedReader(),
                new FenceMappingValidator(mapper), mapper);
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(tempDir.resolve("reports").toString());
        Files.createDirectories(tempDir.resolve("reports"));
        executor = new JobExecutor(new ReportWriter(mapper), properties);
        out = tempDir.resolve("out");
    }

    // ------------------------------------------------------------ fixtures

    /** One quoted allConcepts row. */
    private static String row(int patient, String path, String nval, String tval, String ts) {
        return q(String.valueOf(patient)) + "," + q(path) + "," + q(nval) + "," + q(tval) + "," + q(ts);
    }

    private static String q(String v) {
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String consent(int patient, String value) {
        return row(patient, CONSENTS, "", value, "1700000000000");
    }

    private Path input(String... lines) throws IOException {
        Path f = tempDir.resolve("merged_" + System.nanoTime() + ".csv");
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return f;
    }

    private JobResult run(Path input, String... extra) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("input", input.toString());
        params.put("output", out.toString());
        params.put("fence-mapping", "none");
        for (int i = 0; i < extra.length; i += 2) {
            params.put(extra[i], extra[i + 1]);
        }
        return executor.run(job, params, "test-split");
    }

    private List<String> lines(String partition) throws IOException {
        return Files.readAllLines(out.resolve(partition + "-allConcepts.csv"), StandardCharsets.UTF_8);
    }

    private JsonNode manifest() throws IOException {
        return mapper.readTree(out.resolve("split_manifest.json").toFile());
    }

    private static boolean hasIssue(List<ValidationIssue> issues, String code) {
        return issues.stream().anyMatch(i -> i.code().equals(code));
    }

    private List<String> outputFiles() throws IOException {
        if (!Files.exists(out)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(out)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Patients: 1 -> phs123456.c1, 2 -> phs123456.c2, 3 -> both c1 and c2, 4 -> phs999999.c1. */
    private Path standardInput() throws IOException {
        return input(
                consent(1, "phs123456.c1"),
                consent(2, "phs123456.c2"),
                consent(3, "phs123456.c1"),
                consent(3, "phs123456.c2"),
                consent(4, "phs999999.c1"),
                row(1, "\\phs123456\\pht1\\var1\\", "5", "", "1700000000000"),
                row(2, "\\phs123456\\pht1\\var1\\", "6", "", "1700000000000"),
                row(3, "\\phs123456\\pht1\\var1\\", "7", "", "1700000000000"),
                row(4, "\\phs999999\\pht2\\var2\\", "", "yes", "1700000000000"));
    }

    // ------------------------------------------------------------ splitting

    @Test
    void depth_one_splits_by_study_node_into_each_patients_consent_partitions() throws Exception {
        JobResult r = run(standardInput());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        // c1: patients 1 and 3; c2: patients 2 and 3 (multi-consent patient 3 appears in both)
        assertThat(lines("phs123456.c1")).containsExactly(
                row(1, "\\phs123456\\pht1\\var1\\", "5", "", "1700000000000"),
                row(3, "\\phs123456\\pht1\\var1\\", "7", "", "1700000000000"));
        assertThat(lines("phs123456.c2")).containsExactly(
                row(2, "\\phs123456\\pht1\\var1\\", "6", "", "1700000000000"),
                row(3, "\\phs123456\\pht1\\var1\\", "7", "", "1700000000000"));
        assertThat(lines("phs999999.c1")).containsExactly(
                row(4, "\\phs999999\\pht2\\var2\\", "", "yes", "1700000000000"));
    }

    @Test
    void depth_two_takes_the_node_one_level_down() throws Exception {
        Path in = input(
                consent(1, "phs123456.c1"),
                row(1, "\\Parent\\phs123456\\pht1\\var1\\", "5", "", ""),
                row(1, "\\phs123456\\pht1\\var1\\", "6", "", ""));

        // At depth 2 the first data row's node is the study; in the second row it is "pht1", not a study,
        // so that row is a global variable. by-patient is used because the _consents row itself has no
        // segment at depth 2 (an empty global name, which is an error in own mode).
        JobResult r = run(in, "depth", "2", "globals", "by-patient");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(lines("phs123456.c1")).containsExactly(
                consent(1, "phs123456.c1"),
                row(1, "\\Parent\\phs123456\\pht1\\var1\\", "5", "", ""),
                row(1, "\\phs123456\\pht1\\var1\\", "6", "", ""));
        assertThat(manifest().at("/rows/study").asLong()).isEqualTo(1);
        assertThat(manifest().at("/rows/global").asLong()).isEqualTo(2);
        assertThat(manifest().at("/depth").asInt()).isEqualTo(2);
    }

    @Test
    void depth_two_in_own_mode_fails_on_the_consents_row_which_has_no_node_at_that_depth() throws Exception {
        JobResult r = run(input(consent(1, "phs123456.c1"), row(1, "\\Parent\\phs123456\\v\\", "5", "", "")),
                "depth", "2");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "EMPTY_GLOBAL_NAME")).isTrue();
    }

    @Test
    void depth_zero_makes_everything_a_global_with_an_empty_name_which_is_an_error_in_own_mode() throws Exception {
        JobResult r = run(standardInput(), "depth", "0");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "EMPTY_GLOBAL_NAME")).isTrue();
    }

    @Test
    void mu_delimited_paths_split_exactly_like_backslash_paths_and_rows_stay_unchanged() throws Exception {
        Path in = input(
                row(1, "µ_consentsµ", "", "phs123456.c1", ""),
                row(1, "µphs123456µpht1µvar1µ", "5", "", ""),
                row(1, "\\phs123456\\pht1\\var2\\", "6", "", ""));

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(lines("phs123456.c1")).containsExactly(
                row(1, "µphs123456µpht1µvar1µ", "5", "", ""),
                row(1, "\\phs123456\\pht1\\var2\\", "6", "", ""));
        // the µ-style consents row was recognised as the consents variable and kept as a global
        assertThat(lines("_consents")).containsExactly(row(1, "µ_consentsµ", "", "phs123456.c1", ""));
    }

    @Test
    void a_study_patient_without_a_consent_for_that_study_is_dropped_and_counted() throws Exception {
        Path in = input(
                consent(1, "phs123456.c1"),
                consent(2, "phs999999.c1"),
                row(1, "\\phs123456\\a\\", "1", "", ""),
                row(2, "\\phs123456\\a\\", "2", "", ""),
                row(5, "\\phs123456\\a\\", "3", "", ""));

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(lines("phs123456.c1")).hasSize(1);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "NO_CONSENT")).isTrue();
        assertThat(manifest().at("/rows/dropped/studyNoConsent").asLong()).isEqualTo(2);
        assertThat(manifest().at("/rows/balanced").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------ globals

    private Path inputWithGlobals() throws IOException {
        return input(
                consent(1, "phs123456.c1"),
                consent(2, "phs123456.c2"),
                consent(2, "phs999999.c1"),
                row(1, "\\phs123456\\a\\", "1", "", ""),
                row(1, "\\DCC Harmonized data set\\age\\", "30", "", ""),
                row(2, "\\DCC Harmonized data set\\age\\", "40", "", ""),
                row(2, "\\pheno-sub-set\\x\\", "", "z", ""),
                row(9, "\\DCC Harmonized data set\\age\\", "50", "", ""));
    }

    @Test
    void globals_own_gives_each_node_its_own_partition_with_dashes_replaced() throws Exception {
        JobResult r = run(inputWithGlobals());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(outputFiles()).containsExactly(
                "DCC Harmonized data set-allConcepts.csv", "_consents-allConcepts.csv", "pheno_sub_set-allConcepts.csv",
                "phs123456.c1-allConcepts.csv", "split_manifest.json");
        assertThat(lines("DCC Harmonized data set")).hasSize(3); // includes patient 9, who has no consents
        assertThat(lines("pheno_sub_set")).containsExactly(row(2, "\\pheno-sub-set\\x\\", "", "z", ""));
        assertThat(lines("_consents")).hasSize(3);
        JsonNode m = manifest();
        assertThat(m.at("/globals").asText()).isEqualTo("own");
        assertThat(partition(m, "pheno_sub_set").get("kind").asText()).isEqualTo("global");
        assertThat(partition(m, "phs123456.c1").get("kind").asText()).isEqualTo("study");
        assertThat(m.at("/rows/balanced").asBoolean()).isTrue();
        assertThat(m.at("/rows/extraCopies").asLong()).isZero();
    }

    @Test
    void globals_by_patient_copies_global_rows_into_each_of_the_patients_consent_partitions() throws Exception {
        JobResult r = run(inputWithGlobals(), "globals", "by-patient");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS); // patient 9 has no consents
        assertThat(outputFiles()).containsExactly(
                "phs123456.c1-allConcepts.csv", "phs123456.c2-allConcepts.csv", "phs999999.c1-allConcepts.csv",
                "split_manifest.json");
        // patient 1 -> c1 only
        assertThat(lines("phs123456.c1")).contains(row(1, "\\DCC Harmonized data set\\age\\", "30", "", ""))
                .doesNotContain(row(2, "\\DCC Harmonized data set\\age\\", "40", "", ""));
        // patient 2 has two consents (phs123456.c2, phs999999.c1): both get both global rows
        // (the _consents rows are global rows too, so both of patient 2's consent rows are copied)
        assertThat(lines("phs123456.c2")).containsExactly(consent(2, "phs123456.c2"), consent(2, "phs999999.c1"),
                row(2, "\\DCC Harmonized data set\\age\\", "40", "", ""),
                row(2, "\\pheno-sub-set\\x\\", "", "z", ""));
        assertThat(lines("phs999999.c1")).containsExactly(consent(2, "phs123456.c2"), consent(2, "phs999999.c1"),
                row(2, "\\DCC Harmonized data set\\age\\", "40", "", ""),
                row(2, "\\pheno-sub-set\\x\\", "", "z", ""));
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "NO_CONSENT_GLOBAL")).isTrue();

        JsonNode rows = manifest().get("rows");
        assertThat(rows.get("input").asLong()).isEqualTo(8);
        assertThat(rows.get("study").asLong()).isEqualTo(1);
        // _consents x3, DCC x2 (patient 9 dropped), pheno-sub-set x1 = 6 global source rows
        assertThat(rows.get("global").asLong()).isEqualTo(6);
        assertThat(rows.at("/dropped/globalNoConsent").asLong()).isEqualTo(1);
        assertThat(rows.get("balanced").asBoolean()).isTrue();
        // lines in all partition files, from the manifest, must equal the rows.partitionLines the job reports
        long partitionLines = 0;
        for (JsonNode p : manifest().get("partitions")) {
            partitionLines += p.get("rows").asLong();
        }
        assertThat(rows.get("partitionLines").asLong()).isEqualTo(partitionLines);
        assertThat(rows.get("extraCopies").asLong()).isEqualTo(partitionLines - 1 - 6).isPositive();
    }

    @Test
    void a_node_that_looks_like_a_study_but_has_no_consents_is_flagged() throws Exception {
        Path in = input(
                consent(1, "phs123456.c1"),
                row(1, "\\phs999999\\a\\", "1", "", ""));

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(r.getOutputValidation().getIssues()).anyMatch(i -> i.code().equals("STUDY_NODE_WITHOUT_CONSENTS")
                && i.message().contains("phs999999"));
        assertThat(lines("phs999999")).hasSize(1);
        assertThat(manifest().get("studyNodesWithoutConsents")).extracting(JsonNode::asText).containsExactly("phs999999");
    }

    @Test
    void a_study_looking_node_without_consents_is_flagged_in_by_patient_mode_too() throws Exception {
        Path in = input(
                consent(1, "phs123456.c1"),
                row(1, "\\phs123456\\a\\", "1", "", ""),
                row(1, "\\phs999999\\a\\", "2", "", ""));

        JobResult r = run(in, "globals", "by-patient");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(r.getOutputValidation().getIssues()).anyMatch(i -> i.code().equals("STUDY_NODE_WITHOUT_CONSENTS")
                && i.message().contains("phs999999") && i.message().contains("by-patient"));
        assertThat(manifest().get("studyNodesWithoutConsents")).extracting(JsonNode::asText).containsExactly("phs999999");
        // handled as a global: copied into the patient's partition
        assertThat(lines("phs123456.c1")).contains(row(1, "\\phs999999\\a\\", "2", "", ""));
    }

    // ------------------------------------------------------------ CSVLoader contract

    @Test
    void a_leading_header_row_is_dropped_and_not_counted() throws Exception {
        Path in = input(
                "\"PATIENT_NUM\",\"CONCEPT_PATH\",\"NVAL_NUM\",\"TVAL_CHAR\",\"TIMESTAMP\"",
                consent(1, "phs123456.c1"),
                row(1, "\\phs123456\\a\\", "1", "", ""));

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(manifest().at("/rows/input").asLong()).isEqualTo(2);
        assertThat(manifest().get("headerRowDropped").asBoolean()).isTrue();
        assertThat(r.getOutputValidation().getIssues()).anyMatch(i -> i.code().equals("HEADER_DROPPED"));
        assertThat(lines("phs123456.c1")).noneMatch(l -> l.contains("PATIENT_NUM"));
    }

    @Test
    void a_non_integer_patient_after_the_first_row_fails_the_run_and_writes_nothing() throws Exception {
        Path in = input(
                consent(1, "phs123456.c1"),
                row(1, "\\phs123456\\a\\", "1", "", ""),
                "\"abc\",\"\\phs123456\\a\\\",\"1\",\"\",\"\"");

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(r.getOutputValidation().getIssues()).anyMatch(i -> i.code().equals("BAD_PATIENT_NUM")
                && i.message().contains("row 3"));
        assertThat(outputFiles()).isEmpty();
    }

    @Test
    void a_row_without_five_fields_fails() throws Exception {
        Path in = input(consent(1, "phs123456.c1"), "\"1\",\"\\phs123456\\a\\\",\"1\",\"\"");

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "BAD_FIELD_COUNT")).isTrue();
    }

    @Test
    void a_timestamp_that_is_not_an_integer_fails() throws Exception {
        for (String bad : List.of("12.5", "abc", "1e9", "2020-01-01", "- 5")) {
            Path in = input(consent(1, "phs123456.c1"), row(1, "\\phs123456\\a\\", "1", "", bad));
            JobResult r = run(in, "force", "true");
            assertThat(r.getExitCode()).as("timestamp '%s'", bad).isEqualTo(ExitCode.VALIDATION_FAILED);
            assertThat(hasIssue(r.getOutputValidation().getIssues(), "BAD_TIMESTAMP")).isTrue();
        }
    }

    @Test
    void negative_and_blank_timestamps_are_accepted() throws Exception {
        Path in = input(consent(1, "phs123456.c1"),
                row(1, "\\phs123456\\a\\", "1", "", "-5"), row(1, "\\phs123456\\b\\", "1", "", ""));

        assertThat(run(in).getExitCode()).isEqualTo(ExitCode.SUCCESS);
    }

    @Test
    void nval_follows_csvloader_so_padded_nan_and_infinity_pass() throws Exception {
        Path in = input(consent(1, "phs123456.c1"),
                row(1, "\\phs123456\\a\\", " 5 ", "", ""),
                row(1, "\\phs123456\\b\\", "NaN", "", ""),
                row(1, "\\phs123456\\c\\", "Infinity", "", ""),
                row(1, "\\phs123456\\d\\", "-1.5e3", "", ""));

        assertThat(run(in).getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(lines("phs123456.c1")).hasSize(4);
    }

    @Test
    void a_non_numeric_nval_and_an_empty_concept_path_fail() throws Exception {
        Path in = input(consent(1, "phs123456.c1"),
                row(1, "\\phs123456\\a\\", "1,5", "", ""),
                row(1, "", "1", "", ""));

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "BAD_NVAL")).isTrue();
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "EMPTY_CONCEPT_PATH")).isTrue();
    }

    @Test
    void timestamps_of_zero_and_likely_seconds_are_reported() throws Exception {
        Path in = input(consent(1, "phs123456.c1"),
                row(1, "\\phs123456\\a\\", "1", "", "0"),
                row(1, "\\phs123456\\b\\", "1", "", "1700000000"),
                row(1, "\\phs123456\\c\\", "1", "", "1700000000000"));

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(manifest().at("/timestamps/zero").asLong()).isEqualTo(1);
        assertThat(manifest().at("/timestamps/likelySeconds").asLong()).isEqualTo(1);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "TIMESTAMP_ZERO")).isTrue();
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "TIMESTAMP_LIKELY_SECONDS")).isTrue();
    }

    @Test
    void a_consent_value_that_cannot_name_a_partition_fails() throws Exception {
        Path in = input(consent(1, "phs123456-c1"), row(1, "\\phs123456\\a\\", "1", "", ""));

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "INVALID_CONSENT_VALUE")).isTrue();
    }

    // ------------------------------------------------------------ output format

    @Test
    void fields_with_quotes_commas_and_newlines_round_trip_and_every_field_is_quoted() throws Exception {
        String tricky = "He said \"hi\", then\nleft";
        Path in = input(consent(1, "phs123456.c1"), row(1, "\\phs123456\\a, b\\", "", tricky, ""));

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        String content = Files.readString(out.resolve("phs123456.c1-allConcepts.csv"), StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("\"1\",\"\\phs123456\\a, b\\\",\"\",\"He said \"\"hi\"\", then\nleft\",\"\"\n");
        assertThat(content).doesNotContain("\r");
        List<List<String>> parsed;
        try (Stream<List<String>> rows = new DelimitedReader()
                .streamRows(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), ',')) {
            parsed = rows.toList();
        }
        assertThat(parsed).containsExactly(List.of("1", "\\phs123456\\a, b\\", "", tricky, ""));
    }

    @Test
    void rows_keep_input_order_within_a_partition_and_blank_lines_are_ignored() throws Exception {
        Path in = input(consent(1, "phs123456.c1"), "", row(1, "\\phs123456\\z\\", "1", "", ""),
                row(1, "\\phs123456\\a\\", "2", "", ""), "");

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(lines("phs123456.c1")).containsExactly(
                row(1, "\\phs123456\\z\\", "1", "", ""), row(1, "\\phs123456\\a\\", "2", "", ""));
        assertThat(manifest().at("/rows/input").asLong()).isEqualTo(3);
    }

    @Test
    void the_manifest_accounts_for_every_input_row_and_counts_patients() throws Exception {
        JobResult r = run(standardInput());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        JsonNode m = manifest();
        JsonNode rows = m.get("rows");
        long dropped = rows.at("/dropped/studyNoConsent").asLong() + rows.at("/dropped/globalNoConsent").asLong();
        assertThat(rows.get("input").asLong()).isEqualTo(9)
                .isEqualTo(rows.get("study").asLong() + rows.get("global").asLong() + dropped);
        assertThat(rows.get("balanced").asBoolean()).isTrue();
        // patient 3 is in two partitions, so partition lines exceed the source rows by one
        assertThat(rows.get("extraCopies").asLong()).isEqualTo(1);
        assertThat(partition(m, "phs123456.c1").get("patients").asLong()).isEqualTo(2);
        assertThat(partition(m, "phs123456.c1").get("rows").asLong()).isEqualTo(2);
        assertThat(partition(m, "phs123456.c1").get("file").asText()).isEqualTo("phs123456.c1-allConcepts.csv");
        assertThat(m.get("depth").asInt()).isEqualTo(1);
        assertThat(m.get("input").get("uri").asText()).endsWith(".csv");
        assertThat(m.get("patientsWithConsents").asLong()).isEqualTo(4);
        assertThat(r.getMetrics()).containsEntry("balanced", true).containsEntry("totalRows", 9L);
    }

    // ------------------------------------------------------------ output safety

    @Test
    void output_under_the_legacy_bucket_mirror_is_refused() throws Exception {
        for (String bad : List.of(
                "s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/data/out/",
                "s3://avillach-73-bdcatalyst-etl/__split__/",
                "/mnt/avillach-73-bdcatalyst-etl/x")) {
            JobResult r = executor.run(job, Map.of("input", standardInput().toString(), "output", bad,
                    "fence-mapping", "none", "force", "true"), "t");
            assertThat(r.getExitCode()).as(bad).isEqualTo(ExitCode.VALIDATION_FAILED);
            assertThat(hasIssue(r.getInputValidation().getIssues(), "OUTPUT_UNDER_MIRROR")).as(bad).isTrue();
        }
    }

    @Test
    void a_non_empty_output_is_refused_without_force_and_nothing_is_deleted() throws Exception {
        Files.createDirectories(out);
        Path existing = out.resolve("keep-me.txt");
        Files.writeString(existing, "x");

        JobResult refused = run(standardInput());

        assertThat(refused.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(refused.getInputValidation().getIssues(), "OUTPUT_NOT_EMPTY")).isTrue();
        assertThat(outputFiles()).containsExactly("keep-me.txt");

        JobResult forced = run(standardInput(), "force", "true");

        assertThat(forced.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(existing).exists();
        assertThat(out.resolve("phs123456.c1-allConcepts.csv")).exists();
    }

    @Test
    void bad_parameters_fail_input_validation() throws Exception {
        Path in = standardInput();

        assertThat(hasIssue(run(in, "depth", "-1").getInputValidation().getIssues(), "INVALID_DEPTH")).isTrue();
        assertThat(hasIssue(run(in, "depth", "x").getInputValidation().getIssues(), "INVALID_DEPTH")).isTrue();
        assertThat(hasIssue(run(in, "globals", "both").getInputValidation().getIssues(), "INVALID_GLOBALS")).isTrue();
        assertThat(hasIssue(run(in, "force", "treu").getInputValidation().getIssues(), "INVALID_FORCE")).isTrue();
        assertThat(executor.run(job, Map.of("output", out.toString()), "t").getExitCode())
                .isEqualTo(ExitCode.VALIDATION_FAILED);
    }

    // ------------------------------------------------------------ fence mapping cross-check

    @Test
    void the_fence_cross_check_reports_but_never_fails_the_run() throws Exception {
        Path fence = tempDir.resolve("fence.json");
        Files.writeString(fence, file(
                legacyEntry("phs123456", "c1", "parent", "/a"),
                legacyEntry("phs123456", "c2", "parent", "/b"),
                legacyEntry("phs999999", "c9", "parent", "/c")));

        JobResult r = run(standardInput(), "fence-mapping", fence.toString());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        List<ValidationIssue> issues = r.getOutputValidation().getIssues();
        assertThat(issues).anyMatch(i -> i.code().equals("FENCE_PARTITION_UNREACHABLE")
                && i.message().contains("phs999999.c1"));
        assertThat(issues).anyMatch(i -> i.code().equals("FENCE_GLOBAL_NOT_GRANTED")
                && i.message().contains("_consents"));
        assertThat(issues).anyMatch(i -> i.code().equals("FENCE_ENTRY_WITHOUT_PARTITION")
                && i.message().contains("phs999999.c9"));
    }

    @Test
    void an_invalid_fence_mapping_downgrades_its_errors_to_warnings() throws Exception {
        Path fence = tempDir.resolve("fence.json");
        Files.writeString(fence, file(
                legacyEntry("phs123456", "c1", "parent", "/a"),
                legacyEntry("phs123456", "c1", "parent", "/a")));

        JobResult r = run(standardInput(), "fence-mapping", fence.toString());

        assertThat(r.getExitCode().isSuccess()).isTrue();
        assertThat(r.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("FENCE_DUPLICATE_KEY") && i.severity().name().equals("WARNING"));
        assertThat(r.getOutputValidation().hasErrors()).isFalse();
    }

    @Test
    void an_unreadable_fence_mapping_is_a_warning_not_a_failure() throws Exception {
        JobResult r = run(standardInput(), "fence-mapping", tempDir.resolve("missing.json").toString());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "FENCE_MAPPING_UNREADABLE")).isTrue();
        assertThat(out.resolve("split_manifest.json")).exists();
    }

    // ------------------------------------------------------------ many partitions / pinning

    @Test
    void two_hundred_interleaved_partitions_keep_row_order_despite_writer_eviction() throws Exception {
        int partitions = 200;
        int rounds = 5;
        List<String> lines = new ArrayList<>();
        for (int p = 1; p <= partitions; p++) {
            lines.add(consent(p, "phs123456.c" + p));
        }
        for (int r = 1; r <= rounds; r++) {
            for (int p = 1; p <= partitions; p++) {
                lines.add(row(p, "\\phs123456\\v" + r + "\\", String.valueOf(r), "", ""));
            }
        }

        JobResult result = run(input(lines.toArray(String[]::new)));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        for (int p = 1; p <= partitions; p++) {
            List<String> expected = new ArrayList<>();
            for (int r = 1; r <= rounds; r++) {
                expected.add(row(p, "\\phs123456\\v" + r + "\\", String.valueOf(r), "", ""));
            }
            assertThat(lines("phs123456.c" + p)).as("partition c%d", p).containsExactlyElementsOf(expected);
        }
        assertThat(manifest().get("partitions")).hasSize(partitions + 1); // + the _consents global
        assertThat(manifest().at("/rows/balanced").asBoolean()).isTrue();
    }

    @Test
    void the_manifest_records_the_pinned_local_input_identity() throws Exception {
        Path in = standardInput();

        run(in);

        JsonNode input = manifest().get("input");
        assertThat(input.get("size").asLong()).isEqualTo(Files.size(in));
        assertThat(input.get("lastModifiedMillis").asLong()).isEqualTo(Files.getLastModifiedTime(in).toMillis());
        assertThat(input.get("versionId").isNull()).isTrue();
    }

    /** An IoResolver that rewrites the input just before a chosen read, as a concurrent upload would. */
    private static final class ChangingIo extends IoResolver {
        private final int changeBeforeRead;
        private final Path file;
        private int reads;

        ChangingIo(Path file, int changeBeforeRead) {
            super(null);
            this.file = file;
            this.changeBeforeRead = changeBeforeRead;
        }

        @Override
        public InputStream openInput(String uri, ObjectInfo pin) {
            if (++reads == changeBeforeRead) {
                try {
                    Files.writeString(file, Files.readString(file) + "\"9\",\"\\x\\\",\"\",\"\",\"\"\n");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return super.openInput(uri, pin);
        }
    }

    @Test
    void an_input_replaced_before_either_pass_fails_with_nothing_uploaded() throws Exception {
        for (int pass : new int[] {1, 2}) {
            Path in = standardInput();
            Path o = tempDir.resolve("pinned-out-" + pass);
            SplitMergedAllConceptsJob changing = new SplitMergedAllConceptsJob(new ChangingIo(in, pass),
                    new DelimitedReader(), new FenceMappingValidator(mapper), mapper);

            JobResult r = executor.run(changing, Map.of("input", in.toString(), "output", o.toString(),
                    "fence-mapping", "none"), "t");

            assertThat(r.getExitCode()).as("changed before read %d", pass).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
            assertThat(r.getErrorMessage()).contains("Input changed");
            assertThat(o).as("nothing uploaded").doesNotExist();
        }
    }

    // ------------------------------------------------------------ opt-in repairs

    private static final String EMBEDDED_HEADER_ROW = "\"subject_id\",\"concept_path\",\"nval_num\",\"tval_char\",\"timestamp\"";

    /** A rejects-file line: the reason, the 1-based row number, then the original row. */
    private static String rejectLine(String reason, int rowNum, String originalRow) {
        return q(reason) + "," + q(String.valueOf(rowNum)) + "," + originalRow;
    }

    /** The manifest's repairs.timestampsConvertedByForm, in file order. */
    private Map<String, Long> byForm() throws IOException {
        Map<String, Long> m = new LinkedHashMap<>();
        manifest().at("/repairs/timestampsConvertedByForm").fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asLong()));
        return m;
    }

    private List<String> rejectLines() throws IOException {
        return Files.readAllLines(out.resolve("rejects").resolve("rejected-rows.csv"), StandardCharsets.UTF_8);
    }

    /**
     * Rows 1-8. Row 4 is an embedded header, 5 a bad nval, 6 and 7 belong to a consent whose name has a '-'
     * (made-up study), 8 has an ISO timestamp. 4 of 8 rows are rejected.
     */
    private Path messyInput() throws IOException {
        return input(
                consent(1, "phs123456.c1"),
                consent(2, "tutorial-demo_one"),
                row(1, "\\phs123456\\a\\", "5", "", "1700000000000"),
                EMBEDDED_HEADER_ROW,
                row(1, "\\phs123456\\b\\", "2402L", "", ""),
                row(2, "\\tutorial-demo_one\\x\\", "1", "", ""),
                row(2, "\\tutorial-demo_one\\y\\", "", "v", ""),
                row(1, "\\phs123456\\c\\", "7", "", "2023-06-06T15:20:00+00:00"));
    }

    private JobResult runRepaired(Path in, String... extra) {
        List<String> params = new ArrayList<>(List.of("timestamps", "convert-iso", "bad-rows", "quarantine",
                "max-reject-fraction", "0.6"));
        params.addAll(List.of(extra));
        return run(in, params.toArray(String[]::new));
    }

    @Test
    void by_default_an_iso_timestamp_is_still_an_error_and_nothing_is_written() throws Exception {
        Path in = input(consent(1, "phs123456.c1"), row(1, "\\phs123456\\a\\", "1", "", "2023-06-06T15:20:00+00:00"));

        JobResult r = run(in);

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "BAD_TIMESTAMP")).isTrue();
        assertThat(outputFiles()).isEmpty();
    }

    @Test
    void by_default_an_embedded_header_is_still_a_bad_patient_num_and_quarantine_alone_does_not_convert() throws Exception {
        Path in = input(consent(1, "phs123456.c1"), EMBEDDED_HEADER_ROW,
                row(1, "\\phs123456\\a\\", "1", "", "2023-06-06T15:20:00Z"));

        JobResult strict = run(in);
        assertThat(strict.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(strict.getOutputValidation().getIssues(), "BAD_PATIENT_NUM")).isTrue();
        assertThat(outputFiles()).isEmpty();

        // quarantine without convert-iso: the ISO row is quarantined as BAD_TIMESTAMP, not converted
        JobResult q = run(in, "bad-rows", "quarantine", "max-reject-fraction", "1");
        assertThat(q.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(manifest().at("/repairs/rejected/byReason/BAD_TIMESTAMP").asLong()).isEqualTo(1);
        assertThat(manifest().at("/repairs/timestampsConverted").asLong()).isZero();
    }

    @Test
    void convert_iso_rewrites_timestamps_with_an_offset_to_epoch_millis() throws Exception {
        Path in = input(consent(1, "phs123456.c1"),
                row(1, "\\phs123456\\a\\", "1", "", "2023-06-06T15:20:00+00:00"),
                row(1, "\\phs123456\\b\\", "1", "", "2023-06-06T15:20:00Z"),
                row(1, "\\phs123456\\c\\", "1", "", "2023-06-06T15:20:00.123Z"),
                row(1, "\\phs123456\\d\\", "1", "", "2023-06-06T17:20:00+02:00"),
                row(1, "\\phs123456\\e\\", "1", "", "1700000000000"),
                row(1, "\\phs123456\\f\\", "1", "", "0"));

        JobResult r = run(in, "timestamps", "convert-iso");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(lines("phs123456.c1")).containsExactly(
                row(1, "\\phs123456\\a\\", "1", "", "1686064800000"),
                row(1, "\\phs123456\\b\\", "1", "", "1686064800000"),
                row(1, "\\phs123456\\c\\", "1", "", "1686064800123"),
                row(1, "\\phs123456\\d\\", "1", "", "1686064800000"),
                row(1, "\\phs123456\\e\\", "1", "", "1700000000000"),
                // integers, 0 included, are CSVLoader's own contract and stay as they are
                row(1, "\\phs123456\\f\\", "1", "", "0"));
        assertThat(manifest().at("/repairs/timestamps").asText()).isEqualTo("convert-iso");
        assertThat(manifest().at("/repairs/badRows").asText()).isEqualTo("fail");
        assertThat(manifest().at("/repairs/timestampsConverted").asLong()).isEqualTo(4);
        assertThat(byForm()).containsExactly(entry("offset", 4L), entry("zoneless-utc", 0L), entry("date-utc", 0L));
        assertThat(manifest().at("/repairs/rejectsFile").isNull()).isTrue();
        List<String> codes = r.getOutputValidation().getIssues().stream().map(ValidationIssue::code).toList();
        assertThat(codes).contains("TIMESTAMPS_CONVERTED").doesNotContain("TIMESTAMPS_ASSUMED_UTC");
        assertThat(r.getMetrics()).containsEntry("timestampsConverted", 4L);
        assertThat(r.getMetrics().get("timestampsConvertedByForm")).isEqualTo(
                Map.of("offset", 4L, "zoneless-utc", 0L, "date-utc", 0L));
        assertThat(outputFiles()).doesNotContain("rejects");
    }

    /** Expected values are those of the ingest-service TimestampParserTest. */
    @Test
    void convert_iso_reads_zoneless_forms_as_utc_and_counts_them_by_form() throws Exception {
        Path in = input(consent(1, "phs123456.c1"),
                row(1, "\\phs123456\\a\\", "1", "", "2023-11-25 14:00:00"),
                row(1, "\\phs123456\\b\\", "1", "", "2024-06-27 00:00:00"),
                row(1, "\\phs123456\\c\\", "1", "", "2020-01-02 03:04:05.678"),
                row(1, "\\phs123456\\d\\", "1", "", "2021-06-07 08:09:10.9"),
                row(1, "\\phs123456\\e\\", "1", "", "2022-03-04 05:06:07.03"),
                row(1, "\\phs123456\\f\\", "1", "", "2025-02-28T12:56:40.500"),
                row(1, "\\phs123456\\g\\", "1", "", "2023-06-06T15:20:00"),
                row(1, "\\phs123456\\h\\", "1", "", "2025-02-28"),
                row(1, "\\phs123456\\i\\", "1", "", "2023-06-06"),
                row(1, "\\phs123456\\j\\", "1", "", "2023-06-06 15:20:00.123456789"),
                row(1, "\\phs123456\\k\\", "1", "", "2021-01-15T10:30:00Z"));

        JobResult r = run(in, "timestamps", "convert-iso");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(lines("phs123456.c1")).containsExactly(
                row(1, "\\phs123456\\a\\", "1", "", "1700920800000"),
                row(1, "\\phs123456\\b\\", "1", "", "1719446400000"),
                row(1, "\\phs123456\\c\\", "1", "", "1577934245678"),
                row(1, "\\phs123456\\d\\", "1", "", "1623053350900"),
                row(1, "\\phs123456\\e\\", "1", "", "1646370367030"),
                row(1, "\\phs123456\\f\\", "1", "", "1740747400500"),
                row(1, "\\phs123456\\g\\", "1", "", "1686064800000"),
                row(1, "\\phs123456\\h\\", "1", "", "1740700800000"),
                row(1, "\\phs123456\\i\\", "1", "", "1686009600000"),
                row(1, "\\phs123456\\j\\", "1", "", "1686064800123"), // sub-millisecond digits truncated
                row(1, "\\phs123456\\k\\", "1", "", "1610706600000"));
        assertThat(manifest().at("/repairs/timestampsConverted").asLong()).isEqualTo(11);
        assertThat(byForm()).containsExactly(entry("offset", 1L), entry("zoneless-utc", 8L), entry("date-utc", 2L));
        assertThat(r.getMetrics()).containsEntry("timestampsConverted", 11L);
        ValidationIssue assumed = r.getOutputValidation().getIssues().stream()
                .filter(i -> i.code().equals("TIMESTAMPS_ASSUMED_UTC")).findFirst().orElseThrow();
        assertThat(assumed.message()).contains("10 timestamp(s)").contains("assumed to be UTC");
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "TIMESTAMPS_CONVERTED")).isTrue();
    }

    @Test
    void convert_iso_still_rejects_what_the_ingest_service_parser_rejects() throws Exception {
        for (String bad : List.of("2023-06-06T15:20:00+0000", "2023-06-06 15:20:00+00:00", "2023-13-06T15:20:00Z",
                "2023-13-45 99:00:00", "2023-02-30", "2023-06-06 15:20", "06/06/2023", "invalid-timestamp", "yesterday")) {
            Path in = input(consent(1, "phs123456.c1"), row(1, "\\phs123456\\a\\", "1", "", bad));
            JobResult r = run(in, "timestamps", "convert-iso", "force", "true");
            assertThat(r.getExitCode()).as("timestamp '%s'", bad).isEqualTo(ExitCode.VALIDATION_FAILED);
            assertThat(hasIssue(r.getOutputValidation().getIssues(), "BAD_TIMESTAMP")).isTrue();
        }
    }

    @Test
    void by_default_zoneless_and_space_separated_timestamps_are_errors() throws Exception {
        for (String bad : List.of("2023-11-25 14:00:00", "2023-06-06T15:20:00", "2023-06-06")) {
            Path in = input(consent(1, "phs123456.c1"), row(1, "\\phs123456\\a\\", "1", "", bad));
            JobResult r = run(in, "force", "true");
            assertThat(r.getExitCode()).as("timestamp '%s'", bad).isEqualTo(ExitCode.VALIDATION_FAILED);
            assertThat(hasIssue(r.getOutputValidation().getIssues(), "BAD_TIMESTAMP")).isTrue();
        }
    }

    @Test
    void quarantine_writes_the_good_rows_and_files_the_rest_under_rejects() throws Exception {
        JobResult r = runRepaired(messyInput());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(lines("phs123456.c1")).containsExactly(
                row(1, "\\phs123456\\a\\", "5", "", "1700000000000"),
                row(1, "\\phs123456\\c\\", "7", "", "1686064800000"));
        assertThat(outputFiles()).containsExactly("_consents-allConcepts.csv", "phs123456.c1-allConcepts.csv", "rejects",
                "split_manifest.json");
        // headerless, every field quoted, reason and row number first; the file is not a partition
        assertThat(rejectLines()).containsExactly(
                rejectLine("EMBEDDED_HEADER", 4, EMBEDDED_HEADER_ROW),
                rejectLine("BAD_NVAL", 5, row(1, "\\phs123456\\b\\", "2402L", "", "")),
                rejectLine("INVALID_CONSENT_VALUE", 6, row(2, "\\tutorial-demo_one\\x\\", "1", "", "")),
                rejectLine("INVALID_CONSENT_VALUE", 7, row(2, "\\tutorial-demo_one\\y\\", "", "v", "")));
        assertThat(out.resolve("rejects").resolve("rejected-rows.csv").getFileName().toString())
                .doesNotEndWith("-allConcepts.csv");

        JsonNode m = manifest();
        JsonNode rows = m.get("rows");
        assertThat(rows.get("input").asLong()).isEqualTo(8);
        assertThat(rows.get("study").asLong()).isEqualTo(2);
        assertThat(rows.get("global").asLong()).isEqualTo(2);
        assertThat(rows.get("rejected").asLong()).isEqualTo(4);
        assertThat(rows.get("accounted").asLong()).isEqualTo(8);
        assertThat(rows.get("balanced").asBoolean()).isTrue();
        JsonNode repairs = m.get("repairs");
        assertThat(repairs.get("timestamps").asText()).isEqualTo("convert-iso");
        assertThat(repairs.get("badRows").asText()).isEqualTo("quarantine");
        assertThat(repairs.get("maxRejectFraction").asDouble()).isEqualTo(0.6);
        assertThat(repairs.get("timestampsConverted").asLong()).isEqualTo(1);
        assertThat(repairs.at("/timestampsConvertedByForm/offset").asLong()).isEqualTo(1);
        assertThat(repairs.at("/timestampsConvertedByForm/zoneless-utc").asLong()).isZero();
        assertThat(repairs.at("/rejected/total").asLong()).isEqualTo(4);
        assertThat(repairs.at("/rejected/byReason/EMBEDDED_HEADER").asLong()).isEqualTo(1);
        assertThat(repairs.at("/rejected/byReason/BAD_NVAL").asLong()).isEqualTo(1);
        assertThat(repairs.at("/rejected/byReason/INVALID_CONSENT_VALUE").asLong()).isEqualTo(2);
        assertThat(repairs.get("droppedConsents")).hasSize(1);
        assertThat(repairs.at("/droppedConsents/0/value").asText()).isEqualTo("tutorial-demo_one");
        assertThat(repairs.at("/droppedConsents/0/rows").asLong()).isEqualTo(2);
        assertThat(repairs.at("/droppedConsents/0/problem").asText()).contains("-");
        assertThat(repairs.get("rejectsFile").asText()).isEqualTo(out.resolve("rejects").resolve("rejected-rows.csv").toString());
        // the dropped consent made no partition, and no rejected row leaked into one
        assertThat(manifest().get("partitions")).extracting(n -> n.get("name").asText())
                .containsExactly("_consents", "phs123456.c1");
        assertThat(Files.readString(out.resolve("phs123456.c1-allConcepts.csv"))).doesNotContain("2402L");

        List<String> codes = r.getOutputValidation().getIssues().stream().map(ValidationIssue::code).toList();
        assertThat(codes).contains("ROWS_QUARANTINED", "CONSENTS_DROPPED", "TIMESTAMPS_CONVERTED");
        assertThat(r.getMetrics()).containsEntry("rejectedRows", 4L).containsEntry("balanced", true);
        assertThat(r.getOutputValidation().hasErrors()).isFalse();
    }

    @Test
    void a_dropped_consent_does_not_stop_the_patients_other_consents_and_counts_each_lost_copy() throws Exception {
        Path in = input(
                consent(1, "phs123456.c1"),
                consent(1, "phs123456.c-9"),
                consent(2, "phs123456.c-9"),
                row(1, "\\phs123456\\a\\", "1", "", ""),
                row(2, "\\phs123456\\a\\", "2", "", ""));

        JobResult r = run(in, "bad-rows", "quarantine", "max-reject-fraction", "0.5");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        // patient 1 still gets the row in c1; patient 2 had only the dropped consent, so its row is rejected
        assertThat(lines("phs123456.c1")).contains(row(1, "\\phs123456\\a\\", "1", "", ""))
                .doesNotContain(row(2, "\\phs123456\\a\\", "2", "", ""));
        assertThat(rejectLines()).containsExactly(rejectLine("INVALID_CONSENT_VALUE", 5, row(2, "\\phs123456\\a\\", "2", "", "")));
        assertThat(manifest().at("/repairs/droppedConsents/0/value").asText()).isEqualTo("phs123456.c-9");
        assertThat(manifest().at("/repairs/droppedConsents/0/rows").asLong()).isEqualTo(2); // one per patient
        assertThat(manifest().at("/rows/balanced").asBoolean()).isTrue();
    }

    @Test
    void a_dropped_consent_in_by_patient_mode_rejects_global_rows_that_had_nowhere_else_to_go() throws Exception {
        Path in = input(
                consent(1, "phs123456.c1"),
                consent(2, "demo-bad"),
                row(1, "\\DCC\\age\\", "30", "", ""),
                row(2, "\\DCC\\age\\", "40", "", ""));

        JobResult r = run(in, "globals", "by-patient", "bad-rows", "quarantine", "max-reject-fraction", "0.5");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(rejectLines()).extracting(l -> l.substring(0, l.indexOf(',')))
                .containsExactly(q("INVALID_CONSENT_VALUE"), q("INVALID_CONSENT_VALUE"));
        assertThat(lines("phs123456.c1")).hasSize(2); // its own _consents row (global) and the age row
        assertThat(manifest().at("/rows/rejected").asLong()).isEqualTo(2); // patient 2's consent row and age row
        assertThat(manifest().at("/rows/balanced").asBoolean()).isTrue();
    }

    @Test
    void a_leading_header_is_dropped_not_rejected_when_quarantining() throws Exception {
        Path in = input(EMBEDDED_HEADER_ROW, consent(1, "phs123456.c1"), row(1, "\\phs123456\\a\\", "1", "", ""));

        JobResult r = run(in, "bad-rows", "quarantine");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(manifest().at("/rows/rejected").asLong()).isZero();
        assertThat(manifest().at("/rows/input").asLong()).isEqualTo(2);
        assertThat(manifest().at("/repairs/rejectsFile").isNull()).isTrue();
        assertThat(out.resolve("rejects")).doesNotExist();
    }

    @Test
    void a_bad_patient_with_a_real_concept_path_is_not_taken_for_a_header() throws Exception {
        Path in = input(consent(1, "phs123456.c1"), row(1, "\\phs123456\\a\\", "1", "", ""),
                "\"abc\",\"\\phs123456\\a\\\",\"1\",\"\",\"\"", "\"1\",\"\\phs123456\\a\\\",\"1\"");

        JobResult r = run(in, "bad-rows", "quarantine", "max-reject-fraction", "0.5");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(manifest().at("/repairs/rejected/byReason/BAD_PATIENT_NUM").asLong()).isEqualTo(1);
        assertThat(manifest().at("/repairs/rejected/byReason/BAD_FIELD_COUNT").asLong()).isEqualTo(1);
        assertThat(manifest().at("/repairs/rejected/byReason/EMBEDDED_HEADER").isMissingNode()).isTrue();
    }

    @Test
    void exceeding_max_reject_fraction_fails_with_nothing_written() throws Exception {
        // default 0.01: 4 of 8 rows rejected
        JobResult r = run(messyInput(), "timestamps", "convert-iso", "bad-rows", "quarantine");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        List<ValidationIssue> issues = r.getOutputValidation().getIssues();
        assertThat(issues).anyMatch(i -> i.code().equals("REJECT_FRACTION_EXCEEDED") && i.message().contains("rejected 2 of 8"));
        assertThat(hasIssue(issues, "NOTHING_WRITTEN")).isTrue();
        assertThat(outputFiles()).isEmpty();
    }

    @Test
    void rejects_from_dropped_consents_count_against_the_fraction_too() throws Exception {
        List<String> lines = new ArrayList<>(List.of(consent(1, "phs123456.c1"), consent(2, "demo-bad")));
        for (int i = 0; i < 100; i++) {
            lines.add(row(1, "\\phs123456\\v" + i + "\\", "1", "", ""));
        }
        lines.add(row(2, "\\demo-bad\\x\\", "1", "", ""));
        lines.add(row(2, "\\demo-bad\\y\\", "1", "", ""));
        Path in = input(lines.toArray(String[]::new));

        JobResult r = run(in, "bad-rows", "quarantine"); // 2 of 104 > 0.01

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(r.getOutputValidation().getIssues()).anyMatch(i -> i.code().equals("REJECT_FRACTION_EXCEEDED")
                && i.message().contains("INVALID_CONSENT_VALUE=2"));
        assertThat(outputFiles()).isEmpty();

        assertThat(run(in, "bad-rows", "quarantine", "max-reject-fraction", "0.02", "force", "true").getExitCode())
                .isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
    }

    @Test
    void a_fraction_of_exactly_the_limit_is_accepted() throws Exception {
        List<String> lines = new ArrayList<>(List.of(consent(1, "phs123456.c1")));
        for (int i = 0; i < 98; i++) {
            lines.add(row(1, "\\phs123456\\v" + i + "\\", "1", "", ""));
        }
        lines.add(row(1, "\\phs123456\\bad\\", "x", "", "")); // 1 of 100 data rows
        Path in = input(lines.toArray(String[]::new));

        assertThat(run(in, "bad-rows", "quarantine").getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
    }

    @Test
    void the_repair_params_are_validated() throws Exception {
        Path in = standardInput();

        for (String bad : List.of("1.5", "-0.1", "abc", "NaN", "Infinity", "0x1p-3", "1,5")) {
            assertThat(hasIssue(run(in, "max-reject-fraction", bad).getInputValidation().getIssues(),
                    "INVALID_MAX_REJECT_FRACTION")).as(bad).isTrue();
        }
        assertThat(hasIssue(run(in, "timestamps", "iso").getInputValidation().getIssues(), "INVALID_TIMESTAMPS")).isTrue();
        assertThat(hasIssue(run(in, "bad-rows", "skip").getInputValidation().getIssues(), "INVALID_BAD_ROWS")).isTrue();
        assertThat(run(in, "bad-rows", "skip").getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(outputFiles()).isEmpty();
        for (String good : List.of("0", "1", "0.5", ".25", "1e-2")) {
            assertThat(hasIssue(run(in, "max-reject-fraction", good, "force", "true").getInputValidation().getIssues(),
                    "INVALID_MAX_REJECT_FRACTION")).as(good).isFalse();
        }
    }

    @Test
    void the_default_run_reports_empty_repairs() throws Exception {
        JobResult r = run(standardInput());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        JsonNode repairs = manifest().get("repairs");
        assertThat(repairs.get("timestamps").asText()).isEqualTo("strict");
        assertThat(repairs.get("badRows").asText()).isEqualTo("fail");
        assertThat(repairs.get("maxRejectFraction").asDouble()).isEqualTo(0.01);
        assertThat(repairs.get("timestampsConverted").asLong()).isZero();
        assertThat(byForm()).containsExactly(entry("offset", 0L), entry("zoneless-utc", 0L), entry("date-utc", 0L));
        assertThat(repairs.at("/rejected/total").asLong()).isZero();
        assertThat(repairs.get("droppedConsents")).isEmpty();
        assertThat(repairs.get("rejectsFile").isNull()).isTrue();
        assertThat(manifest().at("/rows/rejected").asLong()).isZero();
    }

    /** Serves different content on the second read of the input, as if the two passes saw different rows. */
    private static final class ShiftingIo extends IoResolver {
        private final byte[] secondRead;
        private int reads;

        ShiftingIo(String secondRead) {
            super(null);
            this.secondRead = secondRead.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public InputStream openInput(String uri, ObjectInfo pin) {
            return ++reads == 2 ? new ByteArrayInputStream(secondRead) : super.openInput(uri, pin);
        }
    }

    @Test
    void a_pass_that_disagrees_with_the_first_fails_the_run_instead_of_writing() throws Exception {
        Path in = input(consent(1, "phs123456.c1"), row(1, "\\phs123456\\a\\", "x", "", ""),
                row(1, "\\phs123456\\b\\", "1", "", ""));
        String altered = String.join("\n", consent(1, "phs123456.c1"), row(1, "\\phs123456\\a\\", "1", "", ""),
                row(1, "\\phs123456\\b\\", "1", "", "")) + "\n";
        SplitMergedAllConceptsJob shifting = new SplitMergedAllConceptsJob(new ShiftingIo(altered), new DelimitedReader(),
                new FenceMappingValidator(mapper), mapper);

        JobResult r = executor.run(shifting, Map.of("input", in.toString(), "output", out.toString(),
                "fence-mapping", "none", "bad-rows", "quarantine", "max-reject-fraction", "0.5"), "t");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "PASS_MISMATCH")).isTrue();
        assertThat(outputFiles()).isEmpty();
    }

    @Test
    void a_row_rejected_in_pass_one_is_never_written_in_pass_two() throws Exception {
        List<String> lines = new ArrayList<>(List.of(consent(1, "phs123456.c1"), consent(2, "phs123456.c2")));
        List<String> expectedRejects = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add(row(1 + i % 2, "\\phs123456\\ok" + i + "\\", "1", "", ""));
            if (i % 5 == 0) {
                String bad = row(1 + i % 2, "\\phs123456\\bad" + i + "\\", "n/a", "", "");
                lines.add(bad);
                expectedRejects.add(rejectLine("BAD_NVAL", lines.size(), bad));
            }
        }

        JobResult r = run(input(lines.toArray(String[]::new)), "bad-rows", "quarantine", "max-reject-fraction", "0.5");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(rejectLines()).containsExactlyElementsOf(expectedRejects);
        for (String partition : List.of("phs123456.c1", "phs123456.c2", "_consents")) {
            assertThat(lines(partition)).noneMatch(l -> l.contains("bad"));
        }
        assertThat(manifest().at("/rows/balanced").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------ zero-timestamps

    private static String path(String name) {
        return "\\phs123456\\" + name + "\\";
    }

    @Test
    void by_default_zero_timestamps_are_kept_and_reported_as_keep() throws Exception {
        Path in = input(consent(1, "phs123456.c1"),
                row(1, path("a"), "1", "", "0"), row(1, path("b"), "1", "", "00"), row(1, path("c"), "1", "", "-0"));

        JobResult r = run(in);

        assertThat(lines("phs123456.c1")).containsExactly(
                row(1, path("a"), "1", "", "0"), row(1, path("b"), "1", "", "00"), row(1, path("c"), "1", "", "-0"));
        assertThat(manifest().at("/repairs/zeroTimestamps").asText()).isEqualTo("keep");
        assertThat(manifest().at("/repairs/timestampsBlanked").asLong()).isZero();
        assertThat(manifest().at("/timestamps/zero").asLong()).isEqualTo(3);
        assertThat(r.getMetrics()).containsEntry("zeroTimestampsMode", "keep").containsEntry("timestampsBlanked", 0L);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "TIMESTAMPS_BLANKED")).isFalse();
    }

    @Test
    void blank_writes_integer_zero_as_an_empty_field_and_leaves_everything_else_alone() throws Exception {
        Path in = input(consent(1, "phs123456.c1"),
                row(1, path("a"), "1", "", "0"),
                row(1, path("b"), "1", "", "00"),
                row(1, path("c"), "1", "", "-0"),
                row(1, path("d"), "1", "", "1"),
                row(1, path("e"), "1", "", "-5"),
                row(1, path("f"), "1", "", "1700000000000"),
                row(1, path("g"), "1", "", ""));

        JobResult r = run(in, "zero-timestamps", "BLANK");

        // strict timestamps mode: blanking is independent of convert-iso
        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(lines("phs123456.c1")).containsExactly(
                row(1, path("a"), "1", "", ""),
                row(1, path("b"), "1", "", ""),
                row(1, path("c"), "1", "", ""),
                row(1, path("d"), "1", "", "1"),
                row(1, path("e"), "1", "", "-5"),
                row(1, path("f"), "1", "", "1700000000000"),
                row(1, path("g"), "1", "", ""));
        assertThat(manifest().at("/repairs/zeroTimestamps").asText()).isEqualTo("blank");
        assertThat(manifest().at("/repairs/timestamps").asText()).isEqualTo("strict");
        assertThat(manifest().at("/repairs/badRows").asText()).isEqualTo("fail");
        assertThat(manifest().at("/repairs/timestampsBlanked").asLong()).isEqualTo(3);
        assertThat(manifest().at("/repairs/timestampsConverted").asLong()).isZero();
        // timestampZero keeps counting the zeros seen, blanked or not
        assertThat(manifest().at("/timestamps/zero").asLong()).isEqualTo(3);
        assertThat(manifest().at("/rows/balanced").asBoolean()).isTrue();
        assertThat(r.getMetrics()).containsEntry("zeroTimestampsMode", "blank").containsEntry("timestampsBlanked", 3L)
                .containsEntry("timestampZero", 3L);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "TIMESTAMPS_BLANKED")).isTrue();
    }

    @Test
    void blanking_is_a_note_not_a_warning() throws Exception {
        Path in = input(consent(1, "phs123456.c1"), row(1, path("a"), "1", "", "0"));

        JobResult r = run(in, "zero-timestamps", "blank");

        // the TIMESTAMP_ZERO info and TIMESTAMPS_BLANKED are both informational: a clean run stays clean
        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(r.getOutputValidation().getIssues()).filteredOn(i -> i.code().equals("TIMESTAMPS_BLANKED"))
                .singleElement().satisfies(i -> assertThat(i.severity().name()).isEqualTo("INFO"));
    }

    @Test
    void blank_also_blanks_an_iso_timestamp_converted_to_epoch_zero_and_counts_the_conversion() throws Exception {
        Path in = input(consent(1, "phs123456.c1"),
                row(1, path("a"), "1", "", "1970-01-01T00:00:00Z"),
                row(1, path("b"), "1", "", "1970-01-01"),
                row(1, path("c"), "1", "", "1970-01-01T01:00:00+01:00"),
                row(1, path("d"), "1", "", "1970-01-01T00:00:01Z"),
                row(1, path("e"), "1", "", "0"));

        JobResult r = run(in, "timestamps", "convert-iso", "zero-timestamps", "blank");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(lines("phs123456.c1")).containsExactly(
                row(1, path("a"), "1", "", ""),
                row(1, path("b"), "1", "", ""),
                row(1, path("c"), "1", "", ""),
                row(1, path("d"), "1", "", "1000"),
                row(1, path("e"), "1", "", ""));
        // a, b, c, d were ISO and are counted as converted; a, b, c, e are zero and blanked
        assertThat(manifest().at("/repairs/timestampsConverted").asLong()).isEqualTo(4);
        assertThat(manifest().at("/repairs/timestampsBlanked").asLong()).isEqualTo(4);
        assertThat(manifest().at("/timestamps/zero").asLong()).isEqualTo(4);
    }

    @Test
    void without_blank_a_converted_epoch_zero_is_written_as_the_integer_zero() throws Exception {
        Path in = input(consent(1, "phs123456.c1"), row(1, path("a"), "1", "", "1970-01-01T00:00:00Z"));

        run(in, "timestamps", "convert-iso");

        assertThat(lines("phs123456.c1")).containsExactly(row(1, path("a"), "1", "", "0"));
        assertThat(manifest().at("/repairs/timestampsBlanked").asLong()).isZero();
    }

    @Test
    void blank_works_together_with_quarantine_and_a_quarantined_row_keeps_its_final_line() throws Exception {
        Path in = input(
                consent(1, "phs123456.c1"),
                consent(2, "tutorial-demo_one"),
                row(1, path("a"), "1", "", "0"),
                row(1, path("b"), "n/a", "", "0"),
                row(2, "\\tutorial-demo_one\\x\\", "1", "", "0"),
                row(1, path("c"), "1", "", "2023-06-06T00:00:00Z"));

        JobResult r = run(in, "bad-rows", "quarantine", "max-reject-fraction", "0.9", "timestamps", "convert-iso",
                "zero-timestamps", "blank");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(lines("phs123456.c1")).containsExactly(row(1, path("a"), "1", "", ""), row(1, path("c"), "1", "", "1686009600000"));
        // a contract failure is filed as read (the row was never repaired); a row set aside only because its consent
        // cannot name a partition passed the check, so it is filed with the repaired timestamp, like a converted one
        assertThat(rejectLines()).containsExactly(
                rejectLine("BAD_NVAL", 4, row(1, path("b"), "n/a", "", "0")),
                rejectLine("INVALID_CONSENT_VALUE", 5, row(2, "\\tutorial-demo_one\\x\\", "1", "", "")));
        // counted for every row that passed the check: a, the consent-rejected row, not the BAD_NVAL row
        assertThat(manifest().at("/repairs/timestampsBlanked").asLong()).isEqualTo(2);
        assertThat(manifest().at("/rows/balanced").asBoolean()).isTrue();
    }

    @Test
    void a_row_dropped_for_having_no_consent_still_counts_as_blanked_in_both_passes() throws Exception {
        Path in = input(consent(1, "phs123456.c1"),
                row(1, path("a"), "1", "", "0"),
                row(9, path("b"), "1", "", "0"));

        JobResult r = run(in, "zero-timestamps", "blank");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(lines("phs123456.c1")).containsExactly(row(1, path("a"), "1", "", ""));
        assertThat(manifest().at("/repairs/timestampsBlanked").asLong()).isEqualTo(2);
    }

    @Test
    void blank_applies_to_global_rows_and_by_patient_copies_alike() throws Exception {
        Path in = input(consent(1, "phs123456.c1"), consent(1, "phs123456.c2"),
                row(1, "\\age\\", "50", "", "0"));

        run(in, "globals", "by-patient", "zero-timestamps", "blank");

        assertThat(lines("phs123456.c1")).contains(row(1, "\\age\\", "50", "", "")).hasSize(3);
        assertThat(lines("phs123456.c2")).contains(row(1, "\\age\\", "50", "", "")).hasSize(3);
        // the count is per source row, not per copy
        assertThat(manifest().at("/repairs/timestampsBlanked").asLong()).isEqualTo(1);
    }

    @Test
    void the_zero_timestamps_param_is_validated() throws Exception {
        Path in = standardInput();

        JobResult r = run(in, "zero-timestamps", "drop");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(r.getInputValidation().getIssues(), "INVALID_ZERO_TIMESTAMPS")).isTrue();
        assertThat(outputFiles()).isEmpty();
        for (String good : List.of("keep", "blank", " Blank ")) {
            assertThat(hasIssue(run(in, "zero-timestamps", good, "force", "true").getInputValidation().getIssues(),
                    "INVALID_ZERO_TIMESTAMPS")).as(good).isFalse();
        }
    }

    @Test
    void passes_that_disagree_on_how_many_timestamps_were_blanked_fail_the_run() throws Exception {
        Path in = input(consent(1, "phs123456.c1"), row(1, path("a"), "1", "", "0"), row(1, path("b"), "1", "", "5"));
        String altered = String.join("\n", consent(1, "phs123456.c1"), row(1, path("a"), "1", "", "7"),
                row(1, path("b"), "1", "", "5")) + "\n";
        SplitMergedAllConceptsJob shifting = new SplitMergedAllConceptsJob(new ShiftingIo(altered), new DelimitedReader(),
                new FenceMappingValidator(mapper), mapper);

        JobResult r = executor.run(shifting, Map.of("input", in.toString(), "output", out.toString(),
                "fence-mapping", "none", "zero-timestamps", "blank"), "t");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(hasIssue(r.getOutputValidation().getIssues(), "PASS_MISMATCH")).isTrue();
        assertThat(outputFiles()).isEmpty();
    }

    // ------------------------------------------------------------ helpers under test

    @Test
    void segment_treats_the_leading_empty_segment_as_depth_zero() {
        assertThat(SplitMergedAllConceptsJob.segment("\\phs123456\\pht1\\v\\", 0)).isEmpty();
        assertThat(SplitMergedAllConceptsJob.segment("\\phs123456\\pht1\\v\\", 1)).isEqualTo("phs123456");
        assertThat(SplitMergedAllConceptsJob.segment("\\phs123456\\pht1\\v\\", 2)).isEqualTo("pht1");
        assertThat(SplitMergedAllConceptsJob.segment("µphs123456µpht1µ", 1)).isEqualTo("phs123456");
        assertThat(SplitMergedAllConceptsJob.segment("\\a", 1)).isEqualTo("a");
        assertThat(SplitMergedAllConceptsJob.segment("\\a\\", 5)).isEmpty();
        assertThat(SplitMergedAllConceptsJob.segment("noDelimiter", 1)).isEmpty();
    }

    @Test
    void partition_names_cannot_contain_dashes_or_separators() {
        assertThat(SplitMergedAllConceptsJob.partitionNameProblem("phs123456.c1")).isNull();
        assertThat(SplitMergedAllConceptsJob.partitionNameProblem("DCC Harmonized data set")).isNull();
        assertThat(SplitMergedAllConceptsJob.partitionNameProblem("a-b")).isNotNull();
        assertThat(SplitMergedAllConceptsJob.partitionNameProblem("a/b")).isNotNull();
        assertThat(SplitMergedAllConceptsJob.partitionNameProblem("..")).isNotNull();
        assertThat(SplitMergedAllConceptsJob.partitionNameProblem(" ")).isNotNull();
    }

    private static JsonNode partition(JsonNode manifest, String name) {
        List<JsonNode> matches = new ArrayList<>();
        manifest.get("partitions").forEach(p -> {
            if (p.get("name").asText().equals(name)) {
                matches.add(p);
            }
        });
        assertThat(matches).as("partition %s in manifest", name).hasSize(1);
        return matches.getFirst();
    }
}
