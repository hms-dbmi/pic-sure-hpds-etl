package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.Strings;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.Severity;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts.AllConceptsFolders.ConsentFolder;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PERMANENT. Writes {@value #MANIFEST_NAME} at the data root for the production ingestion: first a
 * {@value #GLOBAL_PARTITION} row naming the current version of {@code global_AllConcepts.csv}
 * ({@code --global-allconcepts}), then one row per consent group of every study marked ready in
 * managed inputs, naming its MERGED allConcepts file, with partition {@code {study_id}.{consent}}
 * (folder {@code c1} of {@code phs000001} &rarr; {@code phs000001.c1}). Columns:
 * {@code partition, s3_uri, version_id, last_updated, md5}. The global file's md5 is computed by
 * reading that exact version; a MERGED file's is the one its merge recorded.
 *
 * <p>Runs after {@link MergeAllConceptsJob} at the end of both pipelines. Before writing, every
 * ready consent group must hold: a MERGED file exists; its recorded
 * {@linkplain AllConceptsFolders#fingerprint source fingerprint} equals that of the folder's
 * current sources (it is the merge of exactly the ready files); and it carries its MD5. A ready
 * study with no consent folders, or a missing or empty global AllConcepts file, also fails. Any
 * failure writes <b>no</b> manifest, so ingestion never receives one describing stale or missing
 * data; the report names every problem.
 *
 * <p>A consent folder that cannot be listed but does not block the rest is left out with a
 * WARNING: one with no allConcepts files at all; one holding only merged output and no sources
 * (its own MERGED file, a legacy {@code *_merged.csv}, or another folder's MERGED file), which
 * means the per-study allConcepts generation for that study and consent did not write its files;
 * and one whose MERGED file is empty. A ready study left with no listed consent folder at all is
 * warned about by name; if that leaves no consent group in the manifest at all, nothing is written.
 *
 * <p>The manifest is overwritten in place on a bucket that must have versioning enabled, so every
 * earlier manifest stays retrievable. {@code version_id} pins each row to the exact MERGED version
 * described, since later merges overwrite the same key. A copy is also written to the reports dir.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.generate-allconcepts-manifest.enabled", havingValue = "true")
public class GenerateAllConceptsManifestJob extends AbstractJob<GenerateAllConceptsManifestJob.Output> {

    static final String MANIFEST_NAME = "allConcepts_manifest.csv";
    static final String HEADER = "partition,s3_uri,version_id,last_updated,md5";
    /** The {@code partition} of the global AllConcepts row. */
    static final String GLOBAL_PARTITION = "global";

    private final IoResolver io;
    private final AllConceptsFolders folders;
    private final ManagedInputsService managedInputsService;

    public GenerateAllConceptsManifestJob(IoResolver io, S3Client s3, ManagedInputsService managedInputsService) {
        this.io = io;
        this.folders = new AllConceptsFolders(s3, io);
        this.managedInputsService = managedInputsService;
    }

    @Override
    public String name() {
        return "generate-allconcepts-manifest";
    }

    @Override
    public JobType type() {
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(ParamSpec.required("input",
                                "Data root (s3://, versioned bucket) holding {study_id}/allConcepts/c{consent}/ folders; "
                                        + "the manifest is written here as " + MANIFEST_NAME,
                                "s3://bucket/data/"),
                        ParamSpec.required("global-allconcepts",
                                "The global AllConcepts output (s3://, versioned bucket): the file, or the folder holding "
                                        + GenerateGlobalAllConceptsJob.OUTPUT_FILENAME + " (generate-global-all-concepts --output)",
                                "s3://bucket/global_allconcepts/")),
                List.of(MANIFEST_NAME + " at the data root, listing the MERGED file of every ready consent group; "
                        + "not written if any of them is missing or stale"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        if (!io.isS3(ctx.require("input"))) {
            report.error("S3_REQUIRED", "input must be an s3:// URI: the manifest must be versioned", "--input");
        }
        if (!io.isS3(ctx.require("global-allconcepts"))) {
            report.error("S3_REQUIRED", "global-allconcepts must be an s3:// URI: its version is pinned in the manifest",
                    "--global-allconcepts");
        }
    }

    /** The global file's URI: {@code --global-allconcepts} as given, or with the job's file name if a folder. */
    static String globalUri(String param) {
        return param.endsWith("/") ? param + GenerateGlobalAllConceptsJob.OUTPUT_FILENAME : param;
    }

    @Override
    protected Output execute(JobContext ctx) {
        String base = AllConceptsFolders.normalizeDir(ctx.require("input"));
        folders.requireVersioning(AllConceptsFolders.parseS3Prefix(base)[0]);
        String manifestUri = base + MANIFEST_NAME;
        String globalUri = globalUri(ctx.require("global-allconcepts"));
        String[] global = AllConceptsFolders.parseS3Prefix(globalUri);
        folders.requireVersioning(global[0]);

        Set<String> studyIds = new LinkedHashSet<>();
        managedInputsService.read().stream().filter(ManagedInputRow::isReady).map(ManagedInputRow::studyId)
                .sorted().forEach(studyIds::add);

        List<ManifestRow> rows = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        verifyGlobal(globalUri, global[0], global[1], rows, problems);
        if (studyIds.isEmpty()) {
            problems.add(Problem.error("NO_READY_STUDIES", null, null,
                    "no study is marked ready in managed inputs; refusing to publish an empty manifest"));
        }
        for (String studyId : studyIds) {
            Map<String, ConsentFolder> studyFolders = folders.scanStudy(base, studyId);
            if (studyFolders.isEmpty()) {
                problems.add(Problem.error("NO_CONSENT_FOLDERS", studyId, null, "ready, but has no "
                        + AllConceptsFolders.ALL_CONCEPTS_DIR + "/c{code}/ folders under " + base + studyId + "/"));
            }
            int rowsBefore = rows.size();
            int problemsBefore = problems.size();
            for (ConsentFolder folder : studyFolders.values()) {
                verify(folder, rows, problems);
            }
            boolean studyErrors = problems.subList(problemsBefore, problems.size()).stream()
                    .anyMatch(p -> p.severity() == Severity.ERROR);
            if (!studyFolders.isEmpty() && rows.size() == rowsBefore && !studyErrors) {
                // Every folder was left out with a warning: the study is ready but publishes nothing.
                problems.add(Problem.warning("STUDY_NOT_IN_MANIFEST", studyId, null, studyId + " is ready, "
                        + "but none of its " + studyFolders.size() + " consent folder(s) is in the manifest "
                        + "(see the warnings for each); check its all-concepts-data-generator output"));
            }
        }

        boolean blocked = problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
        if (!blocked && !studyIds.isEmpty() && rows.stream().noneMatch(r -> !GLOBAL_PARTITION.equals(r.partition()))) {
            // Every consent folder was left out with a warning (say, a broken generator run): a
            // manifest of the global row alone would drop every study from ingestion.
            problems.add(Problem.error("NO_CONSENT_GROUPS", null, null, "none of the " + studyIds.size()
                    + " ready stud" + (studyIds.size() == 1 ? "y has" : "ies has") + " a consent group to list "
                    + "(see the warnings); refusing to publish a manifest with no consent group"));
            blocked = true;
        }
        if (blocked) {
            // Errors include a missing or empty global file: the manifest always lists it.
            log.error("Not writing {}: {} problem(s) found", manifestUri, problems.size());
            return new Output(studyIds.size(), rows, problems, manifestUri, null);
        }

        String csv = toCsv(rows);
        String versionId = write(manifestUri, csv, rows.size(), ctx);
        writeReportCopy(ctx, csv);
        log.info("Wrote {} ({} row(s), version {})", manifestUri, rows.size(), versionId);
        return new Output(studyIds.size(), rows, problems, manifestUri, versionId);
    }

    /** Adds the {@value #GLOBAL_PARTITION} row for the global file's current version, or why it can't. */
    private void verifyGlobal(String uri, String bucket, String key, List<ManifestRow> rows, List<Problem> problems) {
        AllConceptsFolders.FileVersion current = folders.currentVersion(bucket, key);
        if (current == null) {
            problems.add(Problem.error("GLOBAL_MISSING", null, null, uri + " does not exist; run "
                    + "generate-global-all-concepts first"));
            return;
        }
        if (current.versionId() == null || current.versionId().isBlank() || "null".equals(current.versionId())) {
            problems.add(Problem.error("GLOBAL_NOT_VERSIONED", null, null, uri + " has no version id (written "
                    + "before versioning was enabled); re-run generate-global-all-concepts"));
            return;
        }
        if (current.size() != null && current.size() == 0) {
            problems.add(Problem.error("GLOBAL_EMPTY", null, null, uri + " is empty"));
            return;
        }
        String md5 = folders.md5(bucket, key, current.versionId());
        rows.add(new ManifestRow(GLOBAL_PARTITION, uri, current.versionId(), current.lastModified().toString(), md5));
    }

    /** Adds the folder's manifest row, or the reasons it can't have one. */
    private void verify(ConsentFolder folder, List<ManifestRow> rows, List<Problem> problems) {
        String studyId = folder.studyId();
        String consentDir = folder.consentDir();
        if (folder.sources().isEmpty() && (folder.merged() != null || !folder.otherMergedFiles().isEmpty())) {
            // Only merged output, no per-study source files: the merged file is not the merge of
            // anything the study now has, so it is left out, and the rest is still published.
            List<String> merged = new ArrayList<>(folder.otherMergedFiles());
            if (folder.merged() != null) {
                merged.add(0, folder.mergedFileName());
            }
            problems.add(Problem.warning(folder.otherMergedFiles().isEmpty()
                            ? "MERGED_WITHOUT_SOURCES" : "ONLY_MERGED_COPIES", studyId, consentDir,
                    onlyMergedMessage(folder, merged) + " Left out of the manifest."));
            return;
        }
        if (folder.sources().isEmpty() && folder.merged() == null) {
            problems.add(Problem.warning("NO_SOURCES", studyId, consentDir, folder.label()
                    + " holds no allConcepts files (only " + folder.ignored() + "); not in the manifest"));
            return;
        }
        if (folder.merged() == null) {
            problems.add(Problem.error("MERGED_MISSING", studyId, consentDir, folder.label() + " has "
                    + folder.sources().size() + " source file(s) but no " + folder.mergedFileName()));
            return;
        }
        Map<String, String> meta = folders.metadata(folder.bucket(), folder.mergedKey(), folder.merged().versionId());
        String recorded = meta.get(AllConceptsFolders.META_FINGERPRINT);
        String md5 = meta.get(AllConceptsFolders.META_MD5);
        if (recorded == null) {
            problems.add(Problem.error("MERGED_NOT_FINGERPRINTED", studyId, consentDir, folder.label() + ": "
                    + folder.mergedFileName() + " does not record which sources it was built from; re-run the merge"));
            return;
        }
        if (!recorded.equals(AllConceptsFolders.fingerprint(folder.sources()))) {
            problems.add(Problem.error("MERGED_STALE", studyId, consentDir, folder.label() + ": "
                    + folder.mergedFileName() + " was not built from the folder's current "
                    + folder.sources().size() + " source file(s) (one was added, changed, or deleted since); "
                    + "re-run the merge"));
            return;
        }
        if (md5 == null || md5.isBlank()) {
            problems.add(Problem.error("MD5_MISSING", studyId, consentDir, folder.label() + ": "
                    + folder.mergedFileName() + " has no recorded md5; re-run the merge"));
            return;
        }
        if (folder.merged().size() != null && folder.merged().size() == 0) {
            problems.add(Problem.warning("MERGED_EMPTY", studyId, consentDir, folder.label() + ": "
                    + folder.mergedFileName() + " is empty (0 bytes: its source files are empty); left out of the "
                    + "manifest. Check the all-concepts-data-generator run for " + studyId + " (or, in the "
                    + "migration, its split-allconcepts run)"));
            return;
        }
        rows.add(new ManifestRow(partition(studyId, consentDir), folder.uri() + folder.mergedFileName(),
                folder.merged().versionId(), folder.merged().lastModified().toString(), md5));
    }

    /**
     * Why a consent folder holding only merged output (no sources) is suspect. Shared with
     * {@link MergeAllConceptsJob}, so both jobs point at the same place.
     */
    static String onlyMergedMessage(ConsentFolder folder, List<String> mergedFiles) {
        return folder.label() + " has no per-study allConcepts source files, only merged file(s) " + mergedFiles
                + ", so the allConcepts output for study " + folder.studyId() + " consent " + folder.consentDir()
                + " is missing: check that study's all-concepts-data-generator run (or, in the migration, its "
                + "split-allconcepts run) wrote its " + AllConceptsFolders.ALL_CONCEPTS_MARKER + " files into "
                + folder.uri() + ", then re-run the merge.";
    }

    /** {@code phs000001}, folder {@code c1} &rarr; {@code phs000001.c1}, the {studyId}.c{code} consent form. */
    static String partition(String studyId, String consentDir) {
        return studyId + "." + consentDir;
    }

    static String toCsv(List<ManifestRow> rows) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (ManifestRow r : rows) {
            csv.append(Strings.csvQuote(r.partition())).append(',')
                    .append(Strings.csvQuote(r.s3Uri())).append(',')
                    .append(Strings.csvQuote(r.versionId())).append(',')
                    .append(r.lastUpdated()).append(',')
                    .append(r.md5()).append('\n');
        }
        return csv.toString();
    }

    private String write(String manifestUri, String csv, int rowCount, JobContext ctx) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("allconcepts-manifest-", ".csv");
            Files.writeString(tmp, csv);
            String versionId = io.writeOutputFile(manifestUri, tmp,
                    Map.of("row-count", String.valueOf(rowCount), "run-id", ctx.runId()));
            if (versionId == null || versionId.isBlank() || "null".equals(versionId)) {
                // requireVersioning passed, so S3 should have versioned the write; refuse to claim it did.
                throw new InfrastructureException("S3 returned no version id for " + manifestUri
                        + "; the manifest may not be versioned");
            }
            return versionId;
        } catch (IOException e) {
            throw new InfrastructureException("Failed to stage the manifest", e);
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

    private void writeReportCopy(JobContext ctx, String csv) {
        try {
            Files.createDirectories(ctx.reportsDir());
            Files.writeString(ctx.reportsDir().resolve(MANIFEST_NAME), csv);
        } catch (IOException e) {
            // The versioned S3 manifest is the deliverable; the archived copy is a convenience.
            log.warn("Could not copy the manifest into the reports dir: {}", e.getMessage());
        }
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        for (Problem p : output.problems()) {
            Map<String, Object> context = new LinkedHashMap<>();
            if (p.studyId() != null) {
                context.put("studyId", p.studyId());
            }
            if (p.consentDir() != null) {
                context.put("consent", p.consentDir());
            }
            report.add(new ValidationIssue(p.severity(), p.code(), p.message(), null, Map.copyOf(context)));
        }
        if (output.versionId() == null) {
            report.error("MANIFEST_NOT_WRITTEN", output.manifestUri() + " was not written: fix the problems above "
                    + "and re-run; the previous manifest version is unchanged");
        } else {
            report.info("MANIFEST_WRITTEN", output.manifestUri() + " (version " + output.versionId() + "): "
                    + "the global AllConcepts file and " + (output.rows().size() - 1) + " consent group(s) from "
                    + output.readyStudies() + " ready stud" + (output.readyStudies() == 1 ? "y" : "ies"));
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("readyStudies", (long) output.readyStudies())
                .metric("manifestRows", (long) output.rows().size())
                .metric("globalAllConcepts", output.rows().stream().filter(r -> GLOBAL_PARTITION.equals(r.partition()))
                        .map(r -> r.s3Uri() + " (version " + r.versionId() + ")").findFirst().orElse(""))
                .metric("manifestUri", output.manifestUri())
                .metric("manifestVersionId", output.versionId() == null ? "" : output.versionId())
                .metric("manifestWritten", output.versionId() != null)
                .metric("problems", (long) output.problems().stream()
                        .filter(p -> p.severity() == Severity.ERROR).count())
                .metric("consentFoldersExcluded", (long) output.problems().stream()
                        .filter(p -> p.severity() == Severity.WARNING && p.consentDir() != null).count());
    }

    /** One manifest line. */
    public record ManifestRow(String partition, String s3Uri, String versionId, String lastUpdated,
                              String md5) {
    }

    /** Why a ready consent group can't be listed (ERROR blocks the manifest). */
    public record Problem(Severity severity, String code, String studyId, String consentDir, String message) {
        static Problem error(String code, String studyId, String consentDir, String message) {
            return new Problem(Severity.ERROR, code, studyId, consentDir, message);
        }

        static Problem warning(String code, String studyId, String consentDir, String message) {
            return new Problem(Severity.WARNING, code, studyId, consentDir, message);
        }
    }

    /**
     * @param versionId  the written manifest's version id; null when it was not written
     */
    public record Output(int readyStudies, List<ManifestRow> rows, List<Problem> problems, String manifestUri,
                         String versionId) {
    }
}
