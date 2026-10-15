package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.EtlException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.Strings;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.Severity;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration.DbgapMultiFileReader.SubjectTable;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SstrPopulateRdsParticipantsJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * MIGRATION. Orchestrates the one-time move of every "ready" study's participant
 * identities into the {@code participants}/{@code consents}/{@code samples} RDS tables,
 * from a legacy export laid out as:
 *
 * <ul>
 *   <li>{@link ManagedInputsService}: provides the study list (columns "Study Abbreviated Name",
 *       "Study Identifier", and "Data is ready to process"), configured via
 *       {@code etl.managed-inputs.uri} or {@code --managed-inputs=<uri>}.</li>
 *   <li>{@code --data-folder}: a base URI (local or {@code s3://}) whose subfolders, one per
 *       study id, hold per-study data. Every rawData file is matched case-insensitively:
 *       <ul>
 *         <li>{@code {base}/{studyId}/rawData/sstr_{studyId}.{v}.txt}: the dbGaP SSTR export
 *             (tab-delimited; the legacy folder-flattened {@code SSTR__sstr_*} and
 *             {@code BDC-ingestion-only__sstr_*} names are accepted, the canonical lowercase name
 *             preferred)</li>
 *         <li>{@code {base}/{studyId}/rawData/{studyid}*Subject.MULTI.txt}, and optionally
 *             {@code {studyid}*Sample.MULTI.txt} and {@code {studyid}*Subject.data_dict.xml}: the
 *             dbGaP phenotype tables, the fallback when there is no usable SSTR</li>
 *         <li>{@code {base}/{studyId}/legacy/data/{ABV_UPPERCASE}_PatientMapping.v2.csv}: per-study
 *             patient mapping (headerless; columns: id, abv, legacy hpds id)</li>
 *       </ul>
 *       Files are staged (copied) into a local temporary directory before processing; the MULTI
 *       files only when a study needs them.</li>
 * </ul>
 *
 * <p>Each ready study's participants come from exactly one source, in this order:
 * <ol>
 *   <li><b>SSTR</b>, always preferred: {@link SstrPopulateRdsParticipantsJob} loads RDS.</li>
 *   <li><b>MULTI</b>, when the study has no SSTR, or its SSTR sub-job fails on bad data (its
 *       transaction rolls back, so nothing from it remains): {@link MultiPopulateRdsParticipantsJob}
 *       loads RDS from the Subject/Sample MULTI files.</li>
 *   <li>Otherwise the study <b>fails</b>, and is listed as having no participant source.</li>
 * </ol>
 * Either way this job then resolves legacy-hpds-id &rarr; new-hpds-id pairs by joining the
 * patient mapping file's id against the source file's dbGaP subject id or the study's own
 * subject id (the mapping file's id may be either).
 *
 * <p>Writes to the reports directory:
 * <ul>
 *   <li>{@code {studyid}_hpds_id_mapping.csv} per succeeded study, and a header-only one per study
 *       marked {@value #NEEDS_DATA_REVIEW}: {@code old_hpds_id,new_hpds_id,common_dbgap_id}</li>
 *   <li>{@value #SOURCES_REPORT}: one row per ready study, saying which source it used, which
 *       files were found, and why it fell back or failed</li>
 *   <li>{@value #ISSUES_REPORT}: every problem found while discovering or reading those files</li>
 *   <li>{@value #CONSENT_COUNTS_REPORT}: participants loaded per consent code, one row per study and
 *       code (carried over by {@code --resume-from}, so a resumed run still has every study's)</li>
 *   <li>{@value #SUMMARY_REPORT}: the two above, for a person to read</li>
 *   <li>{@code studies/{studyid}.json} per study, written as soon as it finishes, so a run that
 *       dies part way still says which studies it completed</li>
 * </ul>
 *
 * <p>Studies are migrated {@code --parallelism} at a time (default {@value #DEFAULT_PARALLELISM}),
 * largest patient mapping first, so the biggest studies are not left running alone at the end.
 *
 * <p>{@code --resume-from} names the reports directory of an earlier run of the same data folder.
 * A study that run migrated successfully is not migrated again: its result and mapping file are
 * copied into this run's reports instead. Studies it failed, skipped, or never reached are migrated,
 * as is one it recorded as migrated with no mapping rows (from before {@value #NEEDS_DATA_REVIEW}).
 *
 * <p>A study whose participants load but whose patient mapping resolves to none of them is not
 * migrated: it is skipped as {@value #NEEDS_DATA_REVIEW}, a warning, so the run goes on without it.
 *
 * <p>Studies are processed independently: a data problem in one study (bad row, unmatched
 * id, no usable source) is recorded as a failure for that study only and does not stop the
 * rest of the run -- surfaced as an output-validation error so the run's exit code still
 * reflects that something needs attention. A failure that is not about the study's data aborts
 * the whole run instead, since retrying per-study would not help: an infrastructure failure
 * (RDS/S3 unreachable) with its retryable exit code, and an unclassified sub-job failure with
 * UNKNOWN (see {@link #abortUnlessDataFailure}).
 *
 * <p>Enabled by {@code etl.jobs.participants-migration.enabled=true}, and also requires
 * {@code etl.jobs.sstr-populate-rds-participants.enabled=true} and
 * {@code etl.jobs.multi-populate-rds-participants.enabled=true} because it injects both sub-jobs.
 * All are on the condition so disabling a sub-job removes this bean rather than breaking context
 * startup on a missing dependency.
 */
@Component
@ConditionalOnProperty(
        name = {"etl.jobs.participants-migration.enabled",
                "etl.jobs.sstr-populate-rds-participants.enabled",
                "etl.jobs.multi-populate-rds-participants.enabled"},
        havingValue = "true")
public class ParticipantsMigrationJob extends AbstractJob<ParticipantsMigrationJob.Output> {

    static final String SOURCES_REPORT = "participants_migration_sources.csv";
    static final String ISSUES_REPORT = "participants_migration_file_issues.csv";
    static final String SUMMARY_REPORT = "participants_migration_file_report.md";
    /** Participants loaded per (study, consent code), as the sub-job wrote them to RDS. */
    static final String CONSENT_COUNTS_REPORT = "participants_migration_consent_counts.csv";

    private static final String SSTR_COL_SUBJECT_ID = "SUBJECT_ID";
    private static final String SSTR_COL_DBGAP_SUBJECT_ID = "dbgap_subject_id";
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int DEFAULT_PARALLELISM = 4;
    /**
     * Each study in flight holds one pooled connection for its load, so more studies than
     * connections would queue on the pool and fail on its timeout. Matches the default
     * {@code spring.datasource.hikari.maximum-pool-size}.
     */
    private static final int MAX_PARALLELISM = 8;
    /** Unresolved patient-mapping ids named in the per-study log summary; the report lists them all. */
    private static final int UNRESOLVED_LOG_SAMPLE = 5;
    private static final int MAX_EXAMPLES = 10;
    /** Holds one {@link StudyRecord} per finished study; what {@code --resume-from} reads back. */
    static final String STUDY_RESULTS_DIR = "studies";
    /** Reports are written here first and renamed, so a sync of the reports dir never copies half a file. */
    static final String PARTIAL_SUFFIX = ".partial";
    static final String REUSED_ISSUE = "REUSED_FROM_PREVIOUS_RUN";
    /**
     * The study has no participant files at all (no SSTR, no Subject.MULTI). It cannot be migrated,
     * but that is a gap in the data, not a broken run: it is reported as skipped and the run goes on.
     */
    static final String NO_PARTICIPANT_FILES = "NO_PARTICIPANT_FILES";
    /**
     * The study's participant source loaded, but not one patient mapping row resolved to a loaded
     * subject, so its mapping file is empty. That is a mismatch between the legacy mapping and the
     * source file to look into, not a broken run: it is reported as skipped and the run goes on.
     */
    static final String NEEDS_DATA_REVIEW = "NEEDS_DATA_REVIEW";
    private static final ObjectMapper RESULT_JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /** A dbGaP table accession, e.g. {@code pht000182}; pairs a Subject.MULTI with its data dictionary. */
    private static final Pattern TABLE_ACCESSION = Pattern.compile("pht\\d+");

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final DbgapMultiFileReader multiReader;
    private final ManagedInputsService managedInputsService;
    private final ParticipantRepository participants;
    private final SstrPopulateRdsParticipantsJob sstrJob;
    private final MultiPopulateRdsParticipantsJob multiJob;
    private final JobExecutor executor;

    public ParticipantsMigrationJob(IoResolver io,
                                     DelimitedReader delimitedReader,
                                     ManagedInputsService managedInputsService,
                                     ParticipantRepository participants,
                                     SstrPopulateRdsParticipantsJob sstrJob,
                                     MultiPopulateRdsParticipantsJob multiJob,
                                     JobExecutor executor) {
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.multiReader = new DbgapMultiFileReader(io, delimitedReader);
        this.managedInputsService = managedInputsService;
        this.participants = participants;
        this.sstrJob = sstrJob;
        this.multiJob = multiJob;
        this.executor = executor;
    }

    @Override
    public String name() {
        return "participants-migration";
    }

    @Override
    public JobType type() {
        return JobType.MIGRATION;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(
                        ParamSpec.required("data-folder",
                                "Base URI whose subfolders, one per study id, hold per-study data: "
                                        + "{base}/{studyId}/rawData/sstr_{studyId}.{v}.txt (case-insensitive; legacy SSTR__/BDC-ingestion-only__ prefixes accepted), "
                                        + "or as a fallback {base}/{studyId}/rawData/{studyid}*Subject.MULTI.txt "
                                        + "(+ optional *Sample.MULTI.txt and *Subject.data_dict.xml), "
                                        + "{base}/{studyId}/legacy/data/{ABV_UPPER}_PatientMapping.v2.csv "
                                        + "(local path or s3:// URI)",
                                "s3://hpds-migration/data"),
                        ParamSpec.optional("batch-size", "Rows per batch insert", "1000"),
                        ParamSpec.optional("parallelism",
                                "Studies migrated at once (1-" + MAX_PARALLELISM + "; default "
                                        + DEFAULT_PARALLELISM + "); each holds one database connection",
                                "4"),
                        ParamSpec.optional("resume-from",
                                "Reports directory of an earlier run over the same data folder; studies it "
                                        + "migrated successfully are copied from it instead of migrated again",
                                "s3://bucket/etl-runner/reports/participants-migration/<run-id>/"),
                        ParamSpec.optional("study-filter",
                                "Comma-separated study ids to process; blank processes every ready study",
                                "phs000200,phs000280")),
                List.of("participants/consents/samples upserted in RDS for every ready study; "
                        + "one {studyid}_hpds_id_mapping.csv written per processed study to the reports dir, plus "
                        + SOURCES_REPORT + ", " + ISSUES_REPORT + " and " + SUMMARY_REPORT));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        ctx.get("batch-size").ifPresent(bs -> {
            try {
                if (Integer.parseInt(bs) <= 0) {
                    report.error("BAD_BATCH_SIZE", "batch-size must be positive, got: " + bs, "--batch-size");
                }
            } catch (NumberFormatException e) {
                report.error("BAD_BATCH_SIZE", "batch-size must be an integer, got: " + bs, "--batch-size");
            }
        });
        ctx.get("parallelism").ifPresent(p -> {
            try {
                int n = Integer.parseInt(p);
                if (n < 1 || n > MAX_PARALLELISM) {
                    report.error("BAD_PARALLELISM", "parallelism must be between 1 and " + MAX_PARALLELISM
                            + " (one database connection each), got: " + p, "--parallelism");
                }
            } catch (NumberFormatException e) {
                report.error("BAD_PARALLELISM", "parallelism must be an integer, got: " + p, "--parallelism");
            }
        });
    }

    @Override
    protected Output execute(JobContext ctx) {
        String baseUri = ctx.require("data-folder");
        int batchSize = Integer.parseInt(ctx.get("batch-size", String.valueOf(DEFAULT_BATCH_SIZE)));
        int parallelism = Integer.parseInt(ctx.get("parallelism", String.valueOf(DEFAULT_PARALLELISM)));
        String resumeFrom = Strings.trimToNull(ctx.get("resume-from", ""));
        if (resumeFrom != null) {
            log.info("Resuming from {}: studies it migrated successfully are copied, not migrated again", resumeFrom);
        }

        List<ManagedInputRow> managedInputs = managedInputsService.read();

        Set<String> studyFilter = parseStudyFilter(ctx.get("study-filter", ""));
        if (!studyFilter.isEmpty()) {
            log.info("Study filter active: only processing {}", studyFilter);
        }

        // Two concurrent loads of one study would purge and rewrite the same consents, so a study
        // listed twice is migrated once.
        Map<String, ManagedInputRow> studies = new LinkedHashMap<>();
        for (ManagedInputRow row : managedInputs) {
            if (!row.isReady() || (!studyFilter.isEmpty() && !studyFilter.contains(row.studyId()))) {
                continue;
            }
            if (studies.putIfAbsent(row.studyId(), row) != null) {
                log.warn("Study '{}' is listed more than once in the managed inputs; migrating it once",
                        row.studyId());
            }
        }

        Path stagingDir;
        try {
            stagingDir = Files.createTempDirectory("migration-staging");
        } catch (IOException e) {
            throw new InfrastructureException("Failed to create staging directory", e);
        }
        log.info("Staging data files to {}; migrating {} studies, {} at a time", stagingDir, studies.size(),
                parallelism);

        ExecutorService pool = Executors.newFixedThreadPool(parallelism,
                Thread.ofPlatform().name("migrate-study-", 1).factory());
        try {
            List<StudyResult> results = migrateAll(new ArrayList<>(studies.values()), baseUri, resumeFrom,
                    stagingDir, batchSize, ctx, pool);
            writeSourceReports(ctx, results);
            return new Output(results);
        } finally {
            // Studies still running are staging into stagingDir and holding open transactions.
            awaitWorkers(pool);
            deleteRecursively(stagingDir);
        }
    }

    /**
     * Stops the pool and waits for every study already running to finish. Returns at once after
     * a normal run, where all have; after a failure it waits however long they take, since a
     * database call does not stop on interrupt. An interrupt while waiting is kept for the caller.
     */
    private void awaitWorkers(ExecutorService pool) {
        pool.shutdownNow();
        boolean interrupted = false;
        while (true) {
            try {
                if (pool.awaitTermination(1, TimeUnit.MINUTES)) {
                    break;
                }
                log.info("Waiting for in-flight studies to finish before removing the staging directory");
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Migrates every study on {@code pool}, largest first, returning results in input order. Studies share no
     * state but the database, where each loads in its own transaction and writes rows in key order
     * (see {@code BatchOps.upsertInChunks}), so two studies sharing a participant wait on each other
     * rather than deadlock.
     *
     * <p>A failure that aborts the run (see {@link #abortUnlessDataFailure}) stops any study not yet
     * started; studies already running finish, so none is left half-written. Of the studies that
     * aborted, the earliest in input order is the one rethrown.
     */
    private List<StudyResult> migrateAll(List<ManagedInputRow> studies, String baseUri, String resumeFrom,
                                         Path stagingDir, int batchSize, JobContext ctx, ExecutorService pool) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicInteger finished = new AtomicInteger();

        Map<String, Future<StudyOutcome>> futures = new HashMap<>();
        for (ManagedInputRow row : largestFirst(studies, baseUri, pool)) {
            futures.put(row.studyId(), pool.submit(() -> {
                if (aborted.get()) {
                    return StudyOutcome.NOT_STARTED;
                }
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                MDC.put("runId", ctx.runId() + "-" + row.studyId());
                try {
                    StudyResult result = migrateStudy(row, baseUri, resumeFrom, stagingDir, batchSize, ctx);
                    log.info("Study '{}' {} ({}/{} finished)", row.studyId(),
                            result.success() ? "migrated" : result.skipped() ? "skipped" : "FAILED", finished.incrementAndGet(), studies.size());
                    return new StudyOutcome(result, null);
                } catch (RuntimeException e) {
                    aborted.set(true);
                    log.error("Study '{}' aborted the run; no further studies will start", row.studyId(), e);
                    return new StudyOutcome(null, e);
                } finally {
                    MDC.clear();
                }
            }));
        }

        List<StudyResult> results = new ArrayList<>();
        RuntimeException abort = null;
        for (ManagedInputRow row : studies) {
            StudyOutcome outcome;
            try {
                outcome = futures.get(row.studyId()).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InfrastructureException("Interrupted while waiting for studies to finish", e);
            } catch (ExecutionException e) {
                // migrateStudy's RuntimeExceptions are caught in the task; this is an Error.
                throw new IllegalStateException("A study worker failed", e.getCause());
            }
            if (outcome.result() != null) {
                results.add(outcome.result());
            } else if (abort == null && outcome.abort() != null) {
                abort = outcome.abort();
            }
        }
        if (abort != null) {
            throw abort;
        }
        return results;
    }

    /**
     * The studies ordered by the size of their patient mapping, largest first: a cheap stand-in for
     * how long each takes, read with one request per study. A study whose mapping can't be sized
     * goes last; it fails quickly anyway.
     */
    private List<ManagedInputRow> largestFirst(List<ManagedInputRow> studies, String baseUri, ExecutorService pool) {
        Map<String, Future<Long>> lookups = new HashMap<>();
        for (ManagedInputRow row : studies) {
            lookups.put(row.studyId(), pool.submit(() -> {
                try {
                    return io.size(patientMappingUri(baseUri, row));
                } catch (RuntimeException e) {
                    log.warn("Study '{}': could not size its patient mapping ({}); scheduling it last",
                            row.studyId(), e.getMessage());
                    return -1L;
                }
            }));
        }
        Map<String, Long> sizes = new HashMap<>();
        for (Map.Entry<String, Future<Long>> lookup : lookups.entrySet()) {
            try {
                sizes.put(lookup.getKey(), lookup.getValue().get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InfrastructureException("Interrupted while sizing studies", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Sizing a study failed", e.getCause());
            }
        }
        List<ManagedInputRow> ordered = studies.stream()
                .sorted(Comparator.comparingLong((ManagedInputRow r) -> sizes.get(r.studyId())).reversed())
                .toList();
        log.info("Migrating largest patient mappings first: {}", ordered.stream().limit(5)
                .map(r -> r.studyId() + " (" + sizes.get(r.studyId()) + " bytes)").toList());
        return ordered;
    }

    /** One study's worker result: its result, the failure that aborted the run, or neither if it never started. */
    private record StudyOutcome(StudyResult result, RuntimeException abort) {
        static final StudyOutcome NOT_STARTED = new StudyOutcome(null, null);
    }

    /**
     * Migrates one study, or copies it from {@code resumeFrom} when that run migrated it. A data
     * problem fails the study and returns its result; a failure that aborts the run is thrown. Its
     * result is written to {@value #STUDY_RESULTS_DIR}/ and its staged files deleted once it finishes.
     */
    private StudyResult migrateStudy(ManagedInputRow row, String baseUri, String resumeFrom, Path stagingDir,
                                     int batchSize, JobContext ctx) {
        StudyResult reused = resumeFrom == null ? null : reusePrevious(row, baseUri, resumeFrom, ctx);
        if (reused != null) {
            writeStudyRecord(ctx, baseUri, reused);
            return reused;
        }
        SourceTrace trace = new SourceTrace(row.studyId());
        StudyResult result;
        try {
            StudyFiles files = discoverStudyFiles(baseUri, row, stagingDir, trace);
            result = processStudy(row, files, batchSize, ctx, trace);
        } catch (InfrastructureException | RunAbortedException e) {
            throw e;
        } catch (Exception e) {
            result = StudyResult.failed(row.studyId(), row.abv(), e.getMessage(), trace.snapshot());
            if (result.skipped()) {
                log.warn("Study '{}' skipped: {}", row.studyId(), e.getMessage());
            } else {
                log.error("Study '{}' failed during migration", row.studyId(), e);
            }
        } finally {
            deleteRecursively(stagingDir.resolve(row.studyId()));
        }
        writeStudyRecord(ctx, baseUri, result);
        return result;
    }

    /**
     * The study's result from {@code resumeFrom}, with its mapping file copied into this run's
     * reports, or null when it must be migrated again: that run failed it, never finished it,
     * or read a different data folder.
     */
    private StudyResult reusePrevious(ManagedInputRow row, String baseUri, String resumeFrom, JobContext ctx) {
        String studyId = row.studyId();
        String recordUri = joinPath(joinPath(resumeFrom, STUDY_RESULTS_DIR), studyId + ".json");
        String mappingUri = joinPath(resumeFrom, mappingFileName(studyId));
        if (!io.exists(recordUri)) {
            return null;
        }
        StudyRecord previous;
        try (InputStream in = io.openInput(recordUri)) {
            previous = RESULT_JSON.readValue(in, StudyRecord.class);
        } catch (IOException e) {
            log.warn("Study '{}': unreadable result in {} ({}); migrating it again", studyId, recordUri,
                    e.getMessage());
            return null;
        }
        if (previous.result() == null || !previous.result().success()) {
            return null;
        }
        if (previous.result().mappingRowCount() == 0) {
            // Recorded as migrated before a study with no mapping rows was flagged for review.
            log.warn("Study '{}': {} migrated it with no mapping rows; migrating it again", studyId, resumeFrom);
            return null;
        }
        if (!baseUri.equals(previous.dataFolder())) {
            log.warn("Study '{}': {} migrated it from a different data folder ({}); migrating it again",
                    studyId, resumeFrom, previous.dataFolder());
            return null;
        }
        if (!io.exists(mappingUri)) {
            log.warn("Study '{}': {} has no mapping file for it; migrating it again", studyId, resumeFrom);
            return null;
        }
        try (InputStream in = io.openInput(mappingUri)) {
            writeReport(ctx, mappingFileName(studyId), in.readAllBytes());
        } catch (IOException e) {
            throw new InfrastructureException("Failed to copy " + mappingUri, e);
        }

        StudyResult r = previous.result();
        List<FileIssue> issues = new ArrayList<>(r.usage().issues().stream()
                .filter(i -> !REUSED_ISSUE.equals(i.code())).toList());
        issues.add(new FileIssue(Severity.INFO, REUSED_ISSUE, null, 1,
                "migrated by an earlier run; result and mapping file copied from " + resumeFrom, List.of()));
        SourceUsage u = r.usage();
        log.info("Study '{}': already migrated by {}; copied its mapping file ({} rows)", studyId, resumeFrom,
                r.mappingRowCount());
        return new StudyResult(r.studyId(), r.abv(), r.source(), true, null, r.mappingRowCount(),
                r.unresolvedHpdsIds(), new SourceUsage(u.sstrFile(), u.sstrStatus(), u.sstrError(),
                u.subjectMultiFile(), u.sampleMultiFile(), u.dataDictFile(), u.multiStatus(), u.consentColumn(),
                u.participantsByConsent(), List.copyOf(issues)));
    }

    private void writeStudyRecord(JobContext ctx, String baseUri, StudyResult result) {
        try {
            writeReport(ctx, STUDY_RESULTS_DIR + "/" + result.studyId() + ".json",
                    RESULT_JSON.writeValueAsBytes(new StudyRecord(baseUri, result)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise the result of study " + result.studyId(), e);
        }
    }

    /**
     * Finds the study's candidate source files and stages the SSTR and patient mapping. The MULTI
     * files are only named here; {@link #runMulti} stages them if the study falls back to them.
     */
    private StudyFiles discoverStudyFiles(String baseUri, ManagedInputRow row, Path stagingDir, SourceTrace trace) {
        String studyId = row.studyId();
        Path studyDir = stagingDir.resolve(studyId);
        String studyBase = joinPath(baseUri, studyId);

        String rawDataDir = joinPath(studyBase, "rawData");
        List<String> rawDataFiles = io.listFileNames(rawDataDir);
        // Staged SSTRs arrive under three naming families, all the same NHLBI artifact:
        // the canonical 'sstr_{phs}.{v}.txt' filename, plus legacy folder-flattened copies
        // 'SSTR__sstr_*' and 'BDC-ingestion-only__sstr_*' (an S3 sync collapsed the source
        // folder into the basename). Match all three, case-insensitively; when a study has
        // several, prefer the canonical name -- it is the freshest staging convention.
        List<String> sstrCandidates = rawDataFiles.stream()
                .filter(name -> isSstrFileFor(name, studyId))
                .sorted(Comparator
                        .comparing((String name) -> !isCanonicalSstrName(name))
                        .thenComparing(name -> name))
                .toList();
        String sstrFileName = sstrCandidates.isEmpty() ? null : sstrCandidates.get(0);
        if (sstrCandidates.size() > 1) {
            trace.issue(Severity.WARNING, "MULTIPLE_SSTR_CANDIDATES", sstrFileName,
                    "found " + sstrCandidates.size() + " SSTR files; using '" + sstrFileName + "'", sstrCandidates);
        }

        List<String> subjectMultis = newestFirst(rawDataFiles, n -> isSubjectMultiFileFor(n, studyId));
        List<String> sampleMultis = newestFirst(rawDataFiles, n -> isSampleMultiFileFor(n, studyId));
        List<String> dataDicts = dataDictCandidates(rawDataFiles, studyId,
                subjectMultis.isEmpty() ? null : subjectMultis.get(0));

        trace.sstrFile = sstrFileName;
        trace.subjectMultiFile = first(subjectMultis);
        trace.sampleMultiFile = first(sampleMultis);
        trace.dataDictFile = first(dataDicts);

        Path localSstr = null;
        if (sstrFileName != null) {
            localSstr = studyDir.resolve(sstrFileName);
            io.copyToLocal(joinPath(rawDataDir, sstrFileName), localSstr);
            log.info("Study '{}': staged SSTR file '{}'", studyId, sstrFileName);
        }

        String pmUri = patientMappingUri(baseUri, row);
        Path localPm = studyDir.resolve(pmUri.substring(pmUri.lastIndexOf('/') + 1));
        io.copyToLocal(pmUri, localPm);

        return new StudyFiles(rawDataDir, studyDir, localSstr, subjectMultis, sampleMultis, dataDicts, localPm);
    }

    private static String patientMappingUri(String baseUri, ManagedInputRow row) {
        return joinPath(joinPath(joinPath(baseUri, row.studyId()), "legacy/data"),
                row.abv().toUpperCase(Locale.ROOT) + "_PatientMapping.v2.csv");
    }

    private static String mappingFileName(String studyId) {
        return studyId + "_hpds_id_mapping.csv";
    }

    private StudyResult processStudy(ManagedInputRow row, StudyFiles files, int batchSize, JobContext ctx,
                                     SourceTrace trace) {
        String studyId = row.studyId();
        List<PatientMappingRow> patientMappings = readPatientMapping(files.patientMappingFile().toString());
        if (patientMappings.isEmpty()) {
            throw new DataException("Patient mapping for study " + studyId + " yielded no subjects; "
                    + "refusing to proceed. Check that the file is complete.");
        }

        SubjectIdentities identities = null;
        if (files.sstrFile() != null) {
            identities = runSstr(studyId, files, batchSize, ctx, trace);
        }
        if (identities == null) {
            if (files.subjectMulti() == null && trace.sstrFile == null) {
                String reason = "no SSTR file and no Subject.MULTI file in rawData";
                trace.issue(Severity.WARNING, NO_PARTICIPANT_FILES, null, reason + "; skipped", List.of());
                throw new DataException(studyId + " has no participant files (" + reason + "); skipped");
            }
            if (files.subjectMulti() == null) {
                // There is a file, it is just unusable: that is a data error to fix, so the study fails.
                String reason = "SSTR '" + trace.sstrFile + "' is malformed (" + trace.sstrError
                        + ") and there is no Subject.MULTI file to fall back to";
                trace.issue(Severity.ERROR, "NO_PARTICIPANT_SOURCE", null, reason, List.of());
                throw new DataException(studyId + " has no usable participant source: " + reason);
            }
            identities = runMulti(studyId, files, batchSize, ctx, trace);
        }

        MappingResult mapping = buildMapping(studyId, identities, patientMappings, batchSize, trace);
        writeMappingFile(ctx, studyId, mapping.mappingRows());
        if (mapping.mappingRows().isEmpty()) {
            String reason = "none of its " + patientMappings.size() + " patient mapping row(s) resolved to a subject "
                    + "loaded from " + identities.file() + " (" + mapping.notInFile() + " not in the file, "
                    + mapping.noParticipant() + " with no participant id, " + mapping.notConsented()
                    + " consent 0); check that the file's subject ids match the legacy patient mapping";
            trace.issue(Severity.WARNING, NEEDS_DATA_REVIEW, identities.file(), reason + "; not migrated",
                    mapping.unresolvedHpdsIds().stream().limit(MAX_EXAMPLES).toList());
            log.warn("Study '{}' needs data review: {}", studyId, reason);
            return StudyResult.needsReview(studyId, row.abv(), trace.source, studyId + " needs data review: " + reason,
                    mapping.unresolvedHpdsIds(), trace.snapshot());
        }
        if (!mapping.unresolvedHpdsIds().isEmpty()) {
            log.warn("Study '{}': {} of {} patient mapping row(s) could not be resolved via {}",
                    studyId, mapping.unresolvedHpdsIds().size(), patientMappings.size(), identities.file());
        }
        return StudyResult.success(studyId, row.abv(), trace.source, mapping.mappingRows().size(),
                mapping.unresolvedHpdsIds(), trace.snapshot());
    }

    /**
     * Loads the study from its SSTR. Returns the subject identities to map against, or null when the
     * SSTR is malformed (the sub-job failed on its data, and rolled back) so the caller falls back.
     */
    private SubjectIdentities runSstr(String studyId, StudyFiles files, int batchSize, JobContext ctx,
                                      SourceTrace trace) {
        String sstrPath = files.sstrFile().toString();
        JobResult result = executor.run(sstrJob,
                Map.of("input", sstrPath, "study-id", studyId, "batch-size", String.valueOf(batchSize)),
                ctx.runId() + "-" + studyId + "-sstr");
        if (!result.isSuccess()) {
            String message = describeFailure(result);
            abortUnlessDataFailure(result, "sstr", studyId, message);
            log.warn("Study '{}': SSTR '{}' is malformed ({}); falling back to the MULTI files",
                    studyId, trace.sstrFile, message);
            trace.sstrStatus = "malformed";
            trace.sstrError = message;
            trace.issue(Severity.WARNING, "SSTR_MALFORMED", trace.sstrFile,
                    "sstr sub-job failed (" + result.getExitCode() + "): " + message, List.of());
            return null;
        }
        if (result.getExitCode() == ExitCode.SUCCESS_WITH_WARNINGS) {
            log.warn("Study '{}': sstr sub-job completed with warnings: {}",
                    studyId, result.getOutputValidation().getIssues());
        }
        trace.sstrStatus = "used";
        trace.source = Source.SSTR;
        trace.participantsByConsent = countsByConsent(result);
        return readSstrIdentities(sstrPath, trace.sstrFile);
    }

    /** Stages the MULTI files and loads the study from them; any failure fails the study. */
    private SubjectIdentities runMulti(String studyId, StudyFiles files, int batchSize, JobContext ctx,
                                       SourceTrace trace) {
        reportMultipleCandidates(files.subjectMultis(), "MULTIPLE_SUBJECT_MULTI_CANDIDATES", trace);
        reportMultipleCandidates(files.sampleMultis(), "MULTIPLE_SAMPLE_MULTI_CANDIDATES", trace);
        reportMultipleCandidates(files.dataDicts(), "MULTIPLE_DATA_DICT_CANDIDATES", trace);
        Path localSubject = stage(files, files.subjectMulti());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("subject-input", localSubject.toString());
        params.put("study-id", studyId);
        params.put("batch-size", String.valueOf(batchSize));
        if (files.sampleMulti() != null) {
            params.put("sample-input", stage(files, files.sampleMulti()).toString());
        } else {
            trace.issue(Severity.INFO, "NO_SAMPLE_MULTI", null,
                    "no Sample.MULTI file; no samples loaded for this study", List.of());
        }
        if (files.dataDict() != null) {
            params.put("data-dict", stage(files, files.dataDict()).toString());
        }

        trace.multiStatus = "attempted";
        JobResult result = executor.run(multiJob, params, ctx.runId() + "-" + studyId + "-multi");
        List<ValidationIssue> subIssues = new ArrayList<>();
        if (result.getInputValidation() != null) {
            subIssues.addAll(result.getInputValidation().getIssues());
        }
        if (result.getOutputValidation() != null) {
            subIssues.addAll(result.getOutputValidation().getIssues());
        }
        subIssues.stream()
                .filter(i -> !"CONSENT_CODE_COUNT".equals(i.code()))
                .forEach(trace::issue);

        if (!result.isSuccess()) {
            String message = describeFailure(result);
            abortUnlessDataFailure(result, "multi", studyId, message);
            trace.multiStatus = "failed";
            trace.issue(Severity.ERROR, "MULTI_LOAD_FAILED", files.subjectMulti(),
                    "multi sub-job failed (" + result.getExitCode() + "): " + message, List.of());
            String sstrPart = trace.sstrError == null ? "" : "; SSTR also unusable: " + trace.sstrError;
            throw new DataException("multi job failed for study " + studyId + ": " + message + sstrPart);
        }
        trace.multiStatus = "used";
        trace.source = Source.MULTI;
        trace.consentColumn = String.valueOf(result.getMetrics().get("consentColumn"));
        trace.participantsByConsent = countsByConsent(result);

        SubjectTable subjects = multiReader.readSubjects(localSubject.toString());
        Map<String, String> subjectIdToDbgapId = new HashMap<>();
        subjects.consented().values().forEach(s -> {
            if (s.subjectId() != null) {
                subjectIdToDbgapId.put(s.subjectId(), s.dbgapSubjectId());
            }
        });
        // Patient mapping ids may be either id, so a consent-0 subject is recognized by both.
        Set<String> notConsentedIds = new LinkedHashSet<>(subjects.notConsented());
        notConsentedIds.addAll(subjects.notConsentedSubjectIds());
        return new SubjectIdentities(files.subjectMulti(), new LinkedHashSet<>(subjects.consented().keySet()),
                subjectIdToDbgapId, notConsentedIds);
    }

    /** A populate sub-job's {@code countsByConsentGroup} metric: participants written per consent code. */
    private static Map<String, Long> countsByConsent(JobResult result) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (result.getMetrics().get("countsByConsentGroup") instanceof Map<?, ?> m) {
            m.forEach((code, n) -> counts.put(String.valueOf(code), ((Number) n).longValue()));
        }
        return Collections.unmodifiableMap(counts);
    }

    /** Sub-job exit codes that mean "this study's files are bad": the only ones handled per study. */
    private static final Set<ExitCode> DATA_FAILURES =
            Set.of(ExitCode.DATA_ERROR, ExitCode.VALIDATION_FAILED, ExitCode.CONFIG_ERROR);

    /**
     * Aborts the whole run unless the sub-job failed on its data. An infrastructure failure keeps
     * its retryable exit code. Anything else (UNKNOWN) is not evidence of a bad file either -- a
     * database that can't open a transaction surfaces as UNKNOWN -- so it aborts rather than
     * labelling every study's SSTR malformed and its MULTI load failed.
     */
    private static void abortUnlessDataFailure(JobResult result, String subJob, String studyId, String message) {
        String abort = subJob + " job failed for study " + studyId + " (" + result.getExitCode() + "): " + message;
        if (result.getExitCode() == ExitCode.INFRASTRUCTURE_ERROR) {
            throw new InfrastructureException(abort);
        }
        if (!DATA_FAILURES.contains(result.getExitCode())) {
            throw new RunAbortedException(result.getExitCode(), abort + "; aborting the run, since this is not a "
                    + "problem with the study's files");
        }
    }

    /** A sub-job failure that is not about one study's data; carries the sub-job's exit code. */
    private static final class RunAbortedException extends EtlException {
        RunAbortedException(ExitCode exitCode, String message) {
            super(exitCode, message);
        }
    }

    private Path stage(StudyFiles files, String fileName) {
        Path local = files.studyDir().resolve(fileName);
        io.copyToLocal(joinPath(files.rawDataDir(), fileName), local);
        log.info("Staged '{}'", fileName);
        return local;
    }

    /** The sub-job's error plus the error-level issues behind it; "Input validation failed" alone says nothing. */
    private static String describeFailure(JobResult result) {
        List<String> errors = new ArrayList<>();
        for (ValidationReport r : new ValidationReport[]{result.getInputValidation(), result.getOutputValidation()}) {
            if (r != null) {
                r.getIssues().stream().filter(i -> i.severity() == Severity.ERROR).map(ValidationIssue::message)
                        .forEach(errors::add);
            }
        }
        String base = result.getErrorMessage() == null ? String.valueOf(result.getExitCode()) : result.getErrorMessage();
        return errors.isEmpty() ? base : base + ": " + String.join("; ", errors);
    }

    /** dbGaP subject ids loaded from the SSTR, and the study's own subject id &rarr; dbGaP id. */
    private SubjectIdentities readSstrIdentities(String sstrPath, String fileName) {
        Map<String, String> subjectIdToDbgapId = new HashMap<>();
        Set<String> dbgapIds = new LinkedHashSet<>();

        InputStream in = io.openInput(sstrPath);
        try (Stream<Map<String, String>> stream = delimitedReader.stream(in, DelimitedReader.TAB)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                String dbgapId = Strings.trimToNull(row.get(SSTR_COL_DBGAP_SUBJECT_ID));
                if (dbgapId == null) {
                    continue;
                }
                dbgapIds.add(dbgapId);
                String subjectId = Strings.trimToNull(row.get(SSTR_COL_SUBJECT_ID));
                if (subjectId != null) {
                    subjectIdToDbgapId.put(subjectId, dbgapId);
                }
            }
        }
        return new SubjectIdentities(fileName, dbgapIds, subjectIdToDbgapId, Set.of());
    }

    /**
     * The source sub-job populated RDS keyed by {@code dbgap_subject_id}. This resolves each
     * patient-mapping row's id (which may be a dbgap id or the study's own subject id) to that
     * same dbgap id, so the mapping file can report the new hpds_id RDS actually assigned.
     */
    private MappingResult buildMapping(String studyId, SubjectIdentities identities,
                                       List<PatientMappingRow> patientMappings, int batchSize, SourceTrace trace) {
        Map<String, Long> idByDbgapId = participants.findIdsChunked(
                new ArrayList<>(identities.dbgapIds()), SstrPopulateRdsParticipantsJob.SOURCE, batchSize);

        List<MappingRow> rows = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<String> notConsented = new ArrayList<>();
        // Unresolved rows are summarised per study rather than logged one by one: a study can have
        // tens of thousands, and every id is already listed in the report's PATIENT_MAPPING_UNRESOLVED issue.
        List<String> notFound = new ArrayList<>();
        List<String> notInFileSample = new ArrayList<>();
        List<String> noParticipantSample = new ArrayList<>();
        int notInFile = 0;
        int noParticipant = 0;
        for (PatientMappingRow pm : patientMappings) {
            String dbgapId = identities.dbgapIds().contains(pm.id())
                    ? pm.id() : identities.subjectIdToDbgapId().get(pm.id());
            if (dbgapId == null && identities.notConsentedIds().contains(pm.id())) {
                notConsented.add(pm.oldHpdsId());
                unresolved.add(pm.oldHpdsId());
                continue;
            }
            if (dbgapId == null) {
                if (notInFile++ < UNRESOLVED_LOG_SAMPLE) {
                    notInFileSample.add("'" + pm.id() + "' (old hpds id " + pm.oldHpdsId() + ")");
                }
                notFound.add(pm.oldHpdsId());
                unresolved.add(pm.oldHpdsId());
                continue;
            }
            Long hpdsId = idByDbgapId.get(dbgapId);
            if (hpdsId == null) {
                if (noParticipant++ < UNRESOLVED_LOG_SAMPLE) {
                    noParticipantSample.add("'" + dbgapId + "' (old hpds id " + pm.oldHpdsId() + ")");
                }
                notFound.add(pm.oldHpdsId());
                unresolved.add(pm.oldHpdsId());
                continue;
            }
            rows.add(new MappingRow(pm.oldHpdsId(), hpdsId, dbgapId));
        }

        if (notInFile > 0) {
            log.warn("Study '{}': {} of {} patient mapping id(s) not found in {}; skipped. First: {}",
                    studyId, notInFile, patientMappings.size(), identities.file(), String.join(", ", notInFileSample));
        }
        if (noParticipant > 0) {
            log.warn("Study '{}': {} of {} dbgap id(s) have no participant id; skipped. First: {}",
                    studyId, noParticipant, patientMappings.size(), String.join(", ", noParticipantSample));
        }
        if (!notConsented.isEmpty()) {
            trace.issue(Severity.INFO, "PATIENT_MAPPING_NOT_CONSENTED", identities.file(),
                    notConsented.size() + " patient mapping row(s) name a consent-0 subject and were not mapped "
                            + "(old hpds ids)", notConsented);
        }
        if (!notFound.isEmpty()) {
            trace.issue(Severity.WARNING, "PATIENT_MAPPING_UNRESOLVED", identities.file(),
                    notFound.size() + " patient mapping row(s) could not be resolved to a loaded subject (old hpds ids)",
                    notFound);
        }
        return new MappingResult(rows, unresolved, notInFile, noParticipant, notConsented.size());
    }

    private List<PatientMappingRow> readPatientMapping(String path) {
        List<PatientMappingRow> rows = new ArrayList<>();
        InputStream in = io.openInput(path);
        try (Stream<List<String>> stream = delimitedReader.streamRows(in, DelimitedReader.COMMA)) {
            for (List<String> row : (Iterable<List<String>>) stream::iterator) {
                if (row.size() < 3) {
                    throw new DataException("Patient mapping row has fewer than 3 columns: " + row);
                }
                String id = Strings.trimToNull(row.get(0));
                String oldHpdsId = Strings.trimToNull(row.get(2));
                if (id == null || oldHpdsId == null) {
                    throw new DataException("Patient mapping row has a blank id or hpds id: " + row);
                }
                rows.add(new PatientMappingRow(id, oldHpdsId));
            }
        }
        return rows;
    }

    private void writeMappingFile(JobContext ctx, String studyId, List<MappingRow> mappingRows) {
        StringBuilder csv = new StringBuilder("old_hpds_id,new_hpds_id,common_dbgap_id\n");
        for (MappingRow row : mappingRows) {
            csv.append(Strings.csvQuote(row.oldHpdsId())).append(',').append(row.newHpdsId()).append(',')
                    .append(Strings.csvQuote(row.commonDbgapId())).append('\n');
        }
        writeReport(ctx, mappingFileName(studyId), csv.toString());
    }

    /** Writes {@value #SOURCES_REPORT}, {@value #ISSUES_REPORT}, {@value #CONSENT_COUNTS_REPORT} and {@value #SUMMARY_REPORT}. */
    private void writeSourceReports(JobContext ctx, List<StudyResult> results) {
        StringBuilder sources = new StringBuilder("study_id,abv,outcome,source,sstr_file,sstr_status,"
                + "subject_multi_file,sample_multi_file,data_dict_file,multi_status,consent_column,"
                + "mapping_rows,unresolved_mapping_rows,error\n");
        StringBuilder issues = new StringBuilder("study_id,severity,code,file,count,message,examples\n");
        StringBuilder consentCounts = new StringBuilder("study_id,abv,outcome,source,consent_code,participants_loaded\n");
        for (StudyResult r : results) {
            SourceUsage u = r.usage();
            u.participantsByConsent().forEach((code, n) -> consentCounts.append(csvRow(r.studyId(), r.abv(),
                    r.outcome(), r.source().label(), code, String.valueOf(n))));
            sources.append(csvRow(r.studyId(), r.abv(), r.outcome(), r.source().label(),
                    u.sstrFile(), u.sstrStatus(), u.subjectMultiFile(), u.sampleMultiFile(), u.dataDictFile(),
                    u.multiStatus(), u.consentColumn(), String.valueOf(r.mappingRowCount()),
                    String.valueOf(r.unresolvedHpdsIds().size()), r.errorMessage()));
            for (FileIssue i : u.issues()) {
                issues.append(csvRow(r.studyId(), i.severity().name(), i.code(), i.file(), String.valueOf(i.count()),
                        i.message(), String.join(" ", i.examples())));
            }
        }
        writeReport(ctx, SOURCES_REPORT, sources.toString());
        writeReport(ctx, ISSUES_REPORT, issues.toString());
        writeReport(ctx, CONSENT_COUNTS_REPORT, consentCounts.toString());
        writeReport(ctx, SUMMARY_REPORT, summaryMarkdown(ctx, results));
    }

    private static String summaryMarkdown(JobContext ctx, List<StudyResult> results) {
        StringBuilder md = new StringBuilder("# Participants migration: participant sources (run ")
                .append(ctx.runId()).append(")\n\n")
                .append("Each study uses its SSTR when it has a usable one, otherwise its Subject/Sample MULTI files; ")
                .append("a study with neither file is skipped; one whose files are unusable fails; one whose patient ")
                .append("mapping resolves to none of its subjects is skipped and needs data review.\n\n");

        Map<String, List<StudyResult>> groups = new LinkedHashMap<>();
        groups.put("Loaded from SSTR", results.stream().filter(r -> r.success() && r.source() == Source.SSTR).toList());
        groups.put("Loaded from MULTI (no SSTR)", results.stream()
                .filter(r -> r.success() && r.source() == Source.MULTI && r.usage().sstrFile() == null).toList());
        groups.put("Loaded from MULTI (SSTR malformed)", results.stream()
                .filter(r -> r.success() && r.source() == Source.MULTI && r.usage().sstrFile() != null).toList());
        groups.put("Skipped: no participant files", results.stream()
                .filter(r -> r.skipped() && !r.needsReview()).toList());
        groups.put("Skipped: needs data review (no patient mapping row resolved)", results.stream()
                .filter(StudyResult::needsReview).toList());
        groups.put("Failed: no usable participant source", results.stream()
                .filter(r -> !r.success() && r.usage().hasIssue("NO_PARTICIPANT_SOURCE")).toList());
        groups.put("Failed: MULTI files unusable", results.stream()
                .filter(r -> !r.success() && r.usage().hasIssue("MULTI_LOAD_FAILED")).toList());
        groups.put("Failed: other", results.stream()
                .filter(r -> !r.success() && !r.skipped() && !r.usage().hasIssue("NO_PARTICIPANT_SOURCE")
                        && !r.usage().hasIssue("MULTI_LOAD_FAILED")).toList());

        md.append("| Outcome | Studies |\n|---|---|\n");
        groups.forEach((label, list) -> md.append("| ").append(label).append(" | ").append(list.size()).append(" |\n"));
        md.append("| **Total ready studies** | ").append(results.size()).append(" |\n\n");

        groups.forEach((label, list) -> {
            if (list.isEmpty()) {
                return;
            }
            md.append("## ").append(label).append("\n\n");
            md.append("| Study | Abv | SSTR | Subject MULTI | Sample MULTI | Data dict | Mapping rows | Unresolved | Error |\n")
                    .append("|---|---|---|---|---|---|---|---|---|\n");
            for (StudyResult r : list) {
                SourceUsage u = r.usage();
                md.append("| ").append(mdCell(r.studyId())).append(" | ").append(mdCell(r.abv()))
                        .append(" | ").append(mdCell(u.sstrFile() == null ? "" : u.sstrFile() + " (" + u.sstrStatus() + ")"))
                        .append(" | ").append(mdCell(u.subjectMultiFile()))
                        .append(" | ").append(mdCell(u.sampleMultiFile()))
                        .append(" | ").append(mdCell(u.dataDictFile()))
                        .append(" | ").append(r.mappingRowCount())
                        .append(" | ").append(r.unresolvedHpdsIds().size())
                        .append(" | ").append(mdCell(r.errorMessage())).append(" |\n");
            }
            md.append('\n');
        });

        md.append("## Issues by study\n\n");
        boolean any = false;
        for (StudyResult r : results) {
            List<FileIssue> list = r.usage().issues();
            if (list.isEmpty()) {
                continue;
            }
            any = true;
            md.append("### ").append(r.studyId()).append(" (").append(r.source().label()).append(", ")
                    .append(r.outcome()).append(")\n\n");
            for (FileIssue i : list) {
                md.append("- **").append(i.severity()).append("** `").append(i.code()).append('`');
                if (i.file() != null && !i.file().isEmpty()) {
                    md.append(" in `").append(i.file()).append('`');
                }
                md.append(": ").append(i.message().replace("\n", " "));
                if (!i.examples().isEmpty() && !i.message().contains(i.examples().get(0))) {
                    md.append(" First: ").append(String.join(", ", i.examples()));
                }
                md.append('\n');
            }
            md.append('\n');
        }
        if (!any) {
            md.append("None.\n");
        }
        return md.toString();
    }

    private static String mdCell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String csvRow(String... values) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(Strings.csvQuote(values[i] == null ? "" : values[i].replace("\n", " ")));
        }
        return row.append('\n').toString();
    }

    private void writeReport(JobContext ctx, String fileName, String content) {
        writeReport(ctx, fileName, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes to a {@value #PARTIAL_SUFFIX} file and renames it, so the runner's periodic sync never uploads half a file. */
    private void writeReport(JobContext ctx, String fileName, byte[] content) {
        try {
            Path target = ctx.reportsDir().resolve(fileName);
            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + PARTIAL_SUFFIX);
            Files.write(partial, content);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write report " + fileName, e);
        }
    }

    static boolean isSstrFileFor(String fileName, String studyId) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt")
                && lower.contains(studyId.toLowerCase(Locale.ROOT))
                && (lower.startsWith("sstr_") || lower.startsWith("bdc-ingestion-only__sstr_"));
    }

    /** The canonical NHLBI filename shape {@code sstr_{phs}.{v}.txt} — not a
     * folder-flattened copy ({@code SSTR__sstr_*} lowercases to {@code sstr__…}). */
    static boolean isCanonicalSstrName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.startsWith("sstr_") && !lower.startsWith("sstr__");
    }

    /** {@code rawData/{studyid}*Subject.MULTI.txt}, case-insensitively. */
    static boolean isSubjectMultiFileFor(String fileName, String studyId) {
        return startsWithStudy(fileName, studyId) && fileName.toLowerCase(Locale.ROOT).endsWith("subject.multi.txt");
    }

    /** {@code rawData/{studyid}*Sample.MULTI.txt}, case-insensitively. */
    static boolean isSampleMultiFileFor(String fileName, String studyId) {
        return startsWithStudy(fileName, studyId) && fileName.toLowerCase(Locale.ROOT).endsWith("sample.multi.txt");
    }

    /** {@code rawData/{studyid}*Subject.data_dict.xml}, case-insensitively. */
    static boolean isSubjectDataDictFor(String fileName, String studyId) {
        return startsWithStudy(fileName, studyId)
                && fileName.toLowerCase(Locale.ROOT).endsWith("subject.data_dict.xml");
    }

    private static boolean startsWithStudy(String fileName, String studyId) {
        return fileName.toLowerCase(Locale.ROOT).startsWith(studyId.toLowerCase(Locale.ROOT));
    }

    /**
     * The matching files, newest release first: descending {@link #NATURAL_ORDER}, so
     * {@code phs000007.v33...} comes before {@code phs000007.v9...}. The first is the one used.
     */
    static List<String> newestFirst(List<String> names, Predicate<String> matches) {
        return names.stream().filter(matches).sorted(NATURAL_ORDER.reversed()).toList();
    }

    /**
     * Data dictionaries for the chosen Subject.MULTI, newest first: only those sharing its table
     * accession ({@code pht######}) when any do, since a release holds one dictionary per table.
     */
    static List<String> dataDictCandidates(List<String> names, String studyId, String subjectMulti) {
        List<String> dicts = newestFirst(names, n -> isSubjectDataDictFor(n, studyId));
        if (subjectMulti != null) {
            Matcher m = TABLE_ACCESSION.matcher(subjectMulti.toLowerCase(Locale.ROOT));
            if (m.find()) {
                String accession = m.group();
                List<String> paired = dicts.stream()
                        .filter(d -> d.toLowerCase(Locale.ROOT).contains(accession)).toList();
                if (!paired.isEmpty()) {
                    return paired;
                }
            }
        }
        return dicts;
    }

    private static void reportMultipleCandidates(List<String> candidates, String code, SourceTrace trace) {
        if (candidates.size() > 1) {
            trace.issue(Severity.WARNING, code, candidates.get(0), "found " + candidates.size()
                    + " candidate files; using the newest, '" + candidates.get(0) + "'", candidates);
        }
    }

    private static String first(List<String> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    /** Compares runs of digits numerically and everything else case-insensitively. */
    static final Comparator<String> NATURAL_ORDER = (a, b) -> {
        Matcher ma = Pattern.compile("\\d+|\\D+").matcher(a.toLowerCase(Locale.ROOT));
        Matcher mb = Pattern.compile("\\d+|\\D+").matcher(b.toLowerCase(Locale.ROOT));
        while (ma.find() && mb.find()) {
            String ta = ma.group();
            String tb = mb.group();
            int cmp = Character.isDigit(ta.charAt(0)) && Character.isDigit(tb.charAt(0))
                    ? new java.math.BigInteger(ta).compareTo(new java.math.BigInteger(tb))
                    : ta.compareTo(tb);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length(), b.length());
    };

    private static Set<String> parseStudyFilter(String raw) {
        Set<String> filter = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                filter.add(id);
            }
        }
        return filter;
    }

    private static String joinPath(String folder, String fileName) {
        return folder.endsWith("/") ? folder + fileName : folder + "/" + fileName;
    }

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(ParticipantsMigrationJob.class);

    private static void deleteRecursively(Path dir) {
        try {
            org.springframework.util.FileSystemUtils.deleteRecursively(dir);
        } catch (IOException e) {
            LOG.warn("Failed to clean up staging directory {}: {}", dir, e.getMessage());
        }
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.studyResults().isEmpty()) {
            report.error("NO_READY_STUDIES", "No studies marked ready to process were found");
        }
        for (StudyResult result : output.studyResults()) {
            SourceUsage usage = result.usage();
            if (result.success()) {
                report.info("STUDY_MIGRATED", result.studyId() + " (" + result.source().label() + "): "
                        + result.mappingRowCount() + " mapping row(s)");
                if (result.source() == Source.MULTI && usage.sstrFile() != null) {
                    report.add(ValidationIssue.warning("SSTR_FALLBACK_TO_MULTI", result.studyId() + ": SSTR '"
                                    + usage.sstrFile() + "' is malformed (" + usage.sstrError()
                                    + "); loaded from " + usage.subjectMultiFile() + " instead",
                            Map.of("studyId", result.studyId(), "sstrFile", usage.sstrFile())));
                }
                // MULTI files only: an SSTR study's issues were log-only before the fallback existed.
                long fileWarnings = result.source() != Source.MULTI ? 0 : usage.issues().stream()
                        .filter(i -> i.severity() == Severity.WARNING)
                        .filter(i -> !i.code().equals("SSTR_MALFORMED") && !i.code().equals("PATIENT_MAPPING_UNRESOLVED"))
                        .count();
                if (fileWarnings > 0) {
                    report.add(ValidationIssue.warning("SOURCE_FILE_ISSUES", result.studyId() + ": " + fileWarnings
                                    + " warning(s) reading its " + result.source().label() + " file(s); see " + ISSUES_REPORT,
                            Map.of("studyId", result.studyId(), "count", fileWarnings)));
                }
                // Absent from the migration without anything failing, so report it as a warning
                // to bring the run back UNSTABLE rather than clean.
                if (!result.unresolvedHpdsIds().isEmpty()) {
                    List<String> sample = result.unresolvedHpdsIds().stream().limit(MAX_EXAMPLES).toList();
                    String reason = "could not be resolved via the " + result.source().label() + " file";
                    report.add(ValidationIssue.warning("SUBJECTS_WITHOUT_CONSENT", result.studyId() + ": "
                                    + result.unresolvedHpdsIds().size() + " subject(s) " + reason
                                    + " and were NOT migrated. First: " + sample,
                            Map.of("studyId", result.studyId(),
                                    "path", result.source().label(),
                                    "count", (long) result.unresolvedHpdsIds().size(),
                                    "firstOldHpdsIds", sample)));
                }
            } else if (result.needsReview()) {
                // A warning, not an error: the run (and the pipeline after it) carries on without it.
                report.add(ValidationIssue.warning("STUDY_NEEDS_DATA_REVIEW", result.errorMessage()
                                + "; not migrated, and its mapping file has no rows",
                        Map.of("studyId", result.studyId(), "reason", NEEDS_DATA_REVIEW,
                                "path", result.source().label(),
                                "count", (long) result.unresolvedHpdsIds().size())));
            } else if (result.skipped()) {
                // A warning, not an error: the run (and the pipeline after it) carries on without it.
                report.add(ValidationIssue.warning("STUDY_SKIPPED", result.studyId()
                                + ": no participant files (no SSTR and no Subject.MULTI in rawData); not migrated",
                        Map.of("studyId", result.studyId(), "reason", NO_PARTICIPANT_FILES)));
            } else {
                String reason = usage.hasIssue("NO_PARTICIPANT_SOURCE") ? "NO_PARTICIPANT_SOURCE"
                        : usage.hasIssue("MULTI_LOAD_FAILED") ? "MULTI_LOAD_FAILED" : "OTHER";
                report.add(ValidationIssue.error("STUDY_FAILED", result.studyId() + ": " + result.errorMessage(),
                        null, Map.of("studyId", result.studyId(), "reason", reason)));
            }
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        List<StudyResult> results = output.studyResults();
        long succeeded = results.stream().filter(StudyResult::success).count();
        long skippedStudies = results.stream().filter(StudyResult::skipped).count();
        long failed = results.size() - succeeded - skippedStudies;
        long skipped = results.stream().mapToLong(r -> r.unresolvedHpdsIds().size()).sum();
        // Cast so every count in this report is a long. Jackson writes int 1 and long 1
        // identically; this only matters to callers reading the metrics map in-process.
        builder.metric("readyStudies", (long) results.size())
                .metric("succeededStudies", succeeded)
                .metric("failedStudies", failed)
                .metric("failedStudyIds", idsWhere(results, r -> !r.success() && !r.skipped()))
                .metric("skippedStudies", skippedStudies)
                .metric("skippedStudyIds", idsWhere(results, StudyResult::skipped))
                .metric("needsReviewStudyIds", idsWhere(results, StudyResult::needsReview))
                .metric("subjectsWithoutConsent", skipped)
                .metric("sstrStudyIds", idsWhere(results, r -> r.success() && r.source() == Source.SSTR))
                .metric("multiStudyIds", idsWhere(results, r -> r.success() && r.source() == Source.MULTI))
                .metric("sstrFallbackStudyIds", idsWhere(results, r -> "malformed".equals(r.usage().sstrStatus())))
                .metric("noParticipantSourceStudyIds", idsWhere(results,
                        r -> r.usage().hasIssue("NO_PARTICIPANT_SOURCE") || r.usage().hasIssue(NO_PARTICIPANT_FILES)))
                .metric("reusedStudyIds", idsWhere(results, r -> r.usage().hasIssue(REUSED_ISSUE)));
    }

    private static List<String> idsWhere(List<StudyResult> results, Predicate<StudyResult> p) {
        return results.stream().filter(p).map(StudyResult::studyId).toList();
    }

    /** Where a study's participants came from. */
    public enum Source {
        SSTR("sstr"), MULTI("multi"), NONE("none");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One problem found while discovering or reading a study's source files. */
    public record FileIssue(Severity severity, String code, String file, long count, String message,
                            List<String> examples) {
    }

    /** What a study's source discovery found and did; one row of {@value #SOURCES_REPORT}. */
    public record SourceUsage(String sstrFile, String sstrStatus, String sstrError, String subjectMultiFile,
                              String sampleMultiFile, String dataDictFile, String multiStatus, String consentColumn,
                              Map<String, Long> participantsByConsent, List<FileIssue> issues) {
        public SourceUsage {
            // Absent from study records written before it existed.
            participantsByConsent = participantsByConsent == null ? Map.of() : participantsByConsent;
        }

        boolean hasIssue(String code) {
            return issues.stream().anyMatch(i -> i.code().equals(code));
        }
    }

    /** Accumulates a {@link SourceUsage} while a study is processed, so a failed study still reports it. */
    private static final class SourceTrace {
        private final String studyId;
        private final List<FileIssue> issues = new ArrayList<>();
        String sstrFile;
        String sstrStatus = "absent";
        String sstrError;
        String subjectMultiFile;
        String sampleMultiFile;
        String dataDictFile;
        String multiStatus = "not attempted";
        String consentColumn;
        Map<String, Long> participantsByConsent = Map.of();
        Source source = Source.NONE;

        SourceTrace(String studyId) {
            this.studyId = studyId;
        }

        void issue(Severity severity, String code, String file, String message, List<String> examples) {
            List<String> first = examples.stream().limit(MAX_EXAMPLES).toList();
            long count = examples.isEmpty() ? 1 : examples.size();
            issues.add(new FileIssue(severity, code, file, count, message, first));
            LOG.atLevel(severity == Severity.ERROR ? org.slf4j.event.Level.ERROR
                            : severity == Severity.WARNING ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.INFO)
                    .log("Study '{}': [{}] {}{}", studyId, code, file == null ? "" : file + ": ", message);
        }

        /** Re-files a sub-job issue, which carries {@code file}/{@code count}/{@code examples} in its context. */
        void issue(ValidationIssue i) {
            Map<String, Object> ctx = i.context() == null ? Map.of() : i.context();
            Object file = ctx.get("file");
            Object count = ctx.get("count");
            List<String> examples = ctx.get("examples") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of();
            issues.add(new FileIssue(i.severity(), i.code(), file == null ? null : file.toString(),
                    count instanceof Number n ? n.longValue() : 1, i.message(), examples));
        }

        SourceUsage snapshot() {
            return new SourceUsage(sstrFile, sstrStatus, sstrError, subjectMultiFile, sampleMultiFile, dataDictFile,
                    multiStatus, consentColumn, participantsByConsent, List.copyOf(issues));
        }
    }

    /** Discovered files; each MULTI list is newest first, and its first entry is the one used. */
    private record StudyFiles(String rawDataDir, Path studyDir, Path sstrFile, List<String> subjectMultis,
                              List<String> sampleMultis, List<String> dataDicts, Path patientMappingFile) {
        String subjectMulti() {
            return first(subjectMultis);
        }

        String sampleMulti() {
            return first(sampleMultis);
        }

        String dataDict() {
            return first(dataDicts);
        }
    }

    /**
     * @param file                  the source file the ids came from, for messages
     * @param dbgapIds              dbGaP subject ids the source loaded
     * @param subjectIdToDbgapId    the study's own subject id &rarr; dbGaP id
     * @param notConsentedIds       dbGaP ids and the study's own subject ids of subjects dropped for consent 0
     */
    private record SubjectIdentities(String file, Set<String> dbgapIds, Map<String, String> subjectIdToDbgapId,
                                     Set<String> notConsentedIds) {
    }

    private record MappingResult(List<MappingRow> mappingRows, List<String> unresolvedHpdsIds,
                                 int notInFile, int noParticipant, int notConsented) {
    }

    private record PatientMappingRow(String id, String oldHpdsId) {
    }

    private record MappingRow(String oldHpdsId, long newHpdsId, String commonDbgapId) {
    }

    /** Outcome of migrating one study; inspected by {@link #validateOutput}/{@link #report}. */
    public record StudyResult(String studyId, String abv, Source source, boolean success, String errorMessage,
                              int mappingRowCount, List<String> unresolvedHpdsIds, SourceUsage usage) {
        static StudyResult success(String studyId, String abv, Source source, int mappingRowCount,
                                   List<String> unresolvedHpdsIds, SourceUsage usage) {
            return new StudyResult(studyId, abv, source, true, null, mappingRowCount, unresolvedHpdsIds, usage);
        }

        static StudyResult failed(String studyId, String abv, String errorMessage, SourceUsage usage) {
            return new StudyResult(studyId, abv, Source.NONE, false, errorMessage, 0, List.of(), usage);
        }

        static StudyResult needsReview(String studyId, String abv, Source source, String errorMessage,
                                       List<String> unresolvedHpdsIds, SourceUsage usage) {
            return new StudyResult(studyId, abv, source, false, errorMessage, 0, unresolvedHpdsIds, usage);
        }

        /**
         * Not migrated because it has no participant files, or needs data review; reported, but not
         * counted as failed.
         */
        @JsonIgnore
        boolean skipped() {
            return !success && (usage.hasIssue(NO_PARTICIPANT_FILES) || usage.hasIssue(NEEDS_DATA_REVIEW));
        }

        /** Skipped because no patient mapping row resolved to a loaded subject. */
        @JsonIgnore
        boolean needsReview() {
            return !success && usage.hasIssue(NEEDS_DATA_REVIEW);
        }

        /** success, skipped, needs_review or failed: the outcome column of {@value #SOURCES_REPORT}. */
        @JsonIgnore
        String outcome() {
            return success ? "success" : needsReview() ? "needs_review" : skipped() ? "skipped" : "failed";
        }
    }

    /** One study's line in {@value #STUDY_RESULTS_DIR}/: its result and the data folder it was migrated from. */
    record StudyRecord(String dataFolder, StudyResult result) {
    }

    /** Immutable result of {@link #execute}; inspected by {@link #validateOutput}/{@link #report}. */
    public record Output(List<StudyResult> studyResults) {
    }
}
