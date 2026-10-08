package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "etl.jobs.merge-allconcepts.enabled", havingValue = "true")
public class MergeAllConceptsJob extends AbstractJob<MergeAllConceptsJob.Output> {

    private static final String MERGED_SUFFIX = "_allConcepts_MERGED.csv";
    private static final String ALL_CONCEPTS_MARKER = "_allConcepts_";
    /** Per-study subfolder holding the per-consent folders; {@code legacy/allConcepts/} is never scanned. */
    static final String ALL_CONCEPTS_DIR = "allConcepts";
    private static final Pattern STUDY_ID_PATTERN = Pattern.compile("phs\\d{6}");
    private static final Pattern CONSENT_FOLDER_PATTERN = Pattern.compile("c[^/]+");

    private final IoResolver io;
    private final S3Client s3;

    public MergeAllConceptsJob(IoResolver io, S3Client s3) {
        this.io = io;
        this.s3 = s3;
    }

    @Override
    public String name() {
        return "merge-allconcepts";
    }

    @Override
    public JobType type() {
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(
                        ParamSpec.required("input",
                                "S3 prefix containing {study_id}/allConcepts/c{consent}/ folders with allConcepts files",
                                "s3://bucket/output/"),
                        ParamSpec.optional("study-ids",
                                "Comma-separated study ids to check. Blank discovers all study folders under input.",
                                "")),
                List.of("One {study_id}_c{code}_allConcepts_MERGED.csv per consent folder that needed merging"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        String input = ctx.require("input");
        if (!io.isS3(input)) {
            report.error("S3_REQUIRED", "input must be an s3:// URI for versioning support", "--input");
        }
    }

    @Override
    protected Output execute(JobContext ctx) {
        String inputBase = normalizeDir(ctx.require("input"));
        Set<String> studyFilter = parseStudyFilter(ctx.get("study-ids").orElse(""));

        // Each study folder also holds legacy inputs (legacy/allConcepts/, rawData/, ...), so only
        // {study_id}/allConcepts/ is listed, never the study folder as a whole.
        List<String> studyIds = studyFilter.isEmpty()
                ? io.listDirectoryNames(inputBase).stream()
                        .filter(name -> STUDY_ID_PATTERN.matcher(name).matches())
                        .sorted()
                        .toList()
                : studyFilter.stream().sorted().toList();
        log.info("Checking {} study folder(s) under {}", studyIds.size(), inputBase);

        Map<String, List<String>> sourceFilesByFolder = new LinkedHashMap<>();
        for (String studyId : studyIds) {
            String studyAllConcepts = studyId + "/" + ALL_CONCEPTS_DIR + "/";
            for (String relativePath : io.listFilesRecursive(inputBase + studyAllConcepts)) {
                // Only files directly inside a consent folder: c{code}/{file}
                String[] parts = relativePath.split("/");
                if (parts.length != 2 || !CONSENT_FOLDER_PATTERN.matcher(parts[0]).matches()) {
                    continue;
                }
                String fileName = parts[1];
                if (!fileName.contains(ALL_CONCEPTS_MARKER) || fileName.endsWith(MERGED_SUFFIX)) {
                    continue;
                }
                sourceFilesByFolder
                        .computeIfAbsent(studyAllConcepts + parts[0] + "/", k -> new ArrayList<>())
                        .add(fileName);
            }
        }

        log.info("Discovered {} consent folder(s) containing allConcepts files{}",
                sourceFilesByFolder.size(),
                studyFilter.isEmpty() ? "" : " (filtered to studies: " + studyFilter + ")");

        Map<String, MergeAction> actions = new LinkedHashMap<>();
        long foldersMerged = 0;
        long foldersSkipped = 0;
        long totalFilesMerged = 0;

        for (Map.Entry<String, List<String>> entry : sourceFilesByFolder.entrySet()) {
            String folder = entry.getKey();
            List<String> sourceFiles = entry.getValue();
            String consentFolderUri = inputBase + folder;

            String mergedFileName = buildMergedFileName(folder);
            String mergedUri = consentFolderUri + mergedFileName;

            MergeReason reason = checkStaleness(consentFolderUri, mergedUri, mergedFileName, sourceFiles);

            if (reason == MergeReason.NONE) {
                log.debug("Merged file {} is up to date", mergedUri);
                foldersSkipped++;
                continue;
            }

            log.info("Merge needed for {} — reason: {}, {} source file(s)",
                    consentFolderUri, reason, sourceFiles.size());

            mergeFiles(consentFolderUri, sourceFiles, mergedUri);
            foldersMerged++;
            totalFilesMerged += sourceFiles.size();

            String label = folder.endsWith("/") ? folder.substring(0, folder.length() - 1) : folder;
            actions.put(label, new MergeAction(reason, sourceFiles.size()));
        }

        return new Output(sourceFilesByFolder.size(), foldersMerged, foldersSkipped, totalFilesMerged, actions);
    }

    /** {@code {study_id}/allConcepts/c{code}/} &rarr; {@code {study_id}_c{code}_allConcepts_MERGED.csv}. */
    static String buildMergedFileName(String folderPath) {
        String studyId = null;
        String consentDir = null;
        String[] segments = folderPath.split("/");
        for (int i = 0; i + 2 < segments.length; i++) {
            if (STUDY_ID_PATTERN.matcher(segments[i]).matches() && segments[i + 1].equals(ALL_CONCEPTS_DIR)) {
                studyId = segments[i];
                consentDir = segments[i + 2];
            }
        }
        if (studyId != null && consentDir != null) {
            return studyId + "_" + consentDir + MERGED_SUFFIX;
        }
        return "allConcepts_MERGED.csv";
    }

    private MergeReason checkStaleness(String consentPrefix, String mergedUri,
                                       String mergedFileName, List<String> sourceFiles) {
        if (!io.exists(mergedUri)) {
            return MergeReason.MISSING;
        }

        String[] mergedParts = parseS3Uri(mergedUri);
        Instant mergedLastModified;
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(mergedParts[0]).key(mergedParts[1]).build());
            mergedLastModified = head.lastModified();
        } catch (NoSuchKeyException e) {
            return MergeReason.MISSING;
        }

        String[] prefixParts = parseS3Prefix(consentPrefix);
        for (String fileName : sourceFiles) {
            String fileKey = prefixParts[1] + fileName;
            try {
                HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                        .bucket(prefixParts[0]).key(fileKey).build());
                if (head.lastModified().isAfter(mergedLastModified)) {
                    log.info("Source file {} is newer than merged file (source: {}, merged: {})",
                            fileName, head.lastModified(), mergedLastModified);
                    return MergeReason.STALE;
                }
            } catch (NoSuchKeyException e) {
                log.warn("Source file {} listed but not found via HeadObject — may have been deleted", fileName);
            }
        }

        if (hasDeletesSince(prefixParts[0], prefixParts[1], mergedLastModified, mergedFileName)) {
            return MergeReason.DELETIONS;
        }

        return MergeReason.NONE;
    }

    private boolean hasDeletesSince(String bucket, String prefix, Instant since, String mergedFileName) {
        String keyMarker = null;
        String versionIdMarker = null;

        do {
            ListObjectVersionsRequest.Builder reqBuilder = ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix(prefix);
            if (keyMarker != null) {
                reqBuilder.keyMarker(keyMarker).versionIdMarker(versionIdMarker);
            }

            ListObjectVersionsResponse resp = s3.listObjectVersions(reqBuilder.build());

            for (DeleteMarkerEntry dm : resp.deleteMarkers()) {
                String fileName = dm.key().substring(prefix.length());
                if (fileName.equals(mergedFileName)) {
                    continue;
                }
                if (!fileName.contains(ALL_CONCEPTS_MARKER)) {
                    continue;
                }
                if (dm.lastModified().isAfter(since)) {
                    log.info("Delete marker found for {} at {} (after merged file at {})",
                            dm.key(), dm.lastModified(), since);
                    return true;
                }
            }

            if (resp.isTruncated()) {
                keyMarker = resp.nextKeyMarker();
                versionIdMarker = resp.nextVersionIdMarker();
            } else {
                break;
            }
        } while (true);

        return false;
    }

    private void mergeFiles(String consentPrefix, List<String> sourceFiles, String mergedUri) {
        io.writeOutput(mergedUri, (OutputStream out) -> {
            for (String fileName : sourceFiles) {
                String sourceUri = consentPrefix + fileName;
                try (InputStream in = io.openInput(sourceUri)) {
                    in.transferTo(out);
                } catch (IOException e) {
                    throw new InfrastructureException("Failed to read source file: " + sourceUri, e);
                }
            }
        });
        log.info("Wrote merged file {} from {} source(s)", mergedUri, sourceFiles.size());
    }

    private static Set<String> parseStudyFilter(String studyIds) {
        if (studyIds == null || studyIds.isBlank()) {
            return Set.of();
        }
        return Set.of(studyIds.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static String normalizeDir(String dir) {
        return dir.endsWith("/") ? dir : dir + "/";
    }

    private static final String S3_PREFIX = "s3://";

    static String[] parseS3Uri(String uri) {
        String rest = uri.substring(S3_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 1 || slash == rest.length() - 1) {
            throw new ConfigException("Malformed S3 URI (expected s3://bucket/key): " + uri);
        }
        return new String[]{rest.substring(0, slash), rest.substring(slash + 1)};
    }

    static String[] parseS3Prefix(String uri) {
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

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.foldersMerged() == 0 && output.foldersSkipped() == 0) {
            report.warning("NO_FOLDERS", "No consent folders with allConcepts files found under the input prefix");
        }
        report.info("SUMMARY", output.foldersMerged() + " folder(s) merged, "
                + output.foldersSkipped() + " already up to date");
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("consentFoldersDiscovered", output.consentFoldersDiscovered())
                .metric("foldersMerged", output.foldersMerged())
                .metric("foldersSkipped", output.foldersSkipped())
                .metric("totalFilesMerged", output.totalFilesMerged())
                .metric("actions", output.actions());
    }

    public record Output(
            long consentFoldersDiscovered,
            long foldersMerged,
            long foldersSkipped,
            long totalFilesMerged,
            Map<String, MergeAction> actions
    ) {}

    public record MergeAction(MergeReason reason, int sourceFileCount) {}

    public enum MergeReason {
        NONE, MISSING, STALE, DELETIONS
    }
}
