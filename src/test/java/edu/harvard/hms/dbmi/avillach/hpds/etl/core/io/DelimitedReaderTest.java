package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The staged SSTR copies come in both LF and CRLF flavors (the
 * {@code BDC-ingestion-only__} folder-flattened copies are CRLF): the reader must
 * produce identical rows for both, with no {@code \r} bleeding into the last column.
 */
class DelimitedReaderTest {

    private static final String HEADER = "SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation";
    private static final String ROW_1 = "700001\tD100-1\t1\tHMB-IRB";
    private static final String ROW_2 = "700002\tD100-2\t2\tGRU";

    private final DelimitedReader reader = new DelimitedReader();

    private static InputStream tsv(String lineEnding) {
        String content = HEADER + lineEnding + ROW_1 + lineEnding + ROW_2 + lineEnding;
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void crlf_and_lf_tsv_parse_identically() {
        List<Map<String, String>> lf;
        List<Map<String, String>> crlf;
        try (Stream<Map<String, String>> rows = reader.stream(tsv("\n"), DelimitedReader.TAB)) {
            lf = rows.toList();
        }
        try (Stream<Map<String, String>> rows = reader.stream(tsv("\r\n"), DelimitedReader.TAB)) {
            crlf = rows.toList();
        }

        assertThat(crlf).isEqualTo(lf);
        assertThat(crlf).hasSize(2);
        assertThat(crlf.get(0)).containsEntry("consent_abbreviation", "HMB-IRB");
        // The failure mode CRLF causes: a trailing \r glued onto the final column.
        assertThat(crlf.get(1).get("consent_abbreviation")).isEqualTo("GRU");
    }
}
