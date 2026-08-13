package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.JobTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permanent pipeline may load several studies at once, and every study load shares
 * {@code source = "DBGap"}, so two studies containing the same {@code dbgap_subject_id} race for
 * that subject's HPDS uuid.
 *
 * <p>Before {@link edu.harvard.hms.dbmi.avillach.hpds.etl.db.ParticipantRepository#resolveOrCreate},
 * both runs would find no participant, generate different uuids, and one insert would be silently
 * discarded by {@code ON CONFLICT DO NOTHING} without reporting the winner -- leaving the losing
 * run to write consents and samples against a uuid that is not in {@code participants}. There are
 * no foreign keys back to {@code participants}, so nothing would have caught it.
 *
 * <p>{@link #every_consent_and_sample_uuid_exists_in_participants()} is the assertion that fails
 * on the old behaviour, and it holds regardless of how the interleaving actually lands, so it is
 * not dependent on winning a timing lottery.
 */
class SstrPopulateRdsParticipantsConcurrencyIT extends AbstractIntegrationTest {

    private static final String HEADER =
            "SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id\tdbgap_sample_id\n";
    private static final String STUDY_A = "phs000001";
    private static final String STUDY_B = "phs000002";
    private static final String SOURCE = "DBGap";

    @Autowired
    private SstrPopulateRdsParticipantsJob job;
    @Autowired
    private JobExecutor executor;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE TABLE participants, consents, samples");
    }

    private static String row(String dbgapSubjectId, String sampleId, String consent) {
        return "SUBJ\t" + sampleId + "\t" + consent + "\tGRU\t" + dbgapSubjectId + "\t" + sampleId + "\n";
    }

    private long participantRowsFor(String dbgapSubjectId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM participants WHERE source_id = ? AND source = ?",
                Long.class, dbgapSubjectId, SOURCE);
        return n == null ? 0 : n;
    }

    private UUID storedUuid(String dbgapSubjectId) {
        return jdbc.queryForObject("SELECT hpds_uuid FROM participants WHERE source_id = ? AND source = ?",
                UUID.class, dbgapSubjectId, SOURCE);
    }

    private long consentRowsForUuid(UUID uuid) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM consents WHERE hpds_uuid = ?", Long.class, uuid);
        return n == null ? 0 : n;
    }

    /** Runs both loads from the same instant so their read-then-insert windows overlap. */
    private List<JobResult> runConcurrently(String inputA, String inputB, String runIdSuffix) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<JobResult> loadA = () -> {
                startTogether.await(30, TimeUnit.SECONDS);
                return executor.run(job, Map.of("input", inputA, "study-id", STUDY_A), "conc-a-" + runIdSuffix);
            };
            Callable<JobResult> loadB = () -> {
                startTogether.await(30, TimeUnit.SECONDS);
                return executor.run(job, Map.of("input", inputB, "study-id", STUDY_B), "conc-b-" + runIdSuffix);
            };

            Future<JobResult> a = pool.submit(loadA);
            Future<JobResult> b = pool.submit(loadB);
            return List.of(a.get(2, TimeUnit.MINUTES), b.get(2, TimeUnit.MINUTES));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The test the review asked for: two studies loaded at once, sharing a dbgap_subject_id, must
     * agree on that subject's HPDS uuid.
     */
    @Test
    void concurrent_study_loads_sharing_a_subject_use_the_same_hpds_uuid() throws Exception {
        String shared = "phs_shared.v1.p1.c1";

        String inputA = JobTestSupport.tempFile("sstr-a.tsv", HEADER
                + row(shared, "SAMP_A1", "1")
                + row("only-in-a", "SAMP_A2", "1"));
        String inputB = JobTestSupport.tempFile("sstr-b.tsv", HEADER
                + row(shared, "SAMP_B1", "2")
                + row("only-in-b", "SAMP_B2", "2"));

        List<JobResult> results = runConcurrently(inputA, inputB, "shared");

        assertThat(results).allSatisfy(r -> assertThat(r.isSuccess())
                .as("both loads should succeed, got %s: %s", r.getExitCode(), r.getErrorMessage())
                .isTrue());

        // Exactly one participant for the shared subject -- the unique constraint guarantees this
        // much even with the bug, which is why it is not the interesting assertion on its own.
        assertThat(participantRowsFor(shared)).isEqualTo(1);

        // ...and both studies wrote their consent row against that one uuid. With the old
        // behaviour the losing run used its own discarded uuid, so this would be 1, not 2.
        UUID sharedUuid = storedUuid(shared);
        assertThat(consentRowsForUuid(sharedUuid))
                .as("both studies should have a consent row for the shared subject's uuid")
                .isEqualTo(2);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE hpds_uuid = ? AND study_id = ?", Long.class,
                sharedUuid, STUDY_A)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE hpds_uuid = ? AND study_id = ?", Long.class,
                sharedUuid, STUDY_B)).isEqualTo(1);
    }

    /**
     * The referential invariant, stated directly. Holds however the race lands, so it is the
     * regression guard that does not depend on timing.
     */
    @Test
    void every_consent_and_sample_uuid_exists_in_participants() throws Exception {
        String shared1 = "phs_shared.v1.p1.c1";
        String shared2 = "phs_shared.v1.p2.c1";

        String inputA = JobTestSupport.tempFile("sstr-a.tsv", HEADER
                + row(shared1, "SAMP_A1", "1")
                + row(shared2, "SAMP_A2", "1"));
        String inputB = JobTestSupport.tempFile("sstr-b.tsv", HEADER
                + row(shared1, "SAMP_B1", "2")
                + row(shared2, "SAMP_B2", "2"));

        List<JobResult> results = runConcurrently(inputA, inputB, "orphans");
        assertThat(results).allSatisfy(r -> assertThat(r.isSuccess()).isTrue());

        Long orphanedConsents = jdbc.queryForObject("""
                SELECT COUNT(*) FROM consents c
                WHERE NOT EXISTS (SELECT 1 FROM participants p WHERE p.hpds_uuid = c.hpds_uuid)
                """, Long.class);
        Long orphanedSamples = jdbc.queryForObject("""
                SELECT COUNT(*) FROM samples s
                WHERE NOT EXISTS (SELECT 1 FROM participants p WHERE p.hpds_uuid = s.hpds_uuid)
                """, Long.class);

        assertThat(orphanedConsents).as("consents referencing a uuid with no participant").isZero();
        assertThat(orphanedSamples).as("samples referencing a uuid with no participant").isZero();

        // Two subjects, one identity each, shared across both studies.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM participants", Long.class)).isEqualTo(2);
    }

    /**
     * Both studies insert the SAME two new subjects, listed in opposite order in their files. If
     * inserts followed file order, the two transactions would each hold the row the other needs
     * and Postgres would break the deadlock by aborting one. resolveOrCreate inserts in sorted
     * source-id order precisely so that cannot happen.
     */
    @Test
    void opposing_insert_orders_do_not_deadlock() throws Exception {
        String first = "phs_shared.v1.p1.c1";
        String second = "phs_shared.v1.p2.c1";

        String inputA = JobTestSupport.tempFile("sstr-a.tsv", HEADER
                + row(first, "SAMP_A1", "1")
                + row(second, "SAMP_A2", "1"));
        // Reversed on purpose.
        String inputB = JobTestSupport.tempFile("sstr-b.tsv", HEADER
                + row(second, "SAMP_B1", "2")
                + row(first, "SAMP_B2", "2"));

        List<JobResult> results = runConcurrently(inputA, inputB, "deadlock");

        assertThat(results).allSatisfy(r -> assertThat(r.isSuccess())
                .as("a deadlock would surface as an aborted transaction: %s / %s",
                        r.getExitCode(), r.getErrorMessage())
                .isTrue());
        assertThat(participantRowsFor(first)).isEqualTo(1);
        assertThat(participantRowsFor(second)).isEqualTo(1);
        assertThat(consentRowsForUuid(storedUuid(first))).isEqualTo(2);
        assertThat(consentRowsForUuid(storedUuid(second))).isEqualTo(2);
    }
}
