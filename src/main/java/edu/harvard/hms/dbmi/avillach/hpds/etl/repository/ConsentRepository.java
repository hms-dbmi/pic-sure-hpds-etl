package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * The conflict key's order; pass it to {@code BatchOps.upsertInChunks}. The key includes
     * study_id, so loads of different studies never touch the same row; the order matters when
     * two loads of the same study overlap, which then wait on each other rather than deadlock.
     */
    public static final Comparator<Consent> KEY_ORDER =
            Comparator.comparingLong(Consent::hpdsId).thenComparing(Consent::studyId);

    private static final String DELETE_BY_STUDY = "DELETE FROM consents WHERE study_id = :studyId";

    private static final Logger log = LoggerFactory.getLogger(ConsentRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public ConsentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upserts the rows in as few statements as the bind-parameter limit allows. Postgres refuses
     * a single {@code ON CONFLICT DO UPDATE} that touches one key twice, so a repeated
     * {@code (hpds_id, study_id)} keeps only its last row, the one a row-at-a-time upsert would
     * have left in the table. A repeat with a different consent code means the input puts one
     * participant in two consent groups of a study; the last still wins, but it is logged as a
     * warning with a sample of the collisions, since that is usually an input problem.
     *
     * @return rows inserted or updated after deduplication, as Postgres reports them: a repeated
     *         key counts once, so this can be less than {@code consents.size()}
     */
    public int batchUpsert(List<Consent> consents) {
        if (consents.isEmpty()) {
            return 0;
        }
        Deduplicated deduplicated = deduplicate(consents);
        if (deduplicated.conflicting() > 0) {
            log.warn("{} consent row(s) repeat an (hpds_id, study_id) with a different consent code; "
                            + "the last row for each key was kept. Sample: {}",
                    deduplicated.conflicting(), String.join("; ", deduplicated.sample()));
        }
        try {
            return MultiRowInsert.execute(jdbc.getJdbcTemplate(), INSERT_INTO, ON_CONFLICT, 4,
                    deduplicated.rows(),
                    c -> new Object[]{c.hpdsId(), c.studyId(), c.consentCode(), c.consentAbbreviation()});
        } catch (DataAccessException e) {
            throw new InfrastructureException("Batch upsert into consents failed", e);
        }
    }

    /** How many conflicting repeats to describe in the warning. */
    static final int CONFLICT_SAMPLE_SIZE = 5;

    /**
     * The rows to write, last row per key in first-seen key order, plus how many rows replaced an
     * earlier one with a different consent code and a description of the first few.
     */
    record Deduplicated(List<Consent> rows, int conflicting, List<String> sample) {}

    static Deduplicated deduplicate(List<Consent> consents) {
        Map<List<Object>, Consent> lastByKey = new LinkedHashMap<>();
        int conflicting = 0;
        List<String> sample = new ArrayList<>();
        for (Consent c : consents) {
            Consent previous = lastByKey.put(List.of(c.hpdsId(), c.studyId()), c);
            if (previous != null && !Objects.equals(previous.consentCode(), c.consentCode())) {
                conflicting++;
                if (sample.size() < CONFLICT_SAMPLE_SIZE) {
                    sample.add("hpds_id=" + c.hpdsId() + " study_id=" + c.studyId()
                            + " codes " + previous.consentCode() + " -> " + c.consentCode());
                }
            }
        }
        return new Deduplicated(new ArrayList<>(lastByKey.values()), conflicting, List.copyOf(sample));
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
