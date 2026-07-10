package edu.harvard.hms.dbmi.avillach.hpds.etl.db;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bulk-oriented access to the {@code participants} table. Uses batched upserts with
 * {@code ON CONFLICT} so re-running a migration is idempotent -- a participant that
 * already exists for a given {@code (source_id, source)} is left untouched.
 *
 * <p>Any {@link DataAccessException} is rewrapped as an {@link InfrastructureException}
 * so a DB outage yields the INFRASTRUCTURE_ERROR exit code (retryable by Jenkins).
 */
@Repository
public class ParticipantRepository {

    private static final String UPSERT = """
            INSERT INTO participants (hpds_uuid, source_id, source)
            VALUES (:hpdsUuid, :sourceId, :source)
            ON CONFLICT (source_id, source) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ParticipantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a batch of participants, skipping any that already exist.
     *
     * @return the number of rows actually inserted (existing rows are not counted)
     */
    public int batchUpsert(List<Participant> participants) {
        if (participants.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = participants.stream()
                .map(p -> new MapSqlParameterSource()
                        .addValue("hpdsUuid", p.hpdsUuid())
                        .addValue("sourceId", p.sourceId())
                        .addValue("source", p.source()))
                .toArray(SqlParameterSource[]::new);
        try {
            int[] counts = jdbc.batchUpdate(UPSERT, batch);
            int inserted = 0;
            for (int c : counts) {
                inserted += Math.max(c, 0);
            }
            return inserted;
        } catch (DataAccessException e) {
            throw new InfrastructureException("Batch upsert into participants failed", e);
        }
    }

    /** Resolves the HPDS uuid for an origin id, if one exists. */
    public Optional<UUID> findUuid(String sourceId, String source) {
        try {
            List<UUID> found = jdbc.query(
                    "SELECT hpds_uuid FROM participants WHERE source_id = :sourceId AND source = :source",
                    new MapSqlParameterSource().addValue("sourceId", sourceId).addValue("source", source),
                    (rs, n) -> rs.getObject("hpds_uuid", UUID.class));
            return found.stream().findFirst();
        } catch (DataAccessException e) {
            throw new InfrastructureException("Lookup in participants failed", e);
        }
    }

    public long count() {
        try {
            Long n = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM participants", Long.class);
            return n == null ? 0 : n;
        } catch (DataAccessException e) {
            throw new InfrastructureException("Count of participants failed", e);
        }
    }
}
