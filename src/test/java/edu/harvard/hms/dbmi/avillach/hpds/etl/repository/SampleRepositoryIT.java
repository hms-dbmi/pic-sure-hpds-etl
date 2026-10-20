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
 * {@link SampleRepository#batchUpsert} against a real Postgres. Samples use
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
                new Sample(1, "S1", "dbgap"),
                new Sample(1, "S1", "dbgap"),
                new Sample(1, "S2", "dbgap")));

        assertThat(inserted).isEqualTo(2);
        assertThat(samples.count()).isEqualTo(2);
    }

    @Test
    void a_rerun_inserts_nothing() {
        List<Sample> rows = List.of(new Sample(1, "S1", "dbgap"), new Sample(2, "S2", "dbgap"));
        assertThat(samples.batchUpsert(rows)).isEqualTo(2);

        assertThat(samples.batchUpsert(rows)).isZero();
        assertThat(samples.count()).isEqualTo(2);
    }

    /** 65,535 parameters / 3 columns = 21,845 rows per statement; this needs two. */
    @Test
    void a_batch_larger_than_one_statement_is_written_completely() {
        int n = MultiRowInsert.MAX_PARAMS / 3 + 10;
        List<Sample> rows = IntStream.range(0, n).mapToObj(i -> new Sample(i, "S" + i, "dbgap")).toList();

        assertThat(samples.batchUpsert(rows)).isEqualTo(n);
        assertThat(samples.count()).isEqualTo(n);
        assertThat(samples.batchUpsert(rows)).isZero();
    }

    @Test
    void finds_a_studys_samples_through_its_consents() {
        consents.batchUpsert(List.of(new Consent(1, "phs1", "c1", "HMB"), new Consent(2, "phs2", "c1", "HMB")));
        samples.batchUpsert(List.of(new Sample(1, "S1", "dbgap"), new Sample(2, "S2", "dbgap")));

        assertThat(samples.findByStudyId("phs1")).containsExactly(new Sample(1, "S1", "dbgap"));
    }
}
