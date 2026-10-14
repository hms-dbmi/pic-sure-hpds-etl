package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk access to the {@code consents} table. A participant belongs to at most one
 * consent group per study, so the upsert conflict target is {@code (hpds_id, study_id)}.
 * On conflict the consent_code/consent_abbreviation are refreshed to the incoming values.
 */
@Repository
public class ConsentRepository {

    private static final String INSERT_INTO =
            "INSERT INTO consents (hpds_id, study_id, consent_code, consent_abbreviation)";
    private static final String ON_CONFLICT = """
            ON CONFLICT (hpds_id, study_id) DO UPDATE SET
                consent_code = EXCLUDED.consent_code,
                consent_abbreviation = EXCLUDED.consent_abbreviation""";

    /**
     * The conflict key's order. Concurrent loads that write rows in one shared order cannot
     * deadlock on each other's uncommitted rows; pass it to {@code BatchOps.upsertInChunks}.
     */
    public static final Comparator<Consent> KEY_ORDER =
            Comparator.comparingLong(Consent::hpdsId).thenComparing(Consent::studyId);

    private static final String DELETE_BY_STUDY = "DELETE FROM consents WHERE study_id = :studyId";

    private final NamedParameterJdbcTemplate jdbc;

    public ConsentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upserts the rows in one statement. Postgres refuses a single {@code ON CONFLICT DO UPDATE}
     * that touches one key twice, so a repeated {@code (hpds_id, study_id)} keeps only its last
     * row, the one a row-at-a-time upsert would have left in the table.
     */
    public int batchUpsert(List<Consent> consents) {
        if (consents.isEmpty()) {
            return 0;
        }
        Map<List<Object>, Consent> lastByKey = new LinkedHashMap<>();
        for (Consent c : consents) {
            lastByKey.put(List.of(c.hpdsId(), c.studyId()), c);
        }
        try {
            return MultiRowInsert.execute(jdbc.getJdbcTemplate(), INSERT_INTO, ON_CONFLICT, 4,
                    new ArrayList<>(lastByKey.values()),
                    c -> new Object[]{c.hpdsId(), c.studyId(), c.consentCode(), c.consentAbbreviation()});
        } catch (DataAccessException e) {
            throw new InfrastructureException("Batch upsert into consents failed", e);
        }
    }

    /** Deletes every consent row for a study, e.g. to re-populate it from a fresh file. */
    public int deleteByStudyId(String studyId) {
        try {
            return jdbc.update(DELETE_BY_STUDY, new MapSqlParameterSource().addValue("studyId", studyId));
        } catch (DataAccessException e) {
            throw new InfrastructureException("Delete from consents failed", e);
        }
    }

    public List<Consent> findByStudyId(String studyId) {
        try {
            return jdbc.query(
                    "SELECT hpds_id, study_id, consent_code, consent_abbreviation FROM consents WHERE study_id = :studyId",
                    new MapSqlParameterSource().addValue("studyId", studyId),
                    (rs, n) -> new Consent(
                            rs.getLong("hpds_id"),
                            rs.getString("study_id"),
                            rs.getString("consent_code"),
                            rs.getString("consent_abbreviation")));
        } catch (DataAccessException e) {
            throw new InfrastructureException("Query consents by study_id failed", e);
        }
    }

    public long count() {
        try {
            Long n = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM consents", Long.class);
            return n == null ? 0 : n;
        } catch (DataAccessException e) {
            throw new InfrastructureException("Count of consents failed", e);
        }
    }
}
