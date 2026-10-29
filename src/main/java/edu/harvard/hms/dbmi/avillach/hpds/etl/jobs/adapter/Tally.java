package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Row accounting of one file: every record read ends as written, rejected or dropped, so
 * {@code input + copies == written + rejected + dropped} holds for each file and for the run. {@code copies}
 * counts the extra deliveries of a global row that goes to several partitions (a row routed to n partitions
 * adds n-1 copies and n written).
 */
final class Tally {

    final String name;
    long input;
    long written;
    long copies;
    long rejected;
    long headers;
    long blankLines;
    /** Global rows of a patient that holds no consent in scope. */
    long noConsent;
    /** Rows that belong to a study that {@code --studies} left out. */
    long excludedStudy;
    /** {@code _studies_consents} rows of a study that the patient holds no consent of. */
    long studyNotHeld;
    /** The expected repeats of a multi-study patient's id row (one per further study), merged into one row. */
    long merged;
    /** Repeats of an id row beyond one per study: duplicates in the global file, merged too. */
    long duplicates;
    /** Data rows under another study's tree, dropped by {@code --foreign-study-rows=drop} (never written, never rejected). */
    long foreignStudy;
    /** Global {@code _consents} rows naming no manifest partition, dropped by {@code --unmatched-consent-rows=drop}. */
    long consentNotInManifest;
    final Map<String, Long> rejectedByCode = new TreeMap<>();
    /** The study node (depth 1) of each dropped foreign row, with the rows under it. Distinct nodes are capped. */
    final Map<String, Long> foreignStudyNodes = new TreeMap<>();
    /** The consent value of each dropped unmatched {@code _consents} row, with the rows holding it. Distinct values are capped. */
    final Map<String, Long> unmatchedConsents = new TreeMap<>();

    /** Most distinct keys kept in {@link #foreignStudyNodes} and {@link #unmatchedConsents}; the rest count under {@link #OTHER}. */
    static final int MAX_DISTINCT = 1000;
    static final String OTHER = "(other)";

    Tally(String name) {
        this.name = name;
    }

    long dropped() {
        return headers + blankLines + noConsent + excludedStudy + studyNotHeld + merged + duplicates + foreignStudy
                + consentNotInManifest;
    }

    void dropForeign(String node) {
        foreignStudy++;
        count(foreignStudyNodes, node);
    }

    void dropUnmatchedConsent(String consent) {
        consentNotInManifest++;
        count(unmatchedConsents, consent);
    }

    private static void count(Map<String, Long> into, String key) {
        into.merge(into.containsKey(key) || into.size() < MAX_DISTINCT ? key : OTHER, 1L, Long::sum);
    }

    /** {@code counts} largest first (ties by name), at most {@code n}, as {@code key (rows), ...}; {@code (+k more)} if cut. */
    static String top(Map<String, Long> counts, int n) {
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(n, sorted.size()); i++) {
            b.append(i == 0 ? "" : ", ").append(AdaptationEngine.abbreviate(sorted.get(i).getKey())).append(" (")
                    .append(sorted.get(i).getValue()).append(')');
        }
        if (sorted.size() > n) {
            b.append(", +").append(sorted.size() - n).append(" more");
        }
        return b.toString();
    }

    boolean balanced() {
        return input + copies == written + rejected + dropped();
    }

    void reject(String code) {
        rejected++;
        rejectedByCode.merge(code, 1L, Long::sum);
    }

    Map<String, Object> droppedDetail() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headers", headers);
        m.put("blankLines", blankLines);
        m.put("mergedIdRows", merged);
        m.put("duplicateIdRows", duplicates);
        m.put("globalNoConsent", noConsent);
        m.put("excludedStudy", excludedStudy);
        m.put("studyNotHeld", studyNotHeld);
        m.put("foreignStudy", foreignStudy);
        m.put("consentNotInManifest", consentNotInManifest);
        return m;
    }
}
