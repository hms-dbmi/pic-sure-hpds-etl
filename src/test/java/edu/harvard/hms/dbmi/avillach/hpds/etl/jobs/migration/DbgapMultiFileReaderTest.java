package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.Severity;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration.DbgapMultiFileReader.SampleTable;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration.DbgapMultiFileReader.SubjectTable;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.JobTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DbgapMultiFileReader}: parsing the dbGaP Subject/Sample MULTI tables and
 * the Subject data dictionary, and how each kind of malformed input is reported or rejected.
 */
class DbgapMultiFileReaderTest {

    /** The preamble dbGaP puts above every phenotype table, including the blank line before the header. */
    static final String PREAMBLE = """
            # Study accession: phs000007.v33.p14
            # Table accession: pht000182.v14.p14
            # Consent group: All
            # Citation instructions: The study accession (phs000007.v33.p14) is used to cite the study.
            #

            """;

    private final DbgapMultiFileReader reader = new DbgapMultiFileReader(new IoResolver(null), new DelimitedReader());

    private static String file(String name, String content) {
        return JobTestSupport.tempFile(name, content);
    }

    private static ValidationIssue issue(List<ValidationIssue> issues, String code) {
        return issues.stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " issue in " + issues));
    }

    // ---- Subject.MULTI -------------------------------------------------------

    @Test
    void reads_subjects_past_the_comment_preamble() {
        String path = file("phs000007.v33.pht000182.v14.p14.FHS_Subject.MULTI.txt", PREAMBLE + """
                dbGaP_Subject_ID\tSUBJECT_ID\tCONSENT\tAFFECTION_STATUS
                1001\tS1\t1\t1
                1002\tS2\t2\t0
                """);

        SubjectTable table = reader.readSubjects(path);

        assertThat(table.rowsRead()).isEqualTo(2);
        assertThat(table.consentColumn()).isEqualTo("CONSENT");
        assertThat(table.subjectIdColumn()).isEqualTo("SUBJECT_ID");
        assertThat(table.consented()).containsOnlyKeys("1001", "1002");
        assertThat(table.consented().get("1001").subjectId()).isEqualTo("S1");
        assertThat(table.consented().get("1002").consentCode()).isEqualTo("2");
        assertThat(table.issues()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"consent", "CONSENT", "Consent_1217", "CONSENTCOL", "GenCons"})
    void accepts_every_known_consent_header_in_any_case(String header) {
        String path = file("s.txt", "dbGaP_Subject_ID\tSUBJID\t" + header + "\n1001\tS1\t1\n");

        SubjectTable table = reader.readSubjects(path);

        assertThat(table.consentColumn()).isEqualTo(header);
        assertThat(table.consented().get("1001").consentCode()).isEqualTo("1");
        assertThat(table.subjectIdColumn()).isEqualTo("SUBJID");
    }

    @Test
    void matches_the_dbgap_subject_id_header_case_insensitively() {
        String path = file("s.txt", "DBGAP_SUBJECT_ID\tconsent\n1001\t1\n");

        assertThat(reader.readSubjects(path).consented()).containsOnlyKeys("1001");
    }

    @Test
    void prefers_the_plain_consent_column_and_warns_when_several_exist() {
        String path = file("s.txt", "dbGaP_Subject_ID\tGENCONS\tCONSENT\n1001\t2\t1\n");

        SubjectTable table = reader.readSubjects(path);

        assertThat(table.consentColumn()).isEqualTo("CONSENT");
        assertThat(table.consented().get("1001").consentCode()).isEqualTo("1");
        ValidationIssue warning = issue(table.issues(), "MULTIPLE_CONSENT_COLUMNS");
        assertThat(warning.severity()).isEqualTo(Severity.WARNING);
        assertThat(warning.message()).contains("GENCONS").contains("CONSENT");
    }

    @Test
    void drops_consent_zero_and_reports_it_as_info() {
        String path = file("s.txt", "dbGaP_Subject_ID\tCONSENT\n1001\t1\n1002\t0\n1003\t00\n");

        SubjectTable table = reader.readSubjects(path);

        assertThat(table.consented()).containsOnlyKeys("1001");
        assertThat(table.notConsented()).containsExactly("1002", "1003");
        ValidationIssue info = issue(table.issues(), "CONSENT_ZERO_DROPPED");
        assertThat(info.severity()).isEqualTo(Severity.INFO);
        assertThat(info.context()).containsEntry("count", 2L).containsEntry("examples", List.of("1002", "1003"));
    }

    @Test
    void records_the_own_subject_ids_of_consent_zero_subjects() {
        String path = file("s.txt", "dbGaP_Subject_ID\tSUBJECT_ID\tCONSENT\n1001\tS1\t1\n1002\tS2\t0\n"
                + "1003\tS3\t0\n1003\tS3\t1\n");

        SubjectTable table = reader.readSubjects(path);

        // 1003 is consented on a later row, so only S2 counts as not consented.
        assertThat(table.notConsented()).containsExactly("1002");
        assertThat(table.notConsentedSubjectIds()).containsExactly("S2");
    }

    @Test
    void normalizes_leading_zeros_in_consent_codes() {
        String path = file("s.txt", "dbGaP_Subject_ID\tCONSENT\n1001\t01\n");

        assertThat(reader.readSubjects(path).consented().get("1001").consentCode()).isEqualTo("1");
    }

    @Test
    void skips_and_reports_blank_and_non_numeric_consents() {
        String path = file("s.txt", "dbGaP_Subject_ID\tCONSENT\n1001\t1\n1002\t\n1003\tGRU\n1004\t1.5\n");

        SubjectTable table = reader.readSubjects(path);

        assertThat(table.consented()).containsOnlyKeys("1001");
        assertThat(issue(table.issues(), "BLANK_CONSENT").context()).containsEntry("count", 1L);
        ValidationIssue nonNumeric = issue(table.issues(), "NON_NUMERIC_CONSENT");
        assertThat(nonNumeric.severity()).isEqualTo(Severity.WARNING);
        assertThat(nonNumeric.context()).containsEntry("examples", List.of("1003=GRU", "1004=1.5"));
    }

    @Test
    void skips_and_reports_blank_dbgap_subject_ids() {
        String path = file("s.txt", "dbGaP_Subject_ID\tCONSENT\n1001\t1\n\t1\n");

        SubjectTable table = reader.readSubjects(path);

        assertThat(table.consented()).containsOnlyKeys("1001");
        assertThat(issue(table.issues(), "BLANK_DBGAP_SUBJECT_ID").context()).containsEntry("count", 1L);
    }

    @Test
    void keeps_the_first_consent_when_a_subject_repeats_with_another() {
        String path = file("s.txt", "dbGaP_Subject_ID\tCONSENT\n1001\t1\n1001\t2\n1001\t1\n");

        SubjectTable table = reader.readSubjects(path);

        assertThat(table.consented().get("1001").consentCode()).isEqualTo("1");
        assertThat(issue(table.issues(), "CONFLICTING_CONSENT").context()).containsEntry("count", 1L);
    }

    @Test
    void a_subject_consented_on_any_row_is_not_counted_as_dropped() {
        String path = file("s.txt", "dbGaP_Subject_ID\tCONSENT\n1001\t0\n1001\t1\n");

        SubjectTable table = reader.readSubjects(path);

        assertThat(table.consented()).containsOnlyKeys("1001");
        assertThat(table.notConsented()).isEmpty();
    }

    @Test
    void reports_a_missing_subject_id_column_as_info() {
        String path = file("s.txt", "dbGaP_Subject_ID\tCONSENT\n1001\t1\n");

        SubjectTable table = reader.readSubjects(path);

        assertThat(table.subjectIdColumn()).isNull();
        assertThat(issue(table.issues(), "NO_SUBJECT_ID_COLUMN").severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void rejects_a_subject_file_with_no_consent_column() {
        String path = file("s.txt", "dbGaP_Subject_ID\tSUBJECT_ID\tAFFECTION_STATUS\n1001\tS1\t1\n");

        assertThatThrownBy(() -> reader.readSubjects(path))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("no consent column")
                .hasMessageContaining("gencons");
    }

    @Test
    void rejects_a_subject_file_with_no_dbgap_subject_id_column() {
        String path = file("s.txt", "SUBJECT_ID\tCONSENT\nS1\t1\n");

        assertThatThrownBy(() -> reader.readSubjects(path))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("missing required column dbGaP_Subject_ID");
    }

    @Test
    void rejects_a_subject_file_with_no_data_rows() {
        String path = file("s.txt", PREAMBLE + "dbGaP_Subject_ID\tCONSENT\n");

        assertThatThrownBy(() -> reader.readSubjects(path))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("no data rows");
    }

    @Test
    void rejects_a_subject_file_that_is_only_a_preamble() {
        String path = file("s.txt", PREAMBLE);

        assertThatThrownBy(() -> reader.readSubjects(path))
                .isInstanceOf(DataException.class);
    }

    @Test
    void rejects_a_row_wider_than_the_header() {
        String path = file("s.txt", "dbGaP_Subject_ID\tCONSENT\n1001\t1\textra\n");

        assertThatThrownBy(() -> reader.readSubjects(path)).isInstanceOf(DataException.class);
    }

    @Test
    void treats_quotes_as_literal_characters() {
        // An unbalanced quote would otherwise swallow every row after it into one field.
        String path = file("s.txt", "dbGaP_Subject_ID\tCONSENT\tNOTE\n1001\t1\t5'10\"\n1002\t2\tok\n");

        assertThat(reader.readSubjects(path).consented()).containsOnlyKeys("1001", "1002");
    }

    // ---- Sample.MULTI --------------------------------------------------------

    @Test
    void reads_dbgap_and_submitted_sample_ids() {
        String path = file("phs000007.v33.pht001415.v1.p14.FHS_Sample.MULTI.txt", PREAMBLE + """
                dbGaP_Subject_ID\tdbGaP_Sample_ID\tBioSample Accession\tSUBJECT_ID\tSAMPLE_ID\tSAMPLE_USE
                1001\t5001\tSAMN1\tS1\tNWD1\tSeq_DNA_WholeGenome
                1001\t5002\tSAMN2\tS1\tNWD2\tSeq_DNA_WholeGenome
                """);

        SampleTable table = reader.readSamples(path);

        assertThat(table.rowsRead()).isEqualTo(2);
        assertThat(table.rows()).extracting(DbgapMultiFileReader.SampleRow::dbgapSampleId).containsExactly("5001", "5002");
        assertThat(table.rows()).extracting(DbgapMultiFileReader.SampleRow::submittedSampleId).containsExactly("NWD1", "NWD2");
        assertThat(table.issues()).isEmpty();
    }

    @Test
    void reads_samples_without_a_submitted_sample_id_column() {
        String path = file("x.txt", "DBGAP_SUBJECT_ID\tDBGAP_SAMPLE_ID\n1001\t5001\n");

        SampleTable table = reader.readSamples(path);

        assertThat(table.rows()).singleElement().satisfies(r -> {
            assertThat(r.dbgapSampleId()).isEqualTo("5001");
            assertThat(r.submittedSampleId()).isNull();
        });
        assertThat(issue(table.issues(), "NO_SUBMITTED_SAMPLE_ID_COLUMN").severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void reports_blank_sample_ids_and_keeps_the_submitted_id() {
        String path = file("x.txt", "dbGaP_Subject_ID\tdbGaP_Sample_ID\tSAMPLE_ID\n1001\t\tNWD1\n1002\t\t\n\t5003\tNWD3\n");

        SampleTable table = reader.readSamples(path);

        assertThat(table.rows()).singleElement().satisfies(r -> {
            assertThat(r.dbgapSubjectId()).isEqualTo("1001");
            assertThat(r.dbgapSampleId()).isNull();
            assertThat(r.submittedSampleId()).isEqualTo("NWD1");
        });
        assertThat(issue(table.issues(), "BLANK_DBGAP_SAMPLE_ID").context()).containsEntry("count", 2L);
        assertThat(issue(table.issues(), "BLANK_SAMPLE_SUBJECT_ID").context()).containsEntry("count", 1L);
    }

    @Test
    void warns_on_a_sample_file_with_no_data_rows() {
        String path = file("x.txt", PREAMBLE + "dbGaP_Subject_ID\tdbGaP_Sample_ID\n");

        SampleTable table = reader.readSamples(path);

        assertThat(table.rows()).isEmpty();
        assertThat(issue(table.issues(), "NO_SAMPLE_ROWS").severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    void rejects_a_sample_file_with_no_dbgap_sample_id_column() {
        String path = file("x.txt", "dbGaP_Subject_ID\tSAMPLE_ID\n1001\tNWD1\n");

        assertThatThrownBy(() -> reader.readSamples(path))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("dbGaP_Sample_ID");
    }

    // ---- Subject.data_dict.xml -----------------------------------------------

    static final String DATA_DICT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <data_table id="pht000182.v14" study_id="phs000007.v33" participant_set="14">
              <variable id="phv00036384.v14">
                <name>SUBJECT_ID</name>
                <description>De-identified Subject ID</description>
                <type>integer</type>
              </variable>
              <variable id="phv00036385.v14">
                <name>consent</name>
                <description>Consent group as determined by DAC</description>
                <type>encoded value</type>
                <value code="0">Subjects did not participate in the study, did not complete a consent document</value>
                <value code="1">Health/Medical/Biomedical (IRB, MDS) (HMB-IRB-MDS)</value>
                <value code="02">Disease-Specific (Cardiovascular Disease, IRB, NPU, MDS) (DS-CVD-IRB-NPU-MDS)</value>
                <value code="3">General Research Use</value>
              </variable>
            </data_table>
            """;

    @Test
    void decodes_the_consent_variable_case_insensitively() {
        String path = file("dict.xml", DATA_DICT);

        Map<String, String> decodings = reader.readConsentDecodings(path, "CONSENT");

        assertThat(decodings)
                .containsEntry("1", "Health/Medical/Biomedical (IRB, MDS) (HMB-IRB-MDS)")
                .containsEntry("2", "Disease-Specific (Cardiovascular Disease, IRB, NPU, MDS) (DS-CVD-IRB-NPU-MDS)")
                .containsEntry("3", "General Research Use");
    }

    @Test
    void returns_no_decodings_when_the_consent_variable_is_absent() {
        String path = file("dict.xml", DATA_DICT);

        assertThat(reader.readConsentDecodings(path, "GENCONS")).isEmpty();
    }

    @Test
    void rejects_a_data_dict_that_is_not_xml() {
        String path = file("dict.xml", "<data_table><variable>");

        assertThatThrownBy(() -> reader.readConsentDecodings(path, "CONSENT"))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("not readable as XML");
    }

    @Test
    void refuses_a_data_dict_declaring_a_doctype() {
        String path = file("dict.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE data_table [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <data_table><variable><name>CONSENT</name><value code="1">&xxe;</value></variable></data_table>
                """);

        assertThatThrownBy(() -> reader.readConsentDecodings(path, "CONSENT")).isInstanceOf(DataException.class);
    }

    @Test
    void the_abbreviation_is_the_trailing_parenthesized_text() {
        assertThat(DbgapMultiFileReader.abbreviationOf("General Research Use (GRU)")).isEqualTo("GRU");
        assertThat(DbgapMultiFileReader.abbreviationOf("Health/Medical/Biomedical (IRB, MDS) (HMB-IRB-MDS)"))
                .isEqualTo("HMB-IRB-MDS");
        assertThat(DbgapMultiFileReader.abbreviationOf("Disease-Specific (CVD) (DS-CVD)  ")).isEqualTo("DS-CVD");
        assertThat(DbgapMultiFileReader.abbreviationOf("General Research Use")).isNull();
        assertThat(DbgapMultiFileReader.abbreviationOf("Disease-Specific (CVD) only")).isNull();
    }

    @Test
    void normalizes_codes() {
        assertThat(DbgapMultiFileReader.normalizeCode(" 01 ")).isEqualTo("1");
        assertThat(DbgapMultiFileReader.normalizeCode("0")).isEqualTo("0");
        assertThat(DbgapMultiFileReader.normalizeCode("000")).isEqualTo("0");
        assertThat(DbgapMultiFileReader.normalizeCode("12")).isEqualTo("12");
        assertThat(DbgapMultiFileReader.normalizeCode("-1")).isNull();
        assertThat(DbgapMultiFileReader.normalizeCode("c1")).isNull();
        assertThat(DbgapMultiFileReader.normalizeCode("")).isNull();
    }
}
