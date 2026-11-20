package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * dbGaP study summaries kept with each study, so the volatile SSTR API is asked for a study version
 * only once. Summaries fetched by QA are written to {@code {root}/{study_id}/qa/{study_id}_{version}_summary.json}
 * (e.g. {@code .../phs000001/qa/phs000001_v1_summary.json}), apart from the curated source data. A file
 * holds the summary's {@code study} object (or the API's whole response; both are read). Thread-safe.
 *
 * <p>Read, in order: that file; the same name under {@code {root}/{study_id}/rawData/} (where
 * summaries are staged, and where earlier runs cached them); else a summary staged there under the
 * alternative name {@code {study_id}*study_summary.json} (the same document, named differently).
 * Those names need not carry the version, so the one used is the first, those naming the version
 * first, that describes the study version. A file is usable only if its {@code accver} names the
 * study's accession and version: one that names no version could be for any.
 */
final class SummaryCache {

    /**
     * A cache read.
     *
     * @param document the parsed file, or null when there is none or it could not be read
     * @param error    why it could not be read (unreadable JSON, an S3 error); null when absent or read
     */
    record Entry(String uri, JsonNode document, String error) {

        boolean found() {
            return document != null;
        }
    }

    private final IoResolver io;
    private final ObjectMapper mapper;
    private final String root;

    SummaryCache(IoResolver io, ObjectMapper mapper, String root) {
        this.io = io;
        this.mapper = mapper;
        this.root = root.endsWith("/") ? root : root + "/";
    }

    /** Where a fetched summary is cached: e.g. {@code s3://bucket/root/phs000001/qa/phs000001_v1_summary.json}. */
    String uri(String studyId, String version) {
        return root + studyId + "/qa/" + fileName(studyId, version);
    }

    /** The same name under the study's rawData folder, where summaries are staged. */
    String stagedUri(String studyId, String version) {
        return root + studyId + "/rawData/" + fileName(studyId, version);
    }

    private static String fileName(String studyId, String version) {
        return studyId + "_" + version + "_summary.json";
    }

    /** {@code {study_id}*study_summary.json}, case-insensitively: the alternative summary name. */
    static boolean isAlternativeSummaryName(String fileName, String studyId) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.startsWith(studyId.toLowerCase(Locale.ROOT)) && lower.endsWith("study_summary.json");
    }

    /**
     * Never throws: a missing file is not found, any failure is an {@link Entry#error}. The first of the
     * files named above that exists, else the first usable alternatively named one; when none is usable,
     * the first one's error.
     */
    Entry read(String studyId, String version) {
        String uri = uri(studyId, version);
        try {
            for (String canonical : List.of(uri, stagedUri(studyId, version))) {
                if (io.exists(canonical)) {
                    return readFile(canonical, studyId, version);
                }
            }
            String dir = root + studyId + "/rawData/";
            // "v1" is named by "phs004477.v1." or "_v1_", not by "v12".
            Pattern namesVersion = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(version.toLowerCase(Locale.ROOT))
                    + "(?![0-9])");
            List<String> alternatives = io.listFileNames(dir).stream()
                    .filter(name -> isAlternativeSummaryName(name, studyId))
                    .sorted(Comparator.comparing((String name) -> !namesVersion.matcher(name.toLowerCase(Locale.ROOT)).find())
                            .thenComparing(name -> name))
                    .toList();
            List<Entry> unusable = new ArrayList<>();
            for (String name : alternatives) {
                Entry entry = readFile(dir + name, studyId, version);
                if (entry.found()) {
                    return entry;
                }
                unusable.add(entry);
            }
            return unusable.isEmpty() ? new Entry(uri, null, null) : unusable.get(0);
        } catch (RuntimeException e) {
            return new Entry(uri, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** The file at uri, usable only as a summary of studyId.version. */
    private Entry readFile(String uri, String studyId, String version) {
        try (InputStream in = io.openInput(uri)) {
            JsonNode doc = mapper.readTree(in);
            if (doc == null || !DbgapStudySummary.studyObject(doc).isObject()
                    || !DbgapStudySummary.studyObject(doc).has("consent_groups")) {
                return new Entry(uri, null, "not a study summary (no consent_groups)");
            }
            DbgapStudySummary s = DbgapStudySummary.fromDocument(doc);
            if (!QaSupport.describes(s, studyId, version)) {
                return new Entry(uri, null, s.accession() == null || s.version() == null
                        ? "names no study version (accver accession " + s.accession() + ", version " + s.version()
                                + "), so it could be for any"
                        : "describes " + QaSupport.named(s) + ", not " + studyId + "." + version);
            }
            return new Entry(uri, doc, null);
        } catch (IOException e) {
            return new Entry(uri, null, "unreadable JSON: " + e.getMessage());
        } catch (RuntimeException e) {
            return new Entry(uri, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Writes the summary's {@code study} object, indented. Throws on failure. */
    void write(String studyId, String version, JsonNode study) throws IOException {
        io.writeOutput(uri(studyId, version), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(study));
    }
}
