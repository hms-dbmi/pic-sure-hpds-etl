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
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.JobTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MultiPopulateRdsParticipantsJob} against {@link InMemoryParticipantStore}
 * fakes: the rows it writes, the issues it reports, and the failures that don't need a live
 * database. Runs through the real {@link JobExecutor} so thrown exceptions get mapped to the same
 * exit codes production sees. {@link MultiPopulateRdsParticipantsJobIT} covers the same against
 * Postgres, including the purge + load transaction.
 */
class MultiPopulateRdsParticipantsJobTest {

    private static final String STUDY = "phs000007";

    private InMemoryParticipantStore store;
    private JobExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryParticipantStore();
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(Files.createTempDirectory("multi-reports").toString());
        executor = new JobExecutor(new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    private MultiPopulateRdsParticipantsJob newJob() {
        return new MultiPopulateRdsParticipantsJob(new IoResolver(null), new DelimitedReader(),
                store.participants, store.consents, store.samples, InMemoryParticipantStore.noOpTransactionManager());
    }

    private JobResult run(String subject, String sample, String dict) {
        Map<String, String> params = new HashMap<>();
        params.put("study-id", STUDY);
        params.put("subject-input", JobTestSupport.tempFile("Subject.MULTI.txt", subject));
        if (sample != null) {
            params.put("sample-input", JobTestSupport.tempFile("Sample.MULTI.txt", sample));
        }
        if (dict != null) {
            params.put("data-dict", JobTestSupport.tempFile("Subject.data_dict.xml", dict));
        }
        return executor.run(newJob(), params, "unit");
    }

    @Test
    void loads_participants_consents_and_samples() {
        JobResult result = run(ParticipantsMigrationJobTest.SUBJECT_MULTI, ParticipantsMigrationJobTest.SAMPLE_MULTI,
                DbgapMultiFileReaderTest.DATA_DICT);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("rowsRead", 3L)
                .containsEntry("distinctParticipants", 2L)
                .containsEntry("participantsInserted", 2L)
                .containsEntry("consentsWritten", 2L)
                .containsEntry("samplesInserted", 2L)
                .containsEntry("submittedSamplesInserted", 2L)
                .containsEntry("notConsentedSubjects", 1L)
                .containsEntry("consentColumn", "CONSENT")
                .containsEntry("sampleFileLoaded", true)
                .containsEntry("countsByConsentGroup", Map.of("1", 1L, "2", 1L));
        assertThat(store.purgedStudies).containsExactly(STUDY);
        assertThat(store.consentsFor(STUDY)).extracting(Consent::consentAbbreviation)
                .containsExactly("HMB-IRB-MDS", "DS-CVD-IRB-NPU-MDS");
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("CONSENT_CODE_COUNT") && i.message().equals("1: 1 participant(s)"));
    }

    @Test
    void reuses_existing_participants_on_a_rerun() {
        run(ParticipantsMigrationJobTest.SUBJECT_MULTI, null, DbgapMultiFileReaderTest.DATA_DICT);
        JobResult second = run(ParticipantsMigrationJobTest.SUBJECT_MULTI, null, DbgapMultiFileReaderTest.DATA_DICT);

        assertThat(second.getMetrics()).containsEntry("participantsInserted", 0L);
        // Purged and repopulated, not appended.
        assertThat(store.consentsFor(STUDY)).hasSize(2);
    }

    @Test
    void fails_with_config_error_when_the_subject_file_is_missing() {
        JobResult result = executor.run(newJob(),
                Map.of("study-id", STUDY, "subject-input", "/no/such/Subject.MULTI.txt"), "unit-missing");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(result.getErrorMessage()).contains("Input file not found");
    }

    @Test
    void fails_input_validation_on_a_bad_batch_size() {
        JobResult result = executor.run(newJob(), Map.of("study-id", STUDY,
                "subject-input", JobTestSupport.tempFile("s.txt", ParticipantsMigrationJobTest.SUBJECT_MULTI),
                "batch-size", "0"), "unit-batch");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues()).anyMatch(i -> i.code().equals("BAD_BATCH_SIZE"));
    }

    @Test
    void fails_input_validation_and_does_not_purge_on_a_bad_study_id() {
        JobResult result = executor.run(newJob(), Map.of("study-id", "phs000007.v33",
                "subject-input", JobTestSupport.tempFile("s.txt", ParticipantsMigrationJobTest.SUBJECT_MULTI)),
                "unit-bad-study");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues()).anyMatch(i -> i.code().equals("BAD_STUDY_ID"));
        verify(store.consents, never()).deleteByStudyId(anyString());
    }

    @Test
    void fails_with_data_error_and_does_not_purge_when_no_subject_is_consented() {
        JobResult result = run("dbGaP_Subject_ID\tCONSENT\n1001\t0\n1002\t\n", null, null);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(result.getErrorMessage()).contains("no subject has a usable consent").contains("refusing to purge");
        verify(store.consents, never()).deleteByStudyId(anyString());
        verify(store.consents, never()).batchUpsert(any());
    }

    @Test
    void fails_with_data_error_when_the_subject_file_has_no_consent_column() {
        JobResult result = run("dbGaP_Subject_ID\tSUBJECT_ID\n1001\tS1\n", null, null);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(result.getErrorMessage()).contains("no consent column");
        verify(store.consents, never()).deleteByStudyId(anyString());
    }

    @Test
    void fails_with_infrastructure_error_when_the_database_is_unreachable() {
        when(store.consents.deleteByStudyId(anyString()))
                .thenThrow(new InfrastructureException("Delete from consents failed: connection refused"));

        JobResult result = run(ParticipantsMigrationJobTest.SUBJECT_MULTI, null, null);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(result.getErrorMessage()).contains("connection refused");
    }

    @Test
    void file_problems_are_warnings_carrying_file_count_and_examples() {
        JobResult result = run("dbGaP_Subject_ID\tCONSENT\n1001\t1\n1002\tGRU\n", null, null);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues())
                .filteredOn(i -> i.code().equals("NON_NUMERIC_CONSENT"))
                .singleElement()
                .satisfies(i -> assertThat(i.context())
                        .containsEntry("file", "Subject.MULTI.txt")
                        .containsEntry("count", 1L)
                        .containsEntry("examples", java.util.List.of("1002=GRU")));
        assertThat(result.getOutputValidation().getIssues()).anyMatch(i -> i.code().equals("NO_DATA_DICT"));
    }
}
