package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.AllConceptsFolders.ConsentFolder;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.AllConceptsFolders.FileVersion;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PERMANENT. Keeps one {@code {study_id}_c{code}_allConcepts_MERGED.csv} per consent folder
 * ({@code {input}{study_id}/allConcepts/c{code}/}) of every study marked ready in managed inputs,
 * concatenating the folder's {@code *_allConcepts_*} source files. Per folder:
 *
 * <ul>
 *   <li>no MERGED file: merge;</li>
 *   <li>a source added, overwritten, or deleted since the MERGED file was written: re-merge,
 *       overwriting it;</li>
 *   <li>a MERGED file but no sources left: delete it (a delete marker; the old version stays
 *       recoverable), since there is nothing to merge;</li>
 *   <li>otherwise: skip.</li>
 * </ul>
 *
 * <p>"Since it was written" is decided by version ids, not timestamps: each MERGED file carries a
 * {@linkplain AllConceptsFolders#fingerprint fingerprint} of the source versions it was built from,
 * and the sources are read at exactly those versions, so a file written while a merge runs is
 * never mistaken for merged. It also carries the MD5 of its content, for
 * {@link GenerateAllConceptsManifestJob}. A MERGED file without a fingerprint (written before this
 * scheme) is re-merged once. The bucket must have versioning enabled.
 *
 * <p>Sources are concatenated in name order, each ending with a newline, so a source missing its
 * final newline cannot run its last row into the next file's first.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.merge-allconcepts.enabled", havingValue = "true")
public class MergeAllConceptsJob extends AbstractJob<MergeAllConceptsJob.Output> {

    private final IoResolver io;
    private final S3Client s3;
    private final AllConceptsFolders folders;
    private final ManagedInputsService managedInputsService;

    public MergeAllConceptsJob(IoResolver io, S3Client s3, ManagedInputsService managedInputsService) {
        this.io = io;
        this.s3 = s3;
        this.folders = new AllConceptsFolders(s3);
        this.managedInputsService = managedInputsService;
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
                                "S3 prefix (versioned bucket) containing {study_id}/allConcepts/c{consent}/ folders",
                                "s3://bucket/output/"),
                        ParamSpec.optional("study-ids",
                                "Comma-separated study ids to check instead of every study marked ready in managed inputs",
                                "")),
                List.of("One up-to-date {study_id}_c{code}_allConcepts_MERGED.csv per consent folder of each ready study"));
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
        String base = AllConceptsFolders.normalizeDir(ctx.require("input"));
        folders.requireVersioning(AllConceptsFolders.parseS3Prefix(base)[0]);

        Set<String> filter = parseStudyIds(ctx.get("study-ids").orElse(""));
        List<String> studyIds;
        List<String> notReady = new ArrayList<>();
        if (filter.isEmpty()) {
            studyIds = managedInputsService.read().stream()
                    .filter(ManagedInputRow::isReady).map(ManagedInputRow::studyId)
                    // A study on two ready rows must be merged once, or its second pass reports
                    // the folders it just merged as UP_TO_DATE.
                    .distinct().sorted().toList();
        } else {
            studyIds = filter.stream().sorted().toList();
            Set<String> ready = new LinkedHashSet<>();
            managedInputsService.read().stream().filter(ManagedInputRow::isReady)
                    .forEach(r -> ready.add(r.studyId()));
            studyIds.stream().filter(id -> !ready.contains(id)).forEach(notReady::add);
        }
        log.info("Checking {} {} stud{} under {}", studyIds.size(), filter.isEmpty() ? "ready" : "requested",
                studyIds.size() == 1 ? "y" : "ies", base);

        Map<String, FolderResult> results = new LinkedHashMap<>();
        List<String> studiesWithoutFolders = new ArrayList<>();
        for (String studyId : studyIds) {
            Map<String, ConsentFolder> studyFolders = folders.scanStudy(base, studyId);
            if (studyFolders.isEmpty()) {
                log.warn("Study {} has no consent folders under {}{}/{}/", studyId, base, studyId,
                        AllConceptsFolders.ALL_CONCEPTS_DIR);
                studiesWithoutFolders.add(studyId);
            }
            for (ConsentFolder folder : studyFolders.values()) {
                results.put(folder.label(), process(folder));
            }
        }
        return new Output(studyIds, studiesWithoutFolders, notReady, results);
    }

    private FolderResult process(ConsentFolder folder) {
        String mergedUri = folder.uri() + folder.mergedFileName();
        String fingerprint = AllConceptsFolders.fingerprint(folder.sources());

        if (folder.sources().isEmpty() && folder.merged() == null) {
            log.warn("{} has no allConcepts source files, only {}", folder.label(), folder.ignored());
            return new FolderResult(Action.NO_SOURCES, 0, null, null, folder.ignored());
        }
        if (folder.sources().isEmpty()) {
            log.warn("{} has a MERGED file but no source files; deleting it", folder.label());
            io.delete(mergedUri);
            return new FolderResult(Action.REMOVED_NO_SOURCES, 0, null, null, folder.ignored());
        }

        Action action;
        if (folder.merged() == null) {
            action = Action.MISSING;
        } else {
            Map<String, String> meta = folders.metadata(folder.bucket(), folder.mergedKey(), folder.merged().versionId());
            String recorded = meta.get(AllConceptsFolders.META_FINGERPRINT);
            if (recorded == null || meta.get(AllConceptsFolders.META_MD5) == null) {
                action = Action.NO_FINGERPRINT;
            } else if (!recorded.equals(fingerprint)) {
                action = Action.SOURCES_CHANGED;
            } else {
                log.debug("{} is up to date", mergedUri);
                return new FolderResult(Action.UP_TO_DATE, folder.sources().size(), folder.merged().versionId(),
                        meta.get(AllConceptsFolders.META_MD5), folder.ignored());
            }
        }

        log.info("Merging {} ({}): {} source file(s)", folder.label(), action, folder.sources().size());
        Merged merged = merge(folder, fingerprint, mergedUri);
        return new FolderResult(action, folder.sources().size(), merged.versionId(), merged.md5(), folder.ignored());
    }

    /** Concatenates the sources at their listed versions into a temp file, then uploads it with its metadata. */
    private Merged merge(ConsentFolder folder, String fingerprint, String mergedUri) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("allconcepts-merge-", ".csv");
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            try (OutputStream out = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)), md5)) {
                for (FileVersion source : folder.sources()) {
                    appendSource(folder.bucket(), source, out);
                }
            }
            String md5Hex = HexFormat.of().formatHex(md5.digest());
            Map<String, String> metadata = Map.of(
                    AllConceptsFolders.META_FINGERPRINT, fingerprint,
                    AllConceptsFolders.META_SOURCE_COUNT, String.valueOf(folder.sources().size()),
                    AllConceptsFolders.META_MD5, md5Hex);
            String versionId = io.writeOutputFile(mergedUri, tmp, metadata);
            log.info("Wrote {} from {} source(s) (md5 {}, version {})", mergedUri, folder.sources().size(),
                    md5Hex, versionId);
            return new Merged(versionId, md5Hex);
        } catch (IOException e) {
            throw new InfrastructureException("Failed to merge " + folder.label(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.warn("Could not delete temp file {}: {}", tmp, e.getMessage());
                }
            }
        }
    }

    /** Copies one source version, adding a final newline if it lacks one. */
    private void appendSource(String bucket, FileVersion source, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int last = -1;
        try (InputStream in = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(source.key()).versionId(source.versionId()).build())) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                last = buffer[n - 1];
            }
        } catch (RuntimeException e) {
            throw new InfrastructureException("Failed to read source s3://" + bucket + "/" + source.key()
                    + " (version " + source.versionId() + ")", e);
        }
        if (last != -1 && last != '\n') {
            out.write('\n');
        }
    }

    private static Set<String> parseStudyIds(String raw) {
        Set<String> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                ids.add(part.trim());
            }
        }
        return ids;
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.studyIds().isEmpty()) {
            report.warning("NO_READY_STUDIES", "No studies are marked ready in managed inputs; nothing to merge");
        } else if (output.folders().isEmpty()) {
            report.warning("NO_FOLDERS", "None of the " + output.studyIds().size()
                    + " studies has a consent folder with allConcepts files");
        }
        for (String studyId : output.studiesWithoutFolders()) {
            report.add(ValidationIssue.warning("STUDY_HAS_NO_CONSENT_FOLDERS", studyId + " has no "
                    + AllConceptsFolders.ALL_CONCEPTS_DIR + "/c{code}/ folders", Map.of("studyId", studyId)));
        }
        if (!output.notReady().isEmpty()) {
            report.info("STUDIES_NOT_READY", "Merged on request though not marked ready: " + output.notReady());
        }
        output.folders().forEach((label, r) -> {
            if (r.action() == Action.NO_SOURCES) {
                report.add(ValidationIssue.warning("CONSENT_FOLDER_WITHOUT_SOURCES", label
                        + ": no allConcepts source files to merge", Map.of("folder", label)));
            }
            if (r.action() == Action.REMOVED_NO_SOURCES) {
                report.add(ValidationIssue.warning("MERGED_WITHOUT_SOURCES_REMOVED", label
                        + ": every source file is gone; its MERGED file was deleted", Map.of("folder", label)));
            }
            if (!r.ignoredFiles().isEmpty()) {
                report.info("IGNORED_FILES", label + ": not merged (no '" + AllConceptsFolders.ALL_CONCEPTS_MARKER
                        + "' in the name, or a MERGED file for another folder): " + r.ignoredFiles());
            }
        });
        report.info("SUMMARY", count(output, Action::merges) + " folder(s) merged, "
                + count(output, a -> a == Action.UP_TO_DATE) + " already up to date, "
                + count(output, a -> a == Action.REMOVED_NO_SOURCES) + " removed");
    }

    private static long count(Output output, java.util.function.Predicate<Action> p) {
        return output.folders().values().stream().filter(r -> p.test(r.action())).count();
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        Map<String, Object> actions = new LinkedHashMap<>();
        output.folders().forEach((label, r) -> actions.put(label,
                Map.of("action", r.action().name(), "sourceFileCount", r.sourceFileCount())));
        builder.metric("studiesChecked", (long) output.studyIds().size())
                .metric("consentFoldersDiscovered", (long) output.folders().size())
                .metric("foldersMerged", count(output, Action::merges))
                .metric("foldersSkipped", count(output, a -> a == Action.UP_TO_DATE))
                .metric("foldersRemoved", count(output, a -> a == Action.REMOVED_NO_SOURCES))
                .metric("totalFilesMerged", output.folders().values().stream()
                        .filter(r -> r.action().merges()).mapToLong(FolderResult::sourceFileCount).sum())
                .metric("studiesWithoutFolders", output.studiesWithoutFolders())
                .metric("actions", actions);
    }

    /** What happened to one consent folder. */
    public enum Action {
        /** MERGED file matches the current sources; left alone. */
        UP_TO_DATE,
        /** No MERGED file; merged. */
        MISSING,
        /** MERGED file predates fingerprints (or lacks its md5); re-merged. */
        NO_FINGERPRINT,
        /** A source was added, overwritten, or deleted since the merge; re-merged. */
        SOURCES_CHANGED,
        /** MERGED file left with no sources; deleted. */
        REMOVED_NO_SOURCES,
        /** Neither sources nor a MERGED file, only ignored files; nothing to do. */
        NO_SOURCES;

        public boolean merges() {
            return this == MISSING || this == NO_FINGERPRINT || this == SOURCES_CHANGED;
        }
    }

    /**
     * @param mergedVersionId  the MERGED file's current version (null once removed)
     * @param md5              its content MD5 (null once removed)
     */
    public record FolderResult(Action action, int sourceFileCount, String mergedVersionId, String md5,
                               List<String> ignoredFiles) {
    }

    private record Merged(String versionId, String md5) {
    }

    /**
     * @param studyIds               the studies checked
     * @param studiesWithoutFolders  of those, the ones with no consent folder at all
     * @param notReady               requested via {@code --study-ids} though not marked ready
     * @param folders                folder label ({@code phs000001/allConcepts/c1}) &rarr; result
     */
    public record Output(List<String> studyIds, List<String> studiesWithoutFolders, List<String> notReady,
                         Map<String, FolderResult> folders) {
    }
}
