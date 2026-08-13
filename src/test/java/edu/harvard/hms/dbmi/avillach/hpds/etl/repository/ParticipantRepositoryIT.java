package edu.harvard.hms.dbmi.avillach.hpds.etl.db;

import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link ParticipantRepository#resolveOrCreate} contract against a real Postgres,
 * deterministically -- no threads, no timing.
 *
 * <p>The first test is the important one: it demonstrates the exact SQL semantics that make
 * {@code batchUpsert} unusable for learning a uuid, which is the whole reason
 * {@code resolveOrCreate} exists. If someone "simplifies" the job back to
 * {@code findUuids + batchUpsert}, that test is what explains why they must not.
 */
class ParticipantRepositoryIT extends AbstractIntegrationTest {

    private static final String SOURCE = "DBGap";

    @Autowired
    private ParticipantRepository participants;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE TABLE participants, consents, samples");
    }

    private UUID storedUuid(String sourceId) {
        return jdbc.queryForObject("SELECT hpds_uuid FROM participants WHERE source_id = ? AND source = ?",
                UUID.class, sourceId, SOURCE);
    }

    /**
     * The losing side of an insert race. A caller that trusts its own candidate uuid after
     * {@code batchUpsert} is holding a uuid that is not in the table -- and would then write
     * consents and samples against it, giving one person two identities.
     */
    @Test
    void batch_upsert_does_not_reveal_the_uuid_that_won_but_resolve_or_create_does() {
        // Stand in for the concurrent job that got there first.
        UUID winner = UUID.randomUUID();
        jdbc.update("INSERT INTO participants (hpds_uuid, source_id, source) VALUES (?, ?, ?)",
                winner, "SUBJ1", SOURCE);

        // The losing job's own candidate.
        UUID loser = UUID.randomUUID();
        int inserted = participants.batchUpsert(List.of(new Participant(loser, "SUBJ1", SOURCE)));

        // ON CONFLICT DO NOTHING: no row inserted, no error, and no hint of the winner's uuid.
        assertThat(inserted).isZero();
        assertThat(storedUuid("SUBJ1")).isEqualTo(winner).isNotEqualTo(loser);

        // resolveOrCreate returns what is actually stored, which is what the job must use.
        ParticipantRepository.Resolution resolution =
                participants.resolveOrCreate(List.of("SUBJ1"), SOURCE, 100);

        assertThat(resolution.uuidsBySourceId()).containsEntry("SUBJ1", winner);
        assertThat(resolution.inserted()).isZero();
    }

    @Test
    void creates_missing_participants_and_reports_how_many_it_inserted() {
        ParticipantRepository.Resolution first =
                participants.resolveOrCreate(List.of("SUBJ1", "SUBJ2"), SOURCE, 100);

        assertThat(first.inserted()).isEqualTo(2);
        assertThat(first.uuidsBySourceId()).containsOnlyKeys("SUBJ1", "SUBJ2");
        assertThat(first.uuidsBySourceId().get("SUBJ1")).isEqualTo(storedUuid("SUBJ1"));

        // Idempotent: a second call inserts nothing and returns the same uuids.
        ParticipantRepository.Resolution second =
                participants.resolveOrCreate(List.of("SUBJ1", "SUBJ2"), SOURCE, 100);

        assertThat(second.inserted()).isZero();
        assertThat(second.uuidsBySourceId()).isEqualTo(first.uuidsBySourceId());
    }

    @Test
    void resolves_a_mix_of_existing_and_new_ids() {
        UUID existing = UUID.randomUUID();
        jdbc.update("INSERT INTO participants (hpds_uuid, source_id, source) VALUES (?, ?, ?)",
                existing, "OLD", SOURCE);

        ParticipantRepository.Resolution resolution =
                participants.resolveOrCreate(List.of("OLD", "NEW"), SOURCE, 100);

        assertThat(resolution.inserted()).isEqualTo(1);
        assertThat(resolution.uuidsBySourceId())
                .containsEntry("OLD", existing)
                .containsEntry("NEW", storedUuid("NEW"));
    }

    /** The same source id in two different sources is two different people. */
    @Test
    void scopes_resolution_by_source() {
        ParticipantRepository.Resolution dbgap =
                participants.resolveOrCreate(List.of("SUBJ1"), SOURCE, 100);
        ParticipantRepository.Resolution study =
                participants.resolveOrCreate(List.of("SUBJ1"), "some-study", 100);

        assertThat(dbgap.uuidsBySourceId().get("SUBJ1"))
                .isNotEqualTo(study.uuidsBySourceId().get("SUBJ1"));
    }

    /** Lookups are chunked, so a study with more subjects than the driver's parameter limit works. */
    @Test
    void resolves_more_ids_than_one_batch() {
        List<String> ids = List.of("S1", "S2", "S3", "S4", "S5", "S6", "S7");

        ParticipantRepository.Resolution resolution = participants.resolveOrCreate(ids, SOURCE, 2);

        assertThat(resolution.inserted()).isEqualTo(7);
        assertThat(resolution.uuidsBySourceId()).hasSize(7);
        for (String id : ids) {
            assertThat(resolution.uuidsBySourceId().get(id)).isEqualTo(storedUuid(id));
        }
    }

    @Test
    void deduplicates_repeated_source_ids() {
        ParticipantRepository.Resolution resolution =
                participants.resolveOrCreate(List.of("SUBJ1", "SUBJ1", "SUBJ1"), SOURCE, 100);

        assertThat(resolution.inserted()).isEqualTo(1);
        assertThat(resolution.uuidsBySourceId()).hasSize(1);
    }

    @Test
    void returns_an_empty_resolution_for_no_ids() {
        ParticipantRepository.Resolution resolution = participants.resolveOrCreate(List.of(), SOURCE, 100);

        assertThat(resolution.uuidsBySourceId()).isEmpty();
        assertThat(resolution.inserted()).isZero();
    }
}
