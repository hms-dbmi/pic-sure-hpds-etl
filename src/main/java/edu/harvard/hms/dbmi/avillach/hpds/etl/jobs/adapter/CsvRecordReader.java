package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Streams the records of an RFC 4180 CSV file, one physical line at a time, strictly as UTF-8 (malformed
 * bytes throw {@link java.nio.charset.MalformedInputException} instead of becoming U+FFFD). Nothing but the
 * current record is held, so a file of any size is read in constant memory.
 *
 * <p>Every line is one record. A line that cannot be read as one (an unterminated quote that no
 * following line closes, text after a closing quote) is returned as a record with {@code fields == null}
 * and costs only that line, never the lines after it. A quoted field that spans lines is returned whole
 * with {@code embeddedLineBreak} set, so the caller can reject it: the runner counts records and lines and
 * needs them equal. A quote left open is followed for at most {@value #MAX_CONTINUATION_LINES} lines.
 */
final class CsvRecordReader implements AutoCloseable {

    /** How many following lines an open quote is followed before the first line is given up as malformed. */
    static final int MAX_CONTINUATION_LINES = 16;

    /**
     * One record. {@code raw} is the text as read (lines joined with LF when the record spans lines),
     * {@code fields} null when the line is not one CSV record.
     */
    record Record(long number, String raw, List<String> fields, boolean embeddedLineBreak) {
        boolean malformed() {
            return fields == null;
        }

        boolean blank() {
            return raw.isEmpty();
        }
    }

    private static final int OK = 0;
    private static final int UNTERMINATED = 1;
    private static final int BAD = 2;

    private final BufferedReader reader;
    private final Deque<String> pushedBack = new ArrayDeque<>();
    private long number;
    private boolean first = true;

    CsvRecordReader(InputStream in) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)), 1 << 16);
    }

    /** The next record, or null at the end of the file. */
    Record next() throws IOException {
        String line = nextLine();
        if (line == null) {
            return null;
        }
        number++;
        long n = number;
        List<String> fields = new ArrayList<>(6);
        int status = line.isEmpty() ? OK : parse(line, fields);
        if (line.isEmpty()) {
            return new Record(n, line, List.of(), false);
        }
        if (status == OK) {
            return new Record(n, line, fields, false);
        }
        if (status == UNTERMINATED) {
            StringBuilder joined = new StringBuilder(line);
            List<String> extra = new ArrayList<>();
            for (int i = 0; i < MAX_CONTINUATION_LINES; i++) {
                // through nextLine(), so lines queued by an earlier give-up are read before the file, in order
                String more = nextLine();
                if (more == null) {
                    break;
                }
                extra.add(more);
                joined.append('\n').append(more);
                fields = new ArrayList<>(6);
                int s = parse(joined, fields);
                if (s == OK) {
                    return new Record(n, joined.toString(), fields, true);
                }
                if (s == BAD) {
                    break;
                }
            }
            for (int i = extra.size() - 1; i >= 0; i--) {
                pushedBack.push(extra.get(i));
            }
        }
        return new Record(n, line, null, false);
    }

    private String nextLine() throws IOException {
        if (!pushedBack.isEmpty()) {
            return pushedBack.pop();
        }
        String line = reader.readLine();
        if (line != null && first) {
            first = false;
            if (!line.isEmpty() && line.charAt(0) == '﻿') {
                line = line.substring(1);
            }
        }
        return line;
    }

    /**
     * Splits one CSV record into {@code out}: fields separated by commas; a field starting with a quote
     * runs to the closing quote, {@code ""} standing for one; any other field is taken as is (a quote inside
     * it is literal, as in commons-csv). Text after a closing quote other than a comma is {@link #BAD}.
     */
    static int parse(CharSequence s, List<String> out) {
        out.clear();
        int n = s.length();
        int i = 0;
        while (true) {
            if (i < n && s.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n) {
                        return UNTERMINATED;
                    }
                    char c = s.charAt(i);
                    if (c == '"') {
                        if (i + 1 < n && s.charAt(i + 1) == '"') {
                            sb.append('"');
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                out.add(sb.toString());
                if (i == n) {
                    return OK;
                }
                if (s.charAt(i) != ',') {
                    return BAD;
                }
                i++;
            } else {
                int j = i;
                while (j < n && s.charAt(j) != ',') {
                    j++;
                }
                out.add(s.subSequence(i, j).toString());
                if (j == n) {
                    return OK;
                }
                i = j + 1;
            }
            if (i == n) {
                out.add("");
                return OK;
            }
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
