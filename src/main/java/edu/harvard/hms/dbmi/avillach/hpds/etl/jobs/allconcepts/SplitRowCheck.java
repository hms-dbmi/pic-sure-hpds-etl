package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one decision both passes of {@link SplitMergedAllConceptsJob} take for a data row: does it meet
 * the CSVLoader contract (see {@code schemas/allconcepts.tableschema.json}), after the opt-in timestamp
 * conversion? Pass 1 and pass 2 call {@link #run} on the same parsed row, so they cannot disagree.
 *
 * <p>An instance is reused for every row of a pass (the input has billions), so the result is read
 * from fields rather than returned as an object. It never changes the row; the caller applies
 * {@link #convertedTimestamp()} when the row is accepted.
 */
final class SplitRowCheck {

    static final String BAD_FIELD_COUNT = "BAD_FIELD_COUNT";
    static final String BAD_PATIENT_NUM = "BAD_PATIENT_NUM";
    static final String EMPTY_CONCEPT_PATH = "EMPTY_CONCEPT_PATH";
    static final String BAD_NVAL = "BAD_NVAL";
    static final String BAD_TIMESTAMP = "BAD_TIMESTAMP";
    static final String EMBEDDED_HEADER = "EMBEDDED_HEADER";

    private static final Pattern TIMESTAMP = Pattern.compile("-?[0-9]*");

    private final boolean convertIso;
    private final boolean quarantine;
    private final List<String> codes = new ArrayList<>(4);
    private final List<String> messages = new ArrayList<>(4);
    private int patient;
    private boolean hasTimestamp;
    private long timestamp;
    private String convertedTimestamp;

    SplitRowCheck(SplitRepairSettings settings) {
        this.convertIso = settings.convertIso();
        this.quarantine = settings.quarantine();
    }

    /** Checks one non-blank data row; true if it may be written. */
    boolean run(List<String> row) {
        codes.clear();
        messages.clear();
        hasTimestamp = false;
        convertedTimestamp = null;

        // Only when quarantining: a stray header is told apart from other bad patient numbers. Without it
        // such a row is a BAD_PATIENT_NUM error, as always.
        if (quarantine && looksLikeEmbeddedHeader(row)) {
            problem(EMBEDDED_HEADER, "header row in the middle of the data: '" + SplitMergedAllConceptsJob.abbreviate(String.join(",", row)) + "'");
            return false;
        }
        if (row.size() != 5) {
            problem(BAD_FIELD_COUNT, "expected 5 fields, found " + row.size());
            return false;
        }
        Integer p = SplitMergedAllConceptsJob.parseInt(row.get(0));
        if (p == null) {
            problem(BAD_PATIENT_NUM, "patient_num is not an integer: '" + SplitMergedAllConceptsJob.abbreviate(row.get(0)) + "'");
            return false;
        }
        patient = p;
        if (row.get(1).isEmpty()) {
            problem(EMPTY_CONCEPT_PATH, "concept_path is empty");
        }
        String nval = row.get(2);
        if (!nval.isEmpty() && !SplitMergedAllConceptsJob.parsesAsDouble(nval)) {
            problem(BAD_NVAL, "nval is not a number (CSVLoader aborts on Double.parseDouble): '"
                    + SplitMergedAllConceptsJob.abbreviate(nval) + "'");
        }
        checkTimestamp(row.get(4));
        return codes.isEmpty();
    }

    private void checkTimestamp(String ts) {
        if (TIMESTAMP.matcher(ts).matches()) {
            if (ts.isEmpty()) {
                return;
            }
            try {
                timestamp = Long.parseLong(ts);
                hasTimestamp = true;
            } catch (NumberFormatException e) {
                problem(BAD_TIMESTAMP, "timestamp does not fit a 64-bit integer: '" + SplitMergedAllConceptsJob.abbreviate(ts) + "'");
            }
            return;
        }
        if (convertIso) {
            Long millis = isoToEpochMillis(ts);
            if (millis != null) {
                timestamp = millis;
                hasTimestamp = true;
                convertedTimestamp = Long.toString(millis);
                return;
            }
        }
        problem(BAD_TIMESTAMP, "timestamp must be blank or an integer (CSVLoader aborts on anything else): '"
                + SplitMergedAllConceptsJob.abbreviate(ts) + "'");
    }

    /**
     * Epoch milliseconds of an ISO-8601 date-time that carries an explicit offset or {@code Z}
     * ({@code 2023-06-06T15:20:00+00:00}, fractional seconds allowed), else null. A local date-time or a
     * bare date has no instant and is never guessed.
     */
    static Long isoToEpochMillis(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }

    /**
     * A five-field row whose first column is not an integer (the {@code isHeader} test) and that holds no
     * concept path (no backslash or mu in any field). A row that merely has a bad patient number but a
     * real path is a data error, not a header.
     */
    static boolean looksLikeEmbeddedHeader(List<String> row) {
        if (row.size() != 5 || !SplitMergedAllConceptsJob.firstColumnIsNotInteger(row)) {
            return false;
        }
        for (String field : row) {
            if (field.indexOf('\\') >= 0 || field.indexOf('µ') >= 0) {
                return false;
            }
        }
        return true;
    }

    private void problem(String code, String message) {
        codes.add(code);
        messages.add(message);
    }

    /** First failed check: the reason a quarantined row is filed under. */
    String reason() {
        return codes.getFirst();
    }

    int problemCount() {
        return codes.size();
    }

    String code(int i) {
        return codes.get(i);
    }

    String message(int i) {
        return messages.get(i);
    }

    /** Valid after {@link #run} returned true. */
    int patient() {
        return patient;
    }

    boolean hasTimestamp() {
        return hasTimestamp;
    }

    /** The timestamp value (after any conversion) when {@link #hasTimestamp()}. */
    long timestamp() {
        return timestamp;
    }

    /** The epoch-millisecond text to put in column 4 if this row's ISO timestamp was converted, else null. */
    String convertedTimestamp() {
        return convertedTimestamp;
    }
}
