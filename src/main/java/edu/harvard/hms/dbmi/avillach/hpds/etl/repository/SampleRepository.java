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
 * Bulk access to the {@code samples} table. A participant may have many samples, and one
 * participant (one dbGaP subject) may be in several studies, so every row is attributed to the
 * study whose load wrote it and the natural key is {@code (hpds_id, study_id, source_sample_id,
 * sample_source)}; the upsert is idempotent on re-run.
 */
@Repository
public class SampleRepository {

    private static final String INSERT_INTO =
            "INSERT INTO samples (hpds_id, study_id, source_sample_id, sample_source)";
    private static final String ON_CONFLICT =
            "ON CONFLICT (hpds_id, study_id, source_sample_id, sample_source) DO NOTHING";

    private static final String DELETE_BY_STUDY = "DELETE FROM samples WHERE study_id = :studyId";

    /**
     * The conflict key's order; pass it to {@code BatchOps.upsertInChunks}. The key includes
     * study_id, so loads of different studies never touch the same row; the order matters when
     * two loads of the same study overlap, which then wait on each other rather than deadlock.
     */
    public static final Comparator<Sample> KEY_ORDER = Comparator.comparingLong(Sample::hpdsId)
            .thenComparing(Sample::studyId)
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
            return MultiRowInsert.execute(jdbc.getJdbcTemplate(), INSERT_INTO, ON_CONFLICT, 4, samples,
                    s -> new Object[]{s.hpdsId(), s.studyId(), s.sourceSampleId(), s.sampleSource()});
        } catch (DataAccessException e) {
            throw new InfrastructureException("Batch upsert into samples failed", e);
        }
    }

    /** Deletes every sample row a study's loads wrote, e.g. to re-populate it from a fresh file. */
    public int deleteByStudyId(String studyId) {
        try {
            return jdbc.update(DELETE_BY_STUDY, new MapSqlParameterSource().addValue("studyId", studyId));
        } catch (DataAccessException e) {
            throw new InfrastructureException("Delete from samples failed", e);
        }
    }

    /**
     * The samples a study's own loads wrote. Never the samples another study loaded for a shared
     * participant: those belong to that study's genomic data, not this one's. Rows from before
     * samples were attributed to studies (a NULL study_id) match no study.
     */
    public List<Sample> findByStudyId(String studyId) {
        try {
            return jdbc.query(
                    """
                    SELECT hpds_id, study_id, source_sample_id, sample_source
                    FROM samples
                    WHERE study_id = :studyId
                    """,
                    new MapSqlParameterSource().addValue("studyId", studyId),
                    (rs, n) -> new Sample(
                            rs.getLong("hpds_id"),
                            rs.getString("study_id"),
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
