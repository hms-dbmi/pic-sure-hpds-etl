package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import java.util.LinkedHashMap;
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
    final Map<String, Long> rejectedByCode = new TreeMap<>();

    Tally(String name) {
        this.name = name;
    }

    long dropped() {
        return headers + blankLines + noConsent + excludedStudy + studyNotHeld + merged + duplicates;
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
        return m;
    }
}
