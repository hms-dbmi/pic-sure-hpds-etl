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
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
 *
 * <p>Folders are checked and merged {@code --parallelism} at a time (default
 * {@value #DEFAULT_PARALLELISM}), largest first, so the biggest are not left running alone at the
 * end. Folders are independent, so one that fails does not stop the others: every folder is
 * attempted, the ones that merged stay merged (and are up to date on a retry), and the first
 * failure, in folder order, fails the run with the rest attached as suppressed.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.merge-allconcepts.enabled", havingValue = "true")
public class MergeAllConceptsJob extends AbstractJob<MergeAllConceptsJob.Output> {

    static final int DEFAULT_PARALLELISM = 8;
    /**
     * Each merge in flight holds up to two S3 connections (a source read and the upload; the SDK
     * pools 50) and, above the multipart threshold, one upload part in memory (see IoResolver).
     */
    static final int MAX_PARALLELISM = 16;

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
                                ""),
                        ParamSpec.optional("parallelism",
                                "Consent folders merged at once (1-" + MAX_PARALLELISM + "; default "
                                        + DEFAULT_PARALLELISM + ")",
                                String.valueOf(DEFAULT_PARALLELISM))),
                List.of("One up-to-date {study_id}_c{code}_allConcepts_MERGED.csv per consent folder of each ready study"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        String input = ctx.require("input");
        if (!io.isS3(input)) {
            report.error("S3_REQUIRED", "input must be an s3:// URI for versioning support", "--input");
        }
        ctx.get("parallelism").ifPresent(p -> {
            try {
                int n = Integer.parseInt(p);
                if (n < 1 || n > MAX_PARALLELISM) {
                    report.error("BAD_PARALLELISM", "parallelism must be between 1 and " + MAX_PARALLELISM
                            + ", got: " + p, "--parallelism");
                }
            } catch (NumberFormatException e) {
                report.error("BAD_PARALLELISM", "parallelism must be an integer, got: " + p, "--parallelism");
            }
        });
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
        int parallelism = Integer.parseInt(ctx.get("parallelism", String.valueOf(DEFAULT_PARALLELISM)));
        log.info("Checking {} {} stud{} under {}, {} folder(s) at a time", studyIds.size(),
                filter.isEmpty() ? "ready" : "requested", studyIds.size() == 1 ? "y" : "ies", base, parallelism);

        try (ExecutorService pool = Executors.newFixedThreadPool(parallelism,
                Thread.ofPlatform().name("merge-", 1).factory())) {
            List<Map<String, ConsentFolder>> scanned = runAll(studyIds,
                    studyId -> folders.scanStudy(base, studyId), studyId -> studyId, pool);

            List<ConsentFolder> all = new ArrayList<>();
            List<String> studiesWithoutFolders = new ArrayList<>();
            for (int i = 0; i < studyIds.size(); i++) {
                if (scanned.get(i).isEmpty()) {
                    log.warn("Study {} has no consent folders under {}{}/{}/", studyIds.get(i), base,
                            studyIds.get(i), AllConceptsFolders.ALL_CONCEPTS_DIR);
                    studiesWithoutFolders.add(studyIds.get(i));
                }
                all.addAll(scanned.get(i).values());
            }
            log.info("Found {} consent folder(s)", all.size());

            AtomicInteger finished = new AtomicInteger();
            List<FolderResult> processed = runAll(all, folder -> {
                FolderResult r = process(folder);
                log.info("{}: {} ({}/{} folders done)", folder.label(), r.action(), finished.incrementAndGet(),
                        all.size());
                return r;
            }, ConsentFolder::label, pool, largestFirst(all));

            Map<String, FolderResult> results = new LinkedHashMap<>();
            for (int i = 0; i < all.size(); i++) {
                results.put(all.get(i).label(), processed.get(i));
            }
            return new Output(studyIds, studiesWithoutFolders, notReady, results);
        }
    }

    private <T, R> List<R> runAll(List<T> items, Function<T, R> task,
                                  Function<T, String> label, ExecutorService pool) {
        return runAll(items, task, label, pool, items);
    }

    /**
     * Runs {@code task} on every item on {@code pool}, submitted in {@code submitOrder}, and returns
     * the results in {@code items} order. Every item is attempted even after one fails; then the
     * first failure in {@code items} order is rethrown, the others attached as suppressed.
     */
    private <T, R> List<R> runAll(List<T> items, Function<T, R> task,
                                  Function<T, String> label, ExecutorService pool,
                                  List<T> submitOrder) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Map<T, Future<R>> futures = new IdentityHashMap<>();
        for (T item : submitOrder) {
            futures.put(item, pool.submit(() -> {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                try {
                    return task.apply(item);
                } catch (RuntimeException e) {
                    log.error("{} failed; carrying on with the rest", label.apply(item), e);
                    throw e;
                } finally {
                    MDC.clear();
                }
            }));
        }

        List<R> results = new ArrayList<>();
        RuntimeException failure = null;
        int failed = 0;
        for (T item : items) {
            try {
                results.add(futures.get(item).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.values().forEach(f -> f.cancel(true));
                throw new InfrastructureException("Interrupted while waiting for merges to finish", e);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof RuntimeException cause)) {
                    throw new IllegalStateException("A merge worker failed", e.getCause());
                }
                failed++;
                results.add(null);
                if (failure == null) {
                    failure = cause;
                } else {
                    failure.addSuppressed(cause);
                }
            }
        }
        if (failure != null) {
            log.error("{} of {} failed; the first is reported", failed, items.size());
            throw failure;
        }
        return results;
    }

    /** By total source bytes, largest first: a cheap stand-in for how long each takes to merge. */
    private static List<ConsentFolder> largestFirst(List<ConsentFolder> all) {
        return all.stream()
                .sorted(Comparator.comparingLong(MergeAllConceptsJob::sourceBytes).reversed())
                .toList();
    }

    private static long sourceBytes(ConsentFolder folder) {
        return folder.sources().stream().mapToLong(f -> f.size() == null ? 0 : f.size()).sum();
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

    /**
     * Streams the sources at their listed versions straight to S3, with no local copy: a merged
     * consent file can outgrow the runner's disk (WHI's c1 did). S3 only takes metadata when an
     * object is created, so the MD5 and length come from a first read; the second read uploads,
     * and fails the upload (leaving no object) if its bytes hash differently from the first.
     */
    private Merged merge(ConsentFolder folder, String fingerprint, String mergedUri) {
        try {
            MessageDigest md5 = md5();
            long length;
            try (InputStream in = new DigestInputStream(new SourcesStream(folder), md5)) {
                length = in.transferTo(OutputStream.nullOutputStream());
            }
            String md5Hex = HexFormat.of().formatHex(md5.digest());
            Map<String, String> metadata = Map.of(
                    AllConceptsFolders.META_FINGERPRINT, fingerprint,
                    AllConceptsFolders.META_SOURCE_COUNT, String.valueOf(folder.sources().size()),
                    AllConceptsFolders.META_MD5, md5Hex);
            String versionId = io.writeOutputStream(mergedUri, length, metadata,
                    () -> new VerifyingStream(new SourcesStream(folder), md5Hex, folder.label()));
            log.info("Wrote {} from {} source(s) (md5 {}, version {})", mergedUri, folder.sources().size(),
                    md5Hex, versionId);
            return new Merged(versionId, md5Hex);
        } catch (IOException e) {
            throw new InfrastructureException("Failed to merge " + folder.label(), e);
        }
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    /**
     * The folder's sources at their listed versions, concatenated in order, each ending with a
     * newline (one is added to a source missing its final newline). Opens one source at a time.
     */
    private final class SourcesStream extends InputStream {
        private final ConsentFolder folder;
        private int next;
        private InputStream current;
        private int last = -1;
        private boolean pendingNewline;

        SourcesStream(ConsentFolder folder) {
            this.folder = folder;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (true) {
                if (pendingNewline) {
                    pendingNewline = false;
                    b[off] = '\n';
                    return 1;
                }
                if (current == null) {
                    if (next >= folder.sources().size()) {
                        return -1;
                    }
                    current = open(folder.sources().get(next++));
                    last = -1;
                }
                int n = current.read(b, off, len);
                if (n > 0) {
                    last = b[off + n - 1];
                    return n;
                }
                if (n < 0) {
                    current.close();
                    current = null;
                    pendingNewline = last != -1 && last != '\n';
                }
            }
        }

        private InputStream open(FileVersion source) {
            try {
                return s3.getObject(GetObjectRequest.builder()
                        .bucket(folder.bucket()).key(source.key()).versionId(source.versionId()).build());
            } catch (RuntimeException e) {
                throw new InfrastructureException("Failed to read source s3://" + folder.bucket() + "/" + source.key()
                        + " (version " + source.versionId() + ")", e);
            }
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
        }
    }

    /** Fails at end of stream if the bytes read do not hash to the MD5 the metadata records. */
    private static final class VerifyingStream extends DigestInputStream {
        private final String expectedMd5;
        private final String label;
        private boolean verified;

        VerifyingStream(InputStream in, String expectedMd5, String label) {
            super(in, md5());
            this.expectedMd5 = expectedMd5;
            this.label = label;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0) {
                verify();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n < 0) {
                verify();
            }
            return n;
        }

        private void verify() throws IOException {
            if (verified) {
                return;
            }
            verified = true;
            String actual = HexFormat.of().formatHex(getMessageDigest().digest());
            if (!actual.equals(expectedMd5)) {
                throw new IOException(label + ": sources read back with md5 " + actual + ", not " + expectedMd5
                        + " as first read; upload abandoned");
            }
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
