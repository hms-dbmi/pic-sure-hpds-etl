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
        assertThat(r.getOutputValidation().getIssues()).anyMatch(i -> i.code().equals("SPLIT_NOTE")
                && i.message().contains("phs999999"));
        assertThat(lines("phs999999")).hasSize(1);
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
