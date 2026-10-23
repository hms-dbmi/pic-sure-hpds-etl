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
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.ConsentCountComparison.DbConsent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.ConsentCountComparison.Row;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.ConsentCountComparison.Status;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.ConsentCountComparison.StudyInput;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.ConsentCountComparison.StudyResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * PERMANENT QA. For every ready study in the managed inputs, counts the distinct participants per
 * consent group in the participant DB and compares them with dbGaP's subject counts for that study
 * version, read from the SSTR API ({@code {dbgap-base-url}/{phs}.{v}/summary}). When given this
 * run's load counts ({@code --load-counts}), it also checks that the DB holds what the loads
 * reported. The DB is authoritative: a load report that disagrees with it is a discrepancy too.
 *
 * <p>Runs last in both pipelines (/Jenkinsfile and Jenkinsfile.migration), read-only. Outcomes:
 * <ul>
 *   <li>every consent matches: exit 0;</li>
 *   <li>any discrepancy (see {@link Status}): one WARNING issue each, SUCCESS_WITH_WARNINGS (exit 0;
 *       validate.sh exits 10 and the build is UNSTABLE);</li>
 *   <li>dbGaP gave no usable answer for some studies after their retries, but answered for at
 *       least one: those studies are UNVERIFIED, one DBGAP_UNAVAILABLE WARNING each, and the run is
 *       SUCCESS_WITH_WARNINGS (UNSTABLE) like any discrepancy. Every other study is compared and
 *       both CSVs are written, the unanswered studies' rows UNVERIFIED;</li>
 *   <li>dbGaP answered for no study at all: exit 4 (INFRASTRUCTURE_ERROR, retryable). The CSVs are
 *       still written and the JSON report still carries the metrics and issues (via
 *       {@link InfrastructureException#withPartialResult}).</li>
 * </ul>
 * A study version dbGaP does not know (HTTP 400, error_code 2) is a VERSION_NOT_FOUND discrepancy,
 * not an outage.
 *
 * <p>Writes into the reports directory, beside the JSON report: {@value #COUNTS_FILE} (one row per
 * study and consent code) and {@value #DISCREPANCIES_FILE} (its non-MATCH rows), columns
 * {@code study_id,version,consent_code,consent_short_name,dbgap_subjects,db_participants,
 * load_participants,load_source,status,note}. {@code status} is MATCH or the findings joined with ';'.
 *
 * <p>The runner needs HTTPS egress to www.ncbi.nlm.nih.gov (see docs/JENKINS.md).
 *
 * <p>Enabled by {@code etl.jobs.qa-consent-counts.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.qa-consent-counts.enabled", havingValue = "true")
public class QaConsentCountsJob extends AbstractJob<QaConsentCountsJob.Output> {

    static final String COUNTS_FILE = "qa_consent_counts.csv";
    static final String DISCREPANCIES_FILE = "qa_consent_count_discrepancies.csv";
    static final String CSV_HEADER = "study_id,version,consent_code,consent_short_name,dbgap_subjects,"
            + "db_participants,load_participants,load_source,status,note";

    static final String COL_STUDY_ID = "study_id";
    static final String COL_CONSENT_CODE = "consent_code";
    static final String COL_PARTICIPANTS_LOADED = "participants_loaded";
    static final String COL_SOURCE = "source";

    static final int DEFAULT_CONCURRENCY = 4;
    static final int DEFAULT_GIVE_UP_AFTER = 20;
    /** 90 minutes: plus one in-flight lookup's retries (~6 min), inside the runner's 7200 s monitor timeout. */
    static final int DEFAULT_DEADLINE_SECONDS = 5400;
    static final int MAX_CONCURRENCY = 16;

    private final ManagedInputsService managedInputsService;
    private final ConsentRepository consents;
    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final Function<DbgapStudyClient.Config, DbgapStudySource> sourceFactory;

    @Autowired
    public QaConsentCountsJob(ManagedInputsService managedInputsService, ConsentRepository consents, IoResolver io,
                              DelimitedReader delimitedReader, ObjectMapper mapper) {
        this(managedInputsService, consents, io, delimitedReader, config -> new DbgapStudyClient(config, mapper));
    }

    /** For tests: {@code sourceFactory} builds the dbGaP source from the run's client settings. */
    QaConsentCountsJob(ManagedInputsService managedInputsService, ConsentRepository consents, IoResolver io,
                       DelimitedReader delimitedReader,
                       Function<DbgapStudyClient.Config, DbgapStudySource> sourceFactory) {
        this.managedInputsService = managedInputsService;
        this.consents = consents;
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.sourceFactory = sourceFactory;
    }

    @Override
    public String name() {
        return "qa-consent-counts";
    }

    @Override
    public JobType type() {
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        DbgapStudyClient.Config d = DbgapStudyClient.Config.defaults();
        return JobExpectations.of(
                List.of(
                        ParamSpec.optional("load-counts",
                                "This run's participants loaded per consent group: a CSV with columns "
                                        + "study_id,consent_code,participants_loaded[,source] (local path or s3:// URI; "
                                        + "participants_migration_consent_counts.csv and migration_consent_counts.csv "
                                        + "qualify; other columns are ignored). Studies it does not list, and groups with "
                                        + "a blank participants_loaded (not loaded this run), are compared DB vs dbGaP "
                                        + "only. Blank skips the load-report check.",
                                "s3://bucket/etl-runner/reports/permanent-pipeline/<tag>/qa_load_counts.csv"),
                        ParamSpec.optional("study-filter",
                                "Comma-separated study ids to check; blank checks every ready study", "phs000200,phs001412"),
                        ParamSpec.optional("concurrency",
                                "Studies looked up in dbGaP at once (1-" + MAX_CONCURRENCY + "; default "
                                        + DEFAULT_CONCURRENCY + ")", "4"),
                        ParamSpec.optional("dbgap-base-url",
                                "dbGaP SSTR study endpoint (default " + d.baseUrl() + ")", d.baseUrl()),
                        ParamSpec.optional("dbgap-max-attempts",
                                "Requests per study, retries included (1-20; default " + d.maxAttempts() + ")", "3"),
                        ParamSpec.optional("dbgap-initial-backoff-seconds",
                                "Wait after the first failed request, doubling per retry (0-600; default "
                                        + d.initialBackoff().toSeconds() + ")", "60"),
                        ParamSpec.optional("dbgap-max-backoff-seconds",
                                "Longest wait between retries (1-3600; default " + d.maxBackoff().toSeconds()
                                        + "); a Retry-After header is honoured instead, up to "
                                        + DbgapStudyClient.MAX_RETRY_AFTER.toMinutes() + " minutes", "120"),
                        ParamSpec.optional("dbgap-timeout-seconds",
                                "Per-request timeout (1-600; default " + d.requestTimeout().toSeconds() + ")", "60"),
                        ParamSpec.optional("dbgap-give-up-after",
                                "Stop asking dbGaP once this many studies in a row got no usable answer "
                                        + "(1-1000; default " + DEFAULT_GIVE_UP_AFTER + "): an outage then ends the lookups "
                                        + "in about half an hour (4 at a time), the rest UNVERIFIED, instead of retrying "
                                        + "every study", "20"),
                        ParamSpec.optional("dbgap-deadline-seconds",
                                "No dbGaP lookup starts after this many seconds of lookups (1-86400; default "
                                        + DEFAULT_DEADLINE_SECONDS + "), so the run ends, with its report, inside the "
                                        + "runner's monitor timeout; studies not looked up by then are UNVERIFIED", "5400")),
                List.of(COUNTS_FILE + " and " + DISCREPANCIES_FILE + " in the reports directory; a WARNING issue "
                        + "per discrepancy or study dbGaP could not answer for; exit 4 when dbGaP answered for no study"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        intParam(ctx, report, "concurrency", 1, MAX_CONCURRENCY);
        intParam(ctx, report, "dbgap-max-attempts", 1, 20);
        intParam(ctx, report, "dbgap-initial-backoff-seconds", 0, 600);
        intParam(ctx, report, "dbgap-max-backoff-seconds", 1, 3600);
        intParam(ctx, report, "dbgap-timeout-seconds", 1, 600);
        intParam(ctx, report, "dbgap-give-up-after", 1, 1000);
        intParam(ctx, report, "dbgap-deadline-seconds", 1, 86400);
        ctx.get("dbgap-base-url").ifPresent(url -> {
            if (!isHttpUrl(url.trim())) {
                report.error("BAD_DBGAP_BASE_URL", "dbgap-base-url must be an http(s) URL, got: " + url,
                        "--dbgap-base-url");
            }
        });
        if (report.hasErrors()) {
            return;
        }
        DbgapStudyClient.Config config = clientConfig(ctx);
        if (config.maxBackoff().compareTo(config.initialBackoff()) < 0) {
            report.error("BAD_BACKOFF", "dbgap-max-backoff-seconds (" + config.maxBackoff().toSeconds()
                    + ") is less than dbgap-initial-backoff-seconds (" + config.initialBackoff().toSeconds() + ")",
                    "--dbgap-max-backoff-seconds");
        }
        ctx.get("load-counts").ifPresent(uri -> {
            if (!io.exists(uri.trim())) {
                report.error("LOAD_COUNTS_NOT_FOUND", "--load-counts " + uri + " does not exist", "--load-counts");
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
        Map<String, Map<String, DbConsent>> db = dbCounts(studyIds);
        String loadCountsUri = Strings.trimToNull(ctx.get("load-counts", ""));
        LoadCounts load = loadCountsUri == null ? LoadCounts.NONE : readLoadCounts(loadCountsUri);

        List<StudyInput> inputs = new ArrayList<>();
        for (ManagedInputRow row : studies) {
            String sid = row.studyId();
            inputs.add(new StudyInput(sid, row.abv(), ConsentCountComparison.normalizeVersion(row.version()),
                    row.version(), db.getOrDefault(sid, Map.of()), load.counts().get(sid), load.sources().get(sid)));
        }
        Map<String, DbgapLookup> lookups = lookUp(inputs, clientConfig(ctx), intValue(ctx, "concurrency", DEFAULT_CONCURRENCY),
                intValue(ctx, "dbgap-give-up-after", DEFAULT_GIVE_UP_AFTER),
                Duration.ofSeconds(intValue(ctx, "dbgap-deadline-seconds", DEFAULT_DEADLINE_SECONDS)));

        List<StudyResult> results = new ArrayList<>();
        for (StudyInput in : inputs) {
            results.add(ConsentCountComparison.compare(in, lookups.get(in.studyId())));
        }
        writeCsvs(ctx.reportsDir(), results);

        Output output = new Output(results, loadCountsUri, load.counts().size());
        List<String> unverified = output.unverifiedStudies();
        // Some studies unanswered is a warning (validateOutput adds one per study); none answered
        // means dbGaP, or the runner's route to it, is down, and nothing was checked.
        if (!unverified.isEmpty() && output.answeredStudies() == 0) {
            ValidationReport partial = new ValidationReport("output");
            addIssues(output, partial);
            throw new InfrastructureException("dbGaP gave no usable answer for any of the "
                    + unverified.size() + " study/studies looked up, after retries: "
                    + String.join(", ", unverified) + ". Their rows in " + COUNTS_FILE + " are UNVERIFIED. "
                    + "Retryable.")
                    .withPartialResult(metrics(output), partial);
        }
        if (!unverified.isEmpty()) {
            log.warn("dbGaP gave no usable answer for {} of {} study/studies after retries ({}); they are "
                    + "UNVERIFIED, every other study was compared", unverified.size(), results.size(),
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

    /* ---- Study selection and inputs ---- */

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

    /** Distinct participants per normalized consent code, per study. */
    private Map<String, Map<String, DbConsent>> dbCounts(List<String> studyIds) {
        Map<String, Map<String, DbConsent>> byStudy = new LinkedHashMap<>();
        for (ConsentRepository.ConsentGroupCount c : consents.countParticipantsByConsent(studyIds)) {
            // "1" and "c1" are one group: participants are unique per study, so their counts add up.
            byStudy.computeIfAbsent(c.studyId(), k -> new LinkedHashMap<>())
                    .merge(ConsentCountComparison.normalizeConsent(c.consentCode()),
                            new DbConsent(c.participants(), c.consentAbbreviation()),
                            (a, b) -> new DbConsent(a.participants() + b.participants(),
                                    a.abbreviation() != null ? a.abbreviation() : b.abbreviation()));
        }
        return byStudy;
    }

    /**
     * Participants loaded this run, per study and normalized consent code (null: listed, but this run did
     * not load it), and each study's source.
     */
    record LoadCounts(Map<String, Map<String, Long>> counts, Map<String, String> sources) {
        static final LoadCounts NONE = new LoadCounts(Map.of(), Map.of());
    }

    LoadCounts readLoadCounts(String uri) {
        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        InputStream in = io.openInput(uri);
        long line = 1;
        try (Stream<Map<String, String>> stream = delimitedReader.stream(in, DelimitedReader.COMMA)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                line++;
                if (line == 2) {
                    List<String> missing = Stream.of(COL_STUDY_ID, COL_CONSENT_CODE, COL_PARTICIPANTS_LOADED)
                            .filter(c -> !row.containsKey(c)).toList();
                    if (!missing.isEmpty()) {
                        throw new DataException("--load-counts " + uri + " is missing column(s) " + missing
                                + "; found " + row.keySet());
                    }
                }
                String sid = Strings.trimToNull(row.get(COL_STUDY_ID));
                String code = Strings.trimToNull(row.get(COL_CONSENT_CODE));
                String n = Strings.trimToNull(row.get(COL_PARTICIPANTS_LOADED));
                if (sid == null || code == null || (n != null && !n.matches("\\d{1,18}"))) {
                    throw new DataException("--load-counts " + uri + " line " + line + ": need a study_id, a "
                            + "consent_code and a whole-number (or blank) participants_loaded, got " + row);
                }
                // Blank: this run did not load the consent group (e.g. the migration split a study whose
                // participants an earlier run loaded), so there is no load count to compare.
                Long loaded = n == null ? null : Long.parseLong(n);
                Map<String, Long> byCode = counts.computeIfAbsent(sid, k -> new LinkedHashMap<>());
                String key = ConsentCountComparison.normalizeConsent(code);
                if (loaded == null || (byCode.containsKey(key) && byCode.get(key) == null)) {
                    byCode.put(key, null);
                } else {
                    byCode.merge(key, loaded, Long::sum);
                }
                String source = Strings.trimToNull(row.get(COL_SOURCE));
                if (source != null) {
                    sources.putIfAbsent(sid, source);
                }
            }
        }
        // A study with no count at all was not loaded this run: leave it out, as if it were not listed.
        // Elsewhere an unknown count stays null, so only that consent goes uncompared.
        counts.values().removeIf(byCode -> byCode.values().stream().allMatch(Objects::isNull));
        sources.keySet().retainAll(counts.keySet());
        log.info("Read load counts for {} study/studies from {}", counts.size(), uri);
        return new LoadCounts(counts, sources);
    }

    /* ---- dbGaP ---- */

    private static DbgapStudyClient.Config clientConfig(JobContext ctx) {
        DbgapStudyClient.Config d = DbgapStudyClient.Config.defaults();
        return new DbgapStudyClient.Config(
                ctx.get("dbgap-base-url", d.baseUrl()).trim(),
                intValue(ctx, "dbgap-max-attempts", d.maxAttempts()),
                Duration.ofSeconds(intValue(ctx, "dbgap-initial-backoff-seconds", (int) d.initialBackoff().toSeconds())),
                Duration.ofSeconds(intValue(ctx, "dbgap-max-backoff-seconds", (int) d.maxBackoff().toSeconds())),
                Duration.ofSeconds(intValue(ctx, "dbgap-timeout-seconds", (int) d.requestTimeout().toSeconds())));
    }

    /**
     * Looks every study with a usable version up in dbGaP, {@code concurrency} at a time (each call
     * takes ~10 s). A study without a version is not looked up and has no entry.
     *
     * <p>An outage must end the run with its report, not run into the runner's monitor
     * timeout (exit 124, nothing written): with requests timing out, every study would take ~6 minutes
     * of retries. So no lookup starts once {@code giveUpAfter} studies in a row got no usable answer
     * (any answer, a NOT_FOUND included, resets the count), or once {@code deadline} has passed since
     * the first; the studies not asked are FAILED with 0 attempts, i.e. UNVERIFIED.
     */
    private Map<String, DbgapLookup> lookUp(List<StudyInput> inputs, DbgapStudyClient.Config config, int concurrency,
                                            int giveUpAfter, Duration deadline) {
        List<StudyInput> toAsk = inputs.stream().filter(i -> i.version() != null).toList();
        Map<String, DbgapLookup> lookups = new LinkedHashMap<>();
        if (toAsk.isEmpty()) {
            return lookups;
        }
        log.info("Looking up {} study version(s) in dbGaP at {} ({} at a time)", toAsk.size(), config.baseUrl(),
                Math.min(concurrency, toAsk.size()));
        AtomicInteger threads = new AtomicInteger();
        AtomicInteger failedInARow = new AtomicInteger();
        long stopAt = System.nanoTime() + deadline.toNanos();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, toAsk.size()), r -> {
            Thread t = new Thread(r, "dbgap-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        try (DbgapStudySource source = sourceFactory.apply(config)) {
            Map<String, Future<DbgapLookup>> futures = new LinkedHashMap<>();
            for (StudyInput in : toAsk) {
                futures.put(in.studyId(), pool.submit(() -> {
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    }
                    try {
                        if (failedInARow.get() >= giveUpAfter) {
                            return DbgapLookup.failed("not asked: dbGaP gave no usable answer for the last "
                                    + failedInARow.get() + " studies in a row, so the run stopped asking", 0);
                        }
                        if (System.nanoTime() - stopAt > 0) {
                            return DbgapLookup.failed("not asked: the " + deadline.toSeconds()
                                    + " s lookup deadline (--dbgap-deadline-seconds) had passed", 0);
                        }
                        DbgapLookup lookup;
                        try {
                            lookup = source.fetch(in.studyId(), in.version());
                        } catch (RuntimeException e) {
                            failedInARow.incrementAndGet();
                            throw e;
                        }
                        if (lookup != null && lookup.outcome() != DbgapLookup.Outcome.FAILED) {
                            failedInARow.set(0);
                        } else {
                            failedInARow.incrementAndGet();
                        }
                        return lookup;
                    } finally {
                        MDC.clear();
                    }
                }));
            }
            for (Map.Entry<String, Future<DbgapLookup>> e : futures.entrySet()) {
                DbgapLookup lookup;
                try {
                    lookup = e.getValue().get();
                } catch (ExecutionException ex) {
                    // A source is not meant to throw; if one does, that study is unverified, not the run lost.
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    log.error("dbGaP lookup of {} threw", e.getKey(), cause);
                    lookup = DbgapLookup.failed(cause.getClass().getSimpleName() + ": " + cause.getMessage(), 1);
                }
                lookups.put(e.getKey(), lookup == null ? DbgapLookup.failed("no answer", 0) : lookup);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("Interrupted while looking studies up in dbGaP", e);
        } finally {
            pool.shutdownNow();
        }
        return lookups;
    }

    /* ---- Output ---- */

    private static void writeCsvs(Path dir, List<StudyResult> results) {
        StringBuilder all = new StringBuilder(CSV_HEADER).append('\n');
        StringBuilder discrepancies = new StringBuilder(CSV_HEADER).append('\n');
        for (StudyResult r : results) {
            for (Row row : r.rows()) {
                String line = csvLine(row);
                all.append(line);
                if (!row.matches()) {
                    discrepancies.append(line);
                }
            }
        }
        writeAtomically(dir, COUNTS_FILE, all.toString());
        writeAtomically(dir, DISCREPANCIES_FILE, discrepancies.toString());
    }

    private static String csvLine(Row r) {
        return String.join(",", Stream.of(r.studyId(), r.version(), r.consentCode(), r.consentShortName(),
                str(r.dbgapSubjects()), String.valueOf(r.dbParticipants()), str(r.loadParticipants()),
                r.loadSource(), r.status(), r.note())
                .map(v -> Strings.csvQuote(v == null ? "" : v.replaceAll("[\r\n]+", " "))).toList()) + "\n";
    }

    private static String str(Long v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** Written aside and renamed: the runner uploads the reports directory while the job runs. */
    private static void writeAtomically(Path dir, String name, String content) {
        Path target = dir.resolve(name);
        Path partial = dir.resolve(name + ".partial");
        try {
            Files.createDirectories(dir);
            Files.writeString(partial, content, StandardCharsets.UTF_8);
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new InfrastructureException("Could not write " + target + ": " + e.getMessage(), e);
        }
    }

    /** One WARNING per discrepancy -- per study for a study-level finding -- and one ERROR per unverified study. */
    private static void addIssues(Output output, ValidationReport report) {
        for (StudyResult r : output.results()) {
            StudyInput in = r.input();
            String label = in.studyId() + (in.version() == null ? "" : "." + in.version());
            if (r.studyStatus() == Status.UNVERIFIED) {
                report.add(ValidationIssue.warning("DBGAP_UNAVAILABLE", label + ": " + r.studyNote(),
                        context(in, null)));
            } else if (r.studyStatus() != null) {
                report.add(ValidationIssue.warning(r.studyStatus().name(), label + ": " + r.studyNote(),
                        context(in, null)));
            }
            for (Row row : r.rows()) {
                for (Status s : row.statuses()) {
                    if (s.studyLevel()) {
                        continue;
                    }
                    report.add(ValidationIssue.warning(s.name(), label + " consent " + row.consentCode()
                            + (row.consentShortName() == null ? "" : " (" + row.consentShortName() + ")")
                            + ": dbGaP " + (row.dbgapSubjects() == null ? "?" : row.dbgapSubjects())
                            + ", DB " + row.dbParticipants()
                            + (row.loadParticipants() == null ? "" : ", load report " + row.loadParticipants())
                            + (row.note() == null ? "" : " -- " + row.note()), context(in, row)));
                }
            }
        }
    }

    private static Map<String, Object> context(StudyInput in, Row row) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("studyId", in.studyId());
        c.put("version", in.version() != null ? in.version() : in.rawVersion());
        if (row != null) {
            c.put("consentCode", row.consentCode());
            c.put("dbgapSubjects", row.dbgapSubjects());
            c.put("dbParticipants", row.dbParticipants());
            c.put("loadParticipants", row.loadParticipants());
            c.put("loadSource", row.loadSource());
        }
        return c;
    }

    /**
     * The report's metrics. Discrepancies are counted per consent row, except a study-level finding
     * (NO_VERSION, VERSION_NOT_FOUND, VERSION_MISMATCH), which counts once for its study; UNVERIFIED
     * is not a discrepancy (nothing was compared) and is listed in unverifiedStudies instead.
     */
    static Map<String, Object> metrics(Output output) {
        Map<Status, Integer> byType = new EnumMap<>(Status.class);
        int attempts = 0;
        int retries = 0;
        int compared = 0;
        int matching = 0;
        List<String> withDiscrepancies = new ArrayList<>();
        for (StudyResult r : output.results()) {
            if (r.lookup() != null) {
                attempts += r.lookup().attempts();
                retries += Math.max(0, r.lookup().attempts() - 1);
            }
            if (r.verified()) {
                compared += r.rows().size();
            }
            boolean any = false;
            if (r.studyStatus() != null && r.studyStatus() != Status.UNVERIFIED) {
                byType.merge(r.studyStatus(), 1, Integer::sum);
                any = true;
            }
            for (Row row : r.rows()) {
                if (row.matches()) {
                    matching++;
                }
                for (Status s : row.statuses()) {
                    if (!s.studyLevel()) {
                        byType.merge(s, 1, Integer::sum);
                        any = true;
                    }
                }
            }
            if (any) {
                withDiscrepancies.add(r.input().studyId());
            }
        }
        Map<String, Integer> byTypeNamed = new LinkedHashMap<>();
        byType.forEach((k, v) -> byTypeNamed.put(k.name(), v));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("studiesChecked", output.results().size());
        m.put("studiesVerified", (int) output.results().stream().filter(StudyResult::verified).count());
        m.put("consentsCompared", compared);
        m.put("consentsMatching", matching);
        m.put("discrepancies", byType.values().stream().mapToInt(Integer::intValue).sum());
        m.put("discrepanciesByType", byTypeNamed);
        m.put("studiesWithDiscrepancies", withDiscrepancies);
        m.put("studiesAnswered", output.answeredStudies());
        m.put("unverifiedStudies", output.unverifiedStudies());
        m.put("apiAttempts", attempts);
        m.put("apiRetries", retries);
        m.put("loadCounts", output.loadCountsUri());
        m.put("studiesWithLoadCounts", output.studiesWithLoadCounts());
        m.put("reportFiles", List.of(COUNTS_FILE, DISCREPANCIES_FILE));
        return m;
    }

    /* ---- Parameters ---- */

    /** A whole-number parameter, trimmed as {@link #intParam} validated it. */
    private static int intValue(JobContext ctx, String name, int defaultValue) {
        return ctx.get(name).map(v -> Integer.parseInt(v.trim())).orElse(defaultValue);
    }

    private static void intParam(JobContext ctx, ValidationReport report, String name, int min, int max) {
        ctx.get(name).ifPresent(raw -> {
            String v = raw.trim();
            if (!v.matches("-?\\d{1,9}") || Integer.parseInt(v) < min || Integer.parseInt(v) > max) {
                report.error("BAD_PARAM", name + " must be a whole number from " + min + " to " + max + ", got: "
                        + raw, "--" + name);
            }
        });
    }

    private static boolean isHttpUrl(String url) {
        try {
            URI u = URI.create(url);
            return u.getHost() != null && Arrays.asList("http", "https").contains(u.getScheme());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Result of {@link #execute}.
     *
     * @param results               one per checked study, in managed-inputs order
     * @param loadCountsUri         the --load-counts given, or null
     * @param studiesWithLoadCounts studies the load counts list (checked or not)
     */
    public record Output(List<StudyResult> results, String loadCountsUri, int studiesWithLoadCounts) {

        List<String> unverifiedStudies() {
            return results.stream().filter(r -> r.studyStatus() == Status.UNVERIFIED)
                    .map(r -> r.input().studyId()).toList();
        }

        /** Studies dbGaP answered for (FOUND or NOT_FOUND), whatever the comparison then found. */
        int answeredStudies() {
            return (int) results.stream().filter(r -> r.lookup() != null
                    && r.lookup().outcome() != DbgapLookup.Outcome.FAILED).count();
        }
    }
}
