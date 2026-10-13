package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MultiPopulateRdsParticipantsJob} against a real Postgres: the rows
 * it writes, the per-study consent purge, sharing participants with the SSTR job's source, and
 * refusing to touch anything when the file has no consented subject.
 */
class MultiPopulateRdsParticipantsJobIT extends AbstractIntegrationTest {

    private static final String STUDY = "phs000007";

    @Autowired
    private MultiPopulateRdsParticipantsJob job;
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

    private JobResult run(String subject, String sample, String runId) {
        Map<String, String> params = new java.util.HashMap<>();
        params.put("study-id", STUDY);
        params.put("subject-input", JobTestSupport.tempFile("Subject.MULTI.txt", subject));
        params.put("data-dict", JobTestSupport.tempFile("Subject.data_dict.xml", DbgapMultiFileReaderTest.DATA_DICT));
        if (sample != null) {
            params.put("sample-input", JobTestSupport.tempFile("Sample.MULTI.txt", sample));
        }
        return executor.run(job, params, runId);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void loads_participants_consents_and_samples() {
        JobResult result = run(ParticipantsMigrationJobTest.SUBJECT_MULTI, ParticipantsMigrationJobTest.SAMPLE_MULTI,
                "it-multi-success");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(participants.count()).isEqualTo(2);
        assertThat(count("participants WHERE source = 'DBGap' AND source_id IN ('1001', '1002')")).isEqualTo(2);
        assertThat(count("consents WHERE study_id = ? AND consent_code = '1' AND consent_abbreviation = 'HMB-IRB-MDS'",
                STUDY)).isEqualTo(1);
        assertThat(count("consents WHERE study_id = ? AND consent_code = '2' AND consent_abbreviation = 'DS-CVD-IRB-NPU-MDS'",
                STUDY)).isEqualTo(1);
        // 2 dbGaP + 2 submitted; consent-0 subject 1003's sample is skipped.
        assertThat(count("samples")).isEqualTo(4);
        assertThat(count("samples WHERE source_sample_id IN ('5003', 'NWD3')")).isZero();
    }

    @Test
    void purges_only_this_study_s_consents_and_is_idempotent() {
        run(ParticipantsMigrationJobTest.SUBJECT_MULTI, null, "it-multi-first");
        jdbc.update("INSERT INTO consents (hpds_id, study_id, consent_code, consent_abbreviation) "
                + "SELECT hpds_id, 'phs999999', '1', 'OTHER' FROM participants WHERE source_id = '1001'");

        JobResult second = run(ParticipantsMigrationJobTest.SUBJECT_MULTI, null, "it-multi-second");

        assertThat(second.getMetrics()).containsEntry("participantsInserted", 0L);
        assertThat(participants.count()).isEqualTo(2);
        assertThat(count("consents WHERE study_id = ?", STUDY)).isEqualTo(2);
        assertThat(count("consents WHERE study_id = 'phs999999'")).isEqualTo(1);
    }

    @Test
    void refuses_to_purge_when_no_subject_is_consented() {
        run(ParticipantsMigrationJobTest.SUBJECT_MULTI, null, "it-multi-seed");

        JobResult result = run("dbGaP_Subject_ID\tCONSENT\n1001\t0\n", null, "it-multi-none");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(count("consents WHERE study_id = ?", STUDY)).isEqualTo(2);
    }
}
