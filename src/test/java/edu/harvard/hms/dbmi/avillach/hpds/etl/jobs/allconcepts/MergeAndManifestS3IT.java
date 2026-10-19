package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.MergeAllConceptsJobTest.md5;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MergeAllConceptsJob} then {@link GenerateAllConceptsManifestJob} against LocalStack S3, so
 * the version-id fingerprints, object metadata and versioning checks are exercised against a real S3
 * API rather than {@link FakeVersionedS3}.
 */
class MergeAndManifestS3IT extends AbstractIntegrationTest {

    private static final String VERSIONED = "hpds-etl-versioned";
    private static final String UNVERSIONED = "hpds-etl-unversioned";

    @Autowired
    private MergeAllConceptsJob merge;
    @Autowired
    private GenerateAllConceptsManifestJob manifest;
    @Autowired
    private JobExecutor executor;
    @Autowired
    private S3Client s3;
    @Autowired
    private EtlProperties properties;

    /** A fresh prefix per test, so tests never see each other's objects. */
    private String prefix;

    @BeforeEach
    void setUp() throws Exception {
        List<String> buckets = s3.listBuckets().buckets().stream().map(b -> b.name()).toList();
        if (!buckets.contains(VERSIONED)) {
            s3.createBucket(CreateBucketRequest.builder().bucket(VERSIONED).build());
            s3.putBucketVersioning(PutBucketVersioningRequest.builder().bucket(VERSIONED)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED).build()).build());
        }
        if (!buckets.contains(UNVERSIONED)) {
            s3.createBucket(CreateBucketRequest.builder().bucket(UNVERSIONED).build());
        }
        prefix = "run-" + UUID.randomUUID() + "/";
        put("global_allconcepts/global_AllConcepts.csv", "global\n");
        managedInputs("A,phs000001,Yes\nB,phs000002,No\n");
    }

    private void managedInputs(String rows) throws Exception {
        Path file = Files.createTempFile("managed_inputs", ".csv");
        Files.writeString(file, "Study Abbreviated Name,Study Identifier,Data is ready to process\n" + rows);
        properties.getManagedInputs().setUri(file.toString());
    }

    private void put(String key, String content) {
        s3.putObject(PutObjectRequest.builder().bucket(VERSIONED).key(prefix + key).build(),
                RequestBody.fromString(content));
    }

    private String get(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(VERSIONED).key(prefix + key).build())
                .asString(StandardCharsets.UTF_8);
    }

    private HeadObjectResponse head(String key) {
        return s3.headObject(HeadObjectRequest.builder().bucket(VERSIONED).key(prefix + key).build());
    }

    private String root() {
        return "s3://" + VERSIONED + "/" + prefix;
    }

    private JobResult runMerge() {
        return executor.run(merge, Map.of("input", root()), "it-merge");
    }

    private JobResult runManifest() {
        return executor.run(manifest, Map.of("input", root(), "global-allconcepts", root() + "global_allconcepts/"),
                "it-manifest");
    }

    private static final String C1 = "phs000001/allConcepts/c1/";
    private static final String MERGED = C1 + "phs000001_c1_allConcepts_MERGED.csv";

    @Test
    void merges_then_publishes_a_manifest_naming_the_exact_merged_version() throws Exception {
        put(C1 + "phs000001_allConcepts_c1_a.csv", "a1\n");
        put(C1 + "phs000001_allConcepts_c1_b.csv", "b1");
        put("phs000002/allConcepts/c1/phs000002_allConcepts_c1.csv", "not ready\n");

        assertThat(runMerge().getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(get(MERGED)).isEqualTo("a1\nb1\n");
        HeadObjectResponse merged = head(MERGED);
        assertThat(merged.metadata()).containsEntry("content-md5", md5("a1\nb1\n")).containsEntry("source-count", "2");

        JobResult result = runManifest();

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        HeadObjectResponse global = head("global_allconcepts/global_AllConcepts.csv");
        assertThat(get("allConcepts_manifest.csv")).isEqualTo(
                "partition,s3_uri,version_id,last_updated,md5\n"
                        + "global," + root() + "global_allconcepts/global_AllConcepts.csv," + global.versionId() + ","
                        + global.lastModified() + "," + md5("global\n") + "\n"
                        + "phs000001.c1," + root() + MERGED + "," + merged.versionId() + ","
                        + merged.lastModified() + "," + md5("a1\nb1\n") + "\n");
        assertThat(result.getMetrics()).containsEntry("manifestVersionId", head("allConcepts_manifest.csv").versionId());
    }

    @Test
    void a_second_merge_skips_and_a_changed_source_re_merges() {
        put(C1 + "phs000001_allConcepts_c1.csv", "old\n");
        runMerge();
        String firstVersion = head(MERGED).versionId();

        assertThat(runMerge().getMetrics()).containsEntry("foldersSkipped", 1L);
        assertThat(head(MERGED).versionId()).isEqualTo(firstVersion);

        put(C1 + "phs000001_allConcepts_c1.csv", "new\n");
        assertThat(runMerge().getMetrics()).containsEntry("foldersMerged", 1L);
        assertThat(get(MERGED)).isEqualTo("new\n");
    }

    @Test
    void a_deleted_source_is_detected_by_the_manifest_then_fixed_by_the_merge() {
        put(C1 + "phs000001_allConcepts_c1_a.csv", "a\n");
        put(C1 + "phs000001_allConcepts_c1_b.csv", "b\n");
        runMerge();
        s3.deleteObject(DeleteObjectRequest.builder().bucket(VERSIONED)
                .key(prefix + C1 + "phs000001_allConcepts_c1_b.csv").build());

        JobResult stale = runManifest();
        assertThat(stale.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(stale.getOutputValidation().getIssues()).extracting(ValidationIssue::code).contains("MERGED_STALE");
        assertThatThrownBy(() -> head("allConcepts_manifest.csv")).isInstanceOf(NoSuchKeyException.class);

        runMerge();
        assertThat(get(MERGED)).isEqualTo("a\n");
        assertThat(runManifest().getExitCode()).isEqualTo(ExitCode.SUCCESS);
    }

    @Test
    void both_jobs_refuse_an_unversioned_bucket() {
        s3.putObject(PutObjectRequest.builder().bucket(UNVERSIONED).key(prefix + C1 + "phs000001_allConcepts_c1.csv")
                .build(), RequestBody.fromString("a\n"));
        String root = "s3://" + UNVERSIONED + "/" + prefix;

        assertThat(executor.run(merge, Map.of("input", root), "it-merge").getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(executor.run(manifest, Map.of("input", root, "global-allconcepts", root + "global/"), "it-manifest")
                .getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
    }

    /** The global file may live in another bucket; that bucket must be versioned too. */
    @Test
    void the_manifest_refuses_a_global_file_in_an_unversioned_bucket() {
        put(C1 + "phs000001_allConcepts_c1.csv", "a\n");
        runMerge();
        s3.putObject(PutObjectRequest.builder().bucket(UNVERSIONED).key(prefix + "global_AllConcepts.csv").build(),
                RequestBody.fromString("global\n"));

        JobResult result = executor.run(manifest, Map.of("input", root(),
                "global-allconcepts", "s3://" + UNVERSIONED + "/" + prefix), "it-manifest");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.CONFIG_ERROR);
        assertThat(result.getErrorMessage()).contains(UNVERSIONED);
    }
}
