package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SampleRepository} against a real Postgres. Samples use
 * {@code ON CONFLICT DO NOTHING}, so a repeated key needs no dedupe and the count is of rows
 * actually inserted: a re-run reports zero.
 */
class SampleRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private SampleRepository samples;
    @Autowired
    private ConsentRepository consents;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE TABLE participants, consents, samples");
    }

    @Test
    void a_key_repeated_in_one_call_is_inserted_once() {
        int inserted = samples.batchUpsert(List.of(
                new Sample(1, "phs1", "S1", "dbgap"),
                new Sample(1, "phs1", "S1", "dbgap"),
                new Sample(1, "phs1", "S2", "dbgap")));

        assertThat(inserted).isEqualTo(2);
        assertThat(samples.count()).isEqualTo(2);
    }

    @Test
    void a_rerun_inserts_nothing() {
        List<Sample> rows = List.of(new Sample(1, "phs1", "S1", "dbgap"), new Sample(2, "phs1", "S2", "dbgap"));
        assertThat(samples.batchUpsert(rows)).isEqualTo(2);

        assertThat(samples.batchUpsert(rows)).isZero();
        assertThat(samples.count()).isEqualTo(2);
    }

    /** 65,535 parameters / 4 columns = 16,383 rows per statement; this needs two. */
    @Test
    void a_batch_larger_than_one_statement_is_written_completely() {
        int n = MultiRowInsert.MAX_PARAMS / 4 + 10;
        List<Sample> rows = IntStream.range(0, n).mapToObj(i -> new Sample(i, "phs1", "S" + i, "dbgap")).toList();

        assertThat(samples.batchUpsert(rows)).isEqualTo(n);
        assertThat(samples.count()).isEqualTo(n);
        assertThat(samples.batchUpsert(rows)).isZero();
    }

    @Test
    void finds_only_the_samples_a_study_loaded() {
        consents.batchUpsert(List.of(new Consent(1, "phs1", "c1", "HMB"), new Consent(2, "phs2", "c1", "HMB")));
        samples.batchUpsert(List.of(new Sample(1, "phs1", "S1", "dbgap"), new Sample(2, "phs2", "S2", "dbgap")));

        assertThat(samples.findByStudyId("phs1")).containsExactly(new Sample(1, "phs1", "S1", "dbgap"));
    }

    /**
     * One dbGaP subject in two studies has one HPDS id. Each study's genomic artifacts must list
     * only the samples that study loaded, never the other study's samples for that participant.
     */
    @Test
    void a_participant_in_two_studies_keeps_each_studys_samples_apart() {
        consents.batchUpsert(List.of(new Consent(1, "phs1", "1", "HMB"), new Consent(1, "phs2", "1", "GRU")));
        samples.batchUpsert(List.of(
                new Sample(1, "phs1", "NWD1", "submitted"),
                new Sample(1, "phs2", "NWD2", "submitted"),
                // The same sample loaded by both studies is a row in each.
                new Sample(1, "phs1", "SHARED", "submitted"),
                new Sample(1, "phs2", "SHARED", "submitted")));

        assertThat(samples.findByStudyId("phs1")).containsExactlyInAnyOrder(
                new Sample(1, "phs1", "NWD1", "submitted"), new Sample(1, "phs1", "SHARED", "submitted"));
        assertThat(samples.findByStudyId("phs2")).containsExactlyInAnyOrder(
                new Sample(1, "phs2", "NWD2", "submitted"), new Sample(1, "phs2", "SHARED", "submitted"));
    }

    @Test
    void delete_by_study_leaves_other_studies_samples_alone() {
        samples.batchUpsert(List.of(new Sample(1, "phs1", "S1", "dbgap"), new Sample(1, "phs2", "S2", "dbgap")));

        assertThat(samples.deleteByStudyId("phs1")).isEqualTo(1);
        assertThat(samples.findByStudyId("phs1")).isEmpty();
        assertThat(samples.findByStudyId("phs2")).containsExactly(new Sample(1, "phs2", "S2", "dbgap"));
    }

    @Test
    void a_sample_with_no_study_matches_no_study() {
        consents.batchUpsert(List.of(new Consent(1, "phs1", "1", "HMB")));
        jdbc.update("INSERT INTO samples (hpds_id, source_sample_id, sample_source) VALUES (1, 'OLD', 'dbgap')");

        assertThat(samples.findByStudyId("phs1")).isEmpty();
    }

    @Test
    void counts_a_studys_samples_of_one_source_per_consent_group() {
        consents.batchUpsert(List.of(new Consent(1, "phs1", "1", "HMB"), new Consent(2, "phs1", "2", "GRU"),
                new Consent(1, "phs2", "1", "GRU")));
        samples.batchUpsert(List.of(
                new Sample(1, "phs1", "S1", "DBGap"), new Sample(1, "phs1", "S1b", "DBGap"),
                new Sample(1, "phs1", "NWD1", "submitted"),
                new Sample(2, "phs1", "S2", "DBGap"),
                // Another study's sample for the shared participant does not count for phs1.
                new Sample(1, "phs2", "S9", "DBGap")));

        assertThat(samples.countSamplesByConsent(List.of("phs1"), "DBGap")).containsExactly(
                new SampleRepository.ConsentGroupSamples("phs1", "1", 2),
                new SampleRepository.ConsentGroupSamples("phs1", "2", 1));
    }
}
