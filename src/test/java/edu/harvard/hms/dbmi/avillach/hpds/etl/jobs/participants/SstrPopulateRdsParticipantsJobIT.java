package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.JobTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link SstrPopulateRdsParticipantsJob} against a real Postgres.
 * Exercises success plus each failure mode, the consent-purge behavior, and the
 * transactional guarantee (a bad row leaves every table untouched).
 */
class SstrPopulateRdsParticipantsJobIT extends AbstractIntegrationTest {

    private static final String HEADER =
            "SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id\tdbgap_sample_id\n";
    private static final String STUDY_ID = "phs001412";

    @Autowired
    private SstrPopulateRdsParticipantsJob job;
    @Autowired
    private JobExecutor executor;
    @Autowired
    private ParticipantRepository participants;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE TABLE participants, consents, samples");
    }

    private static JobResult run(JobExecutor executor, SstrPopulateRdsParticipantsJob job, String input,
                                  String runId) {
        return executor.run(job, Map.of("input", input, "study-id", STUDY_ID), runId);
    }

    private long consentCountForStudy() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM consents WHERE study_id = ?", Long.class, STUDY_ID);
        return n == null ? 0 : n;
    }

    private long sampleCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM samples", Long.class);
        return n == null ? 0 : n;
    }

    @Test
    void loads_participants_consents_and_samples() {
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n"
                + "SUBJ1\tSAMP2\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s2\n"
                + "SUBJ2\tSAMP3\t2\tHMB\tphs001412.v1.p2.c1\tphs001412.v1.p2.s1\n");

        JobResult result = run(executor, job, input, "it-success");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("rowsRead", 3L)
                .containsEntry("distinctParticipants", 2L)
                .containsEntry("participantsInserted", 2L)
                .containsEntry("consentsWritten", 2L)
                .containsEntry("samplesInserted", 3L)
                .containsEntry("submittedSamplesInserted", 3L);
        assertThat(participants.count()).isEqualTo(2);
        assertThat(consentCountForStudy()).isEqualTo(2);
        // 3 dbGaP-id rows + 3 submitted SAMPLE_ID rows
        assertThat(sampleCount()).isEqualTo(6);
    }

    @Test
    void submitted_sample_ids_land_verbatim_with_their_own_source() {
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                + "SUBJ1\tNWD678650\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n");

        JobResult result = run(executor, job, input, "it-submitted-nwd");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        Long nwd = jdbc.queryForObject(
                "SELECT COUNT(*) FROM samples WHERE source_sample_id = 'NWD678650' AND sample_source = ?",
                Long.class, SstrPopulateRdsParticipantsJob.SOURCE_SUBMITTED);
        assertThat(nwd).isEqualTo(1);
        Long dbgap = jdbc.queryForObject(
                "SELECT COUNT(*) FROM samples WHERE source_sample_id = 'phs001412.v1.p1.s1' AND sample_source = ?",
                Long.class, SstrPopulateRdsParticipantsJob.SOURCE);
        assertThat(dbgap).isEqualTo(1);
    }

    @Test
    void blank_sample_id_is_skipped_and_subject_still_populates() {
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n"
                + "SUBJ2\tSAMP3\t2\tHMB\tphs001412.v1.p2.c1\t\n");

        JobResult result = run(executor, job, input, "it-blank-sample");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(participants.count()).isEqualTo(2);
        // SUBJ1: dbGaP + submitted; SUBJ2: submitted only (blank dbgap_sample_id)
        assertThat(sampleCount()).isEqualTo(3);
    }

    @Test
    void is_idempotent_on_rerun_and_reuses_existing_participant_uuid() {
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n");

        run(executor, job, input, "it-first");
        JobResult second = run(executor, job, input, "it-second");

        assertThat(second.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(second.getMetrics())
                .containsEntry("participantsInserted", 0L)
                .containsEntry("samplesInserted", 0L)
                .containsEntry("submittedSamplesInserted", 0L);
        assertThat(participants.count()).isEqualTo(1);
        assertThat(consentCountForStudy()).isEqualTo(1);
        assertThat(sampleCount()).isEqualTo(2);
    }

    @Test
    void purges_stale_consent_rows_for_the_study_before_repopulating() {
        jdbc.update("INSERT INTO consents (hpds_uuid, study_id, consent_code, consent_abbreviation) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID(), STUDY_ID, "9", "STALE");
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n");

        JobResult result = run(executor, job, input, "it-purge");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(consentCountForStudy()).isEqualTo(1);
        Long stale = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE study_id = ? AND consent_abbreviation = ?",
                Long.class, STUDY_ID, "STALE");
        assertThat(stale).isZero();
    }

    @Test
    void blank_consent_abbreviation_defaults_to_empty_string() {
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                + "SUBJ1\tSAMP1\t1\t\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n");

        JobResult result = run(executor, job, input, "it-blank-abbrev");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE study_id = ? AND consent_abbreviation = ''",
                Long.class, STUDY_ID);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void fails_and_rolls_back_on_blank_consent() {
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n"
                + "SUBJ2\tSAMP2\t\tHMB\tphs001412.v1.p2.c1\tphs001412.v1.p2.s1\n");

        JobResult result = run(executor, job, input, "it-blank-consent");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(participants.count()).isEqualTo(0);
        assertThat(consentCountForStudy()).isEqualTo(0);
        assertThat(sampleCount()).isEqualTo(0);
    }

    @Test
    void fails_and_rolls_back_on_blank_dbgap_subject_id() {
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n"
                + "SUBJ2\tSAMP2\t2\tHMB\t\tphs001412.v1.p2.s1\n");

        JobResult result = run(executor, job, input, "it-blank-subject");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(participants.count()).isEqualTo(0);
    }

    @Test
    void fails_on_missing_required_column() {
        String input = JobTestSupport.tempFile("sstr.tsv", """
            SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id
            SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1
            """
        );

        JobResult result = run(executor, job, input, "it-missing-col");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(participants.count()).isEqualTo(0);
    }

    /**
     * A header-only or truncated file must not cost the study its consent groups. The purge is
     * guarded before it runs, so this fails as a DATA_ERROR with the transaction rolled back rather
     * than a VALIDATION_FAILED after the purge has committed.
     */
    @Test
    void refuses_to_purge_consents_when_input_has_no_data_rows() {
        UUID existing = UUID.randomUUID();
        jdbc.update("INSERT INTO consents (hpds_uuid, study_id, consent_code, consent_abbreviation) "
                + "VALUES (?, ?, ?, ?)", existing, STUDY_ID, "1", "GRU");
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER);

        JobResult result = run(executor, job, input, "it-empty");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(result.getErrorMessage()).contains("refusing to purge");
        assertThat(participants.count()).isEqualTo(0);
        // The pre-existing consent row must survive.
        assertThat(consentCountForStudy()).isEqualTo(1);
    }

    @Test
    void fails_validation_on_bad_study_id_format() {
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n");

        JobResult result = executor.run(job, Map.of("input", input, "study-id", "phs1412"), "it-bad-study-id");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("BAD_STUDY_ID"));
        assertThat(participants.count()).isEqualTo(0);
    }

    @Test
    void fails_with_infrastructure_error_when_rds_schema_is_missing_a_required_column() {
        // Simulates RDS drifting out of sync with what the job expects (schema is owned/
        // migrated externally, per application.yml). Restored in `finally` because the
        // Postgres container -- and therefore this schema change -- is shared across every
        // IT test class for the life of the JVM.
        jdbc.execute("ALTER TABLE consents DROP COLUMN consent_abbreviation");
        try {
            String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                    + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n");

            JobResult result = run(executor, job, input, "it-schema-drift");

            assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
            // The transaction wraps purge+load, so the participant insert that preceded
            // the failed consent insert must have rolled back too.
            assertThat(participants.count()).isEqualTo(0);
        } finally {
            jdbc.execute("ALTER TABLE consents ADD COLUMN consent_abbreviation TEXT NOT NULL DEFAULT ''");
            jdbc.execute("ALTER TABLE consents ALTER COLUMN consent_abbreviation DROP DEFAULT");
        }
    }
}
