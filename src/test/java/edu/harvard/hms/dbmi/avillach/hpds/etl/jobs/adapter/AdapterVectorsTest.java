package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AdapterTestSupport.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the job over the committed synthetic vectors in {@code src/test/resources/adapter-vectors} and compares what it
 * writes with each vector's expected files, in the same form for all of them: {@code expected-partitions.csv} (every
 * output row as {@code partition,patient,path,nval,tval,ts}, quoted, sorted) and {@code expected-report.json} (a subset
 * the report and {@code split_manifest.json} must contain; its {@code cases} key names refusals). The expected files
 * are written by hand from the rules in {@code docs/ADAPT_ALLCONCEPTS_CSVLOADER.md}, not produced by the job, and
 * {@code SHA256SUMS} pins them. {@code etl-runners/adapt-allconcepts-csvloader/test/run-tests.sh} runs the same v1
 * through the runner's own gates.
 */
class AdapterVectorsTest {

    @TempDir
    Path tmp;

    private static Path vectorDir(String v) throws URISyntaxException {
        return Path.of(AdapterVectorsTest.class.getResource("/adapter-vectors/" + v + "/README.md").toURI()).getParent();
    }

    private Outcome run(Path inputs, String manifest, Map<String, String> extra, Path out, VersionedObjectSource source) {
        AdaptAllConceptsForCsvLoaderJob job = new AdaptAllConceptsForCsvLoaderJob(new IoResolver(null), source, AdapterTestSupport.JSON);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("manifest", inputs.resolve(manifest).toString());
        params.put("output", out.toString());
        params.putAll(extra);
        return AdapterTestSupport.run(job, tmp.resolve("reports-" + out.getFileName()), params);
    }

    private Outcome run(Path inputs, String manifest, Map<String, String> extra, Path out) {
        return run(inputs, manifest, extra, out, new S3VersionedObjectSource(null));
    }

    private static JsonNode expectedReport(Path vector) throws IOException {
        return AdapterTestSupport.json(vector.resolve("expected-report.json"));
    }

    private void assertMatchesVector(String name, Map<String, String> params) throws Exception {
        Path v = vectorDir(name);
        Path out = tmp.resolve(name + "-out");
        Outcome o = run(v.resolve("inputs"), "allConcepts_manifest.csv", params, out);

        assertThat(o.exit()).as(o.message()).isZero();
        List<String> expected = Files.readAllLines(v.resolve("expected-partitions.csv"), StandardCharsets.UTF_8).stream().sorted().toList();
        assertThat(AdapterTestSupport.outputRows(out)).isEqualTo(expected);
        assertThat(AdapterTestSupport.runnerContractProblems(out)).isEmpty();

        ObjectNode expectedJson = (ObjectNode) expectedReport(v).deepCopy();
        expectedJson.remove("cases");
        assertThat(AdapterTestSupport.subset(expectedJson, AdapterTestSupport.json(out.resolve("split_manifest.json"))))
                .as("split_manifest.json against expected-report.json").isTrue();
        assertThat(AdapterTestSupport.subset(expectedJson, AdapterTestSupport.json(out.resolve("report.json"))))
                .as("report.json against expected-report.json").isTrue();
    }

    // ------------------------------------------------------------------------------------------------ v1 .. v4

    @Test
    void v1_two_studies_three_consents_and_a_patient_in_both_studies() throws Exception {
        assertMatchesVector("v1", Map.of());
    }

    @Test
    void v1_with_multi_study_ids_refuse_stops_on_the_patient_in_both_studies() throws Exception {
        Outcome o = run(vectorDir("v1").resolve("inputs"), "allConcepts_manifest.csv", Map.of("multi-study-ids", "refuse"), tmp.resolve("o"));
        assertThat(o.exit()).isEqualTo(3);
        assertThat(o.message()).contains("MULTI_STUDY_PATIENTS");
    }

    @Test
    void v1_restricted_to_one_study_is_that_studys_rows_with_the_other_studys_rows_dropped() throws Exception {
        Path out = tmp.resolve("o");
        Outcome o = run(vectorDir("v1").resolve("inputs"), "allConcepts_manifest.csv", Map.of("studies", "phs999999"), out);
        assertThat(o.exit()).as(o.message()).isZero();
        assertThat(AdapterTestSupport.outputRows(out)).isNotEmpty().allMatch(r -> r.startsWith("\"phs999999.c1\","));
        // patient 3 is in phs000000 too, but only its phs999999 rows are here: one SUBJ-3 and one SAMP-3 row, not two
        assertThat(AdapterTestSupport.outputRows(out)).filteredOn(r -> r.contains("SUBJ-3")).hasSize(1);
        JsonNode report = AdapterTestSupport.json(out.resolve("report.json"));
        assertThat(report.at("/rows/balanced").asBoolean()).isTrue();
        // patient 3 still counts as a multi-study patient (the manifest lists its other study), which is why its two id rows
        // are merged rather than copied twice
        assertThat(report.at("/multiStudyPatients").asInt()).isEqualTo(1);
        assertThat(report.at("/droppedDetail/mergedIdRows").asInt()).isEqualTo(2);
        assertThat(report.at("/droppedDetail/duplicateIdRows").asInt()).isZero();
        // dropped as belonging to the left-out phs000000: all 5 global rows of patient 1, all 4 of patient 2, and patient 3's
        // _consents row and two _studies_consents rows (its id rows stay, merged)
        assertThat(report.at("/droppedDetail/excludedStudy").asInt()).isEqualTo(12);
        assertThat(AdapterTestSupport.runnerContractProblems(out)).isEmpty();
    }

    @Test
    void v2_migration_style_rows_are_repaired_or_rejected_by_rule() throws Exception {
        assertMatchesVector("v2", Map.of("max-reject-fraction", "0.5"));
        // and the same input fails at the default of no rejects, naming every reject code
        Outcome strict = run(vectorDir("v2").resolve("inputs"), "allConcepts_manifest.csv", Map.of(), tmp.resolve("strict"));
        assertThat(strict.exit()).isEqualTo(3);
        assertThat(strict.message()).contains("REJECT_FRACTION_EXCEEDED").contains("EMBEDDED_LINE_BREAK=1").contains("NUMERIC_NVAL=1")
                .contains("BAD_TIMESTAMP=1").contains("NON_CANONICAL_PATH=1").contains("MALFORMED_RECORD=1");
    }

    @Test
    void v2_rejects_file_holds_the_original_records_with_their_code() throws Exception {
        Path out = tmp.resolve("o");
        run(vectorDir("v2").resolve("inputs"), "allConcepts_manifest.csv", Map.of("max-reject-fraction", "0.5"), out);
        String rejects = Files.readString(out.resolve("rejects/phs123456.c1.csv"));
        assertThat(rejects).contains("\"EMBEDDED_LINE_BREAK\",5,\"2,\"\"\\phs123456\\demo\\note\\\"\",,\"\"line one\nline two\"\",0\"");
        assertThat(rejects).contains("\"NUMERIC_NVAL\",4,\"2,\\phs123456\\demo\\age\\,nan,,0\"");
        assertThat(Files.exists(out.resolve("rejects/global.csv"))).isFalse();
    }

    @Test
    void v4_routing_rejections() throws Exception {
        assertMatchesVector("v4", Map.of("max-reject-fraction", "1"));
    }

    @Test
    void v4_study_specific_ids_stop_the_run_with_exit_3() throws Exception {
        JsonNode c = expectedReport(vectorDir("v4")).at("/cases/study-specific-ids");
        Outcome o = run(vectorDir("v4").resolve(c.get("inputs").asText()), "allConcepts_manifest.csv", Map.of(), tmp.resolve("o"));
        assertThat(o.exit()).isEqualTo(c.get("exit").asInt());
        assertThat(o.message()).contains(c.get("message").asText());
        assertThat(Files.exists(tmp.resolve("o/split_manifest.json"))).isFalse();
    }

    // ------------------------------------------------------------------------------------------------ v3 refusals

    @Test
    void v3_ok_case_matches_its_expected_output() throws Exception {
        Path v = vectorDir("v3");
        Path out = tmp.resolve("ok");
        Outcome o = run(v.resolve("inputs"), "manifest-ok.csv", Map.of(), out);
        assertThat(o.exit()).as(o.message()).isZero();
        assertThat(AdapterTestSupport.outputRows(out))
                .isEqualTo(Files.readAllLines(v.resolve("expected-partitions.csv")).stream().sorted().toList());
    }

    @Test
    void v3_every_refusal_has_the_exit_code_and_reason_its_expected_report_names() throws Exception {
        Path v = vectorDir("v3");
        JsonNode cases = expectedReport(v).get("cases");
        Path inputs = v.resolve("inputs");
        Map<String, Outcome> outcomes = new HashMap<>();
        Map<String, Path> outs = new HashMap<>();
        for (var it = cases.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            Path out = tmp.resolve(name);
            outs.put(name, out);
            Outcome o = switch (name) {
                case "ok" -> run(inputs, "manifest-ok.csv", Map.of(), out);
                case "md5-mismatch" -> run(inputs, "manifest-md5-mismatch.csv", Map.of(), out);
                case "code-0" -> run(inputs, "manifest-code0.csv", Map.of(), out);
                case "code-0-allowed" -> run(inputs, "manifest-code0.csv", Map.of("allow-consent-codes", "0"), out);
                case "hyphenated-partition" -> run(inputs, "manifest-hyphen.csv", Map.of(), out);
                case "patient-id-over-int" -> run(inputs, "manifest-big-id.csv", Map.of(), out);
                case "patient-id-over-int-tolerated" -> run(inputs, "manifest-big-id.csv", Map.of("max-reject-fraction", "0.5"), out);
                case "manifest-version-moved" -> run(inputs, "manifest-ok.csv", Map.of(), out, movingManifest());
                case "output-under-mirror" -> run(inputs, "manifest-ok.csv", Map.of(), Path.of("/avillach-73-bdcatalyst-etl/adapted"), 
                        new S3VersionedObjectSource(null));
                case "output-not-empty" -> {
                    Files.createDirectories(out);
                    Files.writeString(out.resolve("leftover.csv"), "x");
                    yield run(inputs, "manifest-ok.csv", Map.of(), out);
                }
                default -> throw new AssertionError("no scenario for case " + name);
            };
            outcomes.put(name, o);
        }
        for (var it = cases.fields(); it.hasNext(); ) {
            var e = it.next();
            String name = e.getKey();
            JsonNode want = e.getValue();
            Outcome o = outcomes.get(name);
            assertThat(o.exit()).as(name + ": " + o.message()).isEqualTo(want.get("exit").asInt());
            if (want.has("message")) {
                assertThat(o.message()).as(name).contains(want.get("message").asText());
            }
            if (want.has("issue")) {
                assertThat(o.issueCodes()).as(name).contains(want.get("issue").asText());
            }
            Optional<Path> report = Optional.of(want.get("exit").asInt() == 0 ? outs.get(name).resolve("report.json")
                    : o.reportsDir().resolve("adapt-allconcepts-csvloader-test-run-report.json")).filter(Files::exists);
            if (want.has("consentCodesSeen")) {
                assertThat(AdapterTestSupport.json(report.orElseThrow()).get("consentCodesSeen")).as(name).isEqualTo(want.get("consentCodesSeen"));
            }
            if (want.has("rejectsByCode")) {
                assertThat(AdapterTestSupport.json(report.orElseThrow()).at("/rejects/byCode")).as(name).isEqualTo(want.get("rejectsByCode"));
            }
            if (want.get("exit").asInt() != 0) {
                // a refusal or a failure publishes nothing, and leaves a pre-existing output as it was
                try (var files = Files.exists(outs.get(name)) ? Files.list(outs.get(name)) : java.util.stream.Stream.<Path>empty()) {
                    assertThat(files.map(f -> f.getFileName().toString())).as(name).isSubsetOf("leftover.csv");
                }
            }
        }
    }

    /** The local source, except that the manifest's version changes once the first data file has been opened. */
    private static VersionedObjectSource movingManifest() {
        S3VersionedObjectSource local = new S3VersionedObjectSource(null);
        boolean[] moved = {false};
        return new VersionedObjectSource() {
            @Override
            public Optional<String> currentVersionId(String uri) {
                return uri.contains("manifest") ? Optional.of(moved[0] ? "local-moved" : "local-first") : local.currentVersionId(uri);
            }

            @Override
            public InputStream open(String uri, String versionId) {
                if (!uri.contains("manifest")) {
                    moved[0] = true;
                }
                return local.open(uri, versionId);
            }
        };
    }

    // ------------------------------------------------------------------------------------------------ pinning

    @Test
    void the_expected_files_are_pinned_by_their_sha256sums() throws Exception {
        for (String name : List.of("v1", "v2", "v3", "v4")) {
            Path v = vectorDir(name);
            List<String> sums = Files.readAllLines(v.resolve("SHA256SUMS"));
            assertThat(sums).as(name).hasSize(2);
            for (String line : sums) {
                String[] parts = line.split("  ", 2);
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(v.resolve(parts[1])));
                assertThat(HexFormat.of().formatHex(hash)).as(name + "/" + parts[1]).isEqualTo(parts[0]);
            }
            assertThat(sums.stream().map(l -> l.split("  ", 2)[1])).containsExactly("expected-partitions.csv", "expected-report.json");
            assertThat(Files.readString(v.resolve("README.md")).strip().split("\n\n")).as(name + " README is one paragraph after the title")
                    .hasSize(2);
        }
    }

    @Test
    void the_vectors_hold_only_the_placeholder_accessions() throws Exception {
        for (String name : List.of("v1", "v2", "v3", "v4")) {
            try (var files = Files.walk(vectorDir(name))) {
                for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String text = Files.readString(f);
                    var m = java.util.regex.Pattern.compile("phs[0-9]{6}").matcher(text);
                    while (m.find()) {
                        assertThat(m.group()).as(f.toString()).isIn("phs000000", "phs999999", "phs123456");
                    }
                }
            }
        }
    }
}
