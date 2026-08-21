package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllConceptsRowTest {

    @Test
    void nonNumeric_factory_sets_correct_defaults() {
        AllConceptsRow row = AllConceptsRow.nonNumeric("uuid-1", "µ_consentsµ", "phs001.c1");

        assertThat(row.hpdsId()).isEqualTo("uuid-1");
        assertThat(row.conceptPath()).isEqualTo("µ_consentsµ");
        assertThat(row.numericValue()).isEmpty();
        assertThat(row.nonNumericValue()).isEqualTo("phs001.c1");
        assertThat(row.timestamp()).isEqualTo("0");
    }

    @Test
    void rejects_null_hpds_id() {
        assertThatThrownBy(() -> AllConceptsRow.nonNumeric(null, "µpathµ", "val"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hpdsId");
    }

    @Test
    void rejects_blank_hpds_id() {
        assertThatThrownBy(() -> AllConceptsRow.nonNumeric("  ", "µpathµ", "val"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hpdsId");
    }

    @Test
    void rejects_null_concept_path() {
        assertThatThrownBy(() -> AllConceptsRow.nonNumeric("id", null, "val"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conceptPath");
    }

    @Test
    void rejects_concept_path_not_starting_with_mu() {
        assertThatThrownBy(() -> AllConceptsRow.nonNumeric("id", "bad_pathµ", "val"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start and end with µ");
    }

    @Test
    void rejects_concept_path_not_ending_with_mu() {
        assertThatThrownBy(() -> AllConceptsRow.nonNumeric("id", "µbad_path", "val"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start and end with µ");
    }

    @Test
    void rejects_both_numeric_and_non_numeric_populated() {
        assertThatThrownBy(() -> new AllConceptsRow("id", "µpathµ", "42", "text", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void allows_both_empty() {
        AllConceptsRow row = new AllConceptsRow("id", "µpathµ", "", "", "0");
        assertThat(row.numericValue()).isEmpty();
        assertThat(row.nonNumericValue()).isEmpty();
    }

    @Test
    void null_numeric_becomes_empty_string() {
        AllConceptsRow row = new AllConceptsRow("id", "µpathµ", null, "val", "0");
        assertThat(row.numericValue()).isEmpty();
    }

    @Test
    void null_non_numeric_becomes_empty_string() {
        AllConceptsRow row = new AllConceptsRow("id", "µpathµ", "42", null, "0");
        assertThat(row.nonNumericValue()).isEmpty();
    }

    @Test
    void null_timestamp_defaults_to_zero() {
        AllConceptsRow row = new AllConceptsRow("id", "µpathµ", "", "val", null);
        assertThat(row.timestamp()).isEqualTo("0");
    }

    @Test
    void blank_timestamp_defaults_to_zero() {
        AllConceptsRow row = new AllConceptsRow("id", "µpathµ", "", "val", "  ");
        assertThat(row.timestamp()).isEqualTo("0");
    }

    @Test
    void toCsvLine_quotes_all_fields() {
        AllConceptsRow row = AllConceptsRow.nonNumeric("uuid-1", "µ_consentsµ", "phs001.c1");
        assertThat(row.toCsvLine()).isEqualTo("\"uuid-1\",\"µ_consentsµ\",\"\",\"phs001.c1\",\"0\"");
    }

    @Test
    void toCsvLine_escapes_embedded_quotes() {
        AllConceptsRow row = AllConceptsRow.nonNumeric("id", "µpathµ", "val with \"quotes\"");
        assertThat(row.toCsvLine()).isEqualTo("\"id\",\"µpathµ\",\"\",\"val with \"\"quotes\"\"\",\"0\"");
    }
}
