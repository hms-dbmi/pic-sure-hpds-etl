package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.Strings;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SstrPopulateRdsParticipantsJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Kind;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Measure;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Row;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Status;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.StudyInput;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.StudyResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.SummaryOutcome;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.SummarySource;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * PERMANENT QA, the last step of both pipelines. For <em>every</em> study marked ready in the managed
 * inputs -- not only those the run processed, so it is a regression check as well -- compares each
 * consent group's counts with the study version's dbGaP summary:
 * <ul>
 *   <li>the participant DB, for every study: distinct participants against {@code subject_count},
 *       and the study's own dbGaP samples against {@code sample_count};</li>
 *   <li>for the studies the run processed, every per-consent count its job reports and final report
 *       give ({@code --run-counts}, collected by the orchestrator), against {@code subject_count}.</li>
 * </ul>
 *
 * <p>Summaries are read from {@code {data-root}/{study_id}/rawData/{study_id}_{version}_summary.json}
 * when there (see {@link SummaryCache}). Only the rest are asked of dbGaP's SSTR API -- one at a time by
 * default, paced and retried gently (see {@link DbgapBatchLookup}), until {@code --dbgap-deadline-seconds}
 * -- and each answer is stored there at once ({@code --cache-summaries}), so a later run does not ask
 * again. The DB counts, the run counts and the cache are read in parallel.
 *
 * <p>Read-only, apart from the cached summaries. Outcomes:
 * <ul>
 *   <li>every count matches: exit 0;</li>
 *   <li>any discrepancy (see {@link Status}), or a study with no summary (UNVERIFIED): a WARNING per
 *       study and finding, SUCCESS_WITH_WARNINGS (validate.sh exits 10: the build is UNSTABLE);</li>
 *   <li>no study with a usable version has a summary at all (none cached, and dbGaP answered for none):
 *       exit 4, with the report files still written.</li>
 * </ul>
 *
 * <p>Writes into the reports directory, beside the JSON report: {@value PipelineCountsReport#COUNTS_FILE},
 * {@value PipelineCountsReport#DISCREPANCIES_FILE}, {@value PipelineCountsReport#STUDIES_FILE} and the
 * readable {@value PipelineCountsReport#MARKDOWN_FILE}.
 *
 * <p>Enabled by {@code etl.jobs.qa-pipeline-counts.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.qa-pipeline-counts.enabled", havingValue = "true")
public class QaPipelineCountsJob extends AbstractJob<QaPipelineCountsJob.Output> {

    /** One at a time: dbGaP's gateway answers 502 when sent several at once. */
    static final int DEFAULT_CONCURRENCY = 1;
    /** 90 minutes: the pipeline's end is not held for longer; what is not fetched by then is UNVERIFIED. */
    static final int DEFAULT_DEADLINE_SECONDS = 5400;
    static final int DEFAULT_CACHE_CONCURRENCY = 8;
    static final int MAX_CACHE_CONCURRENCY = 64;

    static final String COL_STUDY_ID = "study_id";
    static final String COL_CONSENT_CODE = "consent_code";
    static final String COL_SOURCE = "source";
    static final String COL_PARTICIPANTS = "participants";
    /** Optional: "subjects" (the default) or "samples", the dbGaP count the source is compared with. */
    static final String COL_KIND = "kind";

    private final ManagedInputsService managedInputsService;
    private final ConsentRepository consents;
    private final SampleRepository samples;
    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final ObjectMapper mapper;
    private final Function<DbgapStudyClient.Config, DbgapStudySource> sourceFactory;

    @Autowired
    public QaPipelineCountsJob(ManagedInputsService managedInputsService, ConsentRepository consents,
                               SampleRepository samples, IoResolver io, DelimitedReader delimitedReader,
                               ObjectMapper mapper) {
        this(managedInputsService, consents, samples, io, delimitedReader, mapper,
                config -> new DbgapStudyClient(config, mapper));
    }

    /** For tests: {@code sourceFactory} builds the dbGaP source from the run's client settings. */
    QaPipelineCountsJob(ManagedInputsService managedInputsService, ConsentRepository consents,
                        SampleRepository samples, IoResolver io, DelimitedReader delimitedReader, ObjectMapper mapper,
                        Function<DbgapStudyClient.Config, DbgapStudySource> sourceFactory) {
        this.managedInputsService = managedInputsService;
        this.consents = consents;
        this.samples = samples;
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.mapper = mapper;
        this.sourceFactory = sourceFactory;
    }

    @Override
    public String name() {
        return "qa-pipeline-counts";
    }

    @Override
    public JobType type() {
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        List<ParamSpec> params = new ArrayList<>(List.of(
                ParamSpec.required("data-root",
                        "Root of the per-study folders: summaries are cached at "
                                + "{data-root}/{study_id}/rawData/{study_id}_{version}_summary.json",
                        "s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/BAM_testing"),
                ParamSpec.optional("run-counts",
                        "This run's counts per consent group from its job reports and final report: a CSV with "
                                + "columns study_id,consent_code,source,participants[,kind] (kind: subjects, the "
                                + "default, or samples). Studies it does not list are compared DB vs dbGaP only. "
                                + "Blank: every study DB vs dbGaP only",
                        "s3://bucket/etl-runner/reports/permanent-pipeline/<tag>/qa_run_counts.csv"),
                ParamSpec.optional("pipeline",
                        "Which pipeline ran: migration or permanent (shown in the report)", "permanent"),
                ParamSpec.optional("study-filter",
                        "Comma-separated study ids to check; blank checks every ready study", "phs000200,phs001412"),
                ParamSpec.optional("cache-concurrency",
                        "Cached summaries read at once (1-" + MAX_CACHE_CONCURRENCY + "; default "
                                + DEFAULT_CACHE_CONCURRENCY + ")", "8"),
                ParamSpec.optional("cache-summaries",
                        "Store each summary fetched from dbGaP in the cache (default true)", "true")));
        params.addAll(DbgapParams.specs(DEFAULT_CONCURRENCY, DEFAULT_DEADLINE_SECONDS));
        return JobExpectations.of(params, List.of(
                PipelineCountsReport.COUNTS_FILE + ", " + PipelineCountsReport.DISCREPANCIES_FILE + ", "
                        + PipelineCountsReport.STUDIES_FILE + " and " + PipelineCountsReport.MARKDOWN_FILE
                        + " in the reports directory; summaries fetched from dbGaP cached under data-root; a WARNING "
                        + "per study and finding; exit 4 when no study has a summary"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        DbgapParams.validate(ctx, report);
        DbgapParams.intParam(ctx, report, "cache-concurrency", 1, MAX_CACHE_CONCURRENCY);
        validateBooleanParam(ctx, report, "cache-summaries");
        String pipeline = Strings.trimToNull(ctx.get("pipeline", ""));
        if (pipeline != null && !List.of("migration", "permanent").contains(pipeline.toLowerCase(Locale.ROOT))) {
            report.error("BAD_PARAM", "pipeline must be migration or permanent, got: " + pipeline, "--pipeline");
        }
        if (report.hasErrors()) {
            return;
        }
        ctx.get("run-counts").ifPresent(uri -> {
            if (!io.exists(uri.trim())) {
                report.error("RUN_COUNTS_NOT_FOUND", "--run-counts " + uri + " does not exist", "--run-counts");
            }
        });
        List<ManagedInputRow> rows = managedInputsService.read();
        Set<String> filter = studyFilter(ctx);
        List<ManagedInputRow> selected = select(rows, filter);
        Set<String> selectedIds = new LinkedHashSet<>(selected.stream().map(ManagedInputRow::studyId).toList());
        for (String id : filter) {
            if (!selectedIds.contains(id)) {
                report.add(ValidationIssue.warning("STUDY_FILTER_UNMATCHED",
                        id + " is in --study-filter but is not a ready study in the managed inputs; not checked",
                        Map.of("studyId", id)));
            }
        }
        if (selected.isEmpty()) {
            report.warning("NO_STUDIES_TO_CHECK", "No ready studies in the managed inputs (" + rows.size()
                    + " row(s))" + (filter.isEmpty() ? "" : " match --study-filter") + "; nothing was checked");
        }
    }

    @Override
    protected Output execute(JobContext ctx) {
        List<ManagedInputRow> studies = select(managedInputsService.read(), studyFilter(ctx));
        List<String> studyIds = studies.stream().map(ManagedInputRow::studyId).toList();
        String runCountsUri = Strings.trimToNull(ctx.get("run-counts", ""));
        SummaryCache cache = new SummaryCache(io, mapper, ctx.require("data-root").trim());
        boolean cacheSummaries = ctx.getBoolean("cache-summaries", true);
        int cacheConcurrency = DbgapParams.intValue(ctx, "cache-concurrency", DEFAULT_CACHE_CONCURRENCY);

        // The DB, the run's counts and the cached summaries are independent: read them all at once.
        AtomicInteger threads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(3, cacheConcurrency), r -> {
            Thread t = new Thread(r, "qa-read-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        Map<String, Map<String, Long>> dbParticipants = new LinkedHashMap<>();
        Map<String, Map<String, String>> dbAbbreviations = new LinkedHashMap<>();
        Map<String, Map<String, Long>> dbSamples = new LinkedHashMap<>();
        Map<String, Map<Measure, Map<String, Long>>> runCounts;
        Map<String, SummaryCache.Entry> cached = new LinkedHashMap<>();
        try {
            CompletableFuture<List<ConsentRepository.ConsentGroupCount>> participantsFuture =
                    CompletableFuture.supplyAsync(() -> consents.countParticipantsByConsent(studyIds), pool);
            CompletableFuture<List<SampleRepository.ConsentGroupSamples>> samplesFuture =
                    CompletableFuture.supplyAsync(() -> samples.countSamplesByConsent(studyIds,
                            SstrPopulateRdsParticipantsJob.SOURCE), pool);
            CompletableFuture<Map<String, Map<Measure, Map<String, Long>>>> runFuture =
                    CompletableFuture.supplyAsync(() -> runCountsUri == null ? Map.of() : readRunCounts(runCountsUri), pool);
            Map<String, CompletableFuture<SummaryCache.Entry>> cacheFutures = new LinkedHashMap<>();
            for (ManagedInputRow s : studies) {
                String version = ConsentCountComparison.normalizeVersion(s.version());
                if (version != null) {
                    cacheFutures.put(s.studyId(), CompletableFuture.supplyAsync(() -> cache.read(s.studyId(), version), pool));
                }
            }
            for (ConsentRepository.ConsentGroupCount c : join(participantsFuture)) {
                String code = ConsentCountComparison.normalizeConsent(c.consentCode());
                // "1" and "c1" are one group: participants are unique per study, so their counts add up.
                dbParticipants.computeIfAbsent(c.studyId(), k -> new LinkedHashMap<>()).merge(code, c.participants(), Long::sum);
                if (c.consentAbbreviation() != null) {
                    dbAbbreviations.computeIfAbsent(c.studyId(), k -> new LinkedHashMap<>()).putIfAbsent(code, c.consentAbbreviation());
                }
            }
            for (SampleRepository.ConsentGroupSamples c : join(samplesFuture)) {
                dbSamples.computeIfAbsent(c.studyId(), k -> new LinkedHashMap<>())
                        .merge(ConsentCountComparison.normalizeConsent(c.consentCode()), c.samples(), Long::sum);
            }
            runCounts = join(runFuture);
            cacheFutures.forEach((id, f) -> cached.put(id, join(f)));
        } finally {
            pool.shutdownNow();
        }
        log.info("Read DB counts for {} study/studies, run counts for {}, and {} cached summary/summaries of {} looked for",
                dbParticipants.size(), runCounts.size(), cached.values().stream().filter(SummaryCache.Entry::found).count(),
                cached.size());

        // Only what is not cached is asked of dbGaP, and each answer is cached as it arrives.
        List<DbgapBatchLookup.Request> toAsk = new ArrayList<>();
        Map<String, String> cacheErrors = new LinkedHashMap<>();
        for (ManagedInputRow s : studies) {
            SummaryCache.Entry entry = cached.get(s.studyId());
            if (entry == null || entry.found()) {
                continue;
            }
            if (entry.error() != null) {
                cacheErrors.put(s.studyId(), entry.uri() + ": " + entry.error());
                log.warn("Cached summary {} is unusable ({}); asking dbGaP", entry.uri(), entry.error());
            }
            toAsk.add(new DbgapBatchLookup.Request(s.studyId(), ConsentCountComparison.normalizeVersion(s.version())));
        }
        Map<String, String> uploaded = new ConcurrentHashMap<>();
        Map<String, String> uploadFailures = new ConcurrentHashMap<>();
        DbgapStudyClient.Config config = DbgapParams.clientConfig(ctx);
        Map<String, DbgapLookup> lookups = toAsk.isEmpty() ? Map.of()
                : DbgapBatchLookup.lookUp(toAsk, sourceFactory.apply(config), config.baseUrl(),
                        DbgapParams.concurrency(ctx, DEFAULT_CONCURRENCY), DbgapParams.giveUpAfter(ctx),
                        DbgapParams.unreachablePause(ctx), DbgapParams.deadline(ctx, DEFAULT_DEADLINE_SECONDS),
                        (request, lookup) -> {
                            if (cacheSummaries) {
                                cacheAnswer(cache, request, lookup, uploaded, uploadFailures);
                            }
                        });

        List<StudyResult> results = new ArrayList<>();
        for (ManagedInputRow s : studies) {
            String sid = s.studyId();
            Map<Measure, Map<String, Long>> counts = new LinkedHashMap<>();
            counts.put(PipelineCountComparison.DB_PARTICIPANTS, dbParticipants.getOrDefault(sid, Map.of()));
            counts.put(PipelineCountComparison.DB_SAMPLES, dbSamples.getOrDefault(sid, Map.of()));
            counts.putAll(runCounts.getOrDefault(sid, Map.of()));
            StudyInput in = new StudyInput(sid, s.abv(), ConsentCountComparison.normalizeVersion(s.version()),
                    s.version(), counts, dbAbbreviations.getOrDefault(sid, Map.of()));
            results.add(PipelineCountComparison.compare(in, outcome(cached.get(sid), lookups.get(sid))));
        }

        String pipeline = Strings.trimToNull(ctx.get("pipeline", ""));
        Output output = new Output(results, pipeline == null ? null : pipeline.toLowerCase(Locale.ROOT), runCountsUri,
                runCounts.size(), lookups, Map.copyOf(uploaded), Map.copyOf(uploadFailures), cacheErrors);
        PipelineCountsReport.write(ctx.reportsDir(), ctx.runId(), output);

        List<String> unverified = output.unverifiedStudies();
        long withVersion = results.stream().filter(r -> r.input().version() != null).count();
        if (withVersion > 0 && output.studiesWithSummary() == 0) {
            ValidationReport partial = new ValidationReport("output");
            addIssues(output, partial);
            throw new InfrastructureException("No study has a dbGaP summary: none of the " + withVersion
                    + " is cached under " + ctx.require("data-root").trim() + ", and dbGaP gave no usable answer "
                    + "for any after retries. Their rows in " + PipelineCountsReport.COUNTS_FILE + " are UNVERIFIED. "
                    + "Retryable.")
                    .withPartialResult(metrics(output), partial);
        }
        if (!unverified.isEmpty()) {
            log.warn("{} of {} study/studies have no summary (UNVERIFIED): {}", unverified.size(), results.size(),
                    String.join(", ", unverified));
        }
        return output;
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        addIssues(output, report);
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metrics(metrics(output));
    }

    /* ---- Inputs ---- */

    /** Ready studies (each once), restricted to {@code filter} when it is not empty. */
    private static List<ManagedInputRow> select(List<ManagedInputRow> rows, Set<String> filter) {
        Map<String, ManagedInputRow> byId = new LinkedHashMap<>();
        for (ManagedInputRow row : rows) {
            if (row.isReady() && (filter.isEmpty() || filter.contains(row.studyId()))) {
                byId.putIfAbsent(row.studyId(), row);
            }
        }
        return List.copyOf(byId.values());
    }

    private static Set<String> studyFilter(JobContext ctx) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : ctx.get("study-filter", "").split(",")) {
            if (!id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return ids;
    }

    /**
     * The run's counts: study id to measure (one per source, in first-seen order) to normalized consent
     * code to count. A row with a blank count is skipped (the source did not count that group).
     */
    Map<String, Map<Measure, Map<String, Long>>> readRunCounts(String uri) {
        Map<String, Map<Measure, Map<String, Long>>> byStudy = new LinkedHashMap<>();
        long line = 1;
        try (InputStream in = io.openInput(uri);
             Stream<Map<String, String>> stream = delimitedReader.stream(in, DelimitedReader.COMMA)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                line++;
                if (line == 2) {
                    List<String> missing = Stream.of(COL_STUDY_ID, COL_CONSENT_CODE, COL_SOURCE, COL_PARTICIPANTS)
                            .filter(c -> !row.containsKey(c)).toList();
                    if (!missing.isEmpty()) {
                        throw new DataException("--run-counts " + uri + " is missing column(s) " + missing
                                + "; found " + row.keySet());
                    }
                }
                String sid = Strings.trimToNull(row.get(COL_STUDY_ID));
                String code = Strings.trimToNull(row.get(COL_CONSENT_CODE));
                String source = Strings.trimToNull(row.get(COL_SOURCE));
                String n = Strings.trimToNull(row.get(COL_PARTICIPANTS));
                String kind = Strings.trimToNull(row.get(COL_KIND));
                if (sid == null || code == null || source == null || (n != null && !n.matches("\\d{1,18}"))
                        || (kind != null && !List.of("subjects", "samples").contains(kind.toLowerCase(Locale.ROOT)))) {
                    throw new DataException("--run-counts " + uri + " line " + line + ": need a study_id, a "
                            + "consent_code, a source, a whole-number (or blank) participants and a kind of subjects "
                            + "or samples (or blank), got " + row);
                }
                if (n == null) {
                    continue;
                }
                Measure measure = new Measure(source, "samples".equalsIgnoreCase(kind) ? Kind.SAMPLES : Kind.SUBJECTS);
                byStudy.computeIfAbsent(sid, k -> new LinkedHashMap<>())
                        .computeIfAbsent(measure, k -> new LinkedHashMap<>())
                        .merge(ConsentCountComparison.normalizeConsent(code), Long.parseLong(n), Long::sum);
            }
        } catch (IOException e) {
            throw new InfrastructureException("Could not read --run-counts " + uri + ": " + e.getMessage(), e);
        }
        log.info("Read run counts for {} study/studies from {}", byStudy.size(), uri);
        return byStudy;
    }

    /* ---- Summaries ---- */

    /** Caches a summary dbGaP answered with, unless it describes another study version than requested. */
    private void cacheAnswer(SummaryCache cache, DbgapBatchLookup.Request request, DbgapLookup lookup,
                             Map<String, String> uploaded, Map<String, String> uploadFailures) {
        if (lookup.outcome() != DbgapLookup.Outcome.FOUND || lookup.study() == null) {
            return;
        }
        DbgapStudySummary s = lookup.summary();
        if ((s.accession() != null && !s.accession().equalsIgnoreCase(request.studyId()))
                || (s.version() != null && !("v" + s.version()).equals(request.version()))) {
            log.warn("dbGaP {}.{}: the answer is for {}.v{}; not cached", request.studyId(), request.version(),
                    s.accession(), s.version());
            return;
        }
        String uri = cache.uri(request.studyId(), request.version());
        try {
            cache.write(request.studyId(), request.version(), lookup.study());
            uploaded.put(request.studyId(), uri);
            log.info("Cached the dbGaP summary of {}.{} at {}", request.studyId(), request.version(), uri);
        } catch (IOException | RuntimeException e) {
            uploadFailures.put(request.studyId(), uri + ": " + e.getMessage());
            log.warn("Could not cache the dbGaP summary of {}.{} at {}: {}", request.studyId(), request.version(),
                    uri, e.toString());
        }
    }

    /** The study's summary: the cached one, else dbGaP's answer, else why there is none. */
    private static SummaryOutcome outcome(SummaryCache.Entry cached, DbgapLookup lookup) {
        if (cached != null && cached.found()) {
            return SummaryOutcome.of(DbgapStudySummary.fromDocument(cached.document()), SummarySource.CACHE);
        }
        if (lookup == null) {
            // No usable version: compare() reports NO_VERSION.
            return SummaryOutcome.none(null, null);
        }
        return switch (lookup.outcome()) {
            case FOUND -> SummaryOutcome.of(lookup.summary(), SummarySource.API);
            case NOT_FOUND -> SummaryOutcome.none(Status.VERSION_NOT_FOUND, "dbGaP has no such study version: "
                    + lookup.message());
            case FAILED -> SummaryOutcome.none(Status.UNVERIFIED, "no cached summary"
                    + (cached != null && cached.error() != null ? " (" + cached.error() + ")" : "")
                    + ", and dbGaP gave no usable answer: " + lookup.message());
        };
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    /* ---- Issues and metrics ---- */

    /** Rows quoted in one issue's message. */
    private static final int ISSUE_ROWS_QUOTED = 10;

    /** Per study: one WARNING for a study-level finding, else one per kind of row finding. */
    private static void addIssues(Output output, ValidationReport report) {
        for (StudyResult r : output.results()) {
            StudyInput in = r.input();
            String label = in.studyId() + (in.version() == null ? "" : "." + in.version());
            if (r.studyStatus() != null) {
                String code = r.studyStatus() == Status.UNVERIFIED ? "DBGAP_UNAVAILABLE" : r.studyStatus().name();
                report.add(ValidationIssue.warning(code, label + ": " + r.studyNote(), context(in, null, null)));
                continue;
            }
            Map<Status, List<Row>> byStatus = new EnumMap<>(Status.class);
            for (Row row : r.rows()) {
                if (row.status().discrepancy()) {
                    byStatus.computeIfAbsent(row.status(), k -> new ArrayList<>()).add(row);
                }
            }
            byStatus.forEach((status, rows) -> {
                List<String> quoted = rows.stream().limit(ISSUE_ROWS_QUOTED).map(row -> "c" + row.consentCode()
                        + " " + row.measure().name() + " " + row.count() + " vs dbGaP "
                        + (row.dbgapCount() == null ? "?" : row.dbgapCount())).toList();
                report.add(ValidationIssue.warning(status.name(), label + ": " + rows.size() + " count(s): "
                        + String.join("; ", quoted) + (rows.size() > quoted.size() ? "; ..." : ""),
                        context(in, status, rows.size())));
            });
        }
    }

    private static Map<String, Object> context(StudyInput in, Status status, Integer rows) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("studyId", in.studyId());
        c.put("version", in.version() != null ? in.version() : in.rawVersion());
        c.put("inRun", in.inRun());
        if (rows != null) {
            c.put("rows", rows);
        }
        return c;
    }

    static Map<String, Object> metrics(Output output) {
        Map<String, Long> byType = new LinkedHashMap<>();
        Map<String, Long> byMeasure = new LinkedHashMap<>();
        long compared = 0;
        long matching = 0;
        List<String> withDiscrepancies = new ArrayList<>();
        Set<String> runSources = new LinkedHashSet<>();
        for (StudyResult r : output.results()) {
            r.input().counts().keySet().stream().map(Measure::name)
                    .filter(n -> !n.startsWith("db:")).forEach(runSources::add);
            if (r.studyStatus() != null && r.studyStatus().discrepancy()) {
                byType.merge(r.studyStatus().name(), 1L, Long::sum);
            }
            for (Row row : r.rows()) {
                if (r.verified()) {
                    compared++;
                    if (!row.status().discrepancy()) {
                        matching++;
                    }
                }
                if (row.status().discrepancy() && !row.status().studyLevel()) {
                    byType.merge(row.status().name(), 1L, Long::sum);
                    byMeasure.merge(row.measure().name(), 1L, Long::sum);
                }
            }
            if (r.discrepancies() > 0) {
                withDiscrepancies.add(r.input().studyId());
            }
        }
        int attempts = 0;
        int retries = 0;
        for (DbgapLookup l : output.lookups().values()) {
            attempts += l.attempts();
            retries += Math.max(0, l.attempts() - 1);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pipeline", output.pipeline());
        m.put("studiesChecked", output.results().size());
        m.put("studiesInRun", output.results().stream().filter(r -> r.input().inRun()).count());
        m.put("studiesVerified", output.results().stream().filter(StudyResult::verified).count());
        m.put("countsCompared", compared);
        m.put("countsMatching", matching);
        m.put("discrepancies", byType.values().stream().mapToLong(Long::longValue).sum());
        m.put("discrepanciesByType", byType);
        m.put("discrepanciesByMeasure", byMeasure);
        m.put("studiesWithDiscrepancies", withDiscrepancies);
        m.put("unverifiedStudies", output.unverifiedStudies());
        m.put("summariesFromCache", output.results().stream().filter(r -> r.summarySource() == SummarySource.CACHE).count());
        m.put("summariesFromApi", output.results().stream().filter(r -> r.summarySource() == SummarySource.API).count());
        m.put("summariesCached", output.uploaded().size());
        m.put("summaryCacheFailures", output.uploadFailures());
        m.put("unusableCachedSummaries", output.cacheErrors());
        m.put("apiLookups", output.lookups().size());
        m.put("apiAttempts", attempts);
        m.put("apiRetries", retries);
        m.put("runCounts", output.runCountsUri());
        m.put("runCountSources", List.copyOf(runSources));
        m.put("reportFiles", List.of(PipelineCountsReport.COUNTS_FILE, PipelineCountsReport.DISCREPANCIES_FILE,
                PipelineCountsReport.STUDIES_FILE, PipelineCountsReport.MARKDOWN_FILE));
        return m;
    }

    /**
     * Result of {@link #execute}.
     *
     * @param results            one per checked study, in managed inputs order
     * @param pipeline           "migration", "permanent", or null
     * @param runCountsUri       the --run-counts given, or null
     * @param studiesInRunCounts studies the run counts list
     * @param lookups            dbGaP's answer per study asked (those without a cached summary)
     * @param uploaded           study id to the URI its fetched summary was cached at
     * @param uploadFailures     study id to why its fetched summary could not be cached
     * @param cacheErrors        study id to why its cached summary could not be used
     */
    public record Output(List<StudyResult> results, String pipeline, String runCountsUri, int studiesInRunCounts,
                         Map<String, DbgapLookup> lookups, Map<String, String> uploaded,
                         Map<String, String> uploadFailures, Map<String, String> cacheErrors) {

        List<String> unverifiedStudies() {
            return results.stream().filter(r -> r.studyStatus() == Status.UNVERIFIED)
                    .map(r -> r.input().studyId()).toList();
        }

        /** Studies whose summary was found (cached or fetched), or that dbGaP said do not exist. */
        long studiesWithSummary() {
            return results.stream().filter(r -> r.summarySource() != SummarySource.NONE
                    || r.studyStatus() == Status.VERSION_NOT_FOUND).count();
        }
    }
}
