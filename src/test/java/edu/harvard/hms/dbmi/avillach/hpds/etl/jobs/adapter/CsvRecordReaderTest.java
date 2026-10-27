package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvRecordReaderTest {

    private static List<CsvRecordReader.Record> read(String text) throws IOException {
        return read(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<CsvRecordReader.Record> read(byte[] bytes) throws IOException {
        List<CsvRecordReader.Record> out = new ArrayList<>();
        try (CsvRecordReader r = new CsvRecordReader(new ByteArrayInputStream(bytes))) {
            CsvRecordReader.Record rec;
            while ((rec = r.next()) != null) {
                out.add(rec);
            }
        }
        return out;
    }

    @Test
    void reads_quoted_and_unquoted_fields_with_escaped_quotes_and_empty_fields() throws IOException {
        List<CsvRecordReader.Record> r = read("\"1\",\"a,b\",\"say \"\"hi\"\"\",,\"\"\nx,,y,\n");
        assertThat(r).hasSize(2);
        assertThat(r.get(0).fields()).containsExactly("1", "a,b", "say \"hi\"", "", "");
        assertThat(r.get(1).fields()).containsExactly("x", "", "y", "");
        assertThat(r.get(0).embeddedLineBreak()).isFalse();
    }

    @Test
    void a_quote_inside_an_unquoted_field_is_literal() throws IOException {
        assertThat(read("5\" ruler,b\n").get(0).fields()).containsExactly("5\" ruler", "b");
    }

    @Test
    void a_blank_line_is_a_blank_record() throws IOException {
        List<CsvRecordReader.Record> r = read("a\n\nb\n");
        assertThat(r).hasSize(3);
        assertThat(r.get(1).blank()).isTrue();
        assertThat(r.get(2).number()).isEqualTo(3);
    }

    @Test
    void a_quoted_field_spanning_lines_is_one_record_flagged_as_an_embedded_line_break() throws IOException {
        List<CsvRecordReader.Record> r = read("1,\"a\nb\",c\n2,x,y\n");
        assertThat(r).hasSize(2);
        assertThat(r.get(0).fields()).containsExactly("1", "a\nb", "c");
        assertThat(r.get(0).embeddedLineBreak()).isTrue();
        assertThat(r.get(1).fields()).containsExactly("2", "x", "y");
    }

    @Test
    void an_unterminated_quote_costs_only_its_own_line() throws IOException {
        StringBuilder text = new StringBuilder("1,\"never closed,x\n");
        for (int i = 0; i < CsvRecordReader.MAX_CONTINUATION_LINES + 4; i++) {
            text.append(i).append(",ok,a,b,c\n");
        }
        List<CsvRecordReader.Record> r = read(text.toString());
        assertThat(r.get(0).malformed()).isTrue();
        assertThat(r.get(0).raw()).isEqualTo("1,\"never closed,x");
        assertThat(r).hasSize(1 + CsvRecordReader.MAX_CONTINUATION_LINES + 4);
        assertThat(r.stream().skip(1)).allMatch(rec -> !rec.malformed() && rec.fields().size() == 5);
        assertThat(r.get(1).number()).isEqualTo(2);
    }

    @Test
    void text_after_a_closing_quote_is_malformed_and_does_not_swallow_the_next_line() throws IOException {
        List<CsvRecordReader.Record> r = read("\"a\"b,c\n1,2\n");
        assertThat(r.get(0).malformed()).isTrue();
        assertThat(r.get(1).fields()).containsExactly("1", "2");
    }

    @Test
    void a_leading_byte_order_mark_is_dropped() throws IOException {
        assertThat(read("﻿patient_num,x\n").get(0).fields()).containsExactly("patient_num", "x");
    }

    @Test
    void crlf_line_endings_are_read_as_lf() throws IOException {
        assertThat(read("a,b\r\nc,d\r\n")).extracting(CsvRecordReader.Record::raw).containsExactly("a,b", "c,d");
    }

    @Test
    void bytes_that_are_not_utf8_are_an_error_not_a_replacement_character() {
        byte[] bad = {'a', ',', (byte) 0xC3, (byte) 0x28, '\n'};
        assertThatThrownBy(() -> read(bad)).isInstanceOf(CharacterCodingException.class);
    }
}
