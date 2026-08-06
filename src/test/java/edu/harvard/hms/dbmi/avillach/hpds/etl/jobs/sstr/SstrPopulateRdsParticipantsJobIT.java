package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.sstr;

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
    void loads_participants_consents_and_samples_and_skips_blank_sample_ids() {
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER
                + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n"
                + "SUBJ1\tSAMP2\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s2\n"
                + "SUBJ2\tSAMP3\t2\tHMB\tphs001412.v1.p2.c1\t\n");

        JobResult result = run(executor, job, input, "it-success");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("rowsRead", 3L)
                .containsEntry("distinctParticipants", 2L)
                .containsEntry("participantsInserted", 2L)
                .containsEntry("consentsWritten", 2L)
                .containsEntry("samplesInserted", 2L);
        assertThat(participants.count()).isEqualTo(2);
        assertThat(consentCountForStudy()).isEqualTo(2);
        assertThat(sampleCount()).isEqualTo(2);
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
                .containsEntry("samplesInserted", 0L);
        assertThat(participants.count()).isEqualTo(1);
        assertThat(consentCountForStudy()).isEqualTo(1);
        assertThat(sampleCount()).isEqualTo(1);
    }

    @Test
    void purges_stale_consent_rows_for_the_study_before_repopulating() {
        jdbc.update("INSERT INTO consents (hpds_uuid, study_id, consent_group, consent_abbreviation) "
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
        String input = JobTestSupport.tempFile("sstr.tsv",
                "SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id\n"
                        + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\n");

        JobResult result = run(executor, job, input, "it-missing-col");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(participants.count()).isEqualTo(0);
    }

    @Test
    void fails_validation_on_empty_input() {
        String input = JobTestSupport.tempFile("sstr.tsv", HEADER);

        JobResult result = run(executor, job, input, "it-empty");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(participants.count()).isEqualTo(0);
    }
}
