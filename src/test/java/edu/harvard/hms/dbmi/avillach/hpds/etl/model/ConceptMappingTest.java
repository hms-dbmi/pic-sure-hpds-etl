package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConceptMappingTest {

    private final DelimitedReader reader = new DelimitedReader();

    private List<ConceptMapping> parse(String csv) {
        return ConceptMapping.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), reader);
    }

    @Test
    void parses_standard_mapping_csv() {
        String csv = "\"pht001234.csv:3\",\"µStudyµVariableµ\",\"\",\"TEXT\",\"\"\n"
                + "\"pht001234.csv:4\",\"µStudyµBMIµ\",\"\",\"NUMERIC\",\"\"\n";

        List<ConceptMapping> mappings = parse(csv);

        assertThat(mappings).hasSize(2);
        assertThat(mappings.get(0).fileName()).isEqualTo("pht001234.csv");
        assertThat(mappings.get(0).columnIndex()).isEqualTo(3);
        assertThat(mappings.get(0).conceptPath()).isEqualTo("µStudyµVariableµ");
        assertThat(mappings.get(0).dataType()).isEqualTo(ConceptMapping.DataType.TEXT);

        assertThat(mappings.get(1).columnIndex()).isEqualTo(4);
        assertThat(mappings.get(1).dataType()).isEqualTo(ConceptMapping.DataType.NUMERIC);
    }

    @Test
    void normalizes_backslash_paths() {
        String csv = "\"file.csv:1\",\"\\Study\\Variable\\\",\"\",\"TEXT\",\"\"\n";
        List<ConceptMapping> mappings = parse(csv);

        assertThat(mappings.get(0).conceptPath()).isEqualTo("µStudyµVariableµ");
    }

    @Test
    void extracts_patient_col_from_options() {
        String csv = "\"file.csv:1\",\"µStudyµValµ\",\"\",\"TEXT\",\"patientcol=2\"\n";
        List<ConceptMapping> mappings = parse(csv);

        assertThat(mappings.get(0).patientCol()).isEqualTo(2);
    }

    @Test
    void defaults_patient_col_to_zero() {
        String csv = "\"file.csv:1\",\"µStudyµValµ\",\"\",\"TEXT\",\"\"\n";
        List<ConceptMapping> mappings = parse(csv);

        assertThat(mappings.get(0).patientCol()).isEqualTo(0);
    }

    @Test
    void skips_malformed_keys() {
        String csv = "\"badkey\",\"µStudyµValµ\",\"\",\"TEXT\",\"\"\n"
                + "\"file.csv:1\",\"µStudyµValµ\",\"\",\"TEXT\",\"\"\n";
        List<ConceptMapping> mappings = parse(csv);

        assertThat(mappings).hasSize(1);
        assertThat(mappings.get(0).fileName()).isEqualTo("file.csv");
    }

    @Test
    void skips_rows_with_fewer_than_four_columns() {
        String csv = "\"file.csv:1\",\"µStudyµValµ\"\n"
                + "\"file.csv:2\",\"µStudyµOtherµ\",\"\",\"TEXT\",\"\"\n";
        List<ConceptMapping> mappings = parse(csv);

        assertThat(mappings).hasSize(1);
    }

    @Test
    void throws_on_empty_mapping_file() {
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("no valid mappings");
    }

    @Test
    void defaults_unknown_data_type_to_text() {
        String csv = "\"file.csv:1\",\"µStudyµValµ\",\"\",\"UNKNOWN\",\"\"\n";
        List<ConceptMapping> mappings = parse(csv);

        assertThat(mappings.get(0).dataType()).isEqualTo(ConceptMapping.DataType.TEXT);
    }
}
