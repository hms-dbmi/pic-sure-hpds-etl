package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The per-consent allConcepts folders of a study, read from a versioned S3 bucket:
 * {@code {base}{study_id}/allConcepts/c{code}/}. Shared by {@link MergeAllConceptsJob}, which
 * writes each folder's MERGED file, and {@link GenerateAllConceptsManifestJob}, which verifies it.
 *
 * <p>A MERGED file records the exact source files it was built from as a
 * {@linkplain #fingerprint fingerprint} of their S3 version ids, in its own object metadata. On a
 * versioned bucket every write gives a key a new version id, so a source added, overwritten, or
 * deleted since the merge changes the fingerprint of the folder's current sources. Comparing the
 * two needs no timestamps or delete markers.
 */
public class AllConceptsFolders {

    /** Per-study subfolder holding the per-consent folders; {@code legacy/allConcepts/} is never scanned. */
    public static final String ALL_CONCEPTS_DIR = "allConcepts";
    public static final String MERGED_SUFFIX = "_allConcepts_MERGED.csv";
    /** A source file's name contains this; anything else in a consent folder is ignored. */
    public static final String ALL_CONCEPTS_MARKER = "_allConcepts_";
    private static final Pattern CONSENT_FOLDER_PATTERN = Pattern.compile("c[^/]+");

    /** User metadata on a MERGED object (S3 lower-cases these keys). */
    public static final String META_FINGERPRINT = "source-fingerprint";
    public static final String META_SOURCE_COUNT = "source-count";
    public static final String META_MD5 = "content-md5";

    private static final String S3_PREFIX = "s3://";

    /** Consent folders in code order: {@code c2} before {@code c10}. */
    static final Comparator<String> CONSENT_ORDER =
            Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

    private final S3Client s3;

    public AllConceptsFolders(S3Client s3) {
        this.s3 = s3;
    }

    /**
     * Fails unless the bucket's versioning is {@code Enabled}: change detection and the manifest's
     * version ids both depend on it.
     */
    public void requireVersioning(String bucket) {
        BucketVersioningStatus status;
        try {
            status = s3.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).status();
        } catch (RuntimeException e) {
            throw new InfrastructureException("Could not read the versioning status of bucket " + bucket, e);
        }
        if (status != BucketVersioningStatus.ENABLED) {
            throw new ConfigException("Bucket " + bucket + " must have versioning Enabled (is "
                    + (status == null ? "never enabled" : status) + ")");
        }
    }

    /**
     * Lists a study's consent folders at their current versions. Deleted keys (whose latest version
     * is a delete marker) are absent.
     *
     * @param base {@code s3://bucket/prefix/} holding the study folders
     * @return every consent folder present ({@code c1}, {@code c2}, ...) &rarr; folder, in
     *         {@link #CONSENT_ORDER}. Managed inputs name only studies; the consent groups are
     *         whatever folders the study has.
     */
    public Map<String, ConsentFolder> scanStudy(String base, String studyId) {
        String[] bucketAndPrefix = parseS3Prefix(normalizeDir(base));
        String bucket = bucketAndPrefix[0];
        String studyPrefix = bucketAndPrefix[1] + studyId + "/" + ALL_CONCEPTS_DIR + "/";

        Map<String, FolderBuilder> builders = new TreeMap<>(CONSENT_ORDER);
        String keyMarker = null;
        String versionIdMarker = null;
        try {
            do {
                ListObjectVersionsRequest.Builder req = ListObjectVersionsRequest.builder()
                        .bucket(bucket).prefix(studyPrefix);
                if (keyMarker != null) {
                    req.keyMarker(keyMarker).versionIdMarker(versionIdMarker);
                }
                ListObjectVersionsResponse resp = s3.listObjectVersions(req.build());
                for (ObjectVersion v : resp.versions()) {
                    if (!Boolean.TRUE.equals(v.isLatest())) {
                        continue;
                    }
                    String[] parts = v.key().substring(studyPrefix.length()).split("/");
                    // Only files directly inside a consent folder: c{code}/{file}
                    if (parts.length != 2 || !CONSENT_FOLDER_PATTERN.matcher(parts[0]).matches()) {
                        continue;
                    }
                    String consentDir = parts[0];
                    builders.computeIfAbsent(consentDir, d -> new FolderBuilder(bucket, studyPrefix + d + "/",
                                    studyId, d))
                            .add(new FileVersion(parts[1], v.key(), v.versionId(), v.lastModified(), v.size()));
                }
                if (Boolean.TRUE.equals(resp.isTruncated())) {
                    keyMarker = resp.nextKeyMarker();
                    versionIdMarker = resp.nextVersionIdMarker();
                } else {
                    keyMarker = null;
                }
            } while (keyMarker != null);
        } catch (RuntimeException e) {
            throw new InfrastructureException("Failed to list versions under s3://" + bucket + "/" + studyPrefix, e);
        }

        Map<String, ConsentFolder> folders = new TreeMap<>(CONSENT_ORDER);
        builders.forEach((dir, b) -> folders.put(dir, b.build()));
        return folders;
    }

    /** The user metadata of an object's given version. */
    public Map<String, String> metadata(String bucket, String key, String versionId) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).versionId(versionId).build());
            return head.metadata();
        } catch (RuntimeException e) {
            throw new InfrastructureException("Failed to read metadata of s3://" + bucket + "/" + key
                    + " (version " + versionId + ")", e);
        }
    }

    /**
     * SHA-256 of the sources' sorted {@code name<TAB>versionId} lines: identifies the exact set of
     * source contents a MERGED file was built from.
     */
    public static String fingerprint(List<FileVersion> sources) {
        StringBuilder lines = new StringBuilder();
        sources.stream().sorted(Comparator.comparing(FileVersion::name))
                .forEach(f -> lines.append(f.name()).append('\t').append(f.versionId()).append('\n'));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(lines.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** {@code {study_id}_c{code}_allConcepts_MERGED.csv}. */
    public static String mergedFileName(String studyId, String consentDir) {
        return studyId + "_" + consentDir + MERGED_SUFFIX;
    }

    public static String normalizeDir(String dir) {
        return dir.endsWith("/") ? dir : dir + "/";
    }

    /** {@code s3://bucket[/prefix]} &rarr; {bucket, prefix}. */
    public static String[] parseS3Prefix(String uri) {
        if (!uri.startsWith(S3_PREFIX)) {
            throw new ConfigException("Not an s3:// URI: " + uri);
        }
        String rest = uri.substring(S3_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            slash = rest.length();
        }
        if (slash == 0) {
            throw new ConfigException("Malformed S3 URI (expected s3://bucket[/prefix]): " + uri);
        }
        String key = slash == rest.length() ? "" : rest.substring(slash + 1);
        return new String[]{rest.substring(0, slash), key};
    }

    /** One file's current version. */
    public record FileVersion(String name, String key, String versionId, Instant lastModified, Long size) {
    }

    /**
     * One consent folder at its current versions.
     *
     * @param prefix   key prefix of the folder, ending in {@code /}
     * @param sources  {@code *_allConcepts_*} files other than any MERGED file, sorted by name
     * @param merged   this folder's {@link #mergedFileName MERGED file}, or null if it has none
     * @param ignored  everything else: names without the allConcepts marker, and MERGED files named
     *                 for another study or consent
     */
    public record ConsentFolder(String bucket, String prefix, String studyId, String consentDir,
                                List<FileVersion> sources, FileVersion merged, List<String> ignored) {

        public String mergedFileName() {
            return AllConceptsFolders.mergedFileName(studyId, consentDir);
        }

        public String mergedKey() {
            return prefix + mergedFileName();
        }

        public String uri() {
            return S3_PREFIX + bucket + "/" + prefix;
        }

        public String label() {
            return studyId + "/" + ALL_CONCEPTS_DIR + "/" + consentDir;
        }
    }

    private static final class FolderBuilder {
        private final String bucket;
        private final String prefix;
        private final String studyId;
        private final String consentDir;
        private final List<FileVersion> sources = new ArrayList<>();
        private final List<String> ignored = new ArrayList<>();
        private FileVersion merged;

        FolderBuilder(String bucket, String prefix, String studyId, String consentDir) {
            this.bucket = bucket;
            this.prefix = prefix;
            this.studyId = studyId;
            this.consentDir = consentDir;
        }

        void add(FileVersion f) {
            if (f.name().equals(mergedFileName(studyId, consentDir))) {
                merged = f;
            } else if (f.name().contains(ALL_CONCEPTS_MARKER) && !f.name().endsWith(MERGED_SUFFIX)) {
                sources.add(f);
            } else {
                ignored.add(f.name());
            }
        }

        ConsentFolder build() {
            sources.sort(Comparator.comparing(FileVersion::name));
            ignored.sort(Comparator.naturalOrder());
            return new ConsentFolder(bucket, prefix, studyId, consentDir, List.copyOf(sources), merged,
                    List.copyOf(ignored));
        }
    }
}
