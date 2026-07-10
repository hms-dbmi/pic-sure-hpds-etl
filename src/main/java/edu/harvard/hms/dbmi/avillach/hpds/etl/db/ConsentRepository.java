package edu.harvard.hms.dbmi.avillach.hpds.etl.db;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Bulk access to the {@code consents} table. A participant belongs to at most one
 * consent group per study, so the upsert conflict target is {@code (hpds_uuid, study_id)}.
 * On conflict the consent_group is refreshed to the incoming value.
 */
@Repository
public class ConsentRepository {

    private static final String UPSERT = """
            INSERT INTO consents (hpds_uuid, study_id, consent_group)
            VALUES (:hpdsUuid, :studyId, :consentGroup)
            ON CONFLICT (hpds_uuid, study_id) DO UPDATE SET consent_group = EXCLUDED.consent_group
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ConsentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int batchUpsert(List<Consent> consents) {
        if (consents.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = consents.stream()
                .map(c -> new MapSqlParameterSource()
                        .addValue("hpdsUuid", c.hpdsUuid())
                        .addValue("studyId", c.studyId())
                        .addValue("consentGroup", c.consentGroup()))
                .toArray(SqlParameterSource[]::new);
        try {
            int[] counts = jdbc.batchUpdate(UPSERT, batch);
            int affected = 0;
            for (int c : counts) {
                affected += Math.max(c, 0);
            }
            return affected;
        } catch (DataAccessException e) {
            throw new InfrastructureException("Batch upsert into consents failed", e);
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
