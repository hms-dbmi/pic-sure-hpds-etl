package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.JobTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link SingleConsentDataPopulateRdsParticipantsJob} covering
 * failures that don't need a live database: a missing input file, and the database being
 * unreachable. Runs through the real {@link JobExecutor} (not just {@code job.run}) so
 * thrown exceptions get mapped to the same exit codes production sees; the repositories
 * are mocked instead of backed by a real connection.
 *
 * <p>Business logic that actually reads/writes RDS is covered by
 * {@link SingleConsentDataPopulateRdsParticipantsJobIT} against a real Postgres instead.
 */
class SingleConsentDataPopulateRdsParticipantsJobTest {

    private static final String VALID_FILE = "subject_id\nSUBJ1\n";

    private static JobExecutor newExecutor() throws Exception {
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(Files.createTempDirectory("single-consent-reports").toString());
        return new JobExecutor(new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    /** Lets TransactionTemplate run its callback without a real DataSource/connection. */
    private static PlatformTransactionManager noOpTransactionManager() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return txManager;
    }

    private static SingleConsentDataPopulateRdsParticipantsJob newJob(ParticipantRepository participants,
                                                                       ConsentRepository consents,
                                                                       SampleRepository samples) {
        return new SingleConsentDataPopulateRdsParticipantsJob(new IoResolver(null), new DelimitedReader(),
                participants, consents, samples, noOpTransactionManager());
    }

    @Test
    void fails_with_config_error_when_input_file_is_missing() throws Exception {
        SingleConsentDataPopulateRdsParticipantsJob job = newJob(
                mock(ParticipantRepository.class), mock(ConsentRepository.class), mock(SampleRepository.class));

        JobResult result = newExecutor().run(job,
                Map.of("input", "/no/such/file/subjects.csv", "study-id", "my-study-01",
                        "consent-type", "single"),
                "unit-missing-file");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(result.getErrorMessage()).contains("Input file not found");
    }

    @Test
    void fails_with_infrastructure_error_when_the_database_is_unreachable() throws Exception {
        ParticipantRepository participants = mock(ParticipantRepository.class);
        when(participants.findUuids(any(), any()))
                .thenThrow(new InfrastructureException("Batch lookup in participants failed: connection refused"));

        SingleConsentDataPopulateRdsParticipantsJob job = newJob(
                participants, mock(ConsentRepository.class), mock(SampleRepository.class));

        String input = JobTestSupport.tempFile("subjects.csv", VALID_FILE);
        JobResult result = newExecutor().run(job,
                Map.of("input", input, "study-id", "my-study-01", "consent-type", "single"),
                "unit-db-down");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(result.getErrorMessage()).contains("connection refused");
    }
}
