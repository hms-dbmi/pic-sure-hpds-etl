package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reads delimited files (CSV/TSV) as a stream of header-keyed rows. Streaming rather
 * than materializing the whole file keeps memory flat for large migration inputs.
 *
 * <p>Each row is a {@code Map<String,String>} keyed by the header column names.
 */
@Component
public class DelimitedReader {

    public static final char COMMA = ',';
    public static final char TAB = '\t';

    private final CsvMapper csvMapper = new CsvMapper();

    /**
     * Streams rows from a delimited input. The returned stream is lazy and MUST be
     * closed (use try-with-resources) so the underlying input stream is released.
     *
     * @param in        source stream (closed when the returned stream is closed)
     * @param separator column separator, e.g. {@link #COMMA} or {@link #TAB}
     */
    public Stream<Map<String, String>> stream(InputStream in, char separator) {
        CsvSchema schema = CsvSchema.emptySchema()
                .withHeader()
                .withColumnSeparator(separator)
                .withNullValue("");
        try {
            MappingIterator<Map<String, String>> it = csvMapper
                    .readerFor(Map.class)
                    .with(schema)
                    .readValues(in);
            return StreamSupport
                    .stream(java.util.Spliterators.spliteratorUnknownSize(
                            new NormalizingIterator(it), java.util.Spliterator.ORDERED), false)
                    .onClose(() -> closeQuietly(it, in));
        } catch (IOException e) {
            closeQuietly(null, in);
            throw new DataException("Failed to read delimited input", e);
        }
    }

    /** Convenience: read a delimited input fully into a list (small files/tests only). */
    public List<Map<String, String>> readAll(InputStream in, char separator) {
        try (Stream<Map<String, String>> s = stream(in, separator)) {
            return s.toList();
        }
    }

    /**
     * Streams rows from a headerless delimited input as positional column values --
     * for files with no header row (e.g. {@code consents.csv}, patient mapping exports).
     * The returned stream is lazy and MUST be closed (use try-with-resources).
     *
     * @param in        source stream (closed when the returned stream is closed)
     * @param separator column separator, e.g. {@link #COMMA} or {@link #TAB}
     */
    public Stream<List<String>> streamRows(InputStream in, char separator) {
        CsvSchema schema = CsvSchema.emptySchema()
                .withColumnSeparator(separator)
                .withNullValue("");
        try {
            // String[] + WRAP_AS_ARRAY: with no header, a plain schema still rejects rows
            // wider than its (zero) declared columns -- WRAP_AS_ARRAY bypasses that check.
            MappingIterator<String[]> it = csvMapper
                    .readerFor(String[].class)
                    .with(CsvParser.Feature.WRAP_AS_ARRAY)
                    .with(schema)
                    .readValues(in);
            return StreamSupport
                    .stream(java.util.Spliterators.spliteratorUnknownSize(
                            new NormalizingRowIterator(it), java.util.Spliterator.ORDERED), false)
                    .onClose(() -> closeQuietly(it, in));
        } catch (IOException e) {
            closeQuietly(null, in);
            throw new DataException("Failed to read delimited input", e);
        }
    }

    /** Wraps Jackson's iterator so IOExceptions become DataExceptions and nulls become "". */
    private static final class NormalizingIterator implements java.util.Iterator<Map<String, String>> {
        private final MappingIterator<Map<String, String>> delegate;

        NormalizingIterator(MappingIterator<Map<String, String>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            try {
                return delegate.hasNextValue();
            } catch (IOException e) {
                throw new DataException("Malformed delimited row", e);
            }
        }

        @Override
        public Map<String, String> next() {
            try {
                Map<String, String> row = delegate.nextValue();
                Map<String, String> normalized = new LinkedHashMap<>();
                row.forEach((k, v) -> normalized.put(k, v == null ? "" : v));
                return normalized;
            } catch (IOException e) {
                throw new DataException("Malformed delimited row", e);
            }
        }
    }

    /** Same normalization as {@link NormalizingIterator}, but for headerless positional rows. */
    private static final class NormalizingRowIterator implements java.util.Iterator<List<String>> {
        private final MappingIterator<String[]> delegate;

        NormalizingRowIterator(MappingIterator<String[]> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            try {
                return delegate.hasNextValue();
            } catch (IOException e) {
                throw new DataException("Malformed delimited row", e);
            }
        }

        @Override
        public List<String> next() {
            try {
                String[] row = delegate.nextValue();
                List<String> normalized = new java.util.ArrayList<>(row.length);
                for (String v : row) {
                    normalized.add(v == null ? "" : v);
                }
                return normalized;
            } catch (IOException e) {
                throw new DataException("Malformed delimited row", e);
            }
        }
    }

    private static void closeQuietly(MappingIterator<?> it, InputStream in) {
        try {
            if (it != null) it.close();
        } catch (IOException e) {
            try {
                if (in != null) in.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new UncheckedIOException(e);
        }
        try {
            if (in != null) in.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
