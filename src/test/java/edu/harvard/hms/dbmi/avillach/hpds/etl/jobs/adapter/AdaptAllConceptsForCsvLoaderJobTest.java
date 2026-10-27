package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AdapterTestSupport.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AdapterTestSupport.lines;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AdapterTestSupport.q;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job end to end over an in-memory versioned source (so manifest rows carry real {@code s3://} URIs and version
 * ids) and a local output: the guards, what is pinned and checked, atomic output, and the exit codes. The data
 * expectations are in {@link AdapterVectorsTest}.
 */
class AdaptAllConceptsForCsvLoaderJobTest {

    private static final String BASE = "s3://bkt/data/";
    private static final String MANIFEST = BASE + "allConcepts_manifest.csv";
    private static final String C = "µ_consentsµ";
    private static final String SC0 = "µ_studies_consentsµphs000000µ";
    private static final String AGE0 = "µphs000000µdemoµageµ";
    private static final String AGE9 = "µphs999999µdemoµageµ";

    @TempDir
    Path tmp;
    Path out;
    Path reports;
    InMemoryVersionedObjectSource src;
    List<String> written;
    AdaptAllConceptsForCsvLoaderJob job;
    private final List<String[]> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        out = tmp.resolve("out");
        reports = tmp.resolve("reports");
        src = new InMemoryVersionedObjectSource();
        written = new ArrayList<>();
        // records the order of uploads: split_manifest.json must be last
        IoResolver io = new IoResolver(null) {
            @Override
            public void writeOutputFile(String uri, Path file) {
                written.add(uri.substring(uri.lastIndexOf(out.toString()) + out.toString().length() + 1));
                super.writeOutputFile(uri, file);
            }
        };
        job = new AdaptAllConceptsForCsvLoaderJob(io, src, AdapterTestSupport.JSON);
        rows.clear();
    }

    // ------------------------------------------------------------------------------------------------ fixture

    /** Adds a file at {@code BASE + name} and a manifest row for it (the partition "global" for the global file). */
    String file(String partition, String name, String content) {
        String version = src.put(BASE + name, content);
        rows.add(new String[]{partition, BASE + name, version, InMemoryVersionedObjectSource.md5(content)});
        return version;
    }

    String publishManifest() {
        StringBuilder b = new StringBuilder("partition,s3_uri,version_id,last_updated,md5\n");
        for (String[] r : rows) {
            b.append(r[0]).append(',').append(r[1]).append(',').append(r[2]).append(",2026-01-01T00:00:00Z,").append(r[3]).append('\n');
        }
        src.put(MANIFEST, b.toString());
        return MANIFEST;
    }

    /** One study, one consent, patients 1 and 2. */
    void simple() {
        file("global", "global.csv", lines(q(1, C, "", "phs000000.c1", 0), q(1, SC0, "", "TRUE", 0), q(2, C, "", "phs000000.c1", 0)));
        file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0), q(2, AGE0, "41", "", 0)));
        publishManifest();
    }

    Outcome run(String... kv) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("manifest", MANIFEST);
        params.put("output", out.toString());
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return AdapterTestSupport.run(job, reports, params);
    }

    boolean outputIsEmpty() throws IOException {
        if (!Files.exists(out)) {
            return true;
        }
        try (Stream<Path> s = Files.walk(out)) {
            return s.noneMatch(Files::isRegularFile);
        }
    }

    // ------------------------------------------------------------------------------------------------ success

    @Test
    void adapts_a_dataset_uploads_the_partitions_first_and_the_split_manifest_last() throws IOException {
        simple();
        Outcome o = run();

        assertThat(o.exit()).as(o.message()).isZero();
        assertThat(written.getLast()).isEqualTo("split_manifest.json");
        assertThat(written).containsExactly("phs000000.c1-allConcepts.csv", "report.json", "split_manifest.json");
        assertThat(AdapterTestSupport.outputRows(out)).containsExactly(
                q("phs000000.c1", 1, C, "", "phs000000.c1", ""), q("phs000000.c1", 1, SC0, "", "TRUE", ""),
                q("phs000000.c1", 1, AGE0, "40", "", ""), q("phs000000.c1", 2, C, "", "phs000000.c1", ""),
                q("phs000000.c1", 2, AGE0, "41", "", "")).doesNotHaveDuplicates();
        assertThat(AdapterTestSupport.runnerContractProblems(out)).isEmpty();
        JsonNode m = AdapterTestSupport.json(out.resolve("split_manifest.json"));
        assertThat(m.at("/input/uri").asText()).isEqualTo(MANIFEST);
        assertThat(m.at("/input/versionId").asText()).isEqualTo("v1");
        assertThat(m.at("/input/md5").asText()).hasSize(32);
        assertThat(m.at("/input/rowCount").asInt()).isEqualTo(2);
        assertThat(m.at("/rows/balanced").asBoolean()).isTrue();
        assertThat(m.at("/partitions/0/patients").asInt()).isEqualTo(2);
        assertThat(reports.resolve("adapt-allconcepts-csvloader-test-run-report.json")).exists();
        assertThat(o.result().getMetrics()).containsEntry("rowsWritten", 5L).containsEntry("balanced", true);
    }

    @Test
    void every_file_is_read_by_the_version_id_the_manifest_names_not_the_current_one() throws IOException {
        file("global", "global.csv", lines(q(1, C, "", "phs000000.c1", 0)));
        String v = file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0)));
        publishManifest();
        // a later overwrite of the same key (a re-merge) that the manifest does not describe
        src.put(BASE + "c1.csv", lines(q(1, AGE0, "99", "", 0)));

        Outcome o = run();

        assertThat(o.exit()).as(o.message()).isZero();
        assertThat(v).isEqualTo("v1");
        assertThat(src.opened).contains(BASE + "c1.csv#v1").doesNotContain(BASE + "c1.csv#v2");
        assertThat(AdapterTestSupport.outputRows(out)).contains(q("phs000000.c1", 1, AGE0, "40", "", ""));
    }

    @Test
    void more_partitions_than_open_files_are_written_correctly() throws IOException {
        List<String> g = new ArrayList<>();
        for (int i = 1; i <= 70; i++) {
            g.add(q(i, C, "", "phs000000.c" + i, 0));
        }
        file("global", "global.csv", lines(g.toArray(String[]::new)));
        for (int i = 1; i <= 70; i++) {
            file("phs000000.c" + i, "c" + i + ".csv", lines(q(i, AGE0, i, "", 0)));
        }
        publishManifest();

        Outcome o = run();

        assertThat(o.exit()).as(o.message()).isZero();
        assertThat(AdapterTestSupport.outputRows(out)).hasSize(140);
        assertThat(AdapterTestSupport.runnerContractProblems(out)).isEmpty();
    }

    @Test
    void studies_limits_the_partitions_and_drops_the_global_rows_of_the_others_as_dropped_not_rejected() throws IOException {
        file("global", "global.csv", lines(q(1, C, "", "phs000000.c1", 0), q(2, C, "", "phs999999.c1", 0),
                q(2, "µ_studies_consentsµphs999999µ", "", "TRUE", 0)));
        file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0)));
        file("phs999999.c1", "d1.csv", lines(q(2, AGE9, "41", "", 0)));
        publishManifest();

        Outcome o = run("studies", "phs000000");

        assertThat(o.exit()).as(o.message()).isZero();
        assertThat(Files.exists(out.resolve("phs999999.c1-allConcepts.csv"))).isFalse();
        JsonNode r = AdapterTestSupport.json(out.resolve("report.json"));
        assertThat(r.at("/rows/rejected").asInt()).isZero();
        assertThat(r.at("/droppedDetail/excludedStudy").asInt()).isEqualTo(2);
        assertThat(r.at("/rows/balanced").asBoolean()).isTrue();
        assertThat(src.opened).noneMatch(s -> s.contains("d1.csv"));
        assertThat(r.at("/consentCodesSeen").toString()).isEqualTo("[\"1\"]");
    }

    @Test
    void a_rejected_row_within_the_allowed_fraction_is_reported_and_kept_out_of_the_data() throws IOException {
        file("global", "global.csv", lines(q(1, C, "", "phs000000.c1", 0)));
        file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0), q(1, AGE0, "nan", "", 0)));
        publishManifest();

        Outcome o = run("max-reject-fraction", "0.5");

        assertThat(o.exit()).as(o.message()).isZero();
        assertThat(AdapterTestSupport.outputRows(out)).hasSize(2);
        assertThat(Files.readAllLines(out.resolve("rejects/phs000000.c1.csv"))).hasSize(1)
                .allMatch(l -> l.startsWith("\"NUMERIC_NVAL\",2,"));
        assertThat(o.warnings()).anyMatch(w -> w.contains("1 row(s) rejected"));
    }

    // ------------------------------------------------------------------------------------------------ guards (exit 2)

    @Test
    void an_output_under_the_legacy_bucket_prefix_is_refused_before_anything_is_read() throws IOException {
        simple();
        Outcome o = run("output", "s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/general/adapted/");
        assertThat(o.exit()).isEqualTo(2);
        assertThat(o.issueCodes()).contains("OUTPUT_UNDER_MIRROR");
        assertThat(src.opened).isEmpty();
        o = run("output", "s3://avillach-73-bdcatalyst-etl/x/");
        assertThat(o.issueCodes()).contains("OUTPUT_UNDER_MIRROR");
    }

    @Test
    void a_non_empty_output_is_refused_before_anything_is_read() throws IOException {
        simple();
        Files.createDirectories(out);
        Files.writeString(out.resolve("old.csv"), "x");
        Outcome o = run();
        assertThat(o.exit()).isEqualTo(2);
        assertThat(o.issueCodes()).contains("OUTPUT_NOT_EMPTY");
        assertThat(src.opened).isEmpty();
        assertThat(Files.readString(out.resolve("old.csv"))).isEqualTo("x");
    }

    @Test
    void bad_parameters_are_refused() {
        simple();
        assertThat(run("max-reject-fraction", "1.5").issueCodes()).contains("BAD_MAX_REJECT_FRACTION");
        assertThat(run("max-reject-fraction", "abc").issueCodes()).contains("BAD_MAX_REJECT_FRACTION");
        assertThat(run("multi-study-ids", "merge").issueCodes()).contains("BAD_MULTI_STUDY_IDS");
        assertThat(run("zero-timestamps", "keep").issueCodes()).contains("BAD_ZERO_TIMESTAMPS");
        assertThat(run("studies", "phs 1").issueCodes()).contains("BAD_STUDY_ID");
        assertThat(run("allow-consent-codes", "a-b").issueCodes()).contains("BAD_CONSENT_CODE");
        assertThat(src.opened).isEmpty(); // none of the above read even the manifest
        assertThat(run("studies", "phs123456").issueCodes()).contains("STUDY_NOT_IN_MANIFEST");
        assertThat(src.opened).containsOnly(MANIFEST + "#v1"); // the manifest alone, no data file
    }

    @Test
    void missing_required_parameters_exit_2() {
        simple();
        Outcome o = AdapterTestSupport.run(job, reports, Map.of());
        assertThat(o.exit()).isEqualTo(2);
        assertThat(o.issueCodes()).contains("MISSING_PARAM");
    }

    @Test
    void manifest_refusals_exit_2_without_reading_any_data_file() throws IOException {
        file("global", "global.csv", lines(q(1, C, "", "phs000000.c0", 0)));
        file("phs000000.c0", "c0.csv", lines(q(1, AGE0, "40", "", 0)));
        publishManifest();
        Outcome o = run();
        assertThat(o.exit()).isEqualTo(2);
        assertThat(o.issueCodes()).containsExactly("CONSENT_CODE_NOT_ALLOWED");
        assertThat(src.opened).containsExactly(MANIFEST + "#v1"); // only the manifest itself
        assertThat(outputIsEmpty()).isTrue();

        o = run("allow-consent-codes", "0");
        assertThat(o.exit()).as(o.message()).isZero();
        assertThat(AdapterTestSupport.json(out.resolve("split_manifest.json")).at("/consentCodesSeen").toString()).isEqualTo("[\"0\"]");
    }

    @Test
    void an_s3_row_without_a_version_id_is_refused() throws IOException {
        src.put(MANIFEST, "partition,s3_uri,version_id,last_updated,md5\n"
                + "global," + BASE + "g.csv,,t," + "0".repeat(32) + "\n"
                + "phs000000.c1," + BASE + "c.csv,v1,t," + "0".repeat(32) + "\n");
        Outcome o = run();
        assertThat(o.exit()).isEqualTo(2);
        assertThat(o.issueCodes()).contains("MANIFEST_VERSION_MISSING");
    }

    @Test
    void a_missing_manifest_is_a_config_error_and_a_broken_one_a_validation_error() {
        assertThat(run().exit()).isEqualTo(ExitCode.CONFIG_ERROR.code());
        src.put(MANIFEST, "not,a,manifest\n");
        Outcome o = run();
        assertThat(o.exit()).isEqualTo(2);
        assertThat(o.issueCodes()).contains("MANIFEST_FORMAT");
    }

    // ------------------------------------------------------------------------------------------------ data failures (exit 3)

    @Test
    void an_md5_that_differs_from_the_manifest_fails_naming_the_partition_and_leaves_the_output_empty() throws IOException {
        file("global", "global.csv", lines(q(1, C, "", "phs000000.c1", 0)));
        file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0)));
        rows.get(1)[3] = "f".repeat(32);
        publishManifest();

        Outcome o = run();

        assertThat(o.exit()).isEqualTo(3);
        assertThat(o.message()).contains("MD5_MISMATCH").contains("phs000000.c1");
        assertThat(outputIsEmpty()).isTrue();
        assertThat(written).isEmpty();
        assertThat(reports.resolve("adapt-allconcepts-csvloader-test-run-report.json")).exists();
    }

    @Test
    void a_global_file_whose_md5_differs_fails_naming_global() throws IOException {
        file("global", "global.csv", lines(q(1, C, "", "phs000000.c1", 0)));
        file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0)));
        rows.get(0)[3] = "e".repeat(32);
        publishManifest();

        Outcome o = run();

        assertThat(o.exit()).isEqualTo(3);
        assertThat(o.message()).contains("MD5_MISMATCH: global");
    }

    @Test
    void a_manifest_that_moved_while_the_files_were_read_fails_and_publishes_nothing() throws IOException {
        simple();
        src.beforeOpen = opened -> {
            if (opened.contains("c1.csv")) {
                src.put(MANIFEST, "partition,s3_uri,version_id,last_updated,md5\n");
            }
        };

        Outcome o = run();

        assertThat(o.exit()).isEqualTo(3);
        assertThat(o.message()).contains("MANIFEST_CHANGED").contains("v1").contains("v2");
        assertThat(outputIsEmpty()).isTrue();
    }

    @Test
    void rejects_beyond_the_allowed_fraction_fail_after_writing_the_report_and_the_rejects_but_not_the_output() throws IOException {
        file("global", "global.csv", lines(q(1, C, "", "phs000000.c1", 0)));
        file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0), q(2147483648L, AGE0, "1", "", 0)));
        publishManifest();

        Outcome o = run();

        assertThat(o.exit()).isEqualTo(3);
        assertThat(o.message()).contains("REJECT_FRACTION_EXCEEDED").contains("BAD_PATIENT_ID=1");
        assertThat(outputIsEmpty()).isTrue();
        JsonNode report = AdapterTestSupport.json(reports.resolve("adapt-allconcepts-csvloader-test-run-report.json"));
        assertThat(report.at("/status").asText()).isEqualTo("failed");
        assertThat(report.at("/rejects/byCode/BAD_PATIENT_ID").asInt()).isEqualTo(1);
        assertThat(Files.readAllLines(reports.resolve("adapt-allconcepts-csvloader-test-run-rejects/phs000000.c1.csv"))).hasSize(1);
        assertThat(o.error().partialMetrics()).containsEntry("rowsRejected", 1L);
    }

    @Test
    void a_partition_that_ends_with_no_rows_fails() throws IOException {
        file("global", "global.csv", lines(q(1, C, "", "phs000000.c1", 0)));
        file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0)));
        file("phs000000.c2", "c2.csv", lines(q(1, AGE0, "40", "", 0))); // patient 1 holds no c2 consent
        publishManifest();

        Outcome o = run("max-reject-fraction", "1");

        assertThat(o.exit()).isEqualTo(3);
        assertThat(o.message()).contains("EMPTY_PARTITION: phs000000.c2");
        assertThat(outputIsEmpty()).isTrue();
    }

    @Test
    void ids_that_are_not_patient_level_stop_the_run_in_copy_mode_and_any_multi_study_patient_in_refuse_mode() throws IOException {
        String subj = "µ_source_subject_idµ";
        file("global", "global.csv", lines(
                q(1, C, "", "phs000000.c1", 0), q(1, subj, "", "A", 0),
                q(1, C, "", "phs999999.c1", 0), q(1, subj, "", "B", 0)));
        file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0)));
        file("phs999999.c1", "d1.csv", lines(q(1, AGE9, "41", "", 0)));
        publishManifest();

        Outcome copy = run();
        assertThat(copy.exit()).isEqualTo(3);
        assertThat(copy.message()).contains("ID_ROWS_NOT_PATIENT_LEVEL").contains("patient 1 is in 2 studies");
        assertThat(copy.message()).doesNotContain("\"A\"");
        assertThat(outputIsEmpty()).isTrue();

        Outcome refuse = run("multi-study-ids", "refuse");
        assertThat(refuse.exit()).isEqualTo(3);
        assertThat(refuse.message()).contains("MULTI_STUDY_PATIENTS");
    }

    @Test
    void patient_level_ids_of_a_multi_study_patient_are_copied_once_to_each_partition() throws IOException {
        String subj = "µ_source_subject_idµ";
        file("global", "global.csv", lines(
                q(1, C, "", "phs000000.c1", 0), q(1, subj, "", "A", 0),
                q(1, C, "", "phs999999.c1", 0), q(1, subj, "", "A", 0), q(1, subj, "", "A", 0)));
        file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0)));
        file("phs999999.c1", "d1.csv", lines(q(1, AGE9, "41", "", 0)));
        publishManifest();

        Outcome o = run();

        assertThat(o.exit()).as(o.message()).isZero();
        assertThat(AdapterTestSupport.outputRows(out)).filteredOn(r -> r.contains(subj)).hasSize(2);
        JsonNode r = AdapterTestSupport.json(out.resolve("report.json"));
        assertThat(r.at("/rows/globalCopies").asInt()).isEqualTo(1);
        assertThat(r.at("/droppedDetail/mergedIdRows").asInt()).isEqualTo(1);    // the one expected repeat (two studies)
        assertThat(r.at("/droppedDetail/duplicateIdRows").asInt()).isEqualTo(1); // the third occurrence
        assertThat(r.at("/rows/balanced").asBoolean()).isTrue();
        assertThat(r.at("/multiStudyPatients").asInt()).isEqualTo(1);
    }

    @Test
    void a_file_that_is_not_utf8_fails_as_a_data_error() throws IOException {
        // a source that serves invalid UTF-8 for c1.csv (a String cannot hold it)
        src = new InMemoryVersionedObjectSource() {
            @Override
            public java.io.InputStream open(String uri, String versionId) {
                java.io.InputStream in = super.open(uri, versionId);
                return uri.endsWith("c1.csv") ? new java.io.ByteArrayInputStream(new byte[]{'1', ',', (byte) 0xC3, (byte) 0x28, '\n'}) : in;
            }
        };
        job = new AdaptAllConceptsForCsvLoaderJob(new IoResolver(null), src, AdapterTestSupport.JSON);
        file("global", "global.csv", lines(q(1, C, "", "phs000000.c1", 0)));
        file("phs000000.c1", "c1.csv", lines(q(1, AGE0, "40", "", 0)));
        rows.get(1)[3] = "0".repeat(32); // its md5 is irrelevant: the bytes are refused first
        publishManifest();

        Outcome o = run();

        assertThat(o.exit()).isEqualTo(3);
        assertThat(o.message()).contains("INVALID_UTF8").contains("phs000000.c1");
    }
}
