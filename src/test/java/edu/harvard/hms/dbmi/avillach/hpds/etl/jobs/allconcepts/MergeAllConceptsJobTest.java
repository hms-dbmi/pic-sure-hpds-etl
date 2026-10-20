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
                .anyMatch(i -> i.code().equals("MERGED_WITHOUT_SOURCES_REMOVED")
                        && i.message().contains("all-concepts-data-generator")
                        && "phs000001".equals(i.context().get("studyId")) && "c1".equals(i.context().get("consent")));
    }

    /** A MERGED file written after the scan (say, by a concurrent merge) is not ours to delete. */
    @Test
    void does_not_delete_a_merged_file_that_changed_since_the_scan() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        run();
        s3.delete(C1 + "phs000001_allConcepts_c1.csv");
        java.util.concurrent.atomic.AtomicBoolean rewritten = new java.util.concurrent.atomic.AtomicBoolean();
        s3.beforeHead = req -> {
            if (req.key().equals(MERGED_C1) && req.versionId() == null && !rewritten.getAndSet(true)) {
                s3.put(MERGED_C1, "fresh\n");
            }
        };

        JobResult result = run();

        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("action", "CHANGED_DURING_RUN");
        // The first merge, then the concurrent write; no delete marker on top.
        assertThat(s3.versionCount(MERGED_C1)).isEqualTo(2);
        assertThat(s3.content(MERGED_C1)).isEqualTo("fresh\n");
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("MERGED_CHANGED_DURING_RUN"));
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

        // Read twice (hash, then upload), both times at that version.
        assertThat(s3.reads).extracting(GetObjectRequest::versionId).containsExactly(v, v);
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

    /**
     * A merged file from the older lowercase scheme, hand-copied into a consent folder alongside
     * the per-file sources it was built from, must not be concatenated with them (every row would
     * be duplicated); it is ignored and reported instead.
     */
    @Test
    void ignores_and_reports_a_legacy_lowercase_merged_file() {
        s3.put(C1 + "phs000001_allConcepts_c1_a.csv", "a\n");
        s3.put(C1 + "phs000001_allConcepts_c1_b.csv", "b\n");
        s3.put(C1 + "phs000001_allConcepts_merged.csv", "a\nb\n");

        JobResult result = run();

        assertThat(s3.content(MERGED_C1)).isEqualTo("a\nb\n");
        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("sourceFileCount", 2);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("IGNORED_FILES")
                        && i.message().contains("phs000001_allConcepts_merged.csv"));
    }

    /**
     * With no sources but a legacy merged copy, the folder's data likely sits in that copy: its
     * MERGED file is kept (not deleted as sourceless) and the folder is flagged.
     */
    @Test
    void keeps_the_merged_file_and_warns_when_only_a_legacy_merged_file_remains() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        run();
        String mergedVersion = s3.versionId(MERGED_C1);
        s3.delete(C1 + "phs000001_allConcepts_c1.csv");
        s3.put(C1 + "phs000001_allConcepts_merged.csv", "a\n");

        JobResult result = run();

        assertThat(action(result, "phs000001/allConcepts/c1")).containsEntry("action", "ONLY_MERGED_COPIES");
        assertThat(s3.versionId(MERGED_C1)).isEqualTo(mergedVersion);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("ONLY_MERGED_COPIES")
                        && i.message().contains("phs000001_allConcepts_merged.csv")
                        && i.message().contains("all-concepts-data-generator")
                        && i.message().contains("study phs000001 consent c1")
                        && "phs000001".equals(i.context().get("studyId")) && "c1".equals(i.context().get("consent")));
    }

    @Test
    void ignores_merged_files_of_any_case_but_still_recognizes_its_own_merged_file() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        s3.put(C1 + "phs000001_allConcepts_Merged.csv", "legacy\n");
        s3.put(C1 + "phs000001_c1_allConcepts_merged.CSV", "legacy\n");
        s3.put(C1 + "phs000001_c1_allConcepts_MERGED.csv", "stale\n");

        JobResult first = run();

        // Its own MERGED file is recognized (no fingerprint yet, so re-merged), not treated as a source.
        assertThat(action(first, "phs000001/allConcepts/c1")).containsEntry("action", "NO_FINGERPRINT");
        assertThat(s3.content(MERGED_C1)).isEqualTo("a\n");
        assertThat(first.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("IGNORED_FILES")
                        && i.message().contains("phs000001_allConcepts_Merged.csv")
                        && i.message().contains("phs000001_c1_allConcepts_merged.CSV")
                        && !i.message().contains("phs000001_c1_allConcepts_MERGED.csv"));

        // Legacy merged files are outside the fingerprint: rewriting one does not trigger a re-merge.
        s3.put(C1 + "phs000001_allConcepts_Merged.csv", "legacy, rewritten\n");
        assertThat(action(run(), "phs000001/allConcepts/c1")).containsEntry("action", "UP_TO_DATE");
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

    @Test
    void streams_the_merge_without_a_local_temp_file_and_reads_each_source_twice() throws Exception {
        s3.put(C1 + "phs000001_allConcepts_c1_a.csv", "a1");
        s3.put(C1 + "phs000001_allConcepts_c1_b.csv", "b1\n");

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(s3.content(MERGED_C1)).isEqualTo("a1\nb1\n");
        assertThat(s3.metadata(MERGED_C1)).containsEntry("content-md5", md5("a1\nb1\n"));
        assertThat(s3.reads).hasSize(4);
    }

    @Test
    void abandons_the_upload_when_the_sources_read_back_differently() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(s3.client.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> {
            String body = reads.getAndIncrement() == 0 ? "a\n" : "z\n";
            return new software.amazon.awssdk.core.ResponseInputStream<>(
                    software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),
                    software.amazon.awssdk.http.AbortableInputStream.create(
                            new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
        });

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(s3.exists(MERGED_C1)).isFalse();
    }

    /** Serves {@code first} on the first read of a source and {@code later} on every read after. */
    private void sourcesReadBack(String first, String later) {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(s3.client.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> {
            String body = reads.getAndIncrement() == 0 ? first : later;
            return new software.amazon.awssdk.core.ResponseInputStream<>(
                    software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),
                    software.amazon.awssdk.http.AbortableInputStream.create(
                            new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
        });
    }

    /**
     * An uploader that reads exactly the content length and stops never sees end of stream, so the
     * check must not wait for it: the same-length but different bytes must not be uploaded under
     * the first read's md5.
     */
    @Test
    void abandons_the_upload_when_a_length_capped_upload_reads_back_different_bytes() {
        s3.lengthCappedReads = true;
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        sourcesReadBack("a\n", "z\n");

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(s3.exists(MERGED_C1)).isFalse();
    }

    @Test
    void a_length_capped_upload_of_unchanged_sources_succeeds() {
        s3.lengthCappedReads = true;
        s3.put(C1 + "phs000001_allConcepts_c1_a.csv", "a1");
        s3.put(C1 + "phs000001_allConcepts_c1_b.csv", "b1\n");

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(s3.content(MERGED_C1)).isEqualTo("a1\nb1\n");
    }

    @Test
    void abandons_the_upload_when_the_sources_read_back_longer_or_shorter() {
        for (String later : List.of("a\nextra\n", "")) {
            setUpQuietly();
            s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
            sourcesReadBack("a\n", later);

            JobResult result = run();

            assertThat(result.getExitCode()).as(later).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
            assertThat(s3.exists(MERGED_C1)).as(later).isFalse();
        }
    }

    private void setUpQuietly() {
        try {
            setUp();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- parallelism -----------------------------------------------------------

    @Test
    void merges_many_folders_concurrently_with_each_from_its_own_sources() {
        StringBuilder csv = new StringBuilder(MANAGED_HEADER);
        for (int study = 1; study <= 6; study++) {
            String id = "phs00010" + study;
            csv.append(id).append(',').append(id).append(",Yes\n");
            for (int c = 1; c <= 4; c++) {
                String dir = "root/" + id + "/allConcepts/c" + c + "/";
                s3.put(dir + id + "_allConcepts_c" + c + "_a.csv", id + " c" + c + " a\n");
                s3.put(dir + id + "_allConcepts_c" + c + "_b.csv", id + " c" + c + " b");
            }
        }
        managedInputs = csv.toString();

        JobResult result = executor.run(job(), Map.of("input", ROOT, "parallelism", "4"), "unit");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("foldersMerged", 24L);
        for (int study = 1; study <= 6; study++) {
            String id = "phs00010" + study;
            for (int c = 1; c <= 4; c++) {
                assertThat(s3.content("root/" + id + "/allConcepts/c" + c + "/" + id + "_c" + c + "_allConcepts_MERGED.csv"))
                        .isEqualTo(id + " c" + c + " a\n" + id + " c" + c + " b\n");
            }
        }
        // Reported in study and consent order, whatever order the folders finished in.
        @SuppressWarnings("unchecked")
        Map<String, Object> actions = (Map<String, Object>) result.getMetrics().get("actions");
        assertThat(actions.keySet()).startsWith("phs000101/allConcepts/c1", "phs000101/allConcepts/c2");
    }

    @Test
    void a_failing_folder_does_not_stop_the_others_but_fails_the_run() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        s3.put(C2 + "phs000001_allConcepts_c2.csv", "b\n");
        when(s3.client.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> {
            GetObjectRequest req = inv.getArgument(0);
            if (req.key().startsWith(C1)) {
                throw new RuntimeException("connection reset");
            }
            return new software.amazon.awssdk.core.ResponseInputStream<>(
                    software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),
                    software.amazon.awssdk.http.AbortableInputStream.create(
                            new java.io.ByteArrayInputStream("b\n".getBytes(StandardCharsets.UTF_8))));
        });

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(result.getErrorMessage()).contains("Failed to read source");
        assertThat(s3.exists(MERGED_C1)).isFalse();
        assertThat(s3.content(MERGED_C2)).isEqualTo("b\n");
    }

    /** The report of a failed run has no metrics, so every folder's outcome is written beside it. */
    @Test
    @SuppressWarnings("unchecked")
    void a_failed_run_still_records_what_every_folder_did() throws Exception {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        s3.put(C2 + "phs000001_allConcepts_c2.csv", "b\n");
        when(s3.client.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> {
            GetObjectRequest req = inv.getArgument(0);
            if (req.key().startsWith(C1)) {
                throw new RuntimeException("connection reset");
            }
            return new software.amazon.awssdk.core.ResponseInputStream<>(
                    software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),
                    software.amazon.awssdk.http.AbortableInputStream.create(
                            new java.io.ByteArrayInputStream("b\n".getBytes(StandardCharsets.UTF_8))));
        });

        JobResult result = run();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        Path folders = tempDir.resolve("reports").resolve("merge-allconcepts-unit-folders.json");
        assertThat(folders).exists();
        Map<String, Object> partial = new ObjectMapper().readValue(folders.toFile(), Map.class);
        assertThat(partial).containsEntry("foldersFailed", 1).containsEntry("consentFoldersDiscovered", 2);
        Map<String, Map<String, Object>> actions = (Map<String, Map<String, Object>>) partial.get("actions");
        assertThat(actions.get("phs000001/allConcepts/c1")).containsEntry("action", "FAILED");
        assertThat((String) actions.get("phs000001/allConcepts/c1").get("error")).contains("Failed to read source");
        assertThat(actions.get("phs000001/allConcepts/c2")).containsEntry("action", "MISSING");
    }

    @Test
    void a_successful_run_writes_no_per_folder_outcomes_file() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");

        run();

        assertThat(tempDir.resolve("reports").resolve("merge-allconcepts-unit-folders.json")).doesNotExist();
    }

    @Test
    void rejects_malformed_study_ids() {
        for (String bad : List.of("phs000001,,phs000002", "phs1", "phs000001/c1", "phs000001, ")) {
            JobResult result = executor.run(job(), Map.of("input", ROOT, "study-ids", bad), "unit");

            assertThat(result.getExitCode()).as(bad).isEqualTo(ExitCode.VALIDATION_FAILED);
            assertThat(result.getInputValidation().getIssues()).as(bad).anyMatch(i -> i.code().equals("BAD_STUDY_ID"));
        }
    }

    @Test
    void rejects_parallelism_out_of_range() {
        for (String bad : List.of("0", "17", "many")) {
            JobResult result = executor.run(job(), Map.of("input", ROOT, "parallelism", bad), "unit");

            assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
            assertThat(result.getInputValidation().getIssues()).anyMatch(i -> i.code().equals("BAD_PARALLELISM"));
        }
    }
}
