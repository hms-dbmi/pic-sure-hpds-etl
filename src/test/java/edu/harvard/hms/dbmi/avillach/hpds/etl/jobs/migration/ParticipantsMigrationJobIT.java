package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ParticipantsMigrationJob} against a real Postgres.
 * Exercises success plus each failure mode, and asserts the transactional guarantee
 * (a bad row leaves the table empty). Add a new {@code @Test} per business case found.
 */
class ParticipantsMigrationJobIT extends AbstractIntegrationTest {

    @Autowired
    private ParticipantsMigrationJob job;
    @Autowired
    private JobExecutor executor;
    @Autowired
    private ParticipantRepository participants;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.execute("TRUNCATE TABLE participants");
    }

    @Test
    void loads_all_rows() {
        String input = JobTestSupport.tempFile("participants.csv",
                "source_id,source\nphs000001,dbgap\nphs000002,dbgap\n1kg-1,1000genomes\n");

        JobResult result = executor.run(job, Map.of("input", input), "it-success");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("inserted", 3);
        assertThat(participants.count()).isEqualTo(3);
    }

    @Test
    void is_idempotent_on_rerun() {
        String input = JobTestSupport.tempFile("participants.csv",
                "source_id,source\nphs000001,dbgap\nphs000002,dbgap\n");

        executor.run(job, Map.of("input", input), "it-first");
        JobResult second = executor.run(job, Map.of("input", input), "it-second");

        assertThat(second.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(second.getMetrics()).containsEntry("inserted", 0);
        assertThat(participants.count()).isEqualTo(2);
    }

    @Test
    void fails_and_rolls_back_on_blank_required_field() {
        String input = JobTestSupport.tempFile("participants.csv",
                "source_id,source\nphs000001,dbgap\n,dbgap\n");

        JobResult result = executor.run(job, Map.of("input", input), "it-blank");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        // The whole load is one transaction, so the first good row must NOT have persisted.
        assertThat(participants.count()).isEqualTo(0);
    }

    @Test
    void fails_on_missing_required_column() {
        String input = JobTestSupport.tempFile("participants.csv",
                "source_id,study\nphs000001,dbgap\n");

        JobResult result = executor.run(job, Map.of("input", input), "it-missing-col");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(participants.count()).isEqualTo(0);
    }

    @Test
    void fails_validation_on_empty_input() {
        String input = JobTestSupport.tempFile("participants.csv", "source_id,source\n");

        JobResult result = executor.run(job, Map.of("input", input), "it-empty");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(participants.count()).isEqualTo(0);
    }
}
