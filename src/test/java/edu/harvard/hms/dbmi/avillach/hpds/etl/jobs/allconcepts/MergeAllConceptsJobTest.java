package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MergeAllConceptsJob} against {@link FakeVersionedS3}: which folders it
 * merges, how it detects that a folder changed since its MERGED file was written (added, updated
 * and deleted sources, via version-id fingerprints), what it writes, and how it fails.
 */
class MergeAllConceptsJobTest {

    static final String ROOT = "s3://bucket/root/";
    static final String C1 = "root/phs000001/allConcepts/c1/";
    static final String C2 = "root/phs000001/allConcepts/c2/";
    static final String MERGED_C1 = C1 + "phs000001_c1_allConcepts_MERGED.csv";
    static final String MERGED_C2 = C2 + "phs000001_c2_allConcepts_MERGED.csv";
    static final String MANAGED_HEADER = "Study Abbreviated Name,Study Identifier,Data is ready to process\n";

    @TempDir
    Path tempDir;

    private FakeVersionedS3 s3;
    private JobExecutor executor;
    private String managedInputs = MANAGED_HEADER + "A,phs000001,Yes\nB,phs000002,No\n";

    @BeforeEach
    void setUp() throws Exception {
        s3 = new FakeVersionedS3();
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(tempDir.resolve("reports").toString());
        executor = new JobExecutor(new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    static ManagedInputsService managedInputsService(Path dir, String csv) {
        try {
            Path file = Files.createTempFile(dir, "managed_inputs", ".csv");
            Files.writeString(file, csv);
            EtlProperties props = new EtlProperties();
            props.getManagedInputs().setUri(file.toString());
            return new ManagedInputsService(new IoResolver(null), new DelimitedReader(), props);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MergeAllConceptsJob job() {
        return new MergeAllConceptsJob(new IoResolver(s3.client), s3.client, managedInputsService(tempDir, managedInputs));
    }

    private JobResult run() {
        return executor.run(job(), Map.of("input", ROOT), "unit");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> action(JobResult result, String folder) {
        return (Map<String, Object>) ((Map<String, Object>) result.getMetrics().get("actions")).get(folder);
    }

    static String md5(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- merging ---------------------------------------------------------------

    @Test
    void merges_every_consent_folder_of_ready_studies_only() throws Exception {
        s3.put(C1 + "phs000001_allConcepts_c1_b.csv", "b1\nb2\n");
        s3.put(C1 + "phs000001_allConcepts_c1_a.csv", "a1\n");
        s3.put(C2 + "phs000001_allConcepts_c2.csv", "x\n");
        s3.put("root/phs000002/allConcepts/c1/phs000002_allConcepts_c1.csv", "not ready\n");

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("studiesChecked", 1L)
                .containsEntry("consentFoldersDiscovered", 2L)
                .containsEntry("foldersMerged", 2L)
                .containsEntry("totalFilesMerged", 3L);
        // Name order, so the same sources always merge to the same bytes.
        assertThat(s3.content(MERGED_C1)).isEqualTo("a1\nb1\nb2\n");
        assertThat(s3.content(MERGED_C2)).isEqualTo("x\n");
        assertThat(s3.exists("root/phs000002/allConcepts/c1/phs000002_c1_allConcepts_MERGED.csv")).isFalse();
        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("action", "MISSING");

        Map<String, String> meta = s3.metadata(MERGED_C1);
        assertThat(meta).containsEntry("content-md5", md5("a1\nb1\nb2\n")).containsEntry("source-count", "2");
        assertThat(meta.get("source-fingerprint")).hasSize(64);
    }

    /**
     * Managed inputs name only the study; every consent folder it has is its own group, merged into
     * its own folder from its own sources only.
     */
    @Test
    void merges_each_consent_folder_of_a_ready_study_into_that_folder() {
        String c10 = "root/phs000001/allConcepts/c10/";
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "one\n");
        s3.put(C2 + "phs000001_allConcepts_c2_a.csv", "two-a\n");
        s3.put(C2 + "phs000001_allConcepts_c2_b.csv", "two-b\n");
        s3.put(c10 + "phs000001_allConcepts_c10.csv", "ten\n");

        JobResult result = run();

        assertThat(result.getMetrics()).containsEntry("consentFoldersDiscovered", 3L).containsEntry("foldersMerged", 3L);
        assertThat(s3.content(MERGED_C1)).isEqualTo("one\n");
        assertThat(s3.content(MERGED_C2)).isEqualTo("two-a\ntwo-b\n");
        assertThat(s3.content(c10 + "phs000001_c10_allConcepts_MERGED.csv")).isEqualTo("ten\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> actions = (Map<String, Object>) result.getMetrics().get("actions");
        // Code order, not string order: c2 before c10.
        assertThat(actions.keySet()).containsExactly(
                "phs000001/allConcepts/c1", "phs000001/allConcepts/c2", "phs000001/allConcepts/c10");
    }

    @Test
    void merges_a_consent_folder_that_appears_after_earlier_runs() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "one\n");
        run();
        s3.put(C2 + "phs000001_allConcepts_c2.csv", "two\n");

        JobResult result = run();

        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("action", "UP_TO_DATE");
        assertThat(action(result, "phs000001/allConcepts/c2")).containsEntry("action", "MISSING");
        assertThat(s3.content(MERGED_C2)).isEqualTo("two\n");
    }

    /** A study on two ready rows is merged once, so the report counts what was really merged. */
    @Test
    void merges_a_study_listed_twice_as_ready_only_once() {
        managedInputs = MANAGED_HEADER + "A,phs000001,Yes\nA2,phs000001,Yes\n";
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "one\n");

        JobResult result = run();

        assertThat(result.getMetrics())
                .containsEntry("studiesChecked", 1L)
                .containsEntry("foldersMerged", 1L)
                .containsEntry("totalFilesMerged", 1L);
        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("action", "MISSING");
        assertThat(s3.versionCount(MERGED_C1)).isEqualTo(1);
    }

    @Test
    void skips_a_folder_whose_sources_have_not_changed() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        run();
        int versions = s3.versionCount(MERGED_C1);

        JobResult second = run();

        assertThat(second.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(second.getMetrics()).containsEntry("foldersMerged", 0L).containsEntry("foldersSkipped", 1L);
        assertThat(action(second, "phs000001/allConcepts/c1")).containsEntry("action", "UP_TO_DATE");
        assertThat(s3.versionCount(MERGED_C1)).isEqualTo(versions);
    }

    @Test
    void re_merges_when_a_source_is_added() {
        s3.put(C1 + "phs000001_allConcepts_c1_a.csv", "a\n");
        run();
        s3.put(C1 + "phs000001_allConcepts_c1_b.csv", "b\n");

        JobResult result = run();

        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("action", "SOURCES_CHANGED");
        assertThat(s3.content(MERGED_C1)).isEqualTo("a\nb\n");
    }

    @Test
    void re_merges_when_a_source_is_overwritten_even_with_the_same_name() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "old\n");
        run();
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "new\n");

        JobResult result = run();

        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("action", "SOURCES_CHANGED");
        assertThat(s3.content(MERGED_C1)).isEqualTo("new\n");
    }

    /** Overwriting a source with identical bytes is still a new version, so it still re-merges. */
    @Test
    void re_merges_when_a_source_is_rewritten_with_identical_content() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "same\n");
        run();
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "same\n");

        assertThat(action(run(), "phs000001/allConcepts/c1")).containsEntry("action", "SOURCES_CHANGED");
    }

    @Test
    void re_merges_when_a_source_is_deleted() {
        s3.put(C1 + "phs000001_allConcepts_c1_a.csv", "a\n");
        s3.put(C1 + "phs000001_allConcepts_c1_b.csv", "b\n");
        run();
        s3.delete(C1 + "phs000001_allConcepts_c1_a.csv");

        JobResult result = run();

        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("action", "SOURCES_CHANGED");
        assertThat(s3.content(MERGED_C1)).isEqualTo("b\n");
    }

    @Test
    void re_merges_a_merged_file_written_before_fingerprints_existed() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        s3.put(MERGED_C1, "a\n");

        JobResult result = run();

        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("action", "NO_FINGERPRINT");
        assertThat(s3.metadata(MERGED_C1)).containsKey("source-fingerprint");
    }

    @Test
    void deletes_the_merged_file_when_every_source_is_gone() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        run();
        s3.delete(C1 + "phs000001_allConcepts_c1.csv");

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("action", "REMOVED_NO_SOURCES");
        assertThat(s3.exists(MERGED_C1)).isFalse();
        // A delete marker: the last merged version is still recoverable.
        assertThat(s3.versionCount(MERGED_C1)).isEqualTo(2);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("MERGED_WITHOUT_SOURCES_REMOVED"));
    }

    @Test
    void ends_every_source_with_a_newline_so_rows_never_run_together() {
        s3.put(C1 + "phs000001_allConcepts_c1_a.csv", "a1\na2");
        s3.put(C1 + "phs000001_allConcepts_c1_b.csv", "");
        s3.put(C1 + "phs000001_allConcepts_c1_c.csv", "c1\n");

        run();

        assertThat(s3.content(MERGED_C1)).isEqualTo("a1\na2\nc1\n");
    }

    @Test
    void reads_each_source_at_the_version_it_fingerprinted() {
        String v = s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");

        run();

        assertThat(s3.reads).extracting(GetObjectRequest::versionId).containsExactly(v);
    }

    @Test
    void merges_only_allconcepts_files_directly_in_consent_folders() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        s3.put(C1 + "notes.txt", "ignore me\n");
        s3.put(C1 + "phs000009_c1_allConcepts_MERGED.csv", "someone else's\n");
        s3.put(C1 + "nested/phs000001_allConcepts_c1.csv", "nested\n");
        s3.put("root/phs000001/allConcepts/notes/phs000001_allConcepts_c1.csv", "not a consent folder\n");
        s3.put("root/phs000001/legacy/allConcepts/phs000001_allConcepts_new_search.csv", "legacy\n");

        JobResult result = run();

        assertThat(result.getMetrics()).containsEntry("consentFoldersDiscovered", 1L);
        assertThat(s3.content(MERGED_C1)).isEqualTo("a\n");
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("IGNORED_FILES") && i.message().contains("notes.txt")
                        && i.message().contains("phs000009_c1_allConcepts_MERGED.csv"));
    }

    @Test
    void pages_through_long_version_listings() {
        s3.pageSize = 1;
        s3.put(C1 + "phs000001_allConcepts_c1_a.csv", "a\n");
        s3.put(C1 + "phs000001_allConcepts_c1_a.csv", "a2\n");
        s3.put(C1 + "phs000001_allConcepts_c1_b.csv", "b\n");

        run();

        assertThat(s3.content(MERGED_C1)).isEqualTo("a2\nb\n");
    }

    @Test
    void study_ids_overrides_the_ready_list() {
        s3.put("root/phs000002/allConcepts/c1/phs000002_allConcepts_c1.csv", "x\n");
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");

        JobResult result = executor.run(job(), Map.of("input", ROOT, "study-ids", "phs000002"), "unit");

        assertThat(s3.exists("root/phs000002/allConcepts/c1/phs000002_c1_allConcepts_MERGED.csv")).isTrue();
        assertThat(s3.exists(MERGED_C1)).isFalse();
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("STUDIES_NOT_READY") && i.message().contains("phs000002"));
    }

    // ---- warnings and failures ---------------------------------------------------

    @Test
    void warns_about_a_ready_study_with_no_consent_folders() {
        managedInputs = MANAGED_HEADER + "A,phs000001,Yes\nC,phs000003,Yes\n";
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getMetrics()).containsEntry("studiesWithoutFolders", List.of("phs000003"));
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("STUDY_HAS_NO_CONSENT_FOLDERS") && i.message().contains("phs000003"));
    }

    @Test
    void warns_about_a_consent_folder_with_no_sources() {
        s3.put(C1 + "readme.txt", "x\n");

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(s3.exists(MERGED_C1)).isFalse();
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("CONSENT_FOLDER_WITHOUT_SOURCES"));
    }

    @Test
    void warns_when_no_study_is_ready() {
        managedInputs = MANAGED_HEADER + "A,phs000001,No\n";

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues()).anyMatch(i -> i.code().equals("NO_READY_STUDIES"));
    }

    @Test
    void fails_with_config_error_when_the_bucket_is_not_versioned() {
        s3.versioning = BucketVersioningStatus.SUSPENDED;
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(result.getErrorMessage()).contains("versioning Enabled").contains("Suspended");
        assertThat(s3.exists(MERGED_C1)).isFalse();
    }

    @Test
    void fails_with_config_error_when_versioning_was_never_enabled() {
        s3.versioning = null;

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(result.getErrorMessage()).contains("never enabled");
    }

    @Test
    void fails_input_validation_when_input_is_not_s3() {
        JobResult result = executor.run(job(), Map.of("input", "/local/dir"), "unit");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues()).anyMatch(i -> i.code().equals("S3_REQUIRED"));
    }

    @Test
    void fails_with_infrastructure_error_when_a_source_cannot_be_read() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        when(s3.client.getObject(any(GetObjectRequest.class))).thenThrow(new RuntimeException("connection reset"));

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(result.getErrorMessage()).contains("Failed to read source");
        assertThat(s3.exists(MERGED_C1)).isFalse();
    }
}
