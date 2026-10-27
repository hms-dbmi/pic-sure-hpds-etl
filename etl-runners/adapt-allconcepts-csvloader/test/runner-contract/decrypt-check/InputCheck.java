import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Checks one headerless allConcepts file against what CSVLoader does with it. It reads the CSV the way CSVLoader does
 * ({@code CSVFormat.DEFAULT.withAllowMissingColumnNames()}, UTF-8 with malformed bytes replaced by U+FFFD), so quoted commas and
 * embedded line breaks count as one record.
 *
 * <p>
 * Errors (CSVLoader aborts or would store wrong data; the run must stop): field 1 is not an int (this catches header rows), field 3 is
 * not empty and not a number (for example {@code 2402L}), field 3 holds only white space (CSVLoader types the concept numeric from that
 * row, then drops every text value of the concept), field 5 is not empty and not a whole number that fits a long.
 *
 * <p>
 * Warnings (CSVLoader accepts the record; counted so the report shows them): fewer than 4 fields (CSVLoader skips the record and logs
 * it), more than 5 fields (the extras are ignored), a record containing U+FFFD (a malformed byte; CSVLoader strips it from paths and text
 * values), an empty concept path. Timestamps of 0 and below 1e11 are counted separately.
 *
 * <p>
 * Usage: InputCheck {@code <file>}. stdout: one JSON object. exit 0 = no errors, 2 = errors (details in the JSON), 1 = could not read.
 * The JSON never contains concept paths or values beyond 40 characters of the offending field 1 / 3 / 5.
 */
public class InputCheck {

    private static final int MAX_EXAMPLES = 5;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: InputCheck <file>");
            System.exit(1);
        }
        long rows = 0, zero = 0, likelySeconds = 0, errors = 0;
        long shortRecords = 0, extraFields = 0, replacementChars = 0, emptyPaths = 0;
        Set<String> concepts = new HashSet<>();
        Map<String, Long> byKind = new LinkedHashMap<>();
        List<String> examples = new ArrayList<>();
        // InputStreamReader replaces malformed input, like the FileReader CSVLoader uses; Files.newBufferedReader would throw
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8), 1 << 20);
            CSVParser parser = CSVFormat.DEFAULT.withAllowMissingColumnNames().parse(reader)) {
            for (CSVRecord record : parser) {
                rows++;
                for (String field : record) {
                    if (field.indexOf('\uFFFD') >= 0) {
                        replacementChars++;
                        break;
                    }
                }
                if (record.size() < 4) {
                    shortRecords++;
                    continue;
                }
                if (record.size() > 5) {
                    extraFields++;
                }
                String kind = null;
                String detail = "";
                if (!isInt(record.get(0))) {
                    kind = "BAD_PATIENT_NUM";
                    detail = "field1=" + clip(record.get(0));
                } else if (!record.get(2).isEmpty() && record.get(2).trim().isEmpty()) {
                    // CSVLoader tests nval.isEmpty(), not trim(): this row makes the concept numeric and stores no value, so the
                    // concept's text values would all be dropped silently
                    kind = "BLANK_NVAL";
                    detail = "field3 holds only white space (" + record.get(2).length() + " characters)";
                } else if (!record.get(2).isEmpty() && !isDouble(record.get(2))) {
                    kind = "BAD_NVAL";
                    detail = "field3=" + clip(record.get(2));
                } else if (record.size() > 4 && !record.get(4).isEmpty()) {
                    String ts = record.get(4);
                    Long value = parseLong(ts);
                    if (value == null) {
                        kind = "BAD_TIMESTAMP";
                        detail = "field5=" + clip(ts);
                    } else if (value == 0) {
                        zero++;
                    } else if (value > 0 && value < 100_000_000_000L) {
                        likelySeconds++;
                    }
                }
                if (kind != null) {
                    errors++;
                    byKind.merge(kind, 1L, Long::sum);
                    if (examples.size() < MAX_EXAMPLES) {
                        examples.add("{\"kind\":" + q(kind) + ",\"row\":" + record.getRecordNumber() + ",\"detail\":" + q(detail) + "}");
                    }
                } else {
                    if (record.get(1).isEmpty()) {
                        emptyPaths++;
                    }
                    concepts.add(record.get(1));
                }
            }
        } catch (Exception e) {
            // a malformed CSV (for example an unterminated quote) is an input error too, not an infrastructure one
            boolean malformed = e instanceof IllegalStateException || e instanceof UncheckedIOException;
            String kind = malformed ? "MALFORMED_CSV" : "UNREADABLE";
            System.out.println("{\"file\":" + q(Path.of(args[0]).getFileName().toString()) + ",\"rows\":" + rows + ",\"errorCount\":1,"
                + "\"errorsByKind\":{\"" + kind + "\":1},\"firstErrors\":[{\"kind\":" + q(kind) + ",\"row\":" + (rows + 1)
                + ",\"detail\":" + q(e.getClass().getSimpleName()) + "}]}");
            System.exit(malformed ? 2 : 1);
        }
        StringBuilder kinds = new StringBuilder();
        for (var e : byKind.entrySet()) {
            kinds.append(kinds.length() == 0 ? "" : ",").append(q(e.getKey())).append(':').append(e.getValue());
        }
        System.out.println(
            "{\"file\":" + q(Path.of(args[0]).getFileName().toString()) + ",\"rows\":" + rows + ",\"distinctConcepts\":" + concepts.size()
                + ",\"zeroTimestamps\":" + zero + ",\"likelySecondsTimestamps\":" + likelySeconds + ",\"warnings\":{\"shortRecordsSkipped\":"
                + shortRecords + ",\"recordsWithExtraFields\":" + extraFields
                + ",\"recordsWithReplacementChars\":" + replacementChars + ",\"emptyConceptPaths\":" + emptyPaths + "},\"errorCount\":"
                + errors + ",\"errorsByKind\":{" + kinds + "},\"firstErrors\":[" + String.join(",", examples) + "]}"
        );
        System.exit(errors == 0 ? 0 : 2);
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDouble(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
