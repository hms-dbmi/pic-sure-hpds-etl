package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.MergeAllConceptsJobTest.C1;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.MergeAllConceptsJobTest.C2;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.MergeAllConceptsJobTest.MANAGED_HEADER;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.MergeAllConceptsJobTest.MERGED_C1;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.MergeAllConceptsJobTest.MERGED_C2;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.MergeAllConceptsJobTest.ROOT;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.MergeAllConceptsJobTest.managedInputsService;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.MergeAllConceptsJobTest.md5;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GenerateAllConceptsManifestJob} against {@link FakeVersionedS3}, with the
 * MERGED files produced by the real {@link MergeAllConceptsJob}: the manifest it writes, and every
 * condition under which it must refuse to write one.
 */
class GenerateAllConceptsManifestJobTest {

    private static final String MANIFEST = "root/allConcepts_manifest.csv";
    private static final String P2_C1 = "root/phs000002/allConcepts/c1/";
    private static final String GLOBAL = "global/global_AllConcepts.csv";
    private static final String GLOBAL_DIR = "s3://bucket/global/";

    @TempDir
    Path tempDir;

    private FakeVersionedS3 s3;
    private JobExecutor executor;
    private Path reportsDir;
    private String managedInputs = MANAGED_HEADER + "A,phs000001,Yes\nB,phs000002,Yes\nC,phs000003,No\n";

    @BeforeEach
    void setUp() {
        s3 = new FakeVersionedS3();
        s3.put(GLOBAL, "global rows\n");
        reportsDir = tempDir.resolve("reports");
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(reportsDir.toString());
        executor = new JobExecutor(new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    private JobResult merge() {
        return executor.run(new MergeAllConceptsJob(new IoResolver(s3.client), s3.client,
                managedInputsService(tempDir, managedInputs)), Map.of("input", ROOT), "unit-merge");
    }

    private JobResult manifest() {
        return executor.run(new GenerateAllConceptsManifestJob(new IoResolver(s3.client), s3.client,
                managedInputsService(tempDir, managedInputs)),
                Map.of("input", ROOT, "global-allconcepts", GLOBAL_DIR), "unit-manifest");
    }

    /** Two ready studies (three consent groups) and one not-ready study, all merged. */
    private void mergedData() {
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        s3.put(C2 + "phs000001_allConcepts_c2.csv", "b\n");
        s3.put(P2_C1 + "phs000002_allConcepts_c1.csv", "c\n");
        s3.put("root/phs000003/allConcepts/c1/phs000003_allConcepts_c1.csv", "not ready\n");
        assertThat(merge().isSuccess()).isTrue();
    }

    private static List<String> codes(JobResult result) {
        return result.getOutputValidation().getIssues().stream().map(ValidationIssue::code).toList();
    }

    /** The manifest must not change when the job refuses to write. */
    private void assertNotWritten(JobResult result, String previousVersion) {
        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getMetrics()).containsEntry("manifestWritten", false);
        assertThat(codes(result)).contains("MANIFEST_NOT_WRITTEN");
        if (previousVersion == null) {
            assertThat(s3.exists(MANIFEST)).isFalse();
        } else {
            assertThat(s3.versionId(MANIFEST)).isEqualTo(previousVersion);
        }
    }

    // ---- success ---------------------------------------------------------------

    @Test
    void lists_every_ready_consent_group_with_its_merged_version_and_md5() throws Exception {
        mergedData();

        JobResult result = manifest();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("readyStudies", 2L)
                .containsEntry("manifestRows", 4L)
                .containsEntry("manifestWritten", true)
                .containsEntry("manifestUri", ROOT + "allConcepts_manifest.csv")
                .containsEntry("manifestVersionId", s3.versionId(MANIFEST));
        assertThat(s3.content(MANIFEST)).isEqualTo(String.join("\n",
                "partition,s3_uri,version_id,last_updated,md5",
                "global,s3://bucket/" + GLOBAL + "," + s3.versionId(GLOBAL) + "," + s3.lastModified(GLOBAL) + ","
                        + md5("global rows\n"),
                "phs000001.c1,s3://bucket/" + MERGED_C1 + "," + s3.versionId(MERGED_C1) + ","
                        + s3.lastModified(MERGED_C1) + "," + md5("a\n"),
                "phs000001.c2,s3://bucket/" + MERGED_C2 + "," + s3.versionId(MERGED_C2) + ","
                        + s3.lastModified(MERGED_C2) + "," + md5("b\n"),
                "phs000002.c1,s3://bucket/" + P2_C1 + "phs000002_c1_allConcepts_MERGED.csv,"
                        + s3.versionId(P2_C1 + "phs000002_c1_allConcepts_MERGED.csv") + ","
                        + s3.lastModified(P2_C1 + "phs000002_c1_allConcepts_MERGED.csv") + "," + md5("c\n"),
                ""));
        // Archived with the job's reports too.
        assertThat(Files.readString(reportsDir.resolve("allConcepts_manifest.csv"))).isEqualTo(s3.content(MANIFEST));
    }

    /**
     * Managed inputs name only the study; each consent folder it has is its own manifest row,
     * pointing at that folder's MERGED file, in code order (c2 before c10).
     */
    @Test
    void lists_each_consent_folder_of_a_ready_study_as_its_own_row() throws Exception {
        managedInputs = MANAGED_HEADER + "A,phs000001,Yes\n";
        String c10 = "root/phs000001/allConcepts/c10/";
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "one\n");
        s3.put(C2 + "phs000001_allConcepts_c2.csv", "two\n");
        s3.put(c10 + "phs000001_allConcepts_c10.csv", "ten\n");
        merge();

        JobResult result = manifest();

        assertThat(result.getMetrics()).containsEntry("manifestRows", 4L);
        List<String> rows = s3.content(MANIFEST).lines().skip(2).toList();
        assertThat(rows).extracting(r -> r.split(",")[0])
                .containsExactly("phs000001.c1", "phs000001.c2", "phs000001.c10");
        assertThat(rows.get(0)).contains(MERGED_C1).endsWith(md5("one\n"));
        assertThat(rows.get(1)).contains(MERGED_C2).endsWith(md5("two\n"));
        assertThat(rows.get(2)).contains(c10 + "phs000001_c10_allConcepts_MERGED.csv").endsWith(md5("ten\n"));
    }

    @Test
    void a_new_consent_folder_joins_the_manifest_once_merged() {
        mergedData();
        manifest();
        s3.put(C2.replace("c2", "c3") + "phs000001_allConcepts_c3.csv", "three\n");

        assertThat(manifest().getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        merge();
        JobResult result = manifest();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("manifestRows", 5L);
        assertThat(s3.content(MANIFEST)).contains("phs000001.c3,");
    }

    @Test
    void the_listed_md5_is_that_of_the_merged_file_s_actual_content() throws Exception {
        s3.put(C1 + "phs000001_allConcepts_c1_a.csv", "a1\na2");
        s3.put(C1 + "phs000001_allConcepts_c1_b.csv", "b1\n");
        managedInputs = MANAGED_HEADER + "A,phs000001,Yes\n";
        merge();

        manifest();

        assertThat(s3.content(MANIFEST)).endsWith("," + md5(s3.content(MERGED_C1)) + "\n");
    }

    @Test
    void each_run_writes_a_new_version_and_keeps_the_old_one() {
        mergedData();
        manifest();
        String first = s3.versionId(MANIFEST);
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a changed\n");
        merge();

        JobResult second = manifest();

        assertThat(second.isSuccess()).isTrue();
        assertThat(s3.versionId(MANIFEST)).isNotEqualTo(first);
        assertThat(s3.versionCount(MANIFEST)).isEqualTo(2);
        assertThat(s3.metadata(MANIFEST)).containsEntry("row-count", "4").containsEntry("run-id", "unit-manifest");
    }

    @Test
    void a_folder_with_no_allconcepts_files_is_left_out_with_a_warning() {
        mergedData();
        s3.put(C2.replace("c2", "c3") + "readme.txt", "x\n");

        JobResult result = manifest();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getMetrics()).containsEntry("manifestRows", 4L);
        assertThat(codes(result)).contains("NO_SOURCES");
    }

    // ---- refusing to write ---------------------------------------------------------

    @Test
    void refuses_when_a_ready_consent_group_has_no_merged_file() {
        mergedData();
        manifest();
        String previous = s3.versionId(MANIFEST);
        s3.put(C2.replace("c2", "c3") + "phs000001_allConcepts_c3.csv", "new consent group\n");

        JobResult result = manifest();

        assertNotWritten(result, previous);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("MERGED_MISSING") && i.context().equals(
                        Map.of("studyId", "phs000001", "consent", "c3")));
    }

    @Test
    void refuses_when_a_source_was_added_since_the_merge() {
        mergedData();
        s3.put(C1 + "phs000001_allConcepts_c1_extra.csv", "late\n");

        JobResult result = manifest();

        assertNotWritten(result, null);
        assertThat(codes(result)).contains("MERGED_STALE");
    }

    @Test
    void refuses_when_a_source_was_overwritten_since_the_merge() {
        mergedData();
        s3.put(C1 + "phs000001_allConcepts_c1.csv", "a\n");

        assertThat(codes(manifest())).contains("MERGED_STALE");
    }

    @Test
    void refuses_when_a_source_was_deleted_since_the_merge() {
        mergedData();
        s3.put(C1 + "phs000001_allConcepts_c1_b.csv", "b\n");
        merge();
        s3.delete(C1 + "phs000001_allConcepts_c1_b.csv");

        assertThat(codes(manifest())).contains("MERGED_STALE");
    }

    @Test
    void refuses_when_the_merged_file_was_replaced_outside_the_merge() {
        mergedData();
        s3.put(MERGED_C1, "hand edited\n");

        JobResult result = manifest();

        assertNotWritten(result, null);
        assertThat(codes(result)).contains("MERGED_NOT_FINGERPRINTED");
    }

    @Test
    void refuses_when_the_merged_file_has_no_md5() {
        mergedData();
        Map<String, String> meta = new java.util.HashMap<>(s3.metadata(MERGED_C1));
        meta.remove("content-md5");
        s3.put(MERGED_C1, "a\n", meta);

        assertThat(codes(manifest())).contains("MD5_MISSING");
    }

    @Test
    void refuses_when_a_merged_file_has_no_sources_left() {
        mergedData();
        s3.delete(C1 + "phs000001_allConcepts_c1.csv");

        assertThat(codes(manifest())).contains("MERGED_WITHOUT_SOURCES");
    }

    @Test
    void refuses_when_a_ready_study_has_no_consent_folders() {
        mergedData();
        managedInputs = MANAGED_HEADER + "A,phs000001,Yes\nB,phs000002,Yes\nD,phs000004,Yes\n";

        JobResult result = manifest();

        assertNotWritten(result, null);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("NO_CONSENT_FOLDERS") && i.message().contains("phs000004"));
    }

    @Test
    void refuses_when_no_study_is_ready() {
        managedInputs = MANAGED_HEADER + "A,phs000001,No\n";

        JobResult result = manifest();

        assertNotWritten(result, null);
        assertThat(codes(result)).contains("NO_READY_STUDIES");
    }

    @Test
    void reports_every_problem_at_once() {
        mergedData();
        s3.put(C1 + "phs000001_allConcepts_c1_extra.csv", "late\n");
        s3.put(P2_C1 + "phs000002_c1_allConcepts_MERGED.csv", "hand edited\n");

        JobResult result = manifest();

        assertThat(codes(result)).contains("MERGED_STALE", "MERGED_NOT_FINGERPRINTED");
        assertThat(result.getMetrics()).containsEntry("problems", 2L);
    }

    @Test
    void fails_with_config_error_when_the_bucket_is_not_versioned() {
        mergedData();
        s3.versioning = BucketVersioningStatus.SUSPENDED;

        JobResult result = manifest();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(s3.exists(MANIFEST)).isFalse();
    }

    @Test
    void fails_with_infrastructure_error_when_s3_does_not_version_the_write() {
        mergedData();
        s3.omitVersionIds = true;

        JobResult result = manifest();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(result.getErrorMessage()).contains("no version id");
    }

    @Test
    void fails_input_validation_when_input_is_not_s3() {
        JobResult result = executor.run(new GenerateAllConceptsManifestJob(new IoResolver(s3.client), s3.client,
                managedInputsService(tempDir, managedInputs)),
                Map.of("input", "/local", "global-allconcepts", "/local/global/"), "unit");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .filteredOn(i -> i.code().equals("S3_REQUIRED")).hasSize(2);
    }

    // ---- global AllConcepts ----------------------------------------------------------

    @Test
    void lists_the_current_global_version_first_with_the_md5_of_that_version() throws Exception {
        mergedData();
        String oldVersion = s3.versionId(GLOBAL);
        s3.put(GLOBAL, "newer global rows\n");

        JobResult result = manifest();

        String globalRow = s3.content(MANIFEST).lines().skip(1).findFirst().orElseThrow();
        assertThat(globalRow).isEqualTo("global,s3://bucket/" + GLOBAL + "," + s3.versionId(GLOBAL) + ","
                + s3.lastModified(GLOBAL) + "," + md5("newer global rows\n"));
        assertThat(globalRow).doesNotContain(oldVersion);
        assertThat(result.getMetrics().get("globalAllConcepts").toString()).contains(s3.versionId(GLOBAL));
    }

    @Test
    void accepts_the_global_file_s_full_uri_as_well_as_its_folder() {
        mergedData();

        JobResult result = executor.run(new GenerateAllConceptsManifestJob(new IoResolver(s3.client), s3.client,
                        managedInputsService(tempDir, managedInputs)),
                Map.of("input", ROOT, "global-allconcepts", "s3://bucket/" + GLOBAL), "unit-manifest");

        assertThat(result.isSuccess()).isTrue();
        assertThat(s3.content(MANIFEST)).contains("global,s3://bucket/" + GLOBAL + ",");
    }

    @Test
    void refuses_when_the_global_file_is_missing() {
        mergedData();
        s3.delete(GLOBAL);

        JobResult result = manifest();

        assertNotWritten(result, null);
        assertThat(codes(result)).contains("GLOBAL_MISSING");
    }

    @Test
    void refuses_when_the_global_file_is_empty() {
        mergedData();
        s3.put(GLOBAL, "");

        JobResult result = manifest();

        assertNotWritten(result, null);
        assertThat(codes(result)).contains("GLOBAL_EMPTY");
    }

    /** The global file's problems are reported alongside the consent groups', not instead of them. */
    @Test
    void reports_global_and_consent_group_problems_together() {
        mergedData();
        s3.delete(GLOBAL);
        s3.put(C1 + "phs000001_allConcepts_c1_extra.csv", "late\n");

        assertThat(codes(manifest())).contains("GLOBAL_MISSING", "MERGED_STALE");
    }

    @Test
    void global_uri_appends_the_file_name_to_a_folder() {
        assertThat(GenerateAllConceptsManifestJob.globalUri("s3://b/g/")).isEqualTo("s3://b/g/global_AllConcepts.csv");
        assertThat(GenerateAllConceptsManifestJob.globalUri("s3://b/g/x.csv")).isEqualTo("s3://b/g/x.csv");
    }

    @Test
    void partition_is_the_study_id_and_consent_folder() {
        assertThat(GenerateAllConceptsManifestJob.partition("phs000001", "c1")).isEqualTo("phs000001.c1");
        assertThat(GenerateAllConceptsManifestJob.partition("phs000001", "c12")).isEqualTo("phs000001.c12");
    }
}
