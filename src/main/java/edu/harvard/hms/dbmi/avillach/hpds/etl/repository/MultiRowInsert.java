package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Writes a list of rows as one {@code INSERT ... VALUES (..), (..), ...} statement rather than a
 * JDBC batch of single-row inserts: one parse and one execution on the server instead of one per
 * row. Unlike the driver's {@code reWriteBatchedInserts}, the statement's update count is exact, so
 * callers can still report how many rows they inserted.
 *
 * <p>Rows are split into several statements only when one would exceed Postgres's bind-parameter
 * limit.
 */
final class MultiRowInsert {

    /** Postgres's per-statement bind-parameter limit (a 16-bit count). */
    static final int MAX_PARAMS = 65_535;

    private MultiRowInsert() {}

    /**
     * @param insertInto   {@code INSERT INTO table (col, ...)}, without {@code VALUES}
     * @param onConflict   the trailing {@code ON CONFLICT ...} clause
     * @param columnCount  values per row; must match {@code values}' array length
     * @param values       a row's bind values, in column order
     * @return rows inserted or updated, as Postgres reports them
     */
    static <T> int execute(JdbcTemplate jdbc, String insertInto, String onConflict, int columnCount,
                           List<T> rows, Function<T, Object[]> values) {
        return execute(jdbc, insertInto, onConflict, columnCount, rows, values, MAX_PARAMS);
    }

    /** As above, with the per-statement parameter limit as an argument so tests can make it tiny. */
    static <T> int execute(JdbcTemplate jdbc, String insertInto, String onConflict, int columnCount,
                           List<T> rows, Function<T, Object[]> values, int maxParams) {
        if (columnCount <= 0) {
            throw new IllegalArgumentException("columnCount must be positive, got " + columnCount);
        }
        if (maxParams < columnCount) {
            throw new IllegalArgumentException(
                    "maxParams (" + maxParams + ") cannot fit one row of " + columnCount + " columns");
        }
        int rowsPerStatement = maxParams / columnCount;
        String tuple = "(" + String.join(", ", Collections.nCopies(columnCount, "?")) + ")";
        int affected = 0;
        for (int i = 0; i < rows.size(); i += rowsPerStatement) {
            List<T> chunk = rows.subList(i, Math.min(i + rowsPerStatement, rows.size()));
            String sql = insertInto + " VALUES " + String.join(", ", Collections.nCopies(chunk.size(), tuple))
                    + " " + onConflict;
            List<Object> args = new ArrayList<>(chunk.size() * columnCount);
            for (int r = 0; r < chunk.size(); r++) {
                Object[] row = values.apply(chunk.get(r));
                // A short or long row would shift every later row's values into the wrong columns.
                if (row == null || row.length != columnCount) {
                    throw new IllegalArgumentException("Row " + (i + r) + " has "
                            + (row == null ? "no" : String.valueOf(row.length)) + " values; expected " + columnCount);
                }
                Collections.addAll(args, row);
            }
            affected += jdbc.update(sql, args.toArray());
        }
        return affected;
    }
}
