package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bulk-oriented access to the {@code participants} table. Batched upserts with
 * {@code ON CONFLICT} keep a re-run idempotent: a participant that already exists for a given
 * {@code (source_id, source)} is left untouched.
 *
 * <p>Use {@link #resolveOrCreate}, not {@link #batchUpsert}, whenever the id is needed
 * afterwards. {@code ON CONFLICT DO NOTHING} discards a losing insert without reporting the id
 * that won, so a caller holding its own candidate would write consents and samples against an
 * id that is not in the table.
 *
 * <p>{@link DataAccessException} is rewrapped as {@link InfrastructureException} so a database
 * outage yields the retryable INFRASTRUCTURE_ERROR exit code.
 */
@Repository
public class ParticipantRepository {

    private static final String UPSERT = """
            INSERT INTO participants (source_id, source)
            VALUES (:sourceId, :source)
            ON CONFLICT (source_id, source) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ParticipantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Resolves the HPDS id for every {@code sourceId}, creating participants for the ones that
     * do not exist yet, and returns the id <em>stored in the table</em> for each — never a
     * locally generated candidate that lost an insert race.
     *
     * <p>Concurrent callers sharing a {@code source} (every SSTR study load uses
     * {@code source = "DBGap"}) can both find a subject missing and both insert. The unique
     * constraint on {@code (source_id, source)} lets one win, and {@code ON CONFLICT DO NOTHING}
     * reports the loser's insert as "0 rows" without revealing the winner. Re-reading after the
     * insert resolves this: under {@code READ COMMITTED} (Postgres's default) a conflicting
     * insert blocks until the concurrent writer commits or aborts, and the following
     * {@code SELECT} takes a fresh snapshot.
     *
     * <p>Inserts are issued in sorted {@code sourceId} order so two callers inserting an
     * overlapping set of new ids cannot deadlock on opposite acquisition orders. Callers sharing
     * subjects still serialize on those rows until the winner's transaction commits.
     *
     * @param batchSize rows per insert batch; also chunks the lookups so a study with more
     *                  subjects than the JDBC parameter limit still resolves
     * @return the stored id per source id, and how many rows this call inserted
     */
    public Resolution resolveOrCreate(Collection<String> sourceIds, String source, int batchSize) {
        if (sourceIds.isEmpty()) {
            return new Resolution(Map.of(), 0);
        }

        List<String> distinct = sourceIds.stream().distinct().toList();
        Map<String, Long> resolved = new LinkedHashMap<>(findIdsChunked(distinct, source, batchSize));

        List<String> missing = distinct.stream()
                .filter(id -> !resolved.containsKey(id))
                .sorted()
                .toList();

        if (missing.isEmpty()) {
            return new Resolution(resolved, 0);
        }

        int inserted = 0;
        for (int i = 0; i < missing.size(); i += batchSize) {
            inserted += batchInsert(missing.subList(i, Math.min(i + batchSize, missing.size())), source);
        }

        Map<String, Long> stored = findIdsChunked(missing, source, batchSize);
        resolved.putAll(stored);

        if (resolved.size() != distinct.size()) {
            List<String> stillMissing = distinct.stream().filter(id -> !resolved.containsKey(id)).limit(5).toList();
            throw new InfrastructureException("Could not resolve a participant id for "
                    + (distinct.size() - resolved.size()) + " of " + distinct.size() + " source id(s) for source '"
                    + source + "' after insertion; first missing: " + stillMissing);
        }
        return new Resolution(resolved, inserted);
    }

    /** Splits an IN (...) lookup so the JDBC driver's parameter limit is never the ceiling. */
    public Map<String, Long> findIdsChunked(List<String> sourceIds, String source, int batchSize) {
        if (sourceIds.size() <= batchSize) {
            return findIds(sourceIds, source);
        }
        Map<String, Long> all = new LinkedHashMap<>();
        for (int i = 0; i < sourceIds.size(); i += batchSize) {
            all.putAll(findIds(sourceIds.subList(i, Math.min(i + batchSize, sourceIds.size())), source));
        }
        return all;
    }

    /**
     * Outcome of {@link #resolveOrCreate}.
     *
     * @param idsBySourceId the id stored in the table for each source id
     * @param inserted      rows this call created; 0 when every participant already existed,
     *                      which is the normal result of a reload
     */
    public record Resolution(Map<String, Long> idsBySourceId, int inserted) {
    }

    /**
     * Inserts a batch of source_id/source pairs, skipping any that already exist.
     * The {@code hpds_id} is assigned by the database sequence.
     *
     * @return the number of rows inserted; existing rows are not counted
     */
    private int batchInsert(List<String> sourceIds, String source) {
        if (sourceIds.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = sourceIds.stream()
                .map(id -> new MapSqlParameterSource()
                        .addValue("sourceId", id)
                        .addValue("source", source))
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

    /**
     * Inserts a batch of participants, skipping any that already exist.
     *
     * <p>Prefer {@link #resolveOrCreate} when the id matters afterwards: this method cannot
     * report the id of a row that already existed.
     *
     * @return the number of rows inserted; existing rows are not counted
     */
    public int batchUpsert(List<Participant> participants) {
        if (participants.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = participants.stream()
                .map(p -> new MapSqlParameterSource()
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

    /** Resolves the HPDS id for an origin id, if one exists. */
    public Optional<Long> findId(String sourceId, String source) {
        try {
            List<Long> found = jdbc.query(
                    "SELECT hpds_id FROM participants WHERE source_id = :sourceId AND source = :source",
                    new MapSqlParameterSource().addValue("sourceId", sourceId).addValue("source", source),
                    (rs, n) -> rs.getLong("hpds_id"));
            return found.stream().findFirst();
        } catch (DataAccessException e) {
            throw new InfrastructureException("Lookup in participants failed", e);
        }
    }

    /** Resolves HPDS ids for a batch of origin ids sharing the same source in one query. */
    public Map<String, Long> findIds(Collection<String> sourceIds, String source) {
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Long> found = new LinkedHashMap<>();
            jdbc.query(
                    "SELECT source_id, hpds_id FROM participants WHERE source_id IN (:sourceIds) AND source = :source",
                    new MapSqlParameterSource().addValue("sourceIds", sourceIds).addValue("source", source),
                    (RowCallbackHandler) rs -> found.put(rs.getString("source_id"), rs.getLong("hpds_id")));
            return found;
        } catch (DataAccessException e) {
            throw new InfrastructureException("Batch lookup in participants failed", e);
        }
    }

    public List<Participant> findByStudyId(String studyId) {
        try {
            return jdbc.query(
                    """
                    SELECT DISTINCT p.hpds_id, p.source_id, p.source
                    FROM participants p
                    JOIN consents c ON p.hpds_id = c.hpds_id
                    WHERE c.study_id = :studyId
                    """,
                    new MapSqlParameterSource().addValue("studyId", studyId),
                    (rs, n) -> new Participant(
                            rs.getLong("hpds_id"),
                            rs.getString("source_id"),
                            rs.getString("source")));
        } catch (DataAccessException e) {
            throw new InfrastructureException("Query participants by study_id failed", e);
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
