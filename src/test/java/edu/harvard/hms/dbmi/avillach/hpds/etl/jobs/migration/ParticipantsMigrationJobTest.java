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
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SstrPopulateRdsParticipantsJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ParticipantsMigrationJob}: source selection (SSTR, else the dbGaP MULTI
 * files, else fail), what each source loads, the source/issue reports, and the failures that
 * don't need a live database. The repositories are {@link InMemoryParticipantStore} fakes and the
 * sub-jobs are real, all run through the real {@link JobExecutor} so thrown exceptions get mapped
 * to the same exit codes production sees.
 *
 * <p>The same paths against a real Postgres (transactions, rollback of a malformed SSTR) are
 * covered by {@link ParticipantsMigrationJobIT}.
 */
class ParticipantsMigrationJobTest {

    private static final String STUDY = "phs000007";
    private static final String ABV = "FHS";
    private static final String MANAGED_HEADER = "Study Abbreviated Name,Study Identifier,Data is ready to process\n";

    static final String SSTR = """
            SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id\tdbgap_sample_id
            S1\tNWD1\t1\tHMB-IRB-MDS\t1001\t5001
            """;
    /** Missing CONSENT and consent_abbreviation: the SSTR job rejects it as malformed. */
    static final String MALFORMED_SSTR = """
            SUBJECT_ID\tSAMPLE_ID\tdbgap_subject_id\tdbgap_sample_id
            S1\tNWD1\t1001\t5001
            """;
    static final String SUBJECT_MULTI = DbgapMultiFileReaderTest.PREAMBLE + """
            dbGaP_Subject_ID\tSUBJECT_ID\tCONSENT\tAFFECTION_STATUS
            1001\tS1\t1\t1
            1002\tS2\t2\t0
            1003\tS3\t0\t0
            """;
    static final String SAMPLE_MULTI = DbgapMultiFileReaderTest.PREAMBLE + """
            dbGaP_Subject_ID\tdbGaP_Sample_ID\tSUBJECT_ID\tSAMPLE_ID
            1001\t5001\tS1\tNWD1
            1002\t5002\tS2\tNWD2
            1003\t5003\tS3\tNWD3
            """;
    static final String SUBJECT_MULTI_NAME = "phs000007.v33.pht000182.v14.p14.FHS_Subject.MULTI.txt";
    static final String SAMPLE_MULTI_NAME = "phs000007.v33.pht001415.v1.p14.FHS_Sample.MULTI.txt";
    static final String DATA_DICT_NAME = "phs000007.v33.pht000182.v14.FHS_Subject.data_dict.xml";
    static final String SSTR_NAME = "sstr_phs000007.v33.txt";

    private InMemoryParticipantStore store;
    private PlatformTransactionManager txManager;
    private Path reportsDir;
    private JobExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        store = new InMemoryParticipantStore();
        txManager = InMemoryParticipantStore.noOpTransactionManager();
        reportsDir = Files.createTempDirectory("migration-reports");
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(reportsDir.toString());
        executor = new JobExecutor(new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    private static ManagedInputsService managedInputsService(String uri) {
        EtlProperties props = new EtlProperties();
        props.getManagedInputs().setUri(uri);
        return new ManagedInputsService(new IoResolver(null), new DelimitedReader(), props);
    }

    private ParticipantsMigrationJob newJob(String managedInputsUri) {
        IoResolver io = new IoResolver(null);
        DelimitedReader reader = new DelimitedReader();
        return new ParticipantsMigrationJob(io, reader, managedInputsService(managedInputsUri), store.participants,
                new SstrPopulateRdsParticipantsJob(io, reader, store.participants, store.consents, store.samples,
                        txManager),
                new MultiPopulateRdsParticipantsJob(io, reader, store.participants, store.consents, store.samples,
                        txManager),
                executor);
    }

    private JobResult run(Map<String, String> files, String... managedRows) {
        String managed = tempFile("managed_inputs.csv", MANAGED_HEADER + String.join("\n", managedRows) + "\n");
        return executor.run(newJob(managed), Map.of("data-folder", baseUri(files)), "unit");
    }

    private JobResult runOneStudy(Map<String, String> rawData, String patientMapping) {
        Map<String, String> files = new LinkedHashMap<>();
        rawData.forEach((name, content) -> files.put(STUDY + "/rawData/" + name, content));
        files.put(STUDY + "/legacy/data/" + ABV + "_PatientMapping.v2.csv", patientMapping);
        return run(files, ABV + "," + STUDY + ",Yes");
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

    private String report(String name) {
        try {
            return Files.readString(reportsDir.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The {@code participants_migration_sources.csv} row for a study, as column &rarr; value. */
    private Map<String, String> sourcesRow(String studyId) {
        List<String> lines = report(ParticipantsMigrationJob.SOURCES_REPORT).lines().toList();
        String[] header = lines.get(0).split(",", -1);
        String row = lines.stream().filter(l -> l.startsWith(studyId + ",")).findFirst()
                .orElseThrow(() -> new AssertionError("no sources row for " + studyId + " in " + lines));
        List<String> values = parseCsvLine(row);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            out.put(header[i], i < values.size() ? values.get(i) : "");
        }
        return out;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                values.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        values.add(cur.toString());
        return values;
    }

    /** The {@code participants_migration_file_issues.csv} row for a study and code, as column &rarr; value. */
    private Map<String, String> issueRow(String studyId, String code) {
        List<String> lines = report(ParticipantsMigrationJob.ISSUES_REPORT).lines().toList();
        String[] header = lines.get(0).split(",", -1);
        List<String> values = lines.stream().skip(1).map(ParticipantsMigrationJobTest::parseCsvLine)
                .filter(v -> v.get(0).equals(studyId) && v.get(2).equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " issue for " + studyId + " in " + lines));
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            out.put(header[i], values.get(i));
        }
        return out;
    }

    private List<String> issueCodes(String studyId) {
        return report(ParticipantsMigrationJob.ISSUES_REPORT).lines().skip(1)
                .filter(l -> l.startsWith(studyId + ","))
                .map(l -> parseCsvLine(l).get(2))
                .toList();
    }

    // ---- framework-level failures --------------------------------------------

    @Test
    void fails_with_config_error_when_managed_inputs_file_is_missing() {
        JobResult result = executor.run(newJob("/no/such/file/managed_inputs.csv"),
                Map.of("data-folder", "/no/such/folder"), "unit-missing-managed-inputs");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(result.getErrorMessage()).contains("Input file not found");
    }

    @Test
    void study_filter_limits_the_run_to_matching_studies() {
        String managed = tempFile("managed_inputs.csv", MANAGED_HEADER + "ABV1,phs000001,Yes\nABV2,phs000002,Yes\n");
        String folder = baseUri(Map.of(
                "phs000002/rawData/sstr_phs000002.v1.txt", SSTR,
                "phs000002/legacy/data/ABV2_PatientMapping.v2.csv", "1001,ABV2,2002\n"));

        JobResult result = executor.run(newJob(managed),
                Map.of("data-folder", folder, "study-filter", "phs000002"), "unit-study-filter");

        assertThat(result.getMetrics()).containsEntry("readyStudies", 1L).containsEntry("succeededStudies", 1L);
        assertThat(result.getMetrics().toString()).contains("phs000002").doesNotContain("phs000001");
    }

    @Test
    void fails_study_when_patient_mapping_is_empty() {
        JobResult result = runOneStudy(Map.of(SSTR_NAME, SSTR), "");

        assertThat(result.getMetrics()).containsEntry("failedStudies", 1L);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("STUDY_FAILED") && i.message().contains("yielded no subjects"));
        // Discovery still made it into the sources report.
        assertThat(sourcesRow(STUDY)).containsEntry("outcome", "failed").containsEntry("sstr_file", SSTR_NAME);
    }

    @Test
    void fails_with_infrastructure_error_when_the_database_is_unreachable_on_the_multi_path() {
        when(store.consents.deleteByStudyId(anyString()))
                .thenThrow(new InfrastructureException("Delete from consents failed: connection refused"));

        JobResult result = runOneStudy(Map.of(SUBJECT_MULTI_NAME, SUBJECT_MULTI), "1001,FHS,9001\n");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(result.getErrorMessage()).contains("connection refused");
    }

    /** A database outage during the SSTR load must abort the run, not be mistaken for a malformed SSTR. */
    @Test
    void an_infrastructure_failure_in_the_sstr_job_does_not_fall_back_to_multi() {
        when(store.consents.deleteByStudyId(anyString()))
                .thenThrow(new InfrastructureException("Delete from consents failed: connection refused"));

        JobResult result = runOneStudy(Map.of(SSTR_NAME, SSTR, SUBJECT_MULTI_NAME, SUBJECT_MULTI),
                "1001,FHS,9001\n");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(result.getErrorMessage()).contains("sstr job failed");
        assertThat(store.participantIds).isEmpty();
    }

    /**
     * A database that can't open a transaction fails the sub-job as UNKNOWN, not INFRASTRUCTURE_ERROR.
     * That is not a malformed SSTR: the run must stop instead of falling back to MULTI and then
     * recording every study as failed for the wrong reason.
     */
    @Test
    void a_non_data_failure_in_the_sstr_job_aborts_the_run_instead_of_falling_back() {
        when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenThrow(new CannotCreateTransactionException("Could not open JDBC Connection"));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("phs000001/rawData/sstr_phs000001.v1.txt", SSTR);
        files.put("phs000001/rawData/phs000001.v1.pht1.p1.A_Subject.MULTI.txt", SUBJECT_MULTI);
        files.put("phs000001/legacy/data/A_PatientMapping.v2.csv", "1001,A,9001\n");
        files.put("phs000002/rawData/sstr_phs000002.v1.txt", SSTR);
        files.put("phs000002/legacy/data/B_PatientMapping.v2.csv", "1001,B,9001\n");

        JobResult result = run(files, "A,phs000001,Yes", "B,phs000002,Yes");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.UNKNOWN);
        assertThat(result.getErrorMessage())
                .contains("sstr job failed for study phs000001 (UNKNOWN)")
                .contains("Could not open JDBC Connection")
                .contains("aborting the run");
        // Stopped at the first study: no MULTI attempt, no second study.
        assertThat(reportsDir.resolve("multi-populate-rds-participants-unit-phs000001-multi.json")).doesNotExist();
        assertThat(reportsDir.resolve("sstr-populate-rds-participants-unit-phs000002-sstr.json")).doesNotExist();
    }

    @Test
    void a_non_data_failure_in_the_multi_job_aborts_the_run() {
        when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenThrow(new CannotCreateTransactionException("Could not open JDBC Connection"));

        JobResult result = runOneStudy(Map.of(SUBJECT_MULTI_NAME, SUBJECT_MULTI), "1001,FHS,9001\n");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.UNKNOWN);
        assertThat(result.getErrorMessage()).contains("multi job failed for study " + STUDY + " (UNKNOWN)")
                .contains("aborting the run");
    }

    /**
     * The SSTR job rejects a non-phs###### id; that must not let the MULTI job purge and rewrite
     * consents under the id the SSTR route refused.
     */
    @Test
    void a_study_id_both_sub_jobs_reject_fails_without_touching_consents() {
        String badId = "phs000007.v33";
        Map<String, String> files = new LinkedHashMap<>();
        files.put(badId + "/rawData/sstr_" + badId + ".txt", SSTR);
        files.put(badId + "/rawData/" + badId + ".pht000182.v14.p14.FHS_Subject.MULTI.txt", SUBJECT_MULTI);
        files.put(badId + "/legacy/data/FHS_PatientMapping.v2.csv", "1001,FHS,9001\n");

        JobResult result = run(files, "FHS," + badId + ",Yes");

        assertThat(result.getMetrics()).containsEntry("failedStudies", 1L);
        assertThat(result.getOutputValidation().getIssues())
                .filteredOn(i -> i.code().equals("STUDY_FAILED"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.message()).contains("study-id must match phs######");
                    assertThat(i.context()).containsEntry("reason", "MULTI_LOAD_FAILED");
                });
        assertThat(store.purgedStudies).isEmpty();
        assertThat(store.consentRows).isEmpty();
    }

    // ---- source selection ----------------------------------------------------

    @Test
    void sstr_is_used_when_present_and_valid_even_if_multi_files_exist() {
        JobResult result = runOneStudy(Map.of(
                SSTR_NAME, SSTR,
                SUBJECT_MULTI_NAME, SUBJECT_MULTI,
                SAMPLE_MULTI_NAME, SAMPLE_MULTI), "1001,FHS,9001\n");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("sstrStudyIds", List.of(STUDY))
                .containsEntry("multiStudyIds", List.of());
        // Only the SSTR's one subject; the MULTI files' 1002 would be here had they been loaded.
        assertThat(store.consentsFor(STUDY)).containsExactly(
                new Consent(store.hpdsId("DBGap", "1001"), STUDY, "1", "HMB-IRB-MDS"));
        assertThat(Files.exists(reportsDir.resolve("multi-populate-rds-participants-unit-" + STUDY + "-multi.json")))
                .isFalse();
        assertThat(sourcesRow(STUDY))
                .containsEntry("source", "sstr")
                .containsEntry("sstr_status", "used")
                .containsEntry("multi_status", "not attempted")
                // Still recorded, so the report shows what was available.
                .containsEntry("subject_multi_file", SUBJECT_MULTI_NAME);
        assertThat(report(ParticipantsMigrationJob.SUMMARY_REPORT)).contains("| Loaded from SSTR | 1 |");
    }

    @Test
    void falls_back_to_multi_when_there_is_no_sstr() throws IOException {
        JobResult result = runOneStudy(Map.of(
                SUBJECT_MULTI_NAME, SUBJECT_MULTI,
                SAMPLE_MULTI_NAME, SAMPLE_MULTI,
                DATA_DICT_NAME, DbgapMultiFileReaderTest.DATA_DICT), "1001,FHS,9001\nS2,FHS,9002\n");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("succeededStudies", 1L)
                .containsEntry("multiStudyIds", List.of(STUDY))
                .containsEntry("sstrFallbackStudyIds", List.of());

        long p1 = store.hpdsId("DBGap", "1001");
        long p2 = store.hpdsId("DBGap", "1002");
        // Consent 0 (1003) is dropped; abbreviations decoded from the data dictionary.
        assertThat(store.consentsFor(STUDY)).containsExactly(
                new Consent(p1, STUDY, "1", "HMB-IRB-MDS"),
                new Consent(p2, STUDY, "2", "DS-CVD-IRB-NPU-MDS"));
        assertThat(store.samplesFrom("DBGap")).containsExactly(new Sample(p1, "5001", "DBGap"), new Sample(p2, "5002", "DBGap"));
        assertThat(store.samplesFrom("submitted")).containsExactly(
                new Sample(p1, "NWD1", "submitted"), new Sample(p2, "NWD2", "submitted"));
        assertThat(store.participantIds).doesNotContainKey("DBGap|1003");

        // Patient mapping ids resolve by dbGaP id (1001) or by the study's own SUBJECT_ID (S2).
        assertThat(Files.readString(reportsDir.resolve(STUDY + "_hpds_id_mapping.csv"))).isEqualTo(
                "old_hpds_id,new_hpds_id,common_dbgap_id\n9001," + p1 + ",1001\n9002," + p2 + ",1002\n");

        assertThat(sourcesRow(STUDY))
                .containsEntry("outcome", "success")
                .containsEntry("source", "multi")
                .containsEntry("sstr_status", "absent")
                .containsEntry("subject_multi_file", SUBJECT_MULTI_NAME)
                .containsEntry("sample_multi_file", SAMPLE_MULTI_NAME)
                .containsEntry("data_dict_file", DATA_DICT_NAME)
                .containsEntry("multi_status", "used")
                .containsEntry("consent_column", "CONSENT")
                .containsEntry("mapping_rows", "2");
        assertThat(issueCodes(STUDY)).contains("CONSENT_ZERO_DROPPED", "SAMPLES_OF_UNCONSENTED_SUBJECTS");
        assertThat(report(ParticipantsMigrationJob.SUMMARY_REPORT))
                .contains("| Loaded from MULTI (no SSTR) | 1 |")
                .contains("## Loaded from MULTI (no SSTR)");
        assertThat(reportsDir.resolve("multi-populate-rds-participants-unit-" + STUDY + "-multi.json")).exists();
    }

    @Test
    void falls_back_to_multi_when_the_sstr_is_malformed() {
        JobResult result = runOneStudy(Map.of(
                SSTR_NAME, MALFORMED_SSTR,
                SUBJECT_MULTI_NAME, SUBJECT_MULTI,
                DATA_DICT_NAME, DbgapMultiFileReaderTest.DATA_DICT), "1001,FHS,9001\n");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getMetrics())
                .containsEntry("succeededStudies", 1L)
                .containsEntry("multiStudyIds", List.of(STUDY))
                .containsEntry("sstrFallbackStudyIds", List.of(STUDY));
        assertThat(result.getOutputValidation().getIssues())
                .filteredOn(i -> i.code().equals("SSTR_FALLBACK_TO_MULTI"))
                .singleElement()
                .satisfies(i -> assertThat(i.message()).contains(SSTR_NAME).contains("CONSENT"));
        assertThat(store.consentsFor(STUDY)).hasSize(2);

        assertThat(sourcesRow(STUDY))
                .containsEntry("source", "multi")
                .containsEntry("sstr_file", SSTR_NAME)
                .containsEntry("sstr_status", "malformed");
        assertThat(issueCodes(STUDY)).contains("SSTR_MALFORMED", "NO_SAMPLE_MULTI");
        assertThat(report(ParticipantsMigrationJob.SUMMARY_REPORT)).contains("| Loaded from MULTI (SSTR malformed) | 1 |");
    }

    @Test
    void fails_a_study_with_neither_sstr_nor_subject_multi() {
        JobResult result = runOneStudy(Map.of(SAMPLE_MULTI_NAME, SAMPLE_MULTI), "1001,FHS,9001\n");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getMetrics())
                .containsEntry("failedStudies", 1L)
                .containsEntry("noParticipantSourceStudyIds", List.of(STUDY));
        assertThat(result.getOutputValidation().getIssues())
                .filteredOn(i -> i.code().equals("STUDY_FAILED"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.message()).contains("no SSTR file and no Subject.MULTI file");
                    assertThat(i.context()).containsEntry("reason", "NO_PARTICIPANT_SOURCE");
                });
        assertThat(store.participantIds).isEmpty();
        assertThat(reportsDir.resolve(STUDY + "_hpds_id_mapping.csv")).doesNotExist();
        assertThat(sourcesRow(STUDY)).containsEntry("outcome", "failed").containsEntry("source", "none");
        assertThat(issueCodes(STUDY)).contains("NO_PARTICIPANT_SOURCE");
        assertThat(report(ParticipantsMigrationJob.SUMMARY_REPORT))
                .contains("| Failed: no usable participant source | 1 |");
    }

    @Test
    void fails_a_study_whose_sstr_is_malformed_and_has_no_multi() {
        JobResult result = runOneStudy(Map.of(SSTR_NAME, MALFORMED_SSTR), "1001,FHS,9001\n");

        assertThat(result.getMetrics())
                .containsEntry("failedStudies", 1L)
                .containsEntry("noParticipantSourceStudyIds", List.of(STUDY))
                .containsEntry("sstrFallbackStudyIds", List.of(STUDY));
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("STUDY_FAILED") && i.message().contains("is malformed")
                        && i.message().contains("no Subject.MULTI file to fall back to"));
        assertThat(sourcesRow(STUDY)).containsEntry("sstr_status", "malformed").containsEntry("outcome", "failed");
        assertThat(issueCodes(STUDY)).containsExactly("SSTR_MALFORMED", "NO_PARTICIPANT_SOURCE");
    }

    @Test
    void fails_a_study_whose_subject_multi_is_malformed() {
        String noConsent = "dbGaP_Subject_ID\tSUBJECT_ID\n1001\tS1\n";

        JobResult result = runOneStudy(Map.of(SUBJECT_MULTI_NAME, noConsent), "1001,FHS,9001\n");

        assertThat(result.getMetrics()).containsEntry("failedStudies", 1L);
        assertThat(result.getOutputValidation().getIssues())
                .filteredOn(i -> i.code().equals("STUDY_FAILED"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.message()).contains("multi job failed").contains("no consent column");
                    assertThat(i.context()).containsEntry("reason", "MULTI_LOAD_FAILED");
                });
        assertThat(store.purgedStudies).isEmpty();
        assertThat(sourcesRow(STUDY)).containsEntry("multi_status", "failed");
        assertThat(issueCodes(STUDY)).contains("MULTI_LOAD_FAILED");
        assertThat(report(ParticipantsMigrationJob.SUMMARY_REPORT)).contains("| Failed: MULTI files unusable | 1 |");
    }

    /** Both sources unusable: the failure names both problems. */
    @Test
    void reports_both_errors_when_the_sstr_and_the_multi_are_malformed() {
        JobResult result = runOneStudy(Map.of(
                SSTR_NAME, MALFORMED_SSTR,
                SUBJECT_MULTI_NAME, "dbGaP_Subject_ID\tCONSENT\n1001\t0\n"), "1001,FHS,9001\n");

        assertThat(result.getOutputValidation().getIssues())
                .filteredOn(i -> i.code().equals("STUDY_FAILED"))
                .singleElement()
                .satisfies(i -> assertThat(i.message())
                        .contains("no subject has a usable consent")
                        .contains("SSTR also unusable"));
    }

    // ---- MULTI details -------------------------------------------------------

    @Test
    void multi_without_a_sample_file_loads_no_samples() {
        JobResult result = runOneStudy(Map.of(
                SUBJECT_MULTI_NAME, SUBJECT_MULTI,
                DATA_DICT_NAME, DbgapMultiFileReaderTest.DATA_DICT), "1001,FHS,9001\n");

        assertThat(result.isSuccess()).isTrue();
        assertThat(store.sampleRows).isEmpty();
        assertThat(sourcesRow(STUDY)).containsEntry("sample_multi_file", "");
        assertThat(issueCodes(STUDY)).contains("NO_SAMPLE_MULTI");
    }

    @Test
    void multi_without_a_data_dict_leaves_abbreviations_blank_and_warns() {
        JobResult result = runOneStudy(Map.of(SUBJECT_MULTI_NAME, SUBJECT_MULTI), "1001,FHS,9001\n");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(store.consentsFor(STUDY)).extracting(Consent::consentAbbreviation).containsOnly("");
        assertThat(issueCodes(STUDY)).contains("NO_DATA_DICT");
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("SOURCE_FILE_ISSUES") && i.message().contains(ParticipantsMigrationJob.ISSUES_REPORT));
    }

    @Test
    void reports_codes_the_data_dict_does_not_decode() {
        String subject = "dbGaP_Subject_ID\tCONSENT\n1001\t1\n1002\t7\n";

        runOneStudy(Map.of(SUBJECT_MULTI_NAME, subject, DATA_DICT_NAME, DbgapMultiFileReaderTest.DATA_DICT),
                "1001,FHS,9001\n");

        assertThat(store.consentsFor(STUDY)).extracting(Consent::consentCode, Consent::consentAbbreviation)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1", "HMB-IRB-MDS"),
                        org.assertj.core.groups.Tuple.tuple("7", ""));
        assertThat(issueCodes(STUDY)).contains("CONSENT_CODE_NOT_DECODED");
    }

    @Test
    void uses_the_full_label_when_it_has_no_parenthesized_abbreviation() {
        String subject = "dbGaP_Subject_ID\tCONSENT\n1001\t3\n";

        runOneStudy(Map.of(SUBJECT_MULTI_NAME, subject, DATA_DICT_NAME, DbgapMultiFileReaderTest.DATA_DICT),
                "1001,FHS,9001\n");

        assertThat(store.consentsFor(STUDY)).extracting(Consent::consentAbbreviation)
                .containsExactly("General Research Use");
        assertThat(issueCodes(STUDY)).contains("CONSENT_LABEL_WITHOUT_ABBREVIATION");
    }

    @Test
    void an_unreadable_data_dict_leaves_abbreviations_blank() {
        runOneStudy(Map.of(SUBJECT_MULTI_NAME, SUBJECT_MULTI, DATA_DICT_NAME, "<not-xml"), "1001,FHS,9001\n");

        assertThat(store.consentsFor(STUDY)).extracting(Consent::consentAbbreviation).containsOnly("");
        assertThat(issueCodes(STUDY)).contains("DATA_DICT_UNREADABLE");
    }

    @Test
    void a_malformed_sample_file_skips_samples_but_loads_subjects() {
        JobResult result = runOneStudy(Map.of(
                SUBJECT_MULTI_NAME, SUBJECT_MULTI,
                SAMPLE_MULTI_NAME, "dbGaP_Subject_ID\tSAMPLE_ID\n1001\tNWD1\n"), "1001,FHS,9001\n");

        assertThat(result.isSuccess()).isTrue();
        assertThat(store.consentsFor(STUDY)).hasSize(2);
        assertThat(store.sampleRows).isEmpty();
        assertThat(issueCodes(STUDY)).contains("SAMPLE_MULTI_UNREADABLE");
    }

    @Test
    void reports_samples_of_subjects_missing_from_the_subject_file() {
        String samples = "dbGaP_Subject_ID\tdbGaP_Sample_ID\n1001\t5001\n4040\t5040\n";

        runOneStudy(Map.of(SUBJECT_MULTI_NAME, SUBJECT_MULTI, SAMPLE_MULTI_NAME, samples), "1001,FHS,9001\n");

        assertThat(store.samplesFrom("DBGap")).extracting(Sample::sourceSampleId).containsExactly("5001");
        assertThat(issueCodes(STUDY)).contains("SAMPLES_OF_UNKNOWN_SUBJECTS");
    }

    @Test
    void patient_mapping_rows_for_consent_zero_subjects_are_reported_not_mapped() {
        // 1003 by its dbGaP id, S3 (also subject 1003) by the study's own SUBJECT_ID.
        JobResult result = runOneStudy(Map.of(SUBJECT_MULTI_NAME, SUBJECT_MULTI),
                "1001,FHS,9001\n1003,FHS,9003\nS3,FHS,9033\nNOPE,FHS,9999\n");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getMetrics()).containsEntry("subjectsWithoutConsent", 3L);
        assertThat(result.getOutputValidation().getIssues())
                .filteredOn(i -> i.code().equals("SUBJECTS_WITHOUT_CONSENT"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.message()).contains("could not be resolved via the multi file");
                    assertThat(i.context()).containsEntry("path", "multi")
                            .containsEntry("firstOldHpdsIds", List.of("9003", "9033", "9999"));
                });
        assertThat(issueRow(STUDY, "PATIENT_MAPPING_NOT_CONSENTED"))
                .containsEntry("severity", "INFO").containsEntry("count", "2").containsEntry("examples", "9003 9033");
        assertThat(issueRow(STUDY, "PATIENT_MAPPING_UNRESOLVED"))
                .containsEntry("severity", "WARNING").containsEntry("count", "1").containsEntry("examples", "9999");
        assertThat(sourcesRow(STUDY)).containsEntry("unresolved_mapping_rows", "3");
    }

    @Test
    void uses_the_newest_subject_multi_and_reports_the_others() {
        String older = "phs000007.v9.pht000182.v5.p9.FHS_Subject.MULTI.txt";
        String oldSubject = "dbGaP_Subject_ID\tCONSENT\n8888\t1\n";

        runOneStudy(Map.of(older, oldSubject, SUBJECT_MULTI_NAME, SUBJECT_MULTI), "1001,FHS,9001\n");

        assertThat(store.participantIds).containsKey("DBGap|1001").doesNotContainKey("DBGap|8888");
        assertThat(sourcesRow(STUDY)).containsEntry("subject_multi_file", SUBJECT_MULTI_NAME);
        assertThat(issueCodes(STUDY)).contains("MULTIPLE_SUBJECT_MULTI_CANDIDATES");
    }

    @Test
    void isolates_a_failing_study_and_reports_every_study_s_source() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("phs000001/rawData/sstr_phs000001.v1.txt", SSTR);
        files.put("phs000001/legacy/data/A_PatientMapping.v2.csv", "1001,A,9001\n");
        files.put("phs000002/rawData/phs000002.v1.pht1.p1.B_Subject.MULTI.txt", "dbGaP_Subject_ID\tCONSENT\n2001\t1\n");
        files.put("phs000002/legacy/data/B_PatientMapping.v2.csv", "2001,B,9002\n");
        files.put("phs000003/legacy/data/C_PatientMapping.v2.csv", "3001,C,9003\n");

        JobResult result = run(files, "A,phs000001,Yes", "B,phs000002,Yes", "C,phs000003,Yes", "D,phs000004,No");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getMetrics())
                .containsEntry("readyStudies", 3L)
                .containsEntry("succeededStudies", 2L)
                .containsEntry("sstrStudyIds", List.of("phs000001"))
                .containsEntry("multiStudyIds", List.of("phs000002"))
                .containsEntry("failedStudyIds", List.of("phs000003"))
                .containsEntry("noParticipantSourceStudyIds", List.of("phs000003"));
        assertThat(sourcesRow("phs000001")).containsEntry("source", "sstr");
        assertThat(sourcesRow("phs000002")).containsEntry("source", "multi");
        assertThat(sourcesRow("phs000003")).containsEntry("source", "none").containsEntry("outcome", "failed");
        assertThat(report(ParticipantsMigrationJob.SOURCES_REPORT)).doesNotContain("phs000004");
    }

    // ---- file discovery ------------------------------------------------------

    @Test
    void sstr_discovery_accepts_all_three_naming_families_case_insensitively() {
        assertThat(ParticipantsMigrationJob.isSstrFileFor("sstr_phs000200.v13.txt", "phs000200")).isTrue();
        assertThat(ParticipantsMigrationJob.isSstrFileFor("SSTR__sstr_phs000281.v8.txt", "phs000281")).isTrue();
        assertThat(ParticipantsMigrationJob.isSstrFileFor("BDC-ingestion-only__sstr_phs000675.v4.txt", "phs000675")).isTrue();
        assertThat(ParticipantsMigrationJob.isSstrFileFor("SSTR_phs000557.v7.txt", "phs000557")).isTrue();
        // wrong study, wrong extension, unrelated names
        assertThat(ParticipantsMigrationJob.isSstrFileFor("sstr_phs000200.v13.txt", "phs000999")).isFalse();
        assertThat(ParticipantsMigrationJob.isSstrFileFor("sstr_phs000200.v13.csv", "phs000200")).isFalse();
        assertThat(ParticipantsMigrationJob.isSstrFileFor("notes_phs000200.txt", "phs000200")).isFalse();
    }

    @Test
    void canonical_sstr_name_is_preferred_over_flattened_copies() {
        assertThat(ParticipantsMigrationJob.isCanonicalSstrName("sstr_phs000557.v8.txt")).isTrue();
        assertThat(ParticipantsMigrationJob.isCanonicalSstrName("SSTR__phs000557.v7.txt")).isFalse();
        assertThat(ParticipantsMigrationJob.isCanonicalSstrName("BDC-ingestion-only__sstr_phs000675.v4.txt")).isFalse();
    }

    @Test
    void multi_discovery_matches_the_study_prefix_and_suffix_case_insensitively() {
        assertThat(ParticipantsMigrationJob.isSubjectMultiFileFor(SUBJECT_MULTI_NAME, "phs000007")).isTrue();
        assertThat(ParticipantsMigrationJob.isSubjectMultiFileFor("PHS000007.v1.Subject.multi.TXT", "phs000007")).isTrue();
        assertThat(ParticipantsMigrationJob.isSubjectMultiFileFor(SUBJECT_MULTI_NAME, "PHS000007")).isTrue();
        assertThat(ParticipantsMigrationJob.isSubjectMultiFileFor(SAMPLE_MULTI_NAME, "phs000007")).isFalse();
        assertThat(ParticipantsMigrationJob.isSubjectMultiFileFor(SUBJECT_MULTI_NAME, "phs000008")).isFalse();
        assertThat(ParticipantsMigrationJob.isSubjectMultiFileFor("x_" + SUBJECT_MULTI_NAME, "phs000007")).isFalse();
        assertThat(ParticipantsMigrationJob.isSubjectMultiFileFor(SUBJECT_MULTI_NAME + ".gz", "phs000007")).isFalse();

        assertThat(ParticipantsMigrationJob.isSampleMultiFileFor(SAMPLE_MULTI_NAME, "phs000007")).isTrue();
        assertThat(ParticipantsMigrationJob.isSampleMultiFileFor(SUBJECT_MULTI_NAME, "phs000007")).isFalse();
        assertThat(ParticipantsMigrationJob.isSampleMultiFileFor(
                "phs000007.v33.pht001416.v1.p14.FHS_Sample_Attributes.MULTI.txt", "phs000007")).isFalse();

        assertThat(ParticipantsMigrationJob.isSubjectDataDictFor(DATA_DICT_NAME, "phs000007")).isTrue();
        assertThat(ParticipantsMigrationJob.isSubjectDataDictFor(
                "phs000007.v33.pht001415.v1.FHS_Sample.data_dict.xml", "phs000007")).isFalse();
    }

    @Test
    void newest_release_sorts_first_by_numeric_version() {
        List<String> names = List.of(
                "phs000007.v9.pht000182.v5.p9.FHS_Subject.MULTI.txt",
                "phs000007.v33.pht000182.v14.p14.FHS_Subject.MULTI.txt",
                "phs000007.v10.pht000182.v6.p10.FHS_Subject.MULTI.txt");

        assertThat(ParticipantsMigrationJob.newestFirst(names, n -> true)).containsExactly(
                "phs000007.v33.pht000182.v14.p14.FHS_Subject.MULTI.txt",
                "phs000007.v10.pht000182.v6.p10.FHS_Subject.MULTI.txt",
                "phs000007.v9.pht000182.v5.p9.FHS_Subject.MULTI.txt");
    }

    @Test
    void data_dict_is_paired_with_the_subject_multi_by_table_accession() {
        List<String> names = List.of(
                "phs000007.v33.pht000182.v14.FHS_Subject.data_dict.xml",
                "phs000007.v33.pht009999.v1.Other_Subject.data_dict.xml");

        assertThat(ParticipantsMigrationJob.dataDictCandidates(names, "phs000007", SUBJECT_MULTI_NAME))
                .containsExactly("phs000007.v33.pht000182.v14.FHS_Subject.data_dict.xml");
        // No pairing possible: every dictionary is a candidate, newest first.
        assertThat(ParticipantsMigrationJob.dataDictCandidates(names, "phs000007", null)).hasSize(2);
    }
}
