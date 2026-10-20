package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Statement splitting in {@link MultiRowInsert}, with a tiny parameter limit standing in for
 * Postgres's 65,535 so the chunk boundaries are visible.
 */
class MultiRowInsertTest {

    private static final String INSERT = "INSERT INTO t (a, b)";
    private static final String CONFLICT = "ON CONFLICT DO NOTHING";

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        // Each statement reports one affected row per bound row, like a plain insert.
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenAnswer(inv -> ((Object[]) inv.getRawArguments()[1]).length / 2);
    }

    private static Object[] pair(int i) {
        return new Object[]{i, "v" + i};
    }

    @Test
    void splits_rows_at_max_params_divided_by_column_count() {
        // 5 params fit 2 two-column rows per statement: 5 rows -> 2 + 2 + 1.
        int affected = MultiRowInsert.execute(jdbc, INSERT, CONFLICT, 2, List.of(1, 2, 3, 4, 5),
                MultiRowInsertTest::pair, 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).update(sql.capture(), args.capture());
        assertThat(sql.getAllValues()).containsExactly(
                INSERT + " VALUES (?, ?), (?, ?) " + CONFLICT,
                INSERT + " VALUES (?, ?), (?, ?) " + CONFLICT,
                INSERT + " VALUES (?, ?) " + CONFLICT);
        assertThat(args.getAllValues().get(0)).containsExactly(1, "v1", 2, "v2");
        assertThat(args.getAllValues().get(1)).containsExactly(3, "v3", 4, "v4");
        assertThat(args.getAllValues().get(2)).containsExactly(5, "v5");
        assertThat(affected).isEqualTo(5);
    }

    @Test
    void rows_that_exactly_fill_statements_leave_no_empty_tail() {
        MultiRowInsert.execute(jdbc, INSERT, CONFLICT, 2, List.of(1, 2, 3, 4), MultiRowInsertTest::pair, 4);
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void the_production_limit_fits_rows_up_to_the_postgres_parameter_cap() {
        MultiRowInsert.execute(jdbc, INSERT, CONFLICT, 2, List.of(1, 2, 3), MultiRowInsertTest::pair);
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
        assertThat(MultiRowInsert.MAX_PARAMS / 4).isEqualTo(16_383);
    }

    @Test
    void no_rows_issue_no_statement() {
        assertThat(MultiRowInsert.execute(jdbc, INSERT, CONFLICT, 2, List.<Integer>of(),
                MultiRowInsertTest::pair, 5)).isZero();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejects_a_non_positive_column_count() {
        assertThatThrownBy(() -> MultiRowInsert.execute(jdbc, INSERT, CONFLICT, 0, List.of(1),
                MultiRowInsertTest::pair))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columnCount");
    }

    @Test
    void rejects_a_limit_too_small_for_one_row() {
        assertThatThrownBy(() -> MultiRowInsert.execute(jdbc, INSERT, CONFLICT, 3, List.of(1),
                i -> new Object[]{i, i, i}, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxParams");
    }

    /** A short row would shift every later row's values into the wrong columns. */
    @Test
    void rejects_a_row_whose_value_count_differs_from_the_column_count() {
        assertThatThrownBy(() -> MultiRowInsert.execute(jdbc, INSERT, CONFLICT, 2, List.of(1, 2),
                i -> i == 2 ? new Object[]{i} : pair(i), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Row 1 has 1 values; expected 2");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
