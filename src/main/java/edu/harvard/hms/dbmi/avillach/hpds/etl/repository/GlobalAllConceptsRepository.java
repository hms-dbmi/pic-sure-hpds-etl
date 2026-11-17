package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.ConceptPaths;
import org.postgresql.PGConnection;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds {@code global_AllConcepts.csv} in the participant DB: Postgres selects, dedupes and formats
 * every row, and {@code COPY ... TO STDOUT} streams them out, so no row is held in the JVM.
 *
 * <p>A {@link Session} holds one connection for its temp tables: the ready study ids, and every
 * {@code (concept, hpds_id, value)} identity row with the ready studies it is found in. Close it to
 * drop them; the pooled connection outlives the session.
 */
@Repository
public class GlobalAllConceptsRepository {

    private final DataSource dataSource;

    public GlobalAllConceptsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Opens a session over {@code studyIds} and collects their identity rows.
     *
     * @throws InfrastructureException when the DB cannot be reached or a statement fails
     */
    public Session open(Collection<String> studyIds) {
        Connection conn;
        try {
            conn = dataSource.getConnection();
        } catch (SQLException e) {
            throw new InfrastructureException("Could not connect to the participant DB: " + e.getMessage(), e);
        }
        Session session = new Session(conn);
        try {
            session.prepare(studyIds);
            return session;
        } catch (RuntimeException e) {
            session.close();
            throw e;
        }
    }

    /**
     * A study's {@code consents} rows: how many, and how many (and which codes) have a blank
     * abbreviation, so no individual {@code _studies_consents} path.
     */
    public record ConsentStats(long rows, long emptyAbbreviations, String emptyAbbreviationCodes) {
    }

    /**
     * One identity concept's rows across the ready studies.
     *
     * @param rowsByStudyCount number of studies &rarr; rows found in that many
     * @param sharedStudySets  for rows in more than one study, the studies joined with {@code ;} &rarr;
     *                         rows, most rows first
     */
    public record IdentityCounts(Map<Integer, Long> rowsByStudyCount, Map<String, Long> sharedStudySets) {
    }

    public static final class Session implements AutoCloseable {

        private static final String STUDIES = "gac_studies";
        private static final String IDENTITIES = "gac_identities";
        /** A consent with an abbreviation: anything but whitespace. */
        private static final String HAS_ABBREVIATION = "c.consent_abbreviation ~ '\\S'";

        private final Connection conn;

        private Session(Connection conn) {
            this.conn = conn;
        }

        private void prepare(Collection<String> studyIds) {
            try (Statement st = conn.createStatement()) {
                // A session that failed before closing leaves nothing behind, but be sure.
                st.execute("DROP TABLE IF EXISTS pg_temp." + IDENTITIES + ", pg_temp." + STUDIES);
                st.execute("CREATE TEMP TABLE " + STUDIES + " (study_id TEXT PRIMARY KEY)");
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO " + STUDIES + " (study_id) VALUES (?) ON CONFLICT DO NOTHING")) {
                    for (String id : studyIds) {
                        insert.setString(1, id);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                // Each (concept, hpds_id, value) once, with every ready study it is found in. A
                // participant's subject ids come through its consents; samples carry their study.
                st.execute("CREATE TEMP TABLE " + IDENTITIES + " AS "
                        + "SELECT concept, hpds_id, value, COUNT(*) AS study_count, "
                        + "       string_agg(study_id, ';' ORDER BY study_id) AS study_ids "
                        + "FROM ("
                        + "  SELECT DISTINCT " + literal(ConceptPaths.SOURCE_SUBJECT_ID) + " AS concept, "
                        + "         p.hpds_id, p.source_id AS value, c.study_id "
                        + "  FROM participants p "
                        + "  JOIN consents c ON c.hpds_id = p.hpds_id "
                        + "  JOIN " + STUDIES + " s ON s.study_id = c.study_id "
                        + "  UNION ALL "
                        + "  SELECT DISTINCT " + literal(ConceptPaths.SOURCE_SAMPLE_ID) + ", "
                        + "         sa.hpds_id, sa.source_sample_id, sa.study_id "
                        + "  FROM samples sa "
                        + "  JOIN " + STUDIES + " s ON s.study_id = sa.study_id"
                        + ") x GROUP BY concept, hpds_id, value");
                st.execute("ANALYZE " + IDENTITIES);
            } catch (SQLException e) {
                throw new InfrastructureException("Could not collect the identity rows: " + e.getMessage(), e);
            }
        }

        /** Per ready study with any {@code consents} rows; a study without is absent. */
        public Map<String, ConsentStats> consentStats() {
            String sql = "SELECT c.study_id, COUNT(*) AS n, "
                    + "       COUNT(*) FILTER (WHERE NOT " + HAS_ABBREVIATION + ") AS empty, "
                    + "       string_agg(DISTINCT c.consent_code, ', ' ORDER BY c.consent_code) "
                    + "           FILTER (WHERE NOT " + HAS_ABBREVIATION + ") AS empty_codes "
                    + "FROM consents c JOIN " + STUDIES + " s ON s.study_id = c.study_id "
                    + "GROUP BY c.study_id";
            Map<String, ConsentStats> stats = new LinkedHashMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    stats.put(rs.getString("study_id"), new ConsentStats(rs.getLong("n"), rs.getLong("empty"),
                            rs.getString("empty_codes")));
                }
                return stats;
            } catch (SQLException e) {
                throw new InfrastructureException("Could not count consents: " + e.getMessage(), e);
            }
        }

        /**
         * Writes every row of {@code global_AllConcepts.csv}, each field quoted, and returns how many:
         * per consent, {@code µ_consentsµ}, {@code µ_studies_consentsµstudyµ} and (when it has an
         * abbreviation) {@code µ_studies_consentsµstudyµabbreviationµ}; then each identity row once.
         */
        public long copyConcepts(OutputStream out) throws IOException {
            String consents = " FROM consents c JOIN " + STUDIES + " s ON s.study_id = c.study_id";
            String studyPath = literal(ConceptPaths.STUDIES_CONSENTS_PREFIX) + " || c.study_id || 'µ'";
            return copy("COPY ("
                    + "SELECT c.hpds_id, " + literal(ConceptPaths.CONSENTS) + ", '', "
                    + "       c.study_id || '.c' || c.consent_code, '0'" + consents
                    + " UNION ALL "
                    + "SELECT c.hpds_id, " + studyPath + ", '', 'TRUE', '0'" + consents
                    + " UNION ALL "
                    + "SELECT c.hpds_id, " + studyPath + " || c.consent_abbreviation || 'µ', '', 'TRUE', '0'"
                    + consents + " WHERE " + HAS_ABBREVIATION
                    + " UNION ALL "
                    + "SELECT hpds_id, concept, '', value, '0' FROM " + IDENTITIES
                    + ") TO STDOUT WITH (FORMAT csv, FORCE_QUOTE *)", out);
        }

        /**
         * Writes {@code concept,hpds_id,value,study_count,study_ids} rows (no header) for every
         * identity row, the text fields quoted, and returns how many.
         */
        public long copyIdentityStudies(OutputStream out) throws IOException {
            return copy("COPY (SELECT concept, hpds_id, value, study_count, study_ids FROM " + IDENTITIES
                    + ") TO STDOUT WITH (FORMAT csv, FORCE_QUOTE (concept, value, study_ids))", out);
        }

        /** How {@code concept}'s rows spread over the ready studies; {@code setsReported} sets at most. */
        public IdentityCounts identityCounts(String concept, int setsReported) {
            Map<Integer, Long> byCount = new TreeMap<>();
            Map<String, Long> sets = new LinkedHashMap<>();
            try {
                try (PreparedStatement ps = conn.prepareStatement("SELECT study_count, COUNT(*) AS n FROM "
                        + IDENTITIES + " WHERE concept = ? GROUP BY study_count")) {
                    ps.setString(1, concept);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            byCount.put(rs.getInt("study_count"), rs.getLong("n"));
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("SELECT study_ids, COUNT(*) AS n FROM "
                        + IDENTITIES + " WHERE concept = ? AND study_count > 1 "
                        + "GROUP BY study_ids ORDER BY n DESC, study_ids LIMIT ?")) {
                    ps.setString(1, concept);
                    ps.setInt(2, setsReported);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            sets.put(rs.getString("study_ids"), rs.getLong("n"));
                        }
                    }
                }
                return new IdentityCounts(byCount, sets);
            } catch (SQLException e) {
                throw new InfrastructureException("Could not summarize " + concept + " rows: " + e.getMessage(), e);
            }
        }

        private long copy(String sql, OutputStream out) throws IOException {
            try {
                return conn.unwrap(PGConnection.class).getCopyAPI().copyOut(sql, out);
            } catch (SQLException e) {
                throw new InfrastructureException("COPY from the participant DB failed: " + e.getMessage(), e);
            }
        }

        /** Drops the temp tables and returns the connection to the pool. */
        @Override
        public void close() {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS pg_temp." + IDENTITIES + ", pg_temp." + STUDIES);
            } catch (SQLException ignored) {
                // The connection may be broken; closing it below is what matters.
            } finally {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // Nothing more to release.
                }
            }
        }

        /** A constant as a SQL string literal. */
        private static String literal(String s) {
            return "'" + s.replace("'", "''") + "'";
        }
    }
}
