package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
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

    /** Which accepted ISO form a converted timestamp was written in; {@code label} is the manifest key. */
    enum TimestampForm {
        /** Carries {@code Z} or an offset: the instant is known. */
        OFFSET("offset"),
        /** Zoneless date-time (space-separated or ISO {@code T}): assumed to be UTC. */
        ZONELESS_UTC("zoneless-utc"),
        /** Bare date: assumed to be midnight UTC. */
        DATE_UTC("date-utc");

        final String label;

        TimestampForm(String label) {
            this.label = label;
        }
    }

    private static final Pattern TIMESTAMP = Pattern.compile("-?[0-9]*");

    // "uuuu-MM-dd HH:mm:ss" with an optional fraction of 1 to 9 digits, as in the ingest-service TimestampParser
    private static final DateTimeFormatter SPACE_SEPARATED = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss").optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .toFormatter().withResolverStyle(ResolverStyle.STRICT);

    private final boolean convertIso;
    private final boolean quarantine;
    private final List<String> codes = new ArrayList<>(4);
    private final List<String> messages = new ArrayList<>(4);
    private int patient;
    private boolean hasTimestamp;
    private long timestamp;
    private String convertedTimestamp;
    private TimestampForm convertedForm;

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
        convertedForm = null;

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
            for (TimestampForm form : TimestampForm.values()) {
                Instant instant = parse(ts, form);
                if (instant != null) {
                    long millis;
                    try {
                        millis = instant.toEpochMilli();
                    } catch (ArithmeticException e) {
                        break; // beyond what a 64-bit millisecond count holds
                    }
                    timestamp = millis;
                    hasTimestamp = true;
                    convertedTimestamp = Long.toString(millis);
                    convertedForm = form;
                    return;
                }
            }
        }
        problem(BAD_TIMESTAMP, "timestamp must be blank or an integer (CSVLoader aborts on anything else): '"
                + SplitMergedAllConceptsJob.abbreviate(ts) + "'");
    }

    /**
     * The first {@link TimestampForm} that {@code s} parses as, else null. The forms and their order follow
     * {@code TimestampParser.parse} of the hpds-ingest ingest-service (hms-dbmi/pic-sure-hpds,
     * ingest-service/.../producer/TimestampParser.java), so a timestamp means the same instant here as
     * there: an ISO-8601 instant with {@code Z} or an offset is kept as given; {@code uuuu-MM-dd HH:mm:ss}
     * with an optional fraction of 1 to 9 digits, an ISO local date-time and an ISO local date are read as
     * UTC (a date as midnight). Integers never get here (CSVLoader's own contract).
     */
    static Instant parse(String s, TimestampForm form) {
        try {
            return switch (form) {
                case OFFSET -> Instant.parse(s);
                case ZONELESS_UTC -> {
                    try {
                        yield LocalDateTime.parse(s, SPACE_SEPARATED).toInstant(ZoneOffset.UTC);
                    } catch (DateTimeParseException e) {
                        yield LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
                    }
                }
                case DATE_UTC -> LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().toInstant(ZoneOffset.UTC);
            };
        } catch (DateTimeParseException e) {
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

    /** The form that was converted, when {@link #convertedTimestamp()} is not null. */
    TimestampForm convertedForm() {
        return convertedForm;
    }
}
