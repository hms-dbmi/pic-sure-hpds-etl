package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ParticipantsMigrationJob} against a real Postgres.
 * Covers the sstr-driven path (delegating to {@link
 * edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SstrPopulateRdsParticipantsJob}),
 * the MULTI fallback (delegating to {@link MultiPopulateRdsParticipantsJob}) both without an SSTR
 * and after a malformed one is rolled back, a study with no source, skipping not-ready studies,
 * and per-study failure isolation.
 *
 * <p>The finer source-selection and reporting cases, and failures that don't need a live
 * database, are covered by {@link ParticipantsMigrationJobTest} instead.
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
    @Autowired
    private EtlProperties properties;

    private Path reportsDir;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE participants, consents, samples");
        reportsDir = Path.of(properties.getReports().getDir());
    }

    @AfterEach
    void cleanUpReportFiles() throws IOException {
        if (Files.isDirectory(reportsDir)) {
            try (var files = Files.list(reportsDir)) {
                for (Path p : files.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith("_hpds_id_mapping.csv") || name.startsWith("participants_migration_");
                }).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private String managedInputs(String... rows) {
        StringBuilder csv = new StringBuilder("Study Abbreviated Name,Study Identifier,Data is ready to process\n");
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        String path = writeFile("managed_inputs", csv.toString());
        properties.getManagedInputs().setUri(path);
        return path;
    }

    /**
     * Creates a base URI directory with the subfolder convention:
     * {@code {studyId}/legacy/data/} for patient mapping, {@code {studyId}/rawData/} for SSTR and
     * MULTI files. Keys are relative paths from the base.
     */
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

    private static String writeFile(String namePrefix, String content) {
        try {
            Path file = Files.createTempDirectory(namePrefix).resolve(namePrefix + ".csv");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long countWhere(String table, String where, Object... args) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class, args);
        return n == null ? 0 : n;
    }

    private long hpdsId(String dbgapId) {
        return jdbc.queryForObject("SELECT hpds_id FROM participants WHERE source_id = ? AND source = 'DBGap'",
                Long.class, dbgapId);
    }

    private String readMappingFile(String studyId) throws IOException {
        return Files.readString(reportsDir.resolve(studyId + "_hpds_id_mapping.csv"));
    }

    @Test
    void sstr_driven_study_populates_rds_and_writes_mapping_file() throws IOException {
        managedInputs("GRU,phs001412,Yes");
        String base = baseUri(Map.of(
                "phs001412/rawData/SSTR_dbGaP_phs001412.txt", """
                SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id\tdbgap_sample_id
                SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1
                """,
                "phs001412/legacy/data/GRU_PatientMapping.v2.csv", "phs001412.v1.p1.c1,GRU,1001\n"));

        JobResult result = executor.run(job,
                Map.of("data-folder", base), "it-sstr");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("succeededStudies", 1L).containsEntry("failedStudies", 0L);
        assertThat(participants.count()).isEqualTo(1);
        long newHpdsId = jdbc.queryForObject(
                "SELECT hpds_id FROM participants WHERE source_id = ? AND source = ?",
                Long.class, "phs001412.v1.p1.c1", "DBGap");
        assertThat(countWhere("consents", "study_id = ? AND consent_code = '1' AND consent_abbreviation = 'GRU'",
                "phs001412")).isEqualTo(1);

        String mapping = readMappingFile("phs001412");
        assertThat(mapping).isEqualTo("old_hpds_id,new_hpds_id,common_dbgap_id\n1001," + newHpdsId + ",phs001412.v1.p1.c1\n");
    }

    /**
     * On the sstr path a subject is lost because its patient-mapping id is not in the sstr file; the
     * warning must say so.
     */
    @Test
    void sstr_study_reports_ids_missing_from_the_sstr_file_as_such() throws IOException {
        managedInputs("GRU,phs001412,Yes");
        String base = baseUri(Map.of(
                "phs001412/rawData/sstr_phs001412.v1.txt", """
                SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id\tdbgap_sample_id
                SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1
                """,
                "phs001412/legacy/data/GRU_PatientMapping.v2.csv",
                "phs001412.v1.p1.c1,GRU,1001\n" + "NOT_IN_SSTR,GRU,1002\n"));

        JobResult result = executor.run(job,
                Map.of("data-folder", base), "it-sstr-unresolved");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getMetrics()).containsEntry("subjectsWithoutConsent", 1L);
        assertThat(result.getOutputValidation().getIssues())
                .filteredOn(i -> i.code().equals("SUBJECTS_WITHOUT_CONSENT"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.message()).contains("could not be resolved via the sstr file");
                    assertThat(i.context())
                            .containsEntry("studyId", "phs001412")
                            .containsEntry("path", "sstr")
                            .containsEntry("count", 1L)
                            .containsEntry("firstOldHpdsIds", List.of("1002"));
                });
    }

    @Test
    void study_without_sstr_is_loaded_from_its_multi_files() throws IOException {
        managedInputs("FHS,phs000007,Yes");
        String base = baseUri(Map.of(
                "phs000007/rawData/" + ParticipantsMigrationJobTest.SUBJECT_MULTI_NAME, ParticipantsMigrationJobTest.SUBJECT_MULTI,
                "phs000007/rawData/" + ParticipantsMigrationJobTest.SAMPLE_MULTI_NAME, ParticipantsMigrationJobTest.SAMPLE_MULTI,
                "phs000007/rawData/" + ParticipantsMigrationJobTest.DATA_DICT_NAME, DbgapMultiFileReaderTest.DATA_DICT,
                "phs000007/legacy/data/FHS_PatientMapping.v2.csv", "1001,FHS,9001\nS2,FHS,9002\n"));

        JobResult result = executor.run(job, Map.of("data-folder", base), "it-multi");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("multiStudyIds", List.of("phs000007"));
        assertThat(participants.count()).isEqualTo(2);
        long p1 = hpdsId("1001");
        long p2 = hpdsId("1002");
        assertThat(countWhere("consents", "study_id = ? AND consent_code = '1' AND consent_abbreviation = 'HMB-IRB-MDS'",
                "phs000007")).isEqualTo(1);
        assertThat(countWhere("consents", "study_id = ? AND consent_code = '2' AND consent_abbreviation = 'DS-CVD-IRB-NPU-MDS'",
                "phs000007")).isEqualTo(1);
        assertThat(countWhere("samples", "sample_source = 'DBGap'")).isEqualTo(2);
        assertThat(countWhere("samples", "sample_source = 'submitted' AND source_sample_id IN ('NWD1', 'NWD2')"))
                .isEqualTo(2);
        assertThat(readMappingFile("phs000007")).isEqualTo("old_hpds_id,new_hpds_id,common_dbgap_id\n"
                + "9001," + p1 + ",1001\n9002," + p2 + ",1002\n");
        assertThat(Files.readString(reportsDir.resolve(ParticipantsMigrationJob.SOURCES_REPORT)))
                .contains("phs000007,FHS,success,multi,,absent," + ParticipantsMigrationJobTest.SUBJECT_MULTI_NAME);
    }

    /**
     * The SSTR fails on its second row, after its job has purged consents and inserted rows. Its
     * transaction must roll all of that back, so only the MULTI load remains: the SSTR's
     * 'FROM-SSTR' abbreviation must not survive.
     */
    @Test
    void a_malformed_sstr_is_rolled_back_and_the_study_loaded_from_multi() {
        managedInputs("FHS,phs000007,Yes");
        String base = baseUri(Map.of(
                "phs000007/rawData/sstr_phs000007.v33.txt", """
                SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id\tdbgap_sample_id
                S9\tNWD9\t1\tFROM-SSTR\t9999\t5999
                S1\tNWD1\t\tFROM-SSTR\t1001\t5001
                """,
                "phs000007/rawData/" + ParticipantsMigrationJobTest.SUBJECT_MULTI_NAME, ParticipantsMigrationJobTest.SUBJECT_MULTI,
                "phs000007/rawData/" + ParticipantsMigrationJobTest.DATA_DICT_NAME, DbgapMultiFileReaderTest.DATA_DICT,
                "phs000007/legacy/data/FHS_PatientMapping.v2.csv", "1001,FHS,9001\n"));

        JobResult result = executor.run(job, Map.of("data-folder", base), "it-sstr-fallback");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getMetrics())
                .containsEntry("multiStudyIds", List.of("phs000007"))
                .containsEntry("sstrFallbackStudyIds", List.of("phs000007"));
        assertThat(result.getOutputValidation().getIssues()).anyMatch(i -> i.code().equals("SSTR_FALLBACK_TO_MULTI"));
        assertThat(countWhere("consents", "consent_abbreviation = 'FROM-SSTR'")).isZero();
        assertThat(countWhere("participants", "source_id = '9999'")).isZero();
        assertThat(countWhere("samples", "source_sample_id = 'NWD9'")).isZero();
        assertThat(countWhere("consents", "study_id = ?", "phs000007")).isEqualTo(2);
    }

    @Test
    void study_with_neither_sstr_nor_multi_fails_and_is_reported() throws IOException {
        managedInputs("FHS,phs000007,Yes");
        String base = baseUri(Map.of("phs000007/legacy/data/FHS_PatientMapping.v2.csv", "1001,FHS,9001\n"));

        JobResult result = executor.run(job, Map.of("data-folder", base), "it-no-source");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getMetrics()).containsEntry("noParticipantSourceStudyIds", List.of("phs000007"));
        assertThat(participants.count()).isZero();
        assertThat(Files.readString(reportsDir.resolve(ParticipantsMigrationJob.ISSUES_REPORT)))
                .contains("phs000007,ERROR,NO_PARTICIPANT_SOURCE");
    }

    @Test
    void study_not_marked_ready_is_skipped_entirely() {
        managedInputs(
                "FHS,phs000007,Yes",
                "NOTSET,phs000008,No");
        String base = baseUri(Map.of(
                "phs000007/rawData/" + ParticipantsMigrationJobTest.SUBJECT_MULTI_NAME, ParticipantsMigrationJobTest.SUBJECT_MULTI,
                "phs000007/rawData/" + ParticipantsMigrationJobTest.DATA_DICT_NAME, DbgapMultiFileReaderTest.DATA_DICT,
                "phs000007/legacy/data/FHS_PatientMapping.v2.csv", "1001,FHS,9001\n"));

        JobResult result = executor.run(job,
                Map.of("data-folder", base), "it-not-ready");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("readyStudies", 1L);
    }

    @Test
    void isolates_a_failing_study_so_others_still_succeed() {
        managedInputs(
                "FHS,phs000007,Yes",
                "MISSING,phs000009,Yes");
        String base = baseUri(Map.of(
                "phs000007/rawData/" + ParticipantsMigrationJobTest.SUBJECT_MULTI_NAME, ParticipantsMigrationJobTest.SUBJECT_MULTI,
                "phs000007/rawData/" + ParticipantsMigrationJobTest.DATA_DICT_NAME, DbgapMultiFileReaderTest.DATA_DICT,
                "phs000007/legacy/data/FHS_PatientMapping.v2.csv", "1001,FHS,9001\n"));

        JobResult result = executor.run(job,
                Map.of("data-folder", base), "it-isolation");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getMetrics()).containsEntry("succeededStudies", 1L).containsEntry("failedStudies", 1L);
        assertThat(countWhere("participants", "source_id = ? AND source = ?", "1001", "DBGap")).isEqualTo(1);
        assertThat(Files.exists(reportsDir.resolve("phs000009_hpds_id_mapping.csv"))).isFalse();
    }
}
