package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.ParticipantRepository;
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
 * Integration tests for {@link SingleConsentDataPopulateRdsParticipantsJob} against a real
 * Postgres. Exercises success (both consent types, with/without sample population),
 * uuid-reuse for pre-existing participants, and every failure mode.
 *
 * <p>{@code STUDY_ID} deliberately does NOT match the {@code phs######} shape used by
 * {@link SstrPopulateRdsParticipantsJob} -- this job's {@code --study-id} is used verbatim
 * and is not format-validated.
 */
class SingleConsentDataPopulateRdsParticipantsJobIT extends AbstractIntegrationTest {

    private static final String STUDY_ID = "my-study-01";

    @Autowired
    private SingleConsentDataPopulateRdsParticipantsJob job;
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

    private static JobResult run(JobExecutor executor, SingleConsentDataPopulateRdsParticipantsJob job,
                                  String input, String consentType, boolean subjectIdIsSampleId, String runId) {
        return executor.run(job, Map.of(
                "input", input,
                "study-id", STUDY_ID,
                "consent-type", consentType,
                "subject-id-is-sample-id", String.valueOf(subjectIdIsSampleId)), runId);
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
    void loads_participants_and_consents_for_single_consent_type_ignoring_header_name() {
        // Header name is deliberately meaningless -- only column position matters.
        String input = JobTestSupport.tempFile("subjects.csv", "totally_random_header\nSUBJ1\nSUBJ2\n");

        JobResult result = run(executor, job, input, "single", false, "it-single");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("rowsRead", 2L)
                .containsEntry("distinctSubjects", 2L)
                .containsEntry("participantsInserted", 2L)
                .containsEntry("consentsWritten", 2L)
                .containsEntry("samplesInserted", 0L)
                .containsEntry("consentCode", "1")
                .containsEntry("consentAbbreviation", "GRU");
        assertThat(participants.count()).isEqualTo(2);
        assertThat(consentCountForStudy()).isEqualTo(2);
        assertThat(sampleCount()).isEqualTo(0);
        Long withCode = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE study_id = ? AND consent_code = '1' AND consent_abbreviation = 'GRU'",
                Long.class, STUDY_ID);
        assertThat(withCode).isEqualTo(2);
    }

    @Test
    void loads_public_consent_type_with_empty_abbreviation() {
        String input = JobTestSupport.tempFile("subjects.csv", "subject_id\nSUBJ1\n");

        JobResult result = run(executor, job, input, "public", false, "it-public");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("consentCode", "public")
                .containsEntry("consentAbbreviation", "");
        Long withCode = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE study_id = ? AND consent_code = 'public' AND consent_abbreviation = ''",
                Long.class, STUDY_ID);
        assertThat(withCode).isEqualTo(1);
    }

    @Test
    void also_populates_samples_when_subject_id_is_sample_id() {
        String input = JobTestSupport.tempFile("subjects.csv", "subject_id\nSUBJ1\nSUBJ2\n");

        JobResult result = run(executor, job, input, "single", true, "it-samples");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("samplesInserted", 2L);
        assertThat(sampleCount()).isEqualTo(2);
        Long matching = jdbc.queryForObject(
                "SELECT COUNT(*) FROM samples WHERE source_sample_id IN ('SUBJ1', 'SUBJ2') AND sample_source = ?",
                Long.class, STUDY_ID);
        assertThat(matching).isEqualTo(2);
    }

    @Test
    void reuses_existing_participant_uuid_for_the_same_source() {
        UUID existingUuid = UUID.randomUUID();
        jdbc.update("INSERT INTO participants (hpds_uuid, source_id, source) VALUES (?, ?, ?)",
                existingUuid, "SUBJ1", STUDY_ID);
        String input = JobTestSupport.tempFile("subjects.csv", "subject_id\nSUBJ1\nSUBJ2\n");

        JobResult result = run(executor, job, input, "single", false, "it-reuse");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("distinctSubjects", 2L)
                .containsEntry("participantsInserted", 1L); // only SUBJ2 is new
        assertThat(participants.count()).isEqualTo(2);
        UUID consentUuidForSubj1 = jdbc.queryForObject(
                "SELECT c.hpds_uuid FROM consents c JOIN participants p ON p.hpds_uuid = c.hpds_uuid "
                        + "WHERE p.source_id = ? AND p.source = ?", UUID.class, "SUBJ1", STUDY_ID);
        assertThat(consentUuidForSubj1).isEqualTo(existingUuid);
    }

    @Test
    void purges_stale_consent_rows_for_the_study_before_repopulating() {
        jdbc.update("INSERT INTO consents (hpds_uuid, study_id, consent_code, consent_abbreviation) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID(), STUDY_ID, "9", "STALE");
        String input = JobTestSupport.tempFile("subjects.csv", "subject_id\nSUBJ1\n");

        JobResult result = run(executor, job, input, "single", false, "it-purge");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(consentCountForStudy()).isEqualTo(1);
        Long stale = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE study_id = ? AND consent_abbreviation = ?",
                Long.class, STUDY_ID, "STALE");
        assertThat(stale).isZero();
    }

    @Test
    void matches_consent_type_case_insensitively() {
        String input = JobTestSupport.tempFile("subjects.csv", "subject_id\nSUBJ1\n");

        JobResult result = run(executor, job, input, "SiNgLe", false, "it-case-insensitive");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("consentCode", "1")
                .containsEntry("consentAbbreviation", "GRU");
    }

    @Test
    void fails_validation_on_bad_consent_type() {
        String input = JobTestSupport.tempFile("subjects.csv", "subject_id\nSUBJ1\n");

        JobResult result = run(executor, job, input, "private", false, "it-bad-consent-type");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("BAD_CONSENT_TYPE"));
        assertThat(participants.count()).isEqualTo(0);
    }

    @Test
    void fails_validation_on_empty_input() {
        String input = JobTestSupport.tempFile("subjects.csv", "subject_id\n");

        JobResult result = run(executor, job, input, "single", false, "it-empty");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(participants.count()).isEqualTo(0);
    }

    @Test
    void fails_and_rolls_back_on_blank_subject_id() {
        String input = JobTestSupport.tempFile("subjects.csv", "subject_id\nSUBJ1\n\nSUBJ3\n");

        JobResult result = run(executor, job, input, "single", false, "it-blank-subject");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(participants.count()).isEqualTo(0);
        assertThat(consentCountForStudy()).isEqualTo(0);
    }

    @Test
    void fails_with_infrastructure_error_when_rds_schema_is_missing_a_required_column() {
        // Simulates RDS drifting out of sync with what the job expects (schema is owned/
        // migrated externally). Restored in `finally` because the Postgres container --
        // and therefore this schema change -- is shared across every IT test class for the
        // life of the JVM.
        jdbc.execute("ALTER TABLE consents DROP COLUMN consent_abbreviation");
        try {
            String input = JobTestSupport.tempFile("subjects.csv", "subject_id\nSUBJ1\n");

            JobResult result = run(executor, job, input, "single", false, "it-schema-drift");

            assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
            // The transaction wraps the whole load, so the participant insert that
            // preceded the failed consent insert must have rolled back too.
            assertThat(participants.count()).isEqualTo(0);
        } finally {
            jdbc.execute("ALTER TABLE consents ADD COLUMN consent_abbreviation TEXT NOT NULL DEFAULT ''");
            jdbc.execute("ALTER TABLE consents ALTER COLUMN consent_abbreviation DROP DEFAULT");
        }
    }
}
