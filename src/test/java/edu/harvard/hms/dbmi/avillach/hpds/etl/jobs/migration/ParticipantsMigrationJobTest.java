package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

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
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SstrPopulateRdsParticipantsJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link ParticipantsMigrationJob} covering failures that don't need
 * a live database: the managed-inputs file missing, the shared consents.csv missing, and
 * the database being unreachable. Runs through the real {@link JobExecutor} (not just
 * {@code job.run}) so thrown exceptions get mapped to the same exit codes production sees.
 *
 * <p>Business logic that actually reads/writes RDS (sstr vs. direct population, the
 * open_access-1000Genomes sample exception, per-study failure isolation, the mapping
 * file's content) is covered by {@link ParticipantsMigrationJobIT} against a real
 * Postgres instead.
 */
class ParticipantsMigrationJobTest {

    private static JobExecutor newExecutor() throws Exception {
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(Files.createTempDirectory("migration-reports").toString());
        return new JobExecutor(new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    /** Lets TransactionTemplate run its callback without a real DataSource/connection. */
    private static PlatformTransactionManager noOpTransactionManager() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return txManager;
    }

    private static ManagedInputsService managedInputsService(String uri) {
        EtlProperties props = new EtlProperties();
        props.getManagedInputs().setUri(uri);
        return new ManagedInputsService(new IoResolver(null), new DelimitedReader(), props);
    }

    private static ParticipantsMigrationJob newJob(ManagedInputsService managedInputsService,
                                                     ParticipantRepository participants,
                                                     ConsentRepository consents,
                                                     SampleRepository samples) {
        return new ParticipantsMigrationJob(new IoResolver(null), new DelimitedReader(),
                managedInputsService, participants, consents, samples, noOpTransactionManager(),
                mock(SstrPopulateRdsParticipantsJob.class), mock(JobExecutor.class));
    }

    private static String baseUri(Map<String, String> filesByRelativePath) {
        try {
            Path dir = Files.createTempDirectory("migration-data");
            for (Map.Entry<String, String> entry : filesByRelativePath.entrySet()) {
                Path file = dir.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            }
            return dir.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String tempFile(String name, String content) {
        try {
            Path file = Files.createTempDirectory("migration-input").resolve(name);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void fails_with_config_error_when_managed_inputs_file_is_missing() throws Exception {
        ParticipantsMigrationJob job = newJob(
                managedInputsService("/no/such/file/managed_inputs.csv"),
                mock(ParticipantRepository.class), mock(ConsentRepository.class), mock(SampleRepository.class));

        JobResult result = newExecutor().run(job,
                Map.of("data-folder", "/no/such/folder"),
                "unit-missing-managed-inputs");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(result.getErrorMessage()).contains("Input file not found");
    }

    @Test
    void fails_with_config_error_when_shared_all_concepts_csv_is_missing() throws Exception {
        String managedInputs = tempFile("managed_inputs.csv",
                "Study Abbreviated Name,Study Identifier,Data is ready to process\nABV1,study-01,Yes\n");
        ParticipantsMigrationJob job = newJob(
                managedInputsService(managedInputs),
                mock(ParticipantRepository.class), mock(ConsentRepository.class), mock(SampleRepository.class));

        String folder = baseUri(Map.of("placeholder.txt", ""));

        JobResult result = newExecutor().run(job,
                Map.of("data-folder", folder), "unit-missing-all-concepts");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(result.getErrorMessage()).contains("GLOBAL_allConcepts_merged.csv");
    }

    @Test
    void fails_study_when_patient_mapping_is_empty() throws Exception {
        String managedInputs = tempFile("managed_inputs.csv",
                "Study Abbreviated Name,Study Identifier,Data is ready to process\nABV1,study-01,Yes\n");
        ParticipantsMigrationJob job = newJob(
                managedInputsService(managedInputs),
                mock(ParticipantRepository.class), mock(ConsentRepository.class), mock(SampleRepository.class));

        String folder = baseUri(Map.of(
                "general/completed/GLOBAL_allConcepts_merged.csv", "\"2002\",\"µ_consentsµ\",\"\",\"study-01.c1\",\"0\"\n",
                "abv1/data/ABV1_PatientMapping.v2.csv", ""));

        JobResult result = newExecutor().run(job,
                Map.of("data-folder", folder), "unit-empty-patient-mapping");

        assertThat(result.getMetrics()).containsEntry("failedStudies", 1L);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("STUDY_FAILED")
                        && i.message().contains("yielded no subjects"));
    }

    @Test
    void fails_with_infrastructure_error_when_the_database_is_unreachable() throws Exception {
        ParticipantRepository participants = mock(ParticipantRepository.class);
        // resolveOrCreate is the first DB call on the direct-population path; see
        // ParticipantRepository.
        when(participants.resolveOrCreate(any(), any(), anyInt()))
                .thenThrow(new InfrastructureException("Batch lookup in participants failed: connection refused"));

        String managedInputs = tempFile("managed_inputs.csv",
                "Study Abbreviated Name,Study Identifier,Data is ready to process\nABV1,study-01,Yes\n");
        ParticipantsMigrationJob job = newJob(
                managedInputsService(managedInputs),
                participants, mock(ConsentRepository.class), mock(SampleRepository.class));

        String folder = baseUri(Map.of(
                "general/completed/GLOBAL_allConcepts_merged.csv", "\"2002\",\"µ_consentsµ\",\"\",\"study-01.c1\",\"0\"\n",
                "abv1/data/ABV1_PatientMapping.v2.csv", "SUBJ1,ABV1,2002\n"));

        JobResult result = newExecutor().run(job,
                Map.of("data-folder", folder), "unit-db-down");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(result.getErrorMessage()).contains("connection refused");
    }
}
