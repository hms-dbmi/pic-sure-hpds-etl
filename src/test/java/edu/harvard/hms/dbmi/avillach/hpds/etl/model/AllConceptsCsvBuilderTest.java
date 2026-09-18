package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AllConceptsCsvBuilderTest {

    @Test
    void empty_builder_produces_empty_output() {
        AllConceptsCsvBuilder builder = new AllConceptsCsvBuilder();
        assertThat(builder.isEmpty()).isTrue();
        assertThat(builder.size()).isZero();
        assertThat(builder.build()).isEmpty();
    }

    @Test
    void single_row_produces_one_line_no_header() {
        AllConceptsCsvBuilder builder = new AllConceptsCsvBuilder();
        builder.add(AllConceptsRow.nonNumeric("uuid-1", "µ_consentsµ", "phs001.c1"));

        String csv = new String(builder.build(), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines).hasSize(1);
        assertThat(lines[0]).isEqualTo("\"uuid-1\",\"µ_consentsµ\",\"\",\"phs001.c1\",\"0\"");
    }

    @Test
    void multiple_rows_each_on_own_line() {
        AllConceptsCsvBuilder builder = new AllConceptsCsvBuilder();
        builder.add(AllConceptsRow.nonNumeric("uuid-1", "µ_consentsµ", "phs001.c1"));
        builder.add(AllConceptsRow.nonNumeric("uuid-2", "µ_source_subject_idµ", "SUBJ1"));

        String csv = new String(builder.build(), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines).hasSize(2);
    }

    @Test
    void addAll_accumulates_rows() {
        AllConceptsCsvBuilder builder = new AllConceptsCsvBuilder();
        builder.addAll(List.of(
                AllConceptsRow.nonNumeric("uuid-1", "µaµ", "v1"),
                AllConceptsRow.nonNumeric("uuid-2", "µbµ", "v2")));
        builder.add(AllConceptsRow.nonNumeric("uuid-3", "µcµ", "v3"));

        assertThat(builder.size()).isEqualTo(3);
        assertThat(builder.rows()).hasSize(3);
    }

    @Test
    void rows_returns_unmodifiable_view() {
        AllConceptsCsvBuilder builder = new AllConceptsCsvBuilder();
        builder.add(AllConceptsRow.nonNumeric("uuid-1", "µaµ", "v1"));

        List<AllConceptsRow> rows = builder.rows();
        assertThat(rows).hasSize(1);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> rows.add(AllConceptsRow.nonNumeric("uuid-2", "µbµ", "v2")));
    }
}
