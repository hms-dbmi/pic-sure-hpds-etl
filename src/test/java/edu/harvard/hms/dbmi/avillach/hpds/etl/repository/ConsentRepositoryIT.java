package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConsentRepository#batchUpsert} against a real Postgres: the in-call dedupe that keeps
 * {@code ON CONFLICT DO UPDATE} from touching a key twice, the warning when that dedupe drops a
 * different consent code, re-run idempotency, and a batch spanning more than one statement.
 */
class ConsentRepositoryIT extends AbstractIntegrationTest {

    private static final String STUDY = "phs000001";

    @Autowired
    private ConsentRepository consents;
    @Autowired
    private JdbcTemplate jdbc;

    private ListAppender<ILoggingEvent> logs;
    private Logger repositoryLogger;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE participants, consents, samples");
        repositoryLogger = (Logger) LoggerFactory.getLogger(ConsentRepository.class);
        logs = new ListAppender<>();
        logs.start();
        repositoryLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        repositoryLogger.detachAppender(logs);
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private String storedCode(long hpdsId) {
        return jdbc.queryForObject("SELECT consent_code FROM consents WHERE hpds_id = ? AND study_id = ?",
                String.class, hpdsId, STUDY);
    }

    /** Without the dedupe Postgres rejects the statement ("cannot affect row a second time"). */
    @Test
    void a_key_repeated_with_the_same_code_is_written_once_without_a_warning() {
        int written = consents.batchUpsert(List.of(
                new Consent(1, STUDY, "c1", "HMB"),
                new Consent(1, STUDY, "c1", "HMB"),
                new Consent(2, STUDY, "c1", "HMB")));

        assertThat(written).isEqualTo(2);
        assertThat(consents.count()).isEqualTo(2);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void a_key_repeated_with_a_different_code_keeps_the_last_row_and_warns() {
        int written = consents.batchUpsert(List.of(
                new Consent(1, STUDY, "c1", "HMB"),
                new Consent(2, STUDY, "c1", "HMB"),
                new Consent(1, STUDY, "c2", "GRU")));

        assertThat(written).isEqualTo(2);
        assertThat(storedCode(1)).isEqualTo("c2");
        assertThat(storedCode(2)).isEqualTo("c1");
        assertThat(warnings()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .startsWith("1 consent row(s)")
                .contains("hpds_id=1 study_id=" + STUDY + " codes c1 -> c2"));
    }

    /** The same participant in two studies is two keys, not a repeat. */
    @Test
    void the_same_participant_in_two_studies_is_not_a_repeat() {
        int written = consents.batchUpsert(List.of(
                new Consent(1, STUDY, "c1", "HMB"),
                new Consent(1, "phs000002", "c2", "GRU")));

        assertThat(written).isEqualTo(2);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void a_rerun_updates_in_place_and_refreshes_codes() {
        List<Consent> rows = List.of(new Consent(1, STUDY, "c1", "HMB"), new Consent(2, STUDY, "c1", "HMB"));
        assertThat(consents.batchUpsert(rows)).isEqualTo(2);

        // DO UPDATE reports every touched row, changed or not.
        assertThat(consents.batchUpsert(rows)).isEqualTo(2);
        assertThat(consents.count()).isEqualTo(2);

        consents.batchUpsert(List.of(new Consent(1, STUDY, "c2", "GRU")));
        assertThat(storedCode(1)).isEqualTo("c2");
        assertThat(consents.count()).isEqualTo(2);
    }

    /** 65,535 parameters / 4 columns = 16,383 rows per statement; this needs two. */
    @Test
    void a_batch_larger_than_one_statement_is_written_completely() {
        int n = MultiRowInsert.MAX_PARAMS / 4 + 10;
        List<Consent> rows = new ArrayList<>(IntStream.range(0, n)
                .mapToObj(i -> new Consent(i, STUDY, "c1", "HMB")).toList());
        // A repeat across the chunk boundary is deduplicated before chunking.
        rows.add(new Consent(0, STUDY, "c2", "GRU"));

        assertThat(consents.batchUpsert(rows)).isEqualTo(n);
        assertThat(consents.count()).isEqualTo(n);
        assertThat(storedCode(0)).isEqualTo("c2");
        assertThat(consents.batchUpsert(rows)).isEqualTo(n);
        assertThat(consents.count()).isEqualTo(n);
    }
}
