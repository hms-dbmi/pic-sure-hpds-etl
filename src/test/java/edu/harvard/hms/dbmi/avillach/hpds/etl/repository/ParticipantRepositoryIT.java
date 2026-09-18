package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link ParticipantRepository#resolveOrCreate} contract against a real Postgres, with no
 * threads or timing involved.
 *
 * <p>{@link #batch_upsert_does_not_reveal_the_id_that_won_but_resolve_or_create_does()} documents
 * the SQL semantics that make {@code batchUpsert} unusable for learning an id, and therefore why
 * a job must not be simplified back to {@code findIds + batchUpsert}.
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

    private long storedId(String sourceId) {
        return jdbc.queryForObject("SELECT hpds_id FROM participants WHERE source_id = ? AND source = ?",
                Long.class, sourceId, SOURCE);
    }

    /**
     * The losing side of an insert race: a caller trusting its own candidate id after
     * {@code batchUpsert} holds an id that is not in the table.
     */
    @Test
    void batch_upsert_does_not_reveal_the_id_that_won_but_resolve_or_create_does() {
        // Stand in for the concurrent job that got there first.
        jdbc.update("INSERT INTO participants (source_id, source) VALUES (?, ?)",
                "SUBJ1", SOURCE);
        long winner = storedId("SUBJ1");

        // The losing job's own candidate.
        int inserted = participants.batchUpsert(List.of(new Participant(0L, "SUBJ1", SOURCE)));

        // ON CONFLICT DO NOTHING: no row inserted, no error, and no hint of the winner's id.
        assertThat(inserted).isZero();
        assertThat(storedId("SUBJ1")).isEqualTo(winner);

        // resolveOrCreate returns what is actually stored, which is what the job must use.
        ParticipantRepository.Resolution resolution =
                participants.resolveOrCreate(List.of("SUBJ1"), SOURCE, 100);

        assertThat(resolution.idsBySourceId()).containsEntry("SUBJ1", winner);
        assertThat(resolution.inserted()).isZero();
    }

    @Test
    void creates_missing_participants_and_reports_how_many_it_inserted() {
        ParticipantRepository.Resolution first =
                participants.resolveOrCreate(List.of("SUBJ1", "SUBJ2"), SOURCE, 100);

        assertThat(first.inserted()).isEqualTo(2);
        assertThat(first.idsBySourceId()).containsOnlyKeys("SUBJ1", "SUBJ2");
        assertThat(first.idsBySourceId().get("SUBJ1")).isEqualTo(storedId("SUBJ1"));

        // Idempotent: a second call inserts nothing and returns the same ids.
        ParticipantRepository.Resolution second =
                participants.resolveOrCreate(List.of("SUBJ1", "SUBJ2"), SOURCE, 100);

        assertThat(second.inserted()).isZero();
        assertThat(second.idsBySourceId()).isEqualTo(first.idsBySourceId());
    }

    @Test
    void resolves_a_mix_of_existing_and_new_ids() {
        jdbc.update("INSERT INTO participants (source_id, source) VALUES (?, ?)",
                "OLD", SOURCE);
        long existing = storedId("OLD");

        ParticipantRepository.Resolution resolution =
                participants.resolveOrCreate(List.of("OLD", "NEW"), SOURCE, 100);

        assertThat(resolution.inserted()).isEqualTo(1);
        assertThat(resolution.idsBySourceId())
                .containsEntry("OLD", existing)
                .containsEntry("NEW", storedId("NEW"));
    }

    /** The same source id in two different sources is two different people. */
    @Test
    void scopes_resolution_by_source() {
        ParticipantRepository.Resolution dbgap =
                participants.resolveOrCreate(List.of("SUBJ1"), SOURCE, 100);
        ParticipantRepository.Resolution study =
                participants.resolveOrCreate(List.of("SUBJ1"), "some-study", 100);

        assertThat(dbgap.idsBySourceId().get("SUBJ1"))
                .isNotEqualTo(study.idsBySourceId().get("SUBJ1"));
    }

    /** Lookups are chunked, so a study with more subjects than the driver's parameter limit works. */
    @Test
    void resolves_more_ids_than_one_batch() {
        List<String> ids = List.of("S1", "S2", "S3", "S4", "S5", "S6", "S7");

        ParticipantRepository.Resolution resolution = participants.resolveOrCreate(ids, SOURCE, 2);

        assertThat(resolution.inserted()).isEqualTo(7);
        assertThat(resolution.idsBySourceId()).hasSize(7);
        for (String id : ids) {
            assertThat(resolution.idsBySourceId().get(id)).isEqualTo(storedId(id));
        }
    }

    @Test
    void deduplicates_repeated_source_ids() {
        ParticipantRepository.Resolution resolution =
                participants.resolveOrCreate(List.of("SUBJ1", "SUBJ1", "SUBJ1"), SOURCE, 100);

        assertThat(resolution.inserted()).isEqualTo(1);
        assertThat(resolution.idsBySourceId()).hasSize(1);
    }

    @Test
    void returns_an_empty_resolution_for_no_ids() {
        ParticipantRepository.Resolution resolution = participants.resolveOrCreate(List.of(), SOURCE, 100);

        assertThat(resolution.idsBySourceId()).isEmpty();
        assertThat(resolution.inserted()).isZero();
    }
}
