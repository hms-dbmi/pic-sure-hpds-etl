package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AdapterTestSupport.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The two documents a run writes, on the v1 vector: their fields and their agreement with each other. */
class SplitManifestWriterTest {

    @TempDir
    Path tmp;

    private Path runV1() throws URISyntaxException {
        Path inputs = Path.of(getClass().getResource("/adapter-vectors/v1/README.md").toURI()).getParent().resolve("inputs");
        Path out = tmp.resolve("out");
        Outcome o = AdapterTestSupport.run(new AdaptAllConceptsForCsvLoaderJob(new IoResolver(null), new S3VersionedObjectSource(null),
                AdapterTestSupport.JSON), tmp.resolve("reports"),
                Map.of("manifest", inputs.resolve("allConcepts_manifest.csv").toString(), "output", out.toString()));
        assertThat(o.exit()).as(o.message()).isZero();
        return out;
    }

    private static List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void split_manifest_partitions_carry_exactly_what_the_runner_reads() throws Exception {
        JsonNode m = AdapterTestSupport.json(runV1().resolve(SplitManifestWriter.SPLIT_MANIFEST));
        assertThat(names(m)).contains("job", "runId", "generatedAt", "input", "output", "rows", "rejects", "multiStudyPatients",
                "consentCodesSeen", "warnings", "partitions");
        assertThat(names(m.get("input"))).containsExactly("uri", "versionId", "md5", "rowCount");
        assertThat(names(m.get("rows"))).containsExactly("input", "globalCopies", "written", "rejected", "dropped", "balanced");
        for (JsonNode p : m.get("partitions")) {
            assertThat(names(p)).containsExactly("name", "kind", "file", "rows", "patients");
            assertThat(p.get("kind").asText()).isEqualTo("study");
            assertThat(p.get("file").asText()).isEqualTo(p.get("name").asText() + "-allConcepts.csv");
        }
        assertThat(m.get("partitions").findValues("name")).extracting(JsonNode::asText)
                .containsExactly("phs000000.c1", "phs000000.c2", "phs999999.c1"); // sorted
    }

    @Test
    void the_report_repeats_the_numbers_and_adds_the_detail() throws Exception {
        Path out = runV1();
        JsonNode m = AdapterTestSupport.json(out.resolve(SplitManifestWriter.SPLIT_MANIFEST));
        JsonNode r = AdapterTestSupport.json(out.resolve(SplitManifestWriter.REPORT));
        assertThat(r.get("status").asText()).isEqualTo("ok");
        assertThat(r.get("rows")).isEqualTo(m.get("rows"));
        assertThat(r.get("rejects")).isEqualTo(m.get("rejects"));
        assertThat(r.get("consentCodesSeen")).isEqualTo(m.get("consentCodesSeen"));
        long total = 0;
        for (int i = 0; i < m.get("partitions").size(); i++) {
            JsonNode a = m.get("partitions").get(i);
            JsonNode b = r.get("partitions").get(i);
            assertThat(b.get("rows")).isEqualTo(a.get("rows"));
            assertThat(b.get("patients")).isEqualTo(a.get("patients"));
            assertThat(b.get("rowsWrittenFromPartition").asLong() + b.get("rowsWrittenFromGlobal").asLong()).isEqualTo(b.get("rows").asLong());
            assertThat(b.get("balanced").asBoolean()).isTrue();
            total += a.get("rows").asLong();
        }
        assertThat(total).isEqualTo(m.at("/rows/written").asLong());
        assertThat(r.at("/global/balanced").asBoolean()).isTrue();
        assertThat(r.at("/droppedDetail/mergedIdRows").asInt()).isEqualTo(2);
        assertThat(Files.readString(out.resolve(SplitManifestWriter.REPORT))).contains("\n  \"status\"");
    }

    @Test
    void a_failed_run_reports_status_failed_with_its_reasons() throws IOException, URISyntaxException {
        Path inputs = Path.of(getClass().getResource("/adapter-vectors/v3/README.md").toURI()).getParent().resolve("inputs");
        Outcome o = AdapterTestSupport.run(new AdaptAllConceptsForCsvLoaderJob(new IoResolver(null), new S3VersionedObjectSource(null),
                AdapterTestSupport.JSON), tmp.resolve("reports"),
                Map.of("manifest", inputs.resolve("manifest-md5-mismatch.csv").toString(), "output", tmp.resolve("o").toString()));
        assertThat(o.exit()).isEqualTo(3);
        JsonNode r = AdapterTestSupport.json(tmp.resolve("reports/adapt-allconcepts-csvloader-test-run-report.json"));
        assertThat(r.get("status").asText()).isEqualTo("failed");
        assertThat(r.at("/failures/0").asText()).contains("MD5_MISMATCH");
    }
}
