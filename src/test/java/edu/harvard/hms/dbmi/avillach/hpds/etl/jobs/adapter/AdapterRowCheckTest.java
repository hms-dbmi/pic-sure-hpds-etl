package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdapterRowCheckTest {

    private final AdapterRowCheck check = new AdapterRowCheck();

    private boolean run(String... f) {
        return check.run(List.of(f), false);
    }

    private static final String P = "µphs000000µdemoµageµ";

    @Test
    void a_good_row_is_written_with_every_field_quoted_and_the_path_as_read() {
        assertThat(run("12", P, "40.5", "", "0")).isTrue();
        assertThat(check.outputLine()).isEqualTo("\"12\",\"µphs000000µdemoµageµ\",\"40.5\",\"\",\"\"");
        assertThat(check.patient()).isEqualTo(12);
        assertThat(check.segments()).containsExactly("phs000000", "demo", "age");
        assertThat(check.timestampBlanked()).isTrue();
    }

    @Test
    void quotes_in_a_value_are_doubled_on_output() {
        assertThat(run("1", P, "", "say \"hi\", ok", "5")).isTrue();
        assertThat(check.outputLine()).isEqualTo("\"1\",\"" + P + "\",\"\",\"say \"\"hi\"\", ok\",\"5\"");
    }

    @Test
    void backslash_paths_are_accepted_and_kept() {
        assertThat(run("1", "\\phs000000\\demo\\age\\", "1", "", "")).isTrue();
        assertThat(check.outputLine()).contains("\"\\phs000000\\demo\\age\\\"");
    }

    @Test
    void a_record_that_is_not_five_fields_is_malformed() {
        assertThat(run("1", P, "")).isFalse();
        assertThat(check.code()).isEqualTo(AdapterRowCheck.MALFORMED_RECORD);
        assertThat(run("1", P, "", "", "0", "extra")).isFalse();
        assertThat(check.code()).isEqualTo(AdapterRowCheck.MALFORMED_RECORD);
        assertThat(check.runMalformed()).isFalse();
        assertThat(check.code()).isEqualTo(AdapterRowCheck.MALFORMED_RECORD);
    }

    @Test
    void a_line_break_in_any_field_is_rejected() {
        assertThat(run("1", P, "", "a\nb", "0")).isFalse();
        assertThat(check.code()).isEqualTo(AdapterRowCheck.EMBEDDED_LINE_BREAK);
        assertThat(run("1", P, "", "a\rb", "0")).isFalse();
        assertThat(check.code()).isEqualTo(AdapterRowCheck.EMBEDDED_LINE_BREAK);
        assertThat(check.run(List.of("1", P, "", "a", "0"), true)).isFalse();
        assertThat(check.code()).isEqualTo(AdapterRowCheck.EMBEDDED_LINE_BREAK);
    }

    @Test
    void the_patient_id_is_an_integer_from_zero_to_int_max() {
        assertThat(run("0", P, "", "x", "0")).isTrue();
        assertThat(run("2147483647", P, "", "x", "0")).isTrue();
        assertThat(check.patient()).isEqualTo(Integer.MAX_VALUE);
        for (String bad : List.of("2147483648", "-1", "1.0", "", "abc", " 5", "99999999999", "1e3")) {
            assertThat(run(bad, P, "", "x", "0")).as(bad).isFalse();
            assertThat(check.code()).as(bad).isEqualTo(AdapterRowCheck.BAD_PATIENT_ID);
        }
    }

    @Test
    void the_numeric_value_is_empty_or_a_finite_number() {
        for (String good : List.of("", "0", "-3", "+3", "3.", ".5", "1e5", "-2.5E-3", "007")) {
            assertThat(run("1", P, good, "", "0")).as(good).isTrue();
        }
        for (String bad : List.of("nan", "NaN", "inf", "Infinity", "-inf", "2402L", "1d", "0x10", "text", " 1", "1 ", "1e999", "--1")) {
            assertThat(run("1", P, bad, "", "0")).as(bad).isFalse();
            assertThat(check.code()).as(bad).isEqualTo(AdapterRowCheck.NUMERIC_NVAL);
        }
    }

    @Test
    void timestamps_zero_and_empty_are_written_empty_other_integers_kept() {
        assertThat(run("1", P, "", "x", "0")).isTrue();
        assertThat(check.timestamp()).isEmpty();
        assertThat(check.timestampBlanked()).isTrue();
        assertThat(run("1", P, "", "x", "")).isTrue();
        assertThat(check.timestamp()).isEmpty();
        assertThat(check.timestampBlanked()).isFalse();
        assertThat(run("1", P, "", "x", "-0")).isTrue();
        assertThat(check.timestamp()).isEmpty();
        assertThat(run("1", P, "", "x", "1700000000000")).isTrue();
        assertThat(check.timestamp()).isEqualTo("1700000000000");
        assertThat(run("1", P, "", "x", "-5")).isTrue();
        assertThat(check.timestamp()).isEqualTo("-5");
    }

    @Test
    void iso_timestamps_become_epoch_milliseconds_in_utc() {
        for (String iso : List.of("2020-01-02T03:04:05Z", "2020-01-02T04:04:05+01:00", "2020-01-02 03:04:05", "2020-01-02T03:04:05")) {
            assertThat(run("1", P, "", "x", iso)).as(iso).isTrue();
            assertThat(check.timestamp()).as(iso).isEqualTo("1577934245000");
            assertThat(check.timestampConverted()).isTrue();
        }
        assertThat(run("1", P, "", "x", "2020-01-02")).isTrue();
        assertThat(check.timestamp()).isEqualTo("1577923200000");
        assertThat(run("1", P, "", "x", "2020-01-02 03:04:05.250")).isTrue();
        assertThat(check.timestamp()).isEqualTo("1577934245250");
        assertThat(run("1", P, "", "x", "1970-01-01T00:00:00Z")).isTrue();
        assertThat(check.timestamp()).isEmpty();
    }

    @Test
    void other_timestamps_are_rejected() {
        for (String bad : List.of("yesterday", "2020-13-01", "2020-02-30", "1.5", "99999999999999999999", "nan", "12:00")) {
            assertThat(run("1", P, "", "x", bad)).as(bad).isFalse();
            assertThat(check.code()).as(bad).isEqualTo(AdapterRowCheck.BAD_TIMESTAMP);
        }
    }

    @Test
    void paths_must_be_wrapped_in_one_delimiter_without_empty_dot_or_control_segments() {
        for (String bad : List.of("", "µ", "µµ", "phs000000µdemoµ", "µphs000000µdemo", "\\phs000000\\demo", "µphs000000\\demoµ",
                "µphs000000µµageµ", "µphs000000µ.µageµ", "µphs000000µ..µageµ", "µphs000000µa\tbµ",
                "µ phs000000µdemoµ", "µphs000000 µdemoµ", "µ�phs000000µdemoµ", "µphs000000µ\u007Fµ")) {
            assertThat(run("1", bad, "", "x", "0")).as(bad).isFalse();
            assertThat(check.code()).as(bad).isEqualTo(AdapterRowCheck.NON_CANONICAL_PATH);
        }
        assertThat(run("1", "µphs000000µ", "", "x", "0")).isTrue();
        assertThat(run("1", "µphs000000µa b µc.dµ", "", "x", "0")).isTrue();
        assertThat(run("1", "µ_consentsµ", "", "phs000000.c1", "0")).isTrue();
    }

    @Test
    void the_header_is_a_first_field_of_patient_num_or_hpds_id_in_any_case() {
        assertThat(AdapterRowCheck.isHeader(List.of("patient_num", "concept_path"))).isTrue();
        assertThat(AdapterRowCheck.isHeader(List.of("HPDS_ID", "x"))).isTrue();
        assertThat(AdapterRowCheck.isHeader(List.of(" Patient_Num ", "x"))).isTrue();
        assertThat(AdapterRowCheck.isHeader(List.of("1", "x"))).isFalse();
        assertThat(AdapterRowCheck.isHeader(null)).isFalse();
    }
}
