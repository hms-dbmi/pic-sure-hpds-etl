package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.ParticipantRepository;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ParticipantsMigrationJob} against a real Postgres.
 * Covers the sstr-driven path (delegating to {@link
 * edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SstrPopulateRdsParticipantsJob}),
 * the direct-population path (patient-mapping/consents.csv join), the
 * open_access-1000Genomes samples exception, skipping not-ready studies, and per-study
 * failure isolation.
 *
 * <p>Failure modes that don't need a live database (managed-inputs/consents.csv missing,
 * the database being unreachable) are covered by {@link ParticipantsMigrationJobTest}
 * instead.
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
    void cleanUpMappingFiles() throws IOException {
        if (Files.isDirectory(reportsDir)) {
            try (var files = Files.list(reportsDir)) {
                for (Path p : files.filter(p -> p.getFileName().toString().endsWith("_hpds_id_mapping.csv")).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static String managedInputs(String... rows) {
        StringBuilder csv = new StringBuilder("Study Abbreviated Name,Study Identifier,Data is ready to process\n");
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        return writeFile("managed_inputs", csv.toString());
    }

    /** Writes each entry as a file into one shared temp directory and returns its path. */
    private static String dataFolder(Map<String, String> filesByName) {
        try {
            Path dir = Files.createTempDirectory("migration-data");
            for (Map.Entry<String, String> entry : filesByName.entrySet()) {
                Files.writeString(dir.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
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

    private String readMappingFile(String studyId) throws IOException {
        return Files.readString(reportsDir.resolve(studyId + "_hpds_id_mapping.csv"));
    }

    /**
     * An open-access study's consent value is the bare study id -- no {@code .c<code>} suffix,
     * because it has no consent group. Those rows used to fail the strict {@code ^.+\.c(\w+)$}
     * match and be skipped, which cost the study every consent row AND every mapping row: it
     * migrated as empty while the run reported success.
     */
    @Test
    void open_access_study_with_no_consent_suffix_migrates_as_public() throws IOException {
        String managedInputsPath = managedInputs("OPENSTUDY,open-study-01,true");
        String dataFolder = dataFolder(Map.of(
                // No ".c<code>" suffix: this is what an open-access study looks like.
                "consents.csv", "\"3001\",\"open-study-01\"\n",
                "OPENSTUDY_PatientMapping.v2.csv", "SUBJ1,OPENSTUDY,3001\n"));

        JobResult result = executor.run(job,
                Map.of("managed-inputs", managedInputsPath, "data-folder", dataFolder), "it-open-access");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("succeededStudies", 1L)
                .containsEntry("failedStudies", 0L)
                .containsEntry("openAccessConsentRows", 1L)
                .containsEntry("unparseableConsentRows", 0L)
                // The subject was migrated, not skipped.
                .containsEntry("subjectsWithoutConsent", 0L);

        assertThat(countWhere("consents", "study_id = ? AND consent_code = 'public'", "open-study-01"))
                .isEqualTo(1);
        assertThat(readMappingFile("open-study-01")).contains("3001,");
    }

    /**
     * A consent value that carries a {@code .c} marker with no usable code is a defect, not open
     * access. Reading it as public would hand an unconsented participant an open-access consent,
     * so it stays skipped -- but the run now reports how many were dropped instead of only
     * logging it.
     */
    @Test
    void a_malformed_consent_value_is_reported_rather_than_read_as_public() throws IOException {
        String managedInputsPath = managedInputs("BADSTUDY,bad-study-01,true");
        String dataFolder = dataFolder(Map.of(
                "consents.csv", "\"4001\",\"bad-study-01.c\"\n",
                "BADSTUDY_PatientMapping.v2.csv", "SUBJ1,BADSTUDY,4001\n"));

        JobResult result = executor.run(job,
                Map.of("managed-inputs", managedInputsPath, "data-folder", dataFolder), "it-bad-consent");

        // The study still "succeeds" -- it is the warnings that carry the problem, which is why
        // they have to be in the report rather than only in the log.
        assertThat(result.getMetrics())
                .containsEntry("unparseableConsentRows", 1L)
                .containsEntry("openAccessConsentRows", 0L)
                .containsEntry("subjectsWithoutConsent", 1L);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("SUBJECTS_WITHOUT_CONSENT"))
                .anyMatch(i -> i.code().equals("UNPARSEABLE_CONSENT_VALUES"));
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(countWhere("consents", "study_id = ?", "bad-study-01")).isZero();
    }

    @Test
    void sstr_driven_study_populates_rds_and_writes_mapping_file() throws IOException {
        String managedInputsPath = managedInputs("GRU,phs001412,true");
        String dataFolder = dataFolder(Map.of(
                "consents.csv", "",
                "phs001412_sstr.tsv",
                "SUBJECT_ID\tSAMPLE_ID\tCONSENT\tconsent_abbreviation\tdbgap_subject_id\tdbgap_sample_id\n"
                        + "SUBJ1\tSAMP1\t1\tGRU\tphs001412.v1.p1.c1\tphs001412.v1.p1.s1\n",
                "GRU_PatientMapping.v2.csv", "phs001412.v1.p1.c1,GRU,1001\n"));

        JobResult result = executor.run(job,
                Map.of("managed-inputs", managedInputsPath, "data-folder", dataFolder), "it-sstr");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("succeededStudies", 1L).containsEntry("failedStudies", 0L);
        assertThat(participants.count()).isEqualTo(1);
        UUID newUuid = jdbc.queryForObject(
                "SELECT hpds_uuid FROM participants WHERE source_id = ? AND source = ?",
                UUID.class, "phs001412.v1.p1.c1", "DBGap");
        assertThat(countWhere("consents", "study_id = ? AND consent_code = '1' AND consent_abbreviation = 'GRU'",
                "phs001412")).isEqualTo(1);

        String mapping = readMappingFile("phs001412");
        assertThat(mapping).isEqualTo("old_hpds_id,new_uuid,common_dbgap_id\n1001," + newUuid + ",phs001412.v1.p1.c1\n");
    }

    @Test
    void non_sstr_study_populates_directly_via_patient_mapping_and_consents_join() throws IOException {
        String managedInputsPath = managedInputs("OTHER,other-study-01,true");
        String dataFolder = dataFolder(Map.of(
                "consents.csv", "\"2002\",\"other-study-01.c1\"\n",
                "OTHER_PatientMapping.v2.csv", "SUBJ42,OTHER,2002\n"));

        JobResult result = executor.run(job,
                Map.of("managed-inputs", managedInputsPath, "data-folder", dataFolder), "it-direct");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(participants.count()).isEqualTo(1);
        UUID newUuid = jdbc.queryForObject(
                "SELECT hpds_uuid FROM participants WHERE source_id = ? AND source = ?",
                UUID.class, "SUBJ42", "other-study-01");
        assertThat(countWhere("consents",
                "study_id = ? AND consent_code = '1' AND consent_abbreviation = ''", "other-study-01")).isEqualTo(1);
        assertThat(countWhere("samples", "sample_source = ?", "other-study-01")).isZero();

        String mapping = readMappingFile("other-study-01");
        assertThat(mapping).isEqualTo("old_hpds_id,new_uuid,common_dbgap_id\n2002," + newUuid + ",SUBJ42\n");
    }

    @Test
    void open_access_1000_genomes_also_populates_samples() {
        String managedInputsPath = managedInputs("open_access-1000Genomes,tg-study-01,true");
        String dataFolder = dataFolder(Map.of(
                "consents.csv", "\"3003\",\"tg-study-01.c1\"\n",
                "OPEN_ACCESS-1000GENOMES_PatientMapping.v2.csv", "SUBJ99,open_access-1000Genomes,3003\n"));

        JobResult result = executor.run(job,
                Map.of("managed-inputs", managedInputsPath, "data-folder", dataFolder), "it-1000genomes");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(countWhere("samples", "source_sample_id = ? AND sample_source = ?",
                "SUBJ99", "tg-study-01")).isEqualTo(1);
    }

    @Test
    void study_not_marked_ready_is_skipped_entirely() {
        // NOTSET has no data files at all -- if the job touched it, it would fail.
        String managedInputsPath = managedInputs(
                "OTHER,other-study-01,true",
                "NOTSET,not-ready-study,false");
        String dataFolder = dataFolder(Map.of(
                "consents.csv", "\"2002\",\"other-study-01.c1\"\n",
                "OTHER_PatientMapping.v2.csv", "SUBJ42,OTHER,2002\n"));

        JobResult result = executor.run(job,
                Map.of("managed-inputs", managedInputsPath, "data-folder", dataFolder), "it-not-ready");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("readyStudies", 1L);
    }

    @Test
    void isolates_a_failing_study_so_others_still_succeed() {
        // MISSING has no patient mapping file on disk -- that study should fail, but
        // OTHER (also ready, in the same run) must still be committed.
        String managedInputsPath = managedInputs(
                "OTHER,other-study-01,true",
                "MISSING,missing-study-01,true");
        String dataFolder = dataFolder(Map.of(
                "consents.csv", "\"2002\",\"other-study-01.c1\"\n",
                "OTHER_PatientMapping.v2.csv", "SUBJ42,OTHER,2002\n"));

        JobResult result = executor.run(job,
                Map.of("managed-inputs", managedInputsPath, "data-folder", dataFolder), "it-isolation");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getMetrics()).containsEntry("succeededStudies", 1L).containsEntry("failedStudies", 1L);
        assertThat(countWhere("participants", "source_id = ? AND source = ?", "SUBJ42", "other-study-01"))
                .isEqualTo(1);
        assertThat(Files.exists(reportsDir.resolve("missing-study-01_hpds_id_mapping.csv"))).isFalse();
    }
}
