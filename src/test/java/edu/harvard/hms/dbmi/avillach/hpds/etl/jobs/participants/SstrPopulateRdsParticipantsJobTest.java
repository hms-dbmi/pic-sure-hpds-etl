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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link SstrPopulateRdsParticipantsJob} covering failures that don't
 * need a live database: a missing input file, and the database being unreachable. Runs
 * through the real {@link JobExecutor} (not just {@code job.run}) so thrown exceptions get
 * mapped to the same exit codes production sees; the repositories are mocked instead of
 * backed by a real connection.
 *
 * <p>Business logic that actually reads/writes RDS (participants/consents/samples upserts,
 * idempotency, purge behavior, schema mismatches) is covered by
 * {@link SstrPopulateRdsParticipantsJobIT} against a real Postgres instead.
 */
class SstrPopulateRdsParticipantsJobTest {

    private static final String VALID_FILE =
            "SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id\tdbgap_sample_id\n"
                    + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n";

    private static JobExecutor newExecutor() throws Exception {
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(Files.createTempDirectory("sstr-reports").toString());
        return new JobExecutor(new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    /** Lets TransactionTemplate run its callback without a real DataSource/connection. */
    private static PlatformTransactionManager noOpTransactionManager() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return txManager;
    }

    private static SstrPopulateRdsParticipantsJob newJob(ParticipantRepository participants,
                                                           ConsentRepository consents,
                                                           SampleRepository samples) {
        return new SstrPopulateRdsParticipantsJob(new IoResolver(null), new DelimitedReader(),
                participants, consents, samples, noOpTransactionManager());
    }

    @Test
    void fails_with_config_error_when_input_file_is_missing() throws Exception {
        SstrPopulateRdsParticipantsJob job = newJob(
                mock(ParticipantRepository.class), mock(ConsentRepository.class), mock(SampleRepository.class));

        JobResult result = newExecutor().run(job,
                Map.of("input", "/no/such/file/sstr.tsv", "study-id", "phs001412"), "unit-missing-file");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(result.getErrorMessage()).contains("Input file not found");
    }

    @Test
    void fails_with_infrastructure_error_when_the_database_is_unreachable() throws Exception {
        ConsentRepository consents = mock(ConsentRepository.class);
        when(consents.deleteByStudyId(anyString()))
                .thenThrow(new InfrastructureException("Delete from consents failed: connection refused"));

        SstrPopulateRdsParticipantsJob job = newJob(
                mock(ParticipantRepository.class), consents, mock(SampleRepository.class));

        String input = JobTestSupport.tempFile("sstr.tsv", VALID_FILE);
        JobResult result = newExecutor().run(job,
                Map.of("input", input, "study-id", "phs001412"), "unit-db-down");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(result.getErrorMessage()).contains("connection refused");
    }

    /**
     * The purge must not happen at all when the file yielded no subjects. Asserting on the
     * repository directly is the point: validateOutput's EMPTY_INPUT check runs after the
     * transaction has committed, so it could report a problem while the consents were already
     * gone. {@code verify(never())} is what pins "the delete was never even attempted".
     */
    @Test
    void does_not_purge_consents_when_the_input_has_no_data_rows() throws Exception {
        ConsentRepository consents = mock(ConsentRepository.class);
        SstrPopulateRdsParticipantsJob job = newJob(
                mock(ParticipantRepository.class), consents, mock(SampleRepository.class));

        String headerOnly =
                "SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id\tdbgap_sample_id\n";
        String input = JobTestSupport.tempFile("sstr.tsv", headerOnly);

        JobResult result = newExecutor().run(job,
                Map.of("input", input, "study-id", "phs001412"), "unit-empty-input");

        // DATA_ERROR, not VALIDATION_FAILED: thrown inside the transaction so it rolls back.
        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(result.getErrorMessage()).contains("refusing to purge");
        verify(consents, never()).deleteByStudyId(anyString());
        verify(consents, never()).batchUpsert(any());
    }
}
