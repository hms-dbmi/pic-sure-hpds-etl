package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;

/**
 * Bulk access to the {@code samples} table. A participant may have many samples, so the
 * natural key is the full triple; the upsert is idempotent on re-run.
 */
@Repository
public class SampleRepository {

    private static final String INSERT_INTO = "INSERT INTO samples (hpds_id, source_sample_id, sample_source)";
    private static final String ON_CONFLICT = "ON CONFLICT (hpds_id, source_sample_id, sample_source) DO NOTHING";

    /**
     * The conflict key's order. Concurrent loads that write rows in one shared order cannot
     * deadlock on each other's uncommitted rows; pass it to {@code BatchOps.upsertInChunks}.
     */
    public static final Comparator<Sample> KEY_ORDER = Comparator.comparingLong(Sample::hpdsId)
            .thenComparing(Sample::sourceSampleId)
            .thenComparing(Sample::sampleSource);

    private final NamedParameterJdbcTemplate jdbc;

    public SampleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int batchUpsert(List<Sample> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        try {
            return MultiRowInsert.execute(jdbc.getJdbcTemplate(), INSERT_INTO, ON_CONFLICT, 3, samples,
                    s -> new Object[]{s.hpdsId(), s.sourceSampleId(), s.sampleSource()});
        } catch (DataAccessException e) {
            throw new InfrastructureException("Batch upsert into samples failed", e);
        }
    }

    public List<Sample> findByStudyId(String studyId) {
        try {
            return jdbc.query(
                    """
                    SELECT DISTINCT s.hpds_id, s.source_sample_id, s.sample_source
                    FROM samples s
                    JOIN consents c ON s.hpds_id = c.hpds_id
                    WHERE c.study_id = :studyId
                    """,
                    new MapSqlParameterSource().addValue("studyId", studyId),
                    (rs, n) -> new Sample(
                            rs.getLong("hpds_id"),
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
