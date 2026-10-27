package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.PartitionSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds and writes the two JSON documents of a run: {@code split_manifest.json}, the contract with the runner
 * (its {@code stage-inputs.sh} and {@code read_split_manifest} read {@code partitions[].name/kind/file/rows}), and
 * {@code report.json}, the same numbers for a person, with the detail. Field names follow the earlier splitter's
 * manifest ({@code SplitMergedAllConceptsJob}) where they mean the same.
 */
final class SplitManifestWriter {

    static final String SPLIT_MANIFEST = "split_manifest.json";
    static final String REPORT = "report.json";

    private SplitManifestWriter() {
    }

    /** What the documents say about the run, besides the engine's counts. */
    record RunMeta(String job, String runId, String generatedAt, String manifestUri, String manifestVersionId, String manifestMd5,
                   int manifestRows, String output, AdapterOptions options, List<String> failures, List<String> warnings) {
    }

    static Map<String, Object> splitManifest(RunMeta meta, AdaptationEngine e) {
        Map<String, Object> m = common(meta, e);
        List<Map<String, Object>> partitions = new ArrayList<>();
        for (PartitionSpec p : e.plan().partitions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", p.name());
            entry.put("kind", "study");
            entry.put("file", p.file());
            entry.put("rows", e.rowsWritten(p));
            entry.put("patients", e.patients[p.index()].cardinality());
            partitions.add(entry);
        }
        m.put("partitions", partitions);
        return m;
    }

    static Map<String, Object> report(RunMeta meta, AdaptationEngine e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", meta.failures().isEmpty() ? "ok" : "failed");
        m.put("failures", meta.failures());
        m.putAll(common(meta, e));
        m.put("droppedDetail", droppedTotals(e));
        Map<String, Object> ts = new LinkedHashMap<>();
        ts.put("convertedFromIso", e.timestampsConverted);
        ts.put("writtenEmptyBecauseZero", e.timestampsBlanked);
        m.put("timestamps", ts);
        m.put("rejectExamples", e.rejectExamples);
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("uri", e.plan().global.s3Uri());
        g.put("versionId", e.plan().global.versionId());
        g.put("md5", e.plan().global.md5());
        g.put("rowsRead", e.global.input);
        g.put("written", e.global.written);
        g.put("copies", e.global.copies);
        g.put("rejected", e.global.rejected);
        g.put("dropped", e.global.droppedDetail());
        g.put("balanced", e.global.balanced());
        m.put("global", g);
        List<Map<String, Object>> partitions = new ArrayList<>();
        for (PartitionSpec p : e.plan().partitions) {
            Tally t = e.parts[p.index()];
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", p.name());
            entry.put("kind", "study");
            entry.put("study", p.study());
            entry.put("consentCode", p.code());
            entry.put("file", p.file());
            entry.put("source", Map.of("uri", p.row().s3Uri(), "versionId", p.row().versionId(), "md5", p.row().md5()));
            entry.put("rows", e.rowsWritten(p));
            entry.put("patients", e.patients[p.index()].cardinality());
            entry.put("rowsRead", t.input);
            entry.put("rowsWrittenFromPartition", t.written);
            entry.put("rowsWrittenFromGlobal", e.globalRows[p.index()]);
            entry.put("rejected", t.rejected);
            entry.put("rejectedByCode", t.rejectedByCode);
            entry.put("dropped", t.droppedDetail());
            entry.put("balanced", t.balanced());
            partitions.add(entry);
        }
        m.put("partitions", partitions);
        return m;
    }

    private static Map<String, Object> common(RunMeta meta, AdaptationEngine e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job", meta.job());
        m.put("runId", meta.runId());
        m.put("generatedAt", meta.generatedAt());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("uri", meta.manifestUri());
        input.put("versionId", meta.manifestVersionId());
        input.put("md5", meta.manifestMd5());
        input.put("rowCount", meta.manifestRows());
        m.put("input", input);
        m.put("output", meta.output());
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("studies", meta.options().studies());
        opts.put("maxRejectFraction", meta.options().maxRejectFraction());
        opts.put("allowConsentCodes", meta.options().allowCodes());
        opts.put("multiStudyIds", meta.options().refuseMultiStudy() ? "refuse" : "copy");
        opts.put("zeroTimestamps", "blank");
        m.put("options", opts);
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("input", e.totalInput());
        rows.put("globalCopies", e.totalCopies());
        rows.put("written", e.totalWritten());
        rows.put("rejected", e.totalRejected());
        rows.put("dropped", e.totalDropped());
        rows.put("balanced", e.balanced());
        m.put("rows", rows);
        Map<String, Object> rejects = new LinkedHashMap<>();
        rejects.put("total", e.totalRejected());
        rejects.put("byCode", rejectsByCode(e));
        rejects.put("byFile", rejectsByFile(e));
        rejects.put("linesNotKeptAfterCap", e.rejectLinesDropped);
        m.put("rejects", rejects);
        m.put("multiStudyPatients", e.index.multiStudyPatients());
        m.put("patientsWithConsents", e.index.patients());
        m.put("consentCodesSeen", new ArrayList<>(e.plan().codesSeen));
        m.put("warnings", meta.warnings());
        return m;
    }

    private static Map<String, Long> rejectsByCode(AdaptationEngine e) {
        Map<String, Long> out = new TreeMap<>();
        e.global.rejectedByCode.forEach((k, v) -> out.merge(k, v, Long::sum));
        for (Tally t : e.parts) {
            t.rejectedByCode.forEach((k, v) -> out.merge(k, v, Long::sum));
        }
        return out;
    }

    private static Map<String, Long> rejectsByFile(AdaptationEngine e) {
        Map<String, Long> out = new TreeMap<>();
        if (e.global.rejected > 0) {
            out.put(AdaptationEngine.GLOBAL, e.global.rejected);
        }
        for (Tally t : e.parts) {
            if (t.rejected > 0) {
                out.put(t.name, t.rejected);
            }
        }
        return out;
    }

    private static Map<String, Object> droppedTotals(AdaptationEngine e) {
        Tally all = new Tally("all");
        List<Tally> tallies = new ArrayList<>(List.of(e.parts));
        tallies.add(e.global);
        for (Tally t : tallies) {
            all.headers += t.headers;
            all.blankLines += t.blankLines;
            all.noConsent += t.noConsent;
            all.excludedStudy += t.excludedStudy;
            all.studyNotHeld += t.studyNotHeld;
            all.merged += t.merged;
            all.duplicates += t.duplicates;
        }
        return all.droppedDetail();
    }

    static void write(ObjectMapper mapper, Path file, Map<String, Object> document) throws IOException {
        mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), document);
    }
}
