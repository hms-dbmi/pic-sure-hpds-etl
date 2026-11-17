package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The parts of a dbGaP SSTR study summary ({@code GET /study/{phs}.{v}/summary}) the consent count
 * QA uses: which study version answered, and each consent group's subject and sample counts.
 *
 * <p>The published schema (dbgap_study.v1.schema.json) marks no field required, so every field is
 * read defensively: an absent or null value becomes null here, and a consent group without a
 * usable {@code code} is dropped (it cannot be matched to anything) and counted in
 * {@link #groupsWithoutCode}.
 *
 * @param accession          {@code study.accver.accession}, e.g. "phs001412"; null if absent
 * @param version            {@code study.accver.version}; null if absent or not a number
 * @param consentGroups      {@code study.consent_groups[]} in response order
 * @param groupsWithoutCode  consent groups dropped for a missing or non-numeric code
 */
public record DbgapStudySummary(String accession, Integer version, List<ConsentGroup> consentGroups,
                                int groupsWithoutCode) {

    /**
     * One {@code consent_groups[]} entry.
     *
     * @param code         the consent code as text ("0", "1", ...), matching how the DB stores it
     * @param shortName    e.g. "HMB-IRB-NPU"; null if absent
     * @param subjectCount {@code subject_count}; null if absent or not a number
     * @param sampleCount  {@code sample_count}; null if absent or not a number
     */
    public record ConsentGroup(String code, String shortName, Long subjectCount, Long sampleCount) {

        /** Without a sample count. */
        public ConsentGroup(String code, String shortName, Long subjectCount) {
            this(code, shortName, subjectCount, null);
        }
    }

    /**
     * Reads a summary document: the API's whole response ({@code {"study": {...}, ...}}) or just its
     * {@code study} object (how {@code {study}_{version}_summary.json} files are stored).
     */
    public static DbgapStudySummary fromDocument(JsonNode document) {
        return from(studyObject(document));
    }

    /** The {@code study} object of a summary document, whichever of the two shapes it has. */
    public static JsonNode studyObject(JsonNode document) {
        JsonNode study = document.path("study");
        return study.isObject() ? study : document;
    }

    /** Reads the {@code study} object of a summary response. */
    public static DbgapStudySummary from(JsonNode study) {
        JsonNode accver = study.path("accver");
        String accession = text(accver.path("accession"));
        Long version = number(accver.path("version"));
        List<ConsentGroup> groups = new ArrayList<>();
        int withoutCode = 0;
        JsonNode array = study.path("consent_groups");
        if (array.isArray()) {
            for (JsonNode g : array) {
                Long code = g.isObject() ? number(g.path("code")) : null;
                if (code == null) {
                    withoutCode++;
                    continue;
                }
                groups.add(new ConsentGroup(String.valueOf(code), text(g.path("short_name")),
                        number(g.path("subject_count")), number(g.path("sample_count"))));
            }
        }
        return new DbgapStudySummary(accession, version == null ? null : version.intValue(), List.copyOf(groups),
                withoutCode);
    }

    private static String text(JsonNode node) {
        return node.isValueNode() && !node.isNull() && !node.asText().isBlank() ? node.asText().trim() : null;
    }

    /** An integral JSON number, or a string holding only digits (some APIs quote numbers). */
    private static Long number(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual() && node.asText().trim().matches("\\d{1,18}")) {
            return Long.parseLong(node.asText().trim());
        }
        return null;
    }
}
