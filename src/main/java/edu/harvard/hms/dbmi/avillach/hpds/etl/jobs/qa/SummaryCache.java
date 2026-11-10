package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;

import java.io.IOException;
import java.io.InputStream;

/**
 * dbGaP study summaries kept beside each study's raw data, so the volatile SSTR API is asked for a
 * study version only once: {@code {root}/{study_id}/rawData/{study_id}_{version}_summary.json}, e.g.
 * {@code .../phs004477/rawData/phs004477_v1_summary.json}. A file holds the summary's {@code study}
 * object (or the API's whole response; both are read). Thread-safe.
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

    /** e.g. {@code s3://bucket/root/phs004477/rawData/phs004477_v1_summary.json} for ("phs004477", "v1"). */
    String uri(String studyId, String version) {
        return root + studyId + "/rawData/" + studyId + "_" + version + "_summary.json";
    }

    /** Never throws: a missing file is not found, any failure is an {@link Entry#error}. */
    Entry read(String studyId, String version) {
        String uri = uri(studyId, version);
        try {
            if (!io.exists(uri)) {
                return new Entry(uri, null, null);
            }
            try (InputStream in = io.openInput(uri)) {
                JsonNode doc = mapper.readTree(in);
                if (doc == null || !DbgapStudySummary.studyObject(doc).isObject()
                        || !DbgapStudySummary.studyObject(doc).has("consent_groups")) {
                    return new Entry(uri, null, "not a study summary (no consent_groups)");
                }
                return new Entry(uri, doc, null);
            }
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
