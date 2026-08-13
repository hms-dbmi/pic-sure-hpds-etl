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
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.JobTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        // resolveOrCreate replaced findUuids + batchUpsert; see ParticipantRepository.
        when(participants.resolveOrCreate(any(), any(), anyInt()))
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

    /**
     * The purge must not run at all when the file yielded no subject ids. {@code verify(never())} asserts
     * the delete was never attempted, which a report assertion cannot show: validateOutput runs
     * after the transaction has committed.
     */
    @Test
    void does_not_purge_consents_when_the_input_has_no_data_rows() throws Exception {
        ConsentRepository consents = mock(ConsentRepository.class);
        SingleConsentDataPopulateRdsParticipantsJob job = newJob(
                mock(ParticipantRepository.class), consents, mock(SampleRepository.class));

        String input = JobTestSupport.tempFile("subjects.csv", "subject_id\n");

        JobResult result = newExecutor().run(job,
                Map.of("input", input, "study-id", "my-study-01", "consent-type", "single"),
                "unit-empty-input");

        // DATA_ERROR, not VALIDATION_FAILED: thrown inside the transaction, so it rolls back.
        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(result.getErrorMessage()).contains("refusing to purge");
        verify(consents, never()).deleteByStudyId(anyString());
        verify(consents, never()).batchUpsert(any());
    }

    /**
     * A misspelt {@code --subject-id-is-sample-id} must fail the run rather than read as false.
     * Under {@link Boolean#parseBoolean}, "treu" would disable the samples load and the job would
     * report SUCCESS, making the typo indistinguishable from asking for no samples.
     */
    @Test
    void rejects_a_misspelt_boolean_instead_of_silently_treating_it_as_false() throws Exception {
        ConsentRepository consents = mock(ConsentRepository.class);
        SampleRepository samples = mock(SampleRepository.class);
        SingleConsentDataPopulateRdsParticipantsJob job = newJob(
                mock(ParticipantRepository.class), consents, samples);

        String input = JobTestSupport.tempFile("subjects.csv", VALID_FILE);
        JobResult result = newExecutor().run(job,
                Map.of("input", input, "study-id", "my-study-01", "consent-type", "single",
                        "subject-id-is-sample-id", "treu"),
                "unit-bad-boolean");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("BAD_BOOLEAN") && i.message().contains("treu"));
        // Input validation runs before execute, so nothing was written.
        verify(consents, never()).deleteByStudyId(anyString());
        verify(samples, never()).batchUpsert(any());
    }

    @Test
    void accepts_alternative_boolean_literals_for_subject_id_is_sample_id() throws Exception {
        assertThat(samplesWrittenWith("yes")).as("'yes' should enable the samples load").isTrue();
        assertThat(samplesWrittenWith("off")).as("'off' should disable the samples load").isFalse();
    }

    /** Runs the job with the given flag value and reports whether any samples row was written. */
    private boolean samplesWrittenWith(String flagValue) throws Exception {
        ParticipantRepository participants = mock(ParticipantRepository.class);
        when(participants.resolveOrCreate(any(), any(), anyInt())).thenAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(0);
            Map<String, UUID> uuids = new LinkedHashMap<>();
            ids.forEach(id -> uuids.put(id, UUID.randomUUID()));
            return new ParticipantRepository.Resolution(uuids, uuids.size());
        });
        SampleRepository samples = mock(SampleRepository.class);

        SingleConsentDataPopulateRdsParticipantsJob job =
                newJob(participants, mock(ConsentRepository.class), samples);

        String input = JobTestSupport.tempFile("subjects.csv", VALID_FILE);
        JobResult result = newExecutor().run(job,
                Map.of("input", input, "study-id", "my-study-01", "consent-type", "single",
                        "subject-id-is-sample-id", flagValue),
                "unit-boolean-" + flagValue);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        return !Mockito.mockingDetails(samples).getInvocations().isEmpty();
    }
}
