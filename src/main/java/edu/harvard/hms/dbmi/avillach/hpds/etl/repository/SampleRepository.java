package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Bulk access to the {@code samples} table. A participant may have many samples, so the
 * natural key is the full triple; the upsert is idempotent on re-run.
 */
@Repository
public class SampleRepository {

    private static final String UPSERT = """
            INSERT INTO samples (hpds_uuid, source_sample_id, sample_source)
            VALUES (:hpdsUuid, :sourceSampleId, :sampleSource)
            ON CONFLICT (hpds_uuid, source_sample_id, sample_source) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SampleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int batchUpsert(List<Sample> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = samples.stream()
                .map(s -> new MapSqlParameterSource()
                        .addValue("hpdsUuid", s.hpdsUuid())
                        .addValue("sourceSampleId", s.sourceSampleId())
                        .addValue("sampleSource", s.sampleSource()))
                .toArray(SqlParameterSource[]::new);
        try {
            int[] counts = jdbc.batchUpdate(UPSERT, batch);
            int inserted = 0;
            for (int c : counts) {
                inserted += Math.max(c, 0);
            }
            return inserted;
        } catch (DataAccessException e) {
            throw new InfrastructureException("Batch upsert into samples failed", e);
        }
    }

    public List<Sample> findByStudyId(String studyId) {
        try {
            return jdbc.query(
                    """
                    SELECT DISTINCT s.hpds_uuid, s.source_sample_id, s.sample_source
                    FROM samples s
                    JOIN consents c ON s.hpds_uuid = c.hpds_uuid
                    WHERE c.study_id = :studyId
                    """,
                    new MapSqlParameterSource().addValue("studyId", studyId),
                    (rs, n) -> new Sample(
                            rs.getObject("hpds_uuid", java.util.UUID.class),
                            rs.getString("source_sample_id"),
                            rs.getString("sample_source")));
        } catch (DataAccessException e) {
            throw new InfrastructureException("Query samples by study_id failed", e);
        }
    }

    public long count() {
        try {
            Long n = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM samples", Long.class);
            return n == null ? 0 : n;
        } catch (DataAccessException e) {
            throw new InfrastructureException("Count of samples failed", e);
        }
    }
}
