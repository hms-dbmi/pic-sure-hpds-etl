package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GenerateGlobalAllConceptsJob} against a real Postgres: the DB builds every row with
 * {@code COPY}, so this is where the output is checked. Row order is not meaningful, so rows are
 * compared as sets.
 */
class GenerateGlobalAllConceptsJobIT extends AbstractIntegrationTest {

    private static final String BUCKET = "hpds-etl-global-it";

    @Autowired
    private GenerateGlobalAllConceptsJob job;
    @Autowired
    private JobExecutor executor;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EtlProperties properties;
    @Autowired
    private IoResolver io;
    @Autowired
    private S3Client s3;

    private Path tmp;
    private Path reportsDir;
    private String previousManagedInputs;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.execute("TRUNCATE TABLE participants, consents, samples");
        tmp = Files.createTempDirectory("global-all-concepts-it");
        reportsDir = Path.of(properties.getReports().getDir());
        previousManagedInputs = properties.getManagedInputs().getUri();
    }

    @AfterEach
    void tearDown() throws IOException {
        properties.getManagedInputs().setUri(previousManagedInputs);
        Files.deleteIfExists(reportsDir.resolve(GenerateGlobalAllConceptsJob.IDENTITY_STUDIES_REPORT));
    }

    /** {@code "ABV,phs...,Yes"} rows. */
    private void managedInputs(String... rows) throws IOException {
        Path file = tmp.resolve("managed_inputs.csv");
        Files.writeString(file, "Study Abbreviated Name,Study Identifier,Data is ready to process\n"
                + String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        properties.getManagedInputs().setUri(file.toString());
    }

    private void consent(long hpdsId, String studyId, String code, String abbreviation) {
        jdbc.update("INSERT INTO consents (hpds_id, study_id, consent_code, consent_abbreviation) VALUES (?, ?, ?, ?)",
                hpdsId, studyId, code, abbreviation);
    }

    private void participant(long hpdsId, String sourceId, String source) {
        jdbc.update("INSERT INTO participants (hpds_id, source_id, source) VALUES (?, ?, ?)", hpdsId, sourceId, source);
    }

    private void sample(long hpdsId, String studyId, String sampleId, String source) {
        jdbc.update("INSERT INTO samples (hpds_id, study_id, source_sample_id, sample_source) VALUES (?, ?, ?, ?)",
                hpdsId, studyId, sampleId, source);
    }

    private JobResult run(Path output, String... extra) {
        return run(output.toString(), extra);
    }

    private JobResult run(String output, String... extra) {
        Map<String, String> params = new HashMap<>(Map.of("output", output));
        for (int i = 0; i < extra.length; i += 2) {
            params.put(extra[i], extra[i + 1]);
        }
        return executor.run(job, params, "global-it");
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static String row(long hpdsId, String concept, String value) {
        return "\"" + hpdsId + "\",\"" + concept + "\",\"\",\"" + value + "\",\"0\"";
    }

    @Test
    void writes_every_concept_type_each_field_quoted() throws IOException {
        managedInputs("STUDY1,phs000904,Yes");
        consent(1, "phs000904", "1", "GRU");
        consent(2, "phs000904", "2", "HMB");
        participant(1, "SUBJ1", "DBGap");
        participant(1, "S-1", "phs000904");
        participant(2, "SUBJ2", "DBGap");
        sample(1, "phs000904", "SAMP1", "DBGap");
        Path out = tmp.resolve("out/");

        JobResult result = run(out + "/");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(lines(out.resolve(GenerateGlobalAllConceptsJob.OUTPUT_FILENAME))).containsExactlyInAnyOrder(
                row(1, "µ_consentsµ", "phs000904.c1"),
                row(2, "µ_consentsµ", "phs000904.c2"),
                row(1, "µ_studies_consentsµphs000904µ", "TRUE"),
                row(2, "µ_studies_consentsµphs000904µ", "TRUE"),
                row(1, "µ_studies_consentsµphs000904µGRUµ", "TRUE"),
                row(2, "µ_studies_consentsµphs000904µHMBµ", "TRUE"),
                row(1, "µ_source_subject_idµ", "SUBJ1"),
                row(1, "µ_source_subject_idµ", "S-1"),
                row(2, "µ_source_subject_idµ", "SUBJ2"),
                row(1, "µ_source_sample_idµ", "SAMP1"));
        assertThat(result.getMetrics())
                .containsEntry("totalRows", 10L)
                .containsEntry("rowsPerStudy", Map.of("phs000904", 6L))
                .containsEntry("subjectIdRows", 3L)
                .containsEntry("sampleIdRows", 1L);
    }

    @Test
    void a_blank_abbreviation_gets_no_individual_path_and_a_warning() throws IOException {
        managedInputs("STUDY1,phs000904,Yes");
        consent(1, "phs000904", "1", "");
        consent(2, "phs000904", "1", "  ");
        consent(3, "phs000904", "2", "HMB");
        Path out = tmp.resolve("out.csv");

        JobResult result = run(out);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(lines(out)).filteredOn(l -> l.contains("µ_studies_consentsµ"))
                .containsExactlyInAnyOrder(
                        row(1, "µ_studies_consentsµphs000904µ", "TRUE"),
                        row(2, "µ_studies_consentsµphs000904µ", "TRUE"),
                        row(3, "µ_studies_consentsµphs000904µ", "TRUE"),
                        row(3, "µ_studies_consentsµphs000904µHMBµ", "TRUE"));
        assertThat(result.getMetrics()).containsEntry("rowsPerStudy", Map.of("phs000904", 7L));
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("EMPTY_CONSENT_ABBREVIATION") && i.message().contains("2 consent(s)"));
    }

    /**
     * One participant (hpds id 1) in a parent study and its TOPMed study: its subject id is written
     * once, a sample both studies loaded is written once, and the report says which studies each row
     * applies to. The per-study concepts stay per study.
     */
    @Test
    void writes_each_subject_and_sample_id_once_and_reports_the_studies_it_applies_to() throws IOException {
        managedInputs("STUDY2,phs000902,Yes", "STUDY3,phs000903,Yes");
        consent(1, "phs000902", "1", "HMB");
        consent(2, "phs000902", "1", "HMB");
        consent(1, "phs000903", "2", "DS");
        participant(1, "SUBJ1", "DBGap");
        participant(2, "SUBJ2", "DBGap");
        sample(1, "phs000902", "SRS1", "DBGap");
        sample(1, "phs000902", "NWD1", "submitted");
        sample(1, "phs000903", "NWD1", "submitted");
        // The same id under both sources in one study counts for that study once.
        sample(1, "phs000903", "NWD9", "DBGap");
        sample(1, "phs000903", "NWD9", "submitted");
        Path out = tmp.resolve("out.csv");

        JobResult result = run(out);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        List<String> rows = lines(out);
        assertThat(rows).filteredOn(l -> l.contains("µ_source_subject_idµ")).containsExactlyInAnyOrder(
                row(1, "µ_source_subject_idµ", "SUBJ1"), row(2, "µ_source_subject_idµ", "SUBJ2"));
        assertThat(rows).filteredOn(l -> l.contains("µ_source_sample_idµ")).containsExactlyInAnyOrder(
                row(1, "µ_source_sample_idµ", "SRS1"), row(1, "µ_source_sample_idµ", "NWD1"),
                row(1, "µ_source_sample_idµ", "NWD9"));
        assertThat(rows).filteredOn(l -> l.contains("µ_consentsµ")).containsExactlyInAnyOrder(
                row(1, "µ_consentsµ", "phs000902.c1"), row(2, "µ_consentsµ", "phs000902.c1"),
                row(1, "µ_consentsµ", "phs000903.c2"));

        assertThat(result.getMetrics())
                .containsEntry("subjectIdRows", 2L)
                .containsEntry("subjectIdsByStudyCount", Map.of("1", 1L, "2", 1L))
                .containsEntry("sharedSubjectIdStudySets", Map.of("phs000902+phs000903", 1L))
                .containsEntry("sampleIdRows", 3L)
                .containsEntry("sampleIdsByStudyCount", Map.of("1", 2L, "2", 1L))
                .containsEntry("sharedSampleIdStudySets", Map.of("phs000902+phs000903", 1L))
                .containsEntry("rowsPerStudy", Map.of("phs000902", 6L, "phs000903", 3L));
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("SHARED_SUBJECT_IDS") && i.message().startsWith("1 of 2 "))
                .anyMatch(i -> i.code().equals("SHARED_SAMPLE_IDS") && i.message().startsWith("1 of 3 "));

        List<String> report = lines(reportsDir.resolve(GenerateGlobalAllConceptsJob.IDENTITY_STUDIES_REPORT));
        assertThat(report.get(0)).isEqualTo("concept,hpds_id,value,study_count,study_ids");
        assertThat(report.subList(1, report.size())).containsExactlyInAnyOrder(
                "\"µ_source_subject_idµ\",1,\"SUBJ1\",2,\"phs000902;phs000903\"",
                "\"µ_source_subject_idµ\",2,\"SUBJ2\",1,\"phs000902\"",
                "\"µ_source_sample_idµ\",1,\"SRS1\",1,\"phs000902\"",
                "\"µ_source_sample_idµ\",1,\"NWD1\",2,\"phs000902;phs000903\"",
                "\"µ_source_sample_idµ\",1,\"NWD9\",1,\"phs000903\"");
    }

    /**
     * A sample is listed for the study that loaded it, whether or not its participant has a consent in
     * that study: samples are attributed by their own study, not through the participant's consents.
     */
    @Test
    void a_sample_is_listed_for_its_own_study_even_without_a_consent_there() throws IOException {
        managedInputs("STUDY2,phs000902,Yes", "STUDY3,phs000903,Yes");
        consent(1, "phs000902", "1", "HMB");
        consent(2, "phs000903", "1", "DS");
        participant(1, "SUBJ1", "DBGap");
        participant(2, "SUBJ2", "DBGap");
        // Participant 1 has no phs000903 consent.
        sample(1, "phs000903", "NWD5", "submitted");
        Path out = tmp.resolve("out.csv");

        JobResult result = run(out);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(lines(out)).contains(row(1, "µ_source_sample_idµ", "NWD5"));
        assertThat(lines(reportsDir.resolve(GenerateGlobalAllConceptsJob.IDENTITY_STUDIES_REPORT)))
                .contains("\"µ_source_sample_idµ\",1,\"NWD5\",1,\"phs000903\"");
    }

    /**
     * A participant in two studies has each of their subject ids listed for both, a study-local one
     * (source = one of the studies) included: ids never overlap between studies, and the source is
     * what aligns them.
     */
    @Test
    void a_study_local_subject_id_is_listed_for_every_study_the_participant_is_in() throws IOException {
        managedInputs("STUDY2,phs000902,Yes", "STUDY3,phs000903,Yes");
        consent(1, "phs000902", "1", "HMB");
        consent(1, "phs000903", "2", "DS");
        participant(1, "SUBJ1", "DBGap");
        participant(1, "LOCAL-1", "phs000903");
        Path out = tmp.resolve("out.csv");

        JobResult result = run(out);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(lines(out)).contains(row(1, "µ_source_subject_idµ", "LOCAL-1"));
        assertThat(lines(reportsDir.resolve(GenerateGlobalAllConceptsJob.IDENTITY_STUDIES_REPORT))).contains(
                "\"µ_source_subject_idµ\",1,\"SUBJ1\",2,\"phs000902;phs000903\"",
                "\"µ_source_subject_idµ\",1,\"LOCAL-1\",2,\"phs000902;phs000903\"");
    }

    /** A participant reached only through a study that is not ready contributes nothing. */
    @Test
    void only_ready_studies_are_written() throws IOException {
        managedInputs("S1,phs000001,Yes", "S2,phs000002,No");
        consent(1, "phs000001", "1", "GRU");
        consent(2, "phs000002", "1", "GRU");
        participant(1, "SUBJ1", "DBGap");
        participant(2, "SUBJ2", "DBGap");
        sample(2, "phs000002", "SAMP2", "DBGap");
        Path out = tmp.resolve("out.csv");

        JobResult result = run(out);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(lines(out)).containsExactlyInAnyOrder(
                row(1, "µ_consentsµ", "phs000001.c1"),
                row(1, "µ_studies_consentsµphs000001µ", "TRUE"),
                row(1, "µ_studies_consentsµphs000001µGRUµ", "TRUE"),
                row(1, "µ_source_subject_idµ", "SUBJ1"));
    }

    @Test
    void doubles_embedded_quotes() throws IOException {
        managedInputs("S1,phs000001,Yes");
        consent(1, "phs000001", "1", "GRU");
        participant(1, "say \"hi\"", "DBGap");
        Path out = tmp.resolve("out.csv");

        run(out);

        assertThat(lines(out)).contains(row(1, "µ_source_subject_idµ", "say \"\"hi\"\""));
    }

    @Test
    void skips_a_study_with_no_consents_in_the_database() throws IOException {
        managedInputs("S1,phs000001,Yes", "S2,phs000002,Yes");
        consent(2, "phs000002", "1", "GRU");
        Path out = tmp.resolve("out.csv");

        JobResult result = run(out);

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("NO_CONSENTS") && i.message().contains("phs000001"));
        assertThat(String.join("\n", lines(out))).doesNotContain("phs000001").contains("phs000002");
    }

    @Test
    void no_consents_for_any_ready_study_is_a_data_error_unless_empty_is_allowed() throws IOException {
        managedInputs("S1,phs000001,Yes");
        Path out = tmp.resolve("out.csv");

        JobResult failed = run(out);
        JobResult allowed = run(out, "allow-empty", "true");

        assertThat(failed.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
        assertThat(failed.getErrorMessage()).contains("No concept rows were generated");
        assertThat(allowed.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(out).doesNotExist();
    }

    /** The temp tables live on a pooled connection: a second run on it must start clean. */
    @Test
    void runs_twice_in_a_row_with_the_same_result() throws IOException {
        managedInputs("S1,phs000001,Yes");
        consent(1, "phs000001", "1", "GRU");
        participant(1, "SUBJ1", "DBGap");
        Path first = tmp.resolve("first.csv");
        Path second = tmp.resolve("second.csv");

        assertThat(run(first).getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(run(second).getExitCode()).isEqualTo(ExitCode.SUCCESS);

        assertThat(lines(second)).containsExactlyInAnyOrderElementsOf(lines(first)).hasSize(4);
    }

    @Test
    void writes_to_s3() throws IOException {
        if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
        managedInputs("S1,phs000001,Yes");
        consent(1, "phs000001", "1", "GRU");

        JobResult result = run("s3://" + BUCKET + "/global/");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        try (InputStream in = io.openInput("s3://" + BUCKET + "/global/" + GenerateGlobalAllConceptsJob.OUTPUT_FILENAME)) {
            assertThat(Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")))
                    .containsExactlyInAnyOrder(
                            row(1, "µ_consentsµ", "phs000001.c1"),
                            row(1, "µ_studies_consentsµphs000001µ", "TRUE"),
                            row(1, "µ_studies_consentsµphs000001µGRUµ", "TRUE"));
        }
    }
}
