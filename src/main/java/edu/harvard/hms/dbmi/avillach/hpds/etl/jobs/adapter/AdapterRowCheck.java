package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one decision the adapter takes for every data row: does it meet the CSVLoader contract, and if so what
 * is written. Modelled on {@code SplitRowCheck} of branch {@code split-by-consent}
 * ({@code jobs/allconcepts/SplitRowCheck.java}); the semantics it needs are copied here, nothing is imported
 * from it. It differs from it in being fail-fast per row (one reject code, the first check that fails), in
 * accepting {@code \} and {@code µ} as delimiters, and in the timestamp and path rules stated below.
 *
 * <p>Checks, in order, each with its reject code:
 * <ol>
 *   <li>exactly 5 fields ({@value #MALFORMED_RECORD}), none holding a CR or LF ({@value #EMBEDDED_LINE_BREAK});</li>
 *   <li>patient id an integer in [0, 2147483647] ({@value #BAD_PATIENT_ID});</li>
 *   <li>concept path ({@value #NON_CANONICAL_PATH}): wrapped, that is, it starts and ends with the same
 *       delimiter ({@code µ} or {@code \}); no empty segment, no {@code .} or {@code ..} segment, no control
 *       character, no segment holding the other delimiter; and ADR-0014 R2 for the first (study) node: no
 *       leading or trailing character up to U+0020 and no U+FFFD (R1 is the wrapped form);</li>
 *   <li>numeric value empty or a finite decimal number ({@value #NUMERIC_NVAL}); {@code nan}, {@code inf},
 *       a trailing type letter or white space are refused;</li>
 *   <li>timestamp ({@value #BAD_TIMESTAMP}): empty or a whole number that is 0 is written empty (zero-timestamps=blank);
 *       any other integer is kept; an ISO-8601 instant with {@code Z} or an offset, a zoneless date-time
 *       ({@code uuuu-MM-dd HH:mm:ss[.fraction]} or ISO {@code T}, read as UTC) or a bare date (midnight UTC)
 *       becomes epoch milliseconds, and 0 after conversion is written empty too.</li>
 * </ol>
 * A path is never rewritten: it is written exactly as read (CSVLoader turns {@code µ} into {@code \}).
 *
 * <p>An instance is reused for every row of a pass; the result is read from fields.
 */
final class AdapterRowCheck {

    static final String MALFORMED_RECORD = "MALFORMED_RECORD";
    static final String EMBEDDED_LINE_BREAK = "EMBEDDED_LINE_BREAK";
    static final String BAD_PATIENT_ID = "BAD_PATIENT_ID";
    static final String NON_CANONICAL_PATH = "NON_CANONICAL_PATH";
    static final String NUMERIC_NVAL = "NUMERIC_NVAL";
    static final String BAD_TIMESTAMP = "BAD_TIMESTAMP";

    /** The allConcepts contract: patient, concept_path, nval, tval, timestamp. */
    static final int FIELDS = 5;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,10}");
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,19}");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?");

    // "uuuu-MM-dd HH:mm:ss" with an optional fraction of 1 to 9 digits, as in the ingest-service TimestampParser
    private static final DateTimeFormatter SPACE_SEPARATED = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss").optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .toFormatter().withResolverStyle(ResolverStyle.STRICT);

    private String code;
    private String message;
    private int patient;
    private String path;
    private String[] segments;
    private String nval;
    private String tval;
    private String timestamp;
    private boolean timestampConverted;
    private boolean timestampBlanked;

    /** Checks one parsed record; true if it may be written. On false, {@link #code()} says why. */
    boolean run(List<String> row, boolean embeddedLineBreak) {
        code = null;
        message = null;
        segments = null;
        timestampConverted = false;
        timestampBlanked = false;
        if (row.size() != FIELDS) {
            return fail(MALFORMED_RECORD, "expected 5 fields, found " + row.size());
        }
        if (embeddedLineBreak || hasLineBreak(row)) {
            return fail(EMBEDDED_LINE_BREAK, "a field holds a line break (the runner counts lines and records, which must be equal)");
        }
        String id = row.get(0);
        if (!DIGITS.matcher(id).matches() || Long.parseLong(id) > Integer.MAX_VALUE) {
            return fail(BAD_PATIENT_ID, "patient id is not an integer in [0, 2147483647]");
        }
        patient = (int) Long.parseLong(id);
        path = row.get(1);
        String pathProblem = pathProblem(path);
        if (pathProblem != null) {
            return fail(NON_CANONICAL_PATH, pathProblem);
        }
        nval = row.get(2);
        if (!nval.isEmpty() && !isFiniteDecimal(nval)) {
            return fail(NUMERIC_NVAL, "numeric value is not empty and not a finite number");
        }
        tval = row.get(3);
        if (!checkTimestamp(row.get(4))) {
            return false;
        }
        return true;
    }

    /** Records that the line was not one CSV record at all: always a reject, code {@value #MALFORMED_RECORD}. */
    boolean runMalformed() {
        code = null;
        segments = null;
        return fail(MALFORMED_RECORD, "the line is not one CSV record (an unterminated quote, or text after a closing quote)");
    }

    private static boolean hasLineBreak(List<String> row) {
        for (String f : row) {
            if (f.indexOf('\n') >= 0 || f.indexOf('\r') >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Null when the path is acceptable (and {@link #segments()} is set), else why not. */
    private String pathProblem(String p) {
        if (p.length() < 3) {
            return "concept path is empty or too short to hold a segment";
        }
        char d = p.charAt(0);
        if (d != 'µ' && d != '\\') {
            return "concept path must start with a delimiter (µ or \\)";
        }
        if (p.charAt(p.length() - 1) != d) {
            return "concept path must end with the delimiter it starts with";
        }
        char other = d == 'µ' ? '\\' : 'µ';
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return "concept path holds a control character";
            }
            if (c == other) {
                return "concept path mixes the delimiters µ and \\";
            }
        }
        String[] segs = split(p, d);
        for (String s : segs) {
            if (s.isEmpty()) {
                return "concept path has an empty segment";
            }
            if (s.equals(".") || s.equals("..")) {
                return "concept path has a '.' or '..' segment";
            }
        }
        String study = segs[0];
        if (study.charAt(0) <= ' ' || study.charAt(study.length() - 1) <= ' ' || study.indexOf('�') >= 0) {
            return "the first node (the study) changes under the loaders' trimming or holds U+FFFD (ADR-0014 R2)";
        }
        segments = segs;
        return null;
    }

    /** The text between the delimiters of a wrapped path; an empty segment stays an (empty) element. */
    private static String[] split(String wrapped, char d) {
        int count = 0;
        for (int i = 1; i < wrapped.length() - 1; i++) {
            if (wrapped.charAt(i) == d) {
                count++;
            }
        }
        String[] out = new String[count + 1];
        int start = 1;
        int k = 0;
        for (int i = 1; i < wrapped.length() - 1; i++) {
            if (wrapped.charAt(i) == d) {
                out[k++] = wrapped.substring(start, i);
                start = i + 1;
            }
        }
        out[k] = wrapped.substring(start, wrapped.length() - 1);
        return out;
    }

    private static boolean isFiniteDecimal(String s) {
        if (!DECIMAL.matcher(s).matches()) {
            return false;
        }
        try {
            return Double.isFinite(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean checkTimestamp(String ts) {
        timestamp = "";
        if (ts.isEmpty()) {
            return true;
        }
        long value;
        if (INTEGER.matcher(ts).matches()) {
            try {
                value = Long.parseLong(ts);
            } catch (NumberFormatException e) {
                return fail(BAD_TIMESTAMP, "timestamp does not fit a 64-bit integer");
            }
        } else {
            Long millis = isoToMillis(ts);
            if (millis == null) {
                return fail(BAD_TIMESTAMP, "timestamp must be empty, an integer or an ISO-8601 date or date-time");
            }
            value = millis;
            timestampConverted = true;
        }
        if (value == 0) {
            timestampBlanked = true;
            timestamp = "";
        } else {
            timestamp = Long.toString(value);
        }
        return true;
    }

    /**
     * Epoch milliseconds of an ISO-8601 instant (with {@code Z} or an offset), a zoneless date-time (space
     * separated, or ISO {@code T}; read as UTC) or a date (midnight UTC); null if {@code s} is none of those
     * or beyond a 64-bit millisecond count. The forms follow {@code TimestampParser.parse} of the hpds-ingest
     * ingest-service, as {@code SplitRowCheck} does.
     */
    static Long isoToMillis(String s) {
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException ignored) {
            // next form
        }
        try {
            try {
                return LocalDateTime.parse(s, SPACE_SEPARATED).toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
            }
        } catch (DateTimeParseException | ArithmeticException ignored) {
            // next form
        }
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }

    private boolean fail(String c, String m) {
        code = c;
        message = m;
        return false;
    }

    /** Whether a first record is a header line: its first field is {@code patient_num} or {@code hpds_id}, in any case. */
    static boolean isHeader(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        String first = fields.get(0).trim();
        return first.equalsIgnoreCase("patient_num") || first.equalsIgnoreCase("hpds_id");
    }

    /** The output line of the accepted row: five double-quoted fields, no line terminator. */
    String outputLine() {
        StringBuilder b = new StringBuilder(path.length() + tval.length() + nval.length() + 24);
        b.append('"').append(patient).append("\",\"");
        quoted(b, path).append("\",\"");
        quoted(b, nval).append("\",\"");
        quoted(b, tval).append("\",\"");
        quoted(b, timestamp).append('"');
        return b.toString();
    }

    private static StringBuilder quoted(StringBuilder b, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                b.append('"');
            }
            b.append(c);
        }
        return b;
    }

    /** The first failed check's reject code; null after a row that passed. */
    String code() {
        return code;
    }

    String message() {
        return message;
    }

    int patient() {
        return patient;
    }

    String path() {
        return path;
    }

    /** The path's segments (delimiters removed), valid after a row that passed. */
    String[] segments() {
        return segments;
    }

    String nval() {
        return nval;
    }

    String tval() {
        return tval;
    }

    /** The timestamp to write (empty for none), valid after a row that passed. */
    String timestamp() {
        return timestamp;
    }

    /** True if an ISO date or date-time was converted to epoch milliseconds. */
    boolean timestampConverted() {
        return timestampConverted;
    }

    /** True if the timestamp was 0 (before or after conversion) and is written empty. */
    boolean timestampBlanked() {
        return timestampBlanked;
    }
}
