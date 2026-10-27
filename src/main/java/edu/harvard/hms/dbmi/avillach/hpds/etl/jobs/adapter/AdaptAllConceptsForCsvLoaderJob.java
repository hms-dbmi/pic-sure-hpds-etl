package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ValidationFailedException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.ManifestFormatException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.ManifestRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.PartitionSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.Plan;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.SplitManifestWriter.RunMeta;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TEMPORARY bridge. Turns the per-study allConcepts layout of {@code allConcepts_manifest.csv} (one file per
 * {@code {study}.c{code}} plus {@code global_AllConcepts.csv}, each pinned by S3 version id and MD5) into the
 * input the in-boundary runner {@code hpds-pheno-load} (flow {@code csvloader-split}) and the stock pic-sure
 * CSVLoader accept: flat {@code <partition>-allConcepts.csv} files and a {@code split_manifest.json}. Delete it
 * with its runner once the per-study pipeline writes that layout itself; see {@code docs/ADAPT_ALLCONCEPTS_CSVLOADER.md}.
 *
 * <p>Every row is checked ({@link AdapterRowCheck}); each global row is routed to the partitions of the patients it
 * belongs to ({@link AdaptationEngine}); rows that fail a check are rejected and counted, never silently changed.
 * Nothing is written to {@code --output} until every check has passed: partition files are built in a local
 * scratch directory, then uploaded, and {@code split_manifest.json} goes last, so a prefix holding that file is
 * complete. A failed run leaves {@code --output} empty and its report (and rejects) in the reports directory.
 *
 * <p>Exit codes: 2 a guard or the manifest refuses (output under the legacy bucket or not empty, bad parameters,
 * a partition name the runner would refuse, a consent code that is not a positive integer and not allowed,
 * a version id missing); 3 the data does not hold up (MD5 mismatch, the manifest moved during the run, ids that
 * are not patient-level, too many rejects, an empty partition); 4 S3 or the disk failed; 5 an input is missing.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.adapt-allconcepts-csvloader.enabled", havingValue = "true")
public class AdaptAllConceptsForCsvLoaderJob extends AbstractJob<AdaptAllConceptsForCsvLoaderJob.Output> {

    static final String JOB_NAME = "adapt-allconcepts-csvloader";
    /** The legacy bucket and the in-boundary mirror prefix that carries its name: never written from the boundary. */
    static final String MIRRORED_BUCKET_MARKER = "avillach-73-bdcatalyst-etl";

    private final IoResolver io;
    private final VersionedObjectSource source;
    private final ObjectMapper mapper;

    public AdaptAllConceptsForCsvLoaderJob(IoResolver io, VersionedObjectSource source, ObjectMapper mapper) {
        this.io = io;
        this.source = source;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return JOB_NAME;
    }

    @Override
    public JobType type() {
        // Temporary, like a migration job: delete it once the per-study pipeline writes the runner's layout itself.
        return JobType.MIGRATION;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(ParamSpec.required("manifest", "allConcepts_manifest.csv (s3:// of a versioned bucket, or a local path for the "
                                + "proof of concept on the test vectors)", "s3://bucket/data/allConcepts_manifest.csv"),
                        ParamSpec.required("output", "Prefix or directory that receives the split layout; must be empty and must not "
                                + "contain " + MIRRORED_BUCKET_MARKER, "s3://bucket/data/transforms/allconcepts_adapted/run-1/"),
                        ParamSpec.optional("studies", "Comma list of study ids to adapt (default: every study of the manifest)",
                                "phs000000,phs999999"),
                        ParamSpec.optional("max-reject-fraction", "Largest share of the input rows that may be rejected (default 0)", "0"),
                        ParamSpec.optional("allow-consent-codes", "Comma list of consent codes accepted although they are not positive "
                                + "integers (default none)", "0,public"),
                        ParamSpec.optional("multi-study-ids", "copy (default): ids of patients in several studies go to every partition "
                                + "they hold when they are patient-level; refuse: any such patient stops the run", "copy"),
                        ParamSpec.optional("zero-timestamps", "blank (the only value): a timestamp of 0 is written empty", "blank"),
                        ParamSpec.optional("work-dir", "Local directory for scratch files (default: the system temp dir); needs room "
                                + "for the whole output", "/mnt/scratch")),
                List.of("<partition>-allConcepts.csv per manifest partition (five quoted fields, no header, unsorted)",
                        "split_manifest.json (the runner's contract), written last",
                        "report.json (counts) and rejects/<partition>.csv (only when there are rejects)"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        AdapterOptions.parse(ctx, report);
        String output = ctx.get("output").orElse(null);
        if (output == null) {
            return; // MISSING_PARAM, reported by AbstractJob
        }
        if (output.contains(MIRRORED_BUCKET_MARKER)) {
            report.error("OUTPUT_UNDER_MIRROR", "output '" + output + "' is under " + MIRRORED_BUCKET_MARKER
                    + ", the legacy bucket and its in-boundary mirror, which no run may write. Use a fresh prefix such as "
                    + "s3://bdc-etl-data-d0d6191/data/transforms/allconcepts_adapted/<run>/.", "--output");
        } else {
            List<String> existing = io.listFilesRecursive(output);
            if (!existing.isEmpty()) {
                report.error("OUTPUT_NOT_EMPTY", "output '" + output + "' already holds " + existing.size() + " object(s) (e.g. "
                        + existing.getFirst() + "); use an empty prefix", "--output");
            }
        }
    }

    @Override
    protected Output execute(JobContext ctx) throws Exception {
        ValidationReport input = new ValidationReport("input");
        AdapterOptions options = AdapterOptions.parse(ctx, input);
        String manifestUri = ctx.require("manifest");
        String output = ctx.require("output");
        String outputDir = output.endsWith("/") ? output : output + "/";
        log.info("zero-timestamps=blank multi-study-ids={} max-reject-fraction={} studies={} allow-consent-codes={}",
                options.refuseMultiStudy() ? "refuse" : "copy", options.maxRejectFraction(),
                options.studies().isEmpty() ? "all" : options.studies(), options.allowCodes());

        // --- the manifest, pinned to the version read now ---------------------------------------------------------
        String manifestVersion = source.currentVersionId(manifestUri)
                .orElseThrow(() -> new ConfigException("The manifest does not exist: " + manifestUri));
        ManifestContent content = readManifest(manifestUri, manifestVersion, input);
        Plan plan = AllConceptsManifestReader.plan(content.rows(), manifestUri, options.studies(), options.allowCodes(), input);
        if (input.hasErrors()) {
            throw new ValidationFailedException(input);
        }
        log.info("Manifest version {}: {} row(s), {} partition(s) in scope, codes seen {}", manifestVersion, content.rows().size(),
                plan.partitions.size(), plan.codesSeen);

        Path work = scratch(ctx);
        try {
            Path partitionsDir = work.resolve("partitions");
            Path rejectsDir = work.resolve("rejects");
            AdaptationEngine engine = new AdaptationEngine(source, plan, options, manifestUri, partitionsDir, rejectsDir);
            try {
                engine.run();
            } catch (DataException | InfrastructureException e) {
                List<String> why = List.of(e.getMessage());
                failureArtifacts(ctx, engine, rejectsDir, meta(ctx, manifestUri, manifestVersion, content, outputDir, options, plan,
                        why, List.of()));
                e.withPartialResult(summary(engine, why), null);
                throw e;
            } catch (IOException e) {
                throw new InfrastructureException("Failed to write scratch files under " + work, e);
            }

            List<String> failures = evaluate(engine, options, manifestUri, manifestVersion);
            List<String> warnings = warnings(engine, options, plan);
            RunMeta meta = meta(ctx, manifestUri, manifestVersion, content, outputDir, options, plan, failures, warnings);
            if (!failures.isEmpty()) {
                failureArtifacts(ctx, engine, rejectsDir, meta);
                DataException failed = new DataException(String.join("; ", failures));
                failed.withPartialResult(summary(engine, failures), null);
                throw failed;
            }
            return publish(ctx, engine, meta, work, outputDir);
        } finally {
            deleteTree(work);
        }
    }

    // ------------------------------------------------------------------------------------------------ manifest

    private record ManifestContent(List<ManifestRow> rows, String md5) {
    }

    private ManifestContent readManifest(String uri, String versionId, ValidationReport report) {
        MessageDigest md5 = md5();
        try (InputStream raw = source.open(uri, versionId); DigestInputStream in = new DigestInputStream(raw, md5)) {
            List<ManifestRow> rows = AllConceptsManifestReader.read(in);
            return new ManifestContent(rows, HexFormat.of().formatHex(md5.digest()));
        } catch (ManifestFormatException e) {
            report.error("MANIFEST_FORMAT", e.getMessage(), "manifest");
            throw new ValidationFailedException(report);
        } catch (CharacterCodingException e) {
            report.error("MANIFEST_FORMAT", "the manifest is not valid UTF-8", "manifest");
            throw new ValidationFailedException(report);
        } catch (IOException e) {
            throw new InfrastructureException("Failed to read the manifest " + uri, e);
        }
    }

    // ------------------------------------------------------------------------------------------------ checks

    /** The reasons this run must not publish, empty when it may. */
    private List<String> evaluate(AdaptationEngine e, AdapterOptions options, String manifestUri, String manifestVersion) {
        List<String> failures = new ArrayList<>();
        for (PartitionSpec p : e.plan().partitions) {
            if (e.rowsWritten(p) == 0) {
                failures.add("EMPTY_PARTITION: " + p.name() + " ended with 0 rows (runner needs rows > 0)");
            }
            if (e.linesWritten(p.name()) != e.rowsWritten(p)) {
                failures.add("WRITTEN_COUNT_MISMATCH: " + p.name() + " file holds " + e.linesWritten(p.name()) + " line(s), counted "
                        + e.rowsWritten(p));
            }
        }
        long input = e.totalInput();
        long rejected = e.totalRejected();
        if (rejected > options.maxRejectFraction() * input) {
            failures.add("REJECT_FRACTION_EXCEEDED: " + rejected + " of " + input + " input row(s) rejected, more than "
                    + "--max-reject-fraction=" + options.maxRejectFraction() + " allows (by code: " + rejectCodes(e) + ")");
        }
        if (!e.balanced()) {
            failures.add("ROW_ACCOUNTING_UNBALANCED: input + globalCopies != written + rejected + dropped");
        }
        Optional<String> now = source.currentVersionId(manifestUri);
        if (now.isEmpty() || !now.get().equals(manifestVersion)) {
            failures.add("MANIFEST_CHANGED: the manifest moved from version " + manifestVersion + " to "
                    + now.orElse("(absent)") + " while the run was reading its files");
        }
        return failures;
    }

    private static Map<String, Long> rejectCodes(AdaptationEngine e) {
        Map<String, Long> out = new java.util.TreeMap<>();
        e.global.rejectedByCode.forEach((k, v) -> out.merge(k, v, Long::sum));
        for (Tally t : e.parts) {
            t.rejectedByCode.forEach((k, v) -> out.merge(k, v, Long::sum));
        }
        return out;
    }

    private static List<String> warnings(AdaptationEngine e, AdapterOptions options, Plan plan) {
        List<String> w = new ArrayList<>();
        long headers = e.global.headers;
        for (Tally t : e.parts) {
            headers += t.headers;
        }
        if (headers > 0) {
            w.add(headers + " header line(s) dropped");
        }
        if (e.global.noConsent > 0) {
            w.add(e.global.noConsent + " global row(s) of patients with no consent in scope dropped");
        }
        if (e.global.studyNotHeld > 0) {
            w.add(e.global.studyNotHeld + " _studies_consents row(s) of a study the patient holds no consent of dropped");
        }
        if (e.global.duplicates > 0) {
            w.add(e.global.duplicates + " id row(s) of multi-study patients occur more often than once per study; merged");
        }
        if (e.global.excludedStudy > 0) {
            w.add(e.global.excludedStudy + " global row(s) of studies left out by --studies dropped");
        }
        long rejected = e.totalRejected();
        if (rejected > 0) {
            w.add(rejected + " row(s) rejected, within --max-reject-fraction=" + options.maxRejectFraction());
        }
        for (PartitionSpec p : plan.partitions) {
            if (!AllConceptsManifestReader.STANDARD_CODE.matcher(p.code()).matches()) {
                w.add("partition " + p.name() + " has the non-standard consent code '" + p.code() + "' (allowed by --allow-consent-codes)");
            }
        }
        if (e.timestampsConverted > 0) {
            w.add(e.timestampsConverted + " ISO timestamp(s) converted to epoch milliseconds");
        }
        return w;
    }

    // ------------------------------------------------------------------------------------------------ output

    private Output publish(JobContext ctx, AdaptationEngine engine, RunMeta meta, Path work, String outputDir) throws IOException {
        Map<String, Object> splitManifest = SplitManifestWriter.splitManifest(meta, engine);
        Map<String, Object> report = SplitManifestWriter.report(meta, engine);
        Path manifestFile = work.resolve(SplitManifestWriter.SPLIT_MANIFEST);
        Path reportFile = work.resolve(SplitManifestWriter.REPORT);
        SplitManifestWriter.write(mapper, manifestFile, splitManifest);
        SplitManifestWriter.write(mapper, reportFile, report);

        // Everything is checked. Upload the data, then the report, and the manifest last: a prefix with a
        // split_manifest.json is complete.
        for (PartitionSpec p : engine.plan().partitions) {
            io.writeOutputFile(outputDir + p.file(), engine.partitionFile(p.name()));
        }
        List<String> rejectFiles = new ArrayList<>();
        if (engine.global.rejected > 0) {
            rejectFiles.add(AdaptationEngine.GLOBAL);
        }
        for (Tally t : engine.parts) {
            if (t.rejected > 0) {
                rejectFiles.add(t.name);
            }
        }
        for (String f : rejectFiles) {
            io.writeOutputFile(outputDir + "rejects/" + f + ".csv", engine.rejectsFile(f));
        }
        io.writeOutputFile(outputDir + SplitManifestWriter.REPORT, reportFile);
        copyReport(ctx, reportFile);
        io.writeOutputFile(outputDir + SplitManifestWriter.SPLIT_MANIFEST, manifestFile);
        log.info("Wrote {} partition file(s) and {} to {}", engine.plan().partitions.size(), SplitManifestWriter.SPLIT_MANIFEST,
                outputDir);
        return new Output(summary(engine, List.of()), meta.warnings(), engine.plan().partitions.size());
    }

    /** A failed run's report and rejects go to the reports directory, so --output stays empty and can be reused. */
    private void failureArtifacts(JobContext ctx, AdaptationEngine engine, Path rejectsDir, RunMeta meta) {
        try {
            Files.createDirectories(ctx.reportsDir());
            Path report = ctx.reportsDir().resolve(JOB_NAME + "-" + ctx.runId() + "-report.json");
            SplitManifestWriter.write(mapper, report, SplitManifestWriter.report(meta, engine));
            Path rejectsOut = ctx.reportsDir().resolve(JOB_NAME + "-" + ctx.runId() + "-rejects");
            engine.flushQuietly();
            if (Files.isDirectory(rejectsDir)) {
                Files.createDirectories(rejectsOut);
                try (var files = Files.list(rejectsDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        Files.copy(f, rejectsOut.resolve(f.getFileName().toString()));
                    }
                }
            }
            log.error("The failed run's report is {}", report);
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not write the failed run's report: {}", ex.getMessage());
        }
    }

    private void copyReport(JobContext ctx, Path reportFile) {
        try {
            Files.createDirectories(ctx.reportsDir());
            Files.copy(reportFile, ctx.reportsDir().resolve(JOB_NAME + "-" + ctx.runId() + "-report.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not copy report.json into the reports dir: {}", e.getMessage());
        }
    }

    private static RunMeta meta(JobContext ctx, String manifestUri, String manifestVersion, ManifestContent content, String outputDir,
                                AdapterOptions options, Plan plan, List<String> failures, List<String> warnings) {
        return new RunMeta(JOB_NAME, ctx.runId(), Instant.now().toString(), manifestUri, manifestVersion, content.md5(),
                content.rows().size(), outputDir, options, failures, warnings);
    }

    private static Map<String, Object> summary(AdaptationEngine e, List<String> failures) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowsInput", e.totalInput());
        m.put("rowsGlobalCopies", e.totalCopies());
        m.put("rowsWritten", e.totalWritten());
        m.put("rowsRejected", e.totalRejected());
        m.put("rowsDropped", e.totalDropped());
        m.put("balanced", e.balanced());
        m.put("partitions", e.plan().partitions.size());
        m.put("multiStudyPatients", e.index.multiStudyPatients());
        m.put("consentCodesSeen", new ArrayList<>(e.plan().codesSeen));
        m.put("rejectsByCode", rejectCodes(e));
        if (!failures.isEmpty()) {
            m.put("failures", failures);
        }
        return m;
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        for (String w : output.warnings()) {
            report.warning("ADAPTER_NOTE", w);
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metrics(output.metrics());
    }

    /** What a successful run produced. */
    public record Output(Map<String, Object> metrics, List<String> warnings, int partitions) {
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Path scratch(JobContext ctx) {
        try {
            Optional<String> base = ctx.get("work-dir");
            if (base.isPresent()) {
                Files.createDirectories(Path.of(base.get()));
                return Files.createTempDirectory(Path.of(base.get()), "adapt-allconcepts-");
            }
            return Files.createTempDirectory("adapt-allconcepts-");
        } catch (IOException e) {
            throw new InfrastructureException("Cannot create the scratch directory", e);
        }
    }

    private static void deleteTree(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // best effort: a leftover scratch directory must not hide the run's own result
        }
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
