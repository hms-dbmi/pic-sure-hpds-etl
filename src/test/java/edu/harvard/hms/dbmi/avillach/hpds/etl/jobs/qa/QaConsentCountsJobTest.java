package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository.ConsentGroupCount;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link QaConsentCountsJob} end to end through {@link JobExecutor}, with the managed inputs and the
 * participant DB mocked and dbGaP replaced by canned answers: exit codes, issues, metrics and both
 * CSVs, for runs that go right and runs that go wrong.
 */
class QaConsentCountsJobTest {

    @TempDir
    Path tempDir;

    private ManagedInputsService managedInputs;
    private ConsentRepository consents;
    private JobExecutor executor;
    private Path reportsDir;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** dbGaP's canned answers, by "phs.vN"; anything else is NOT_FOUND. */
    private final Map<String, DbgapLookup> answers = new ConcurrentHashMap<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final AtomicReference<DbgapStudyClient.Config> configUsed = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        managedInputs = mock(ManagedInputsService.class);
        consents = mock(ConsentRepository.class);
        reportsDir = tempDir.resolve("reports");
        Files.createDirectories(reportsDir);
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(reportsDir.toString());
        executor = new JobExecutor(new ReportWriter(mapper), properties);
    }

    private QaConsentCountsJob job(DbgapStudySource source) {
        return new QaConsentCountsJob(managedInputs, consents, new IoResolver(null), new DelimitedReader(), config -> {
            configUsed.set(config);
            return source;
        });
    }

    private QaConsentCountsJob job() {
        return job((sid, v) -> {
            asked.add(sid + "." + v);
            return answers.getOrDefault(sid + "." + v, DbgapLookup.notFound("Invalid or non-existent study", 1));
        });
    }

    private static ManagedInputRow study(String abv, String studyId, String version, boolean ready) {
        return new ManagedInputRow(abv, studyId, version, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                "", "", "", "", "", ready, false);
    }

    private void managedInputs(ManagedInputRow... rows) {
        when(managedInputs.read()).thenReturn(List.of(rows));
    }

    /** DB counts as (study, code, count) triples. */
    private void dbCounts(Object... triples) {
        List<ConsentGroupCount> rows = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            rows.add(new ConsentGroupCount((String) triples[i], (String) triples[i + 1], "abv",
                    ((Number) triples[i + 2]).longValue()));
        }
        when(consents.countParticipantsByConsent(anyCollection())).thenReturn(rows);
    }

    private static DbgapLookup found(String accession, int version, long... codeAndCount) {
        List<DbgapStudySummary.ConsentGroup> groups = new ArrayList<>();
        for (int i = 0; i < codeAndCount.length; i += 2) {
            groups.add(new DbgapStudySummary.ConsentGroup(String.valueOf(codeAndCount[i]), "G" + codeAndCount[i],
                    codeAndCount[i + 1]));
        }
        return DbgapLookup.found(new DbgapStudySummary(accession, version, groups, 0), 1);
    }

    private JobResult run(QaConsentCountsJob job, Map<String, String> params) {
        return executor.run(job, params, "t");
    }

    private JsonNode reportJson() throws IOException {
        return mapper.readTree(reportsDir.resolve("qa-consent-counts-t.json").toFile());
    }

    private List<String> csv(String name) throws IOException {
        return Files.readAllLines(reportsDir.resolve(name), StandardCharsets.UTF_8);
    }

    private static List<String> codes(JobResult r) {
        List<String> codes = new ArrayList<>();
        for (var phase : new edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport[]{
                r.getInputValidation(), r.getOutputValidation()}) {
            if (phase != null) {
                phase.getIssues().forEach(i -> codes.add(i.severity() + ":" + i.code()));
            }
        }
        return codes;
    }

    /* ---- Going right ---- */

    @Test
    void every_consent_matching_exits_0_with_no_warnings_and_an_empty_discrepancy_file() throws IOException {
        managedInputs(study("DHS", "phs001412", "v4", true), study("FHS", "phs000007", "v33", true));
        dbCounts("phs001412", "0", 576, "phs001412", "1", 402, "phs001412", "2", 3, "phs000007", "1", 10);
        answers.put("phs001412.v4", found("phs001412", 4, 0, 576, 1, 402, 2, 3));
        answers.put("phs000007.v33", found("phs000007", 33, 1, 10));

        JobResult r = run(job(), Map.of());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(codes(r)).isEmpty();
        assertThat(csv(QaConsentCountsJob.COUNTS_FILE)).containsExactly(
                QaConsentCountsJob.CSV_HEADER,
                "phs001412,v4,0,G0,576,576,,,MATCH,",
                "phs001412,v4,1,G1,402,402,,,MATCH,",
                "phs001412,v4,2,G2,3,3,,,MATCH,",
                "phs000007,v33,1,G1,10,10,,,MATCH,");
        assertThat(csv(QaConsentCountsJob.DISCREPANCIES_FILE)).containsExactly(QaConsentCountsJob.CSV_HEADER);
        JsonNode m = reportJson().get("metrics");
        assertThat(m.get("studiesChecked").asInt()).isEqualTo(2);
        assertThat(m.get("studiesVerified").asInt()).isEqualTo(2);
        assertThat(m.get("consentsCompared").asInt()).isEqualTo(4);
        assertThat(m.get("discrepancies").asInt()).isZero();
        assertThat(m.get("apiAttempts").asInt()).isEqualTo(2);
        assertThat(m.get("apiRetries").asInt()).isZero();
        assertThat(m.get("unverifiedStudies")).isEmpty();
    }

    @Test
    void only_ready_studies_are_checked_each_once() {
        managedInputs(study("A", "phs000001", "v1", true), study("B", "phs000002", "v1", false),
                study("A2", "phs000001", "v1", true));
        dbCounts("phs000001", "1", 1);
        answers.put("phs000001.v1", found("phs000001", 1, 1, 1));

        JobResult r = run(job(), Map.of());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(asked).containsExactly("phs000001.v1");
    }

    @Test
    void the_study_filter_limits_the_check_and_names_filter_ids_it_cannot_check() {
        managedInputs(study("A", "phs000001", "v1", true), study("B", "phs000002", "v1", true),
                study("C", "phs000003", "v1", false));
        dbCounts("phs000002", "1", 1);
        answers.put("phs000002.v1", found("phs000002", 1, 1, 1));

        JobResult r = run(job(), Map.of("study-filter", "phs000002, phs000003,phs999999"));

        assertThat(asked).containsExactly("phs000002.v1");
        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(codes(r)).containsExactly("WARNING:STUDY_FILTER_UNMATCHED", "WARNING:STUDY_FILTER_UNMATCHED");
    }

    @Test
    void the_client_is_configured_from_the_parameters_and_defaults() {
        managedInputs(study("A", "phs000001", "v1", true));
        dbCounts();

        run(job(), Map.of());
        assertThat(configUsed.get()).isEqualTo(DbgapStudyClient.Config.defaults());

        run(job(), Map.of("dbgap-base-url", "http://stub.local/study/", "dbgap-max-attempts", "3",
                "dbgap-initial-backoff-seconds", "1", "dbgap-max-backoff-seconds", "9", "dbgap-timeout-seconds", "7"));
        assertThat(configUsed.get()).isEqualTo(new DbgapStudyClient.Config("http://stub.local/study", 3,
                Duration.ofSeconds(1), Duration.ofSeconds(9), Duration.ofSeconds(7)));
    }

    @Test
    void lookups_run_concurrently_but_never_more_than_the_concurrency_at_once() {
        List<ManagedInputRow> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rows.add(study("S" + i, String.format("phs%06d", i), "v1", true));
        }
        when(managedInputs.read()).thenReturn(rows);
        dbCounts();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        QaConsentCountsJob job = job((sid, v) -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return found(sid, 1);
        });

        JobResult r = run(job, Map.of("concurrency", "3"));

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(peak.get()).isBetween(2, 3);
    }

    /* ---- Discrepancies (exit 0 with warnings: the build goes UNSTABLE) ---- */

    @Test
    void every_kind_of_discrepancy_is_a_warning_with_a_csv_row() throws IOException {
        managedInputs(
                study("MIS", "phs000001", "v1", true),    // count mismatch, missing in DB (c0), missing in dbGaP
                study("REP", "phs000002", "v2", true),    // load report disagrees with the DB
                study("NOV", "phs000003", "", true),      // no version
                study("NF", "phs000004", "v9", true),     // dbGaP has no such version
                study("OTH", "phs000005", "v1", true));   // dbGaP answers for another version
        dbCounts("phs000001", "1", 400, "phs000001", "7", 5,
                "phs000002", "1", 10,
                "phs000003", "1", 1,
                "phs000004", "1", 1,
                "phs000005", "1", 1);
        answers.put("phs000001.v1", found("phs000001", 1, 0, 576, 1, 402));
        answers.put("phs000002.v2", found("phs000002", 2, 1, 10));
        answers.put("phs000005.v1", found("phs000005", 2, 1, 1));
        String loadCounts = Files.writeString(tempDir.resolve("load.csv"),
                "study_id,abv,outcome,source,consent_code,participants_loaded\n"
                        + "phs000001,MIS,success,multi,1,400\n"
                        + "phs000001,MIS,success,multi,7,5\n"
                        + "phs000002,REP,success,sstr,c1,9\n"
                        + "phs999999,OTHER,success,sstr,1,1\n").toString();

        JobResult r = run(job(), Map.of("load-counts", loadCounts));

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(asked).doesNotContain("phs000003.v");
        assertThat(codes(r)).containsExactly(
                "WARNING:MISSING_IN_DB", "WARNING:COUNT_MISMATCH", "WARNING:MISSING_IN_DBGAP",
                "WARNING:REPORT_DB_MISMATCH",
                "WARNING:NO_VERSION", "WARNING:VERSION_NOT_FOUND", "WARNING:VERSION_MISMATCH");
        List<String> discrepancies = csv(QaConsentCountsJob.DISCREPANCIES_FILE);
        assertThat(discrepancies).containsExactly(
                QaConsentCountsJob.CSV_HEADER,
                "phs000001,v1,0,G0,576,0,0,multi,MISSING_IN_DB,source MULTI drops consent 0",
                "phs000001,v1,1,G1,402,400,400,multi,COUNT_MISMATCH,",
                "phs000001,v1,7,abv,0,5,5,multi,MISSING_IN_DBGAP,",
                "phs000002,v2,1,G1,10,10,9,sstr,REPORT_DB_MISMATCH,this run's load report says 9 but the DB holds 10",
                "phs000003,,1,abv,,1,,,NO_VERSION,managed inputs Version is blank (expected e.g. v4); not checked against dbGaP",
                "phs000004,v9,1,abv,,1,,,VERSION_NOT_FOUND,dbGaP has no phs000004.v9: Invalid or non-existent study",
                "phs000005,v1,1,abv,,1,,,VERSION_MISMATCH,requested phs000005.v1 but dbGaP answered for phs000005.v2; counts not compared");
        assertThat(csv(QaConsentCountsJob.COUNTS_FILE)).hasSize(discrepancies.size());

        ValidationIssue mismatch = r.getOutputValidation().getIssues().stream()
                .filter(i -> i.code().equals("COUNT_MISMATCH")).findFirst().orElseThrow();
        assertThat(mismatch.message()).isEqualTo("phs000001.v1 consent 1 (G1): dbGaP 402, DB 400, load report 400");
        assertThat(mismatch.context()).containsEntry("studyId", "phs000001").containsEntry("consentCode", "1")
                .containsEntry("dbgapSubjects", 402L).containsEntry("dbParticipants", 400L);

        JsonNode m = reportJson().get("metrics");
        assertThat(m.get("discrepancies").asInt()).isEqualTo(7);
        assertThat(mapper.convertValue(m.get("discrepanciesByType"), Map.class)).hasSize(7).containsAllEntriesOf(Map.of(
                "COUNT_MISMATCH", 1, "MISSING_IN_DB", 1, "MISSING_IN_DBGAP", 1, "REPORT_DB_MISMATCH", 1,
                "NO_VERSION", 1, "VERSION_NOT_FOUND", 1, "VERSION_MISMATCH", 1));
        assertThat(m.get("studiesWithDiscrepancies")).hasSize(5);
        assertThat(m.get("studiesVerified").asInt()).isEqualTo(2);
        assertThat(m.get("studiesWithLoadCounts").asInt()).isEqualTo(3);
        assertThat(m.get("loadCounts").asText()).isEqualTo(loadCounts);
    }

    @Test
    void a_study_the_load_counts_do_not_list_is_not_a_report_mismatch() throws IOException {
        managedInputs(study("A", "phs000001", "v1", true), study("B", "phs000002", "v1", true));
        dbCounts("phs000001", "1", 5, "phs000002", "1", 6);
        answers.put("phs000001.v1", found("phs000001", 1, 1, 5));
        answers.put("phs000002.v1", found("phs000002", 1, 1, 6));
        String loadCounts = Files.writeString(tempDir.resolve("load.csv"),
                "study_id,consent_code,participants_loaded\nphs000001,1,5\n").toString();

        JobResult r = run(job(), Map.of("load-counts", loadCounts));

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(csv(QaConsentCountsJob.COUNTS_FILE)).containsExactly(QaConsentCountsJob.CSV_HEADER,
                "phs000001,v1,1,G1,5,5,5,,MATCH,",
                "phs000002,v1,1,G1,6,6,,,MATCH,");
    }

    @Test
    void no_ready_studies_is_a_warning_not_a_failure() throws IOException {
        managedInputs(study("A", "phs000001", "v1", false));
        dbCounts();

        JobResult r = run(job(), Map.of());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(codes(r)).containsExactly("WARNING:NO_STUDIES_TO_CHECK");
        assertThat(csv(QaConsentCountsJob.COUNTS_FILE)).containsExactly(QaConsentCountsJob.CSV_HEADER);
    }

    /* ---- dbGaP unavailable (exit 4, the report and CSVs still written) ---- */

    @Test
    void a_study_dbgap_cannot_answer_fails_the_run_with_exit_4_but_everything_is_still_reported() throws IOException {
        managedInputs(study("OK", "phs000001", "v1", true), study("DOWN", "phs000002", "v1", true),
                study("BAD", "phs000003", "v1", true));
        dbCounts("phs000001", "1", 5, "phs000002", "1", 6, "phs000003", "1", 7);
        answers.put("phs000001.v1", found("phs000001", 1, 1, 5));
        answers.put("phs000002.v1", DbgapLookup.failed("no usable answer after 5 attempt(s); last failure: HTTP 503", 5));
        answers.put("phs000003.v1", found("phs000003", 1, 1, 8));

        JobResult r = run(job(), Map.of());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(r.getErrorMessage()).contains("1 of 3").contains("phs000002").contains("Retryable");
        assertThat(codes(r)).containsExactly("ERROR:DBGAP_UNAVAILABLE", "WARNING:COUNT_MISMATCH");
        assertThat(csv(QaConsentCountsJob.COUNTS_FILE)).containsExactly(QaConsentCountsJob.CSV_HEADER,
                "phs000001,v1,1,G1,5,5,,,MATCH,",
                "phs000002,v1,1,abv,,6,,,UNVERIFIED,dbGaP gave no usable answer: no usable answer after 5 attempt(s); last failure: HTTP 503",
                "phs000003,v1,1,G1,8,7,,,COUNT_MISMATCH,");
        assertThat(csv(QaConsentCountsJob.DISCREPANCIES_FILE)).hasSize(3);

        // The JSON report on the exit-4 path keeps the metrics and issues, not just the error.
        JsonNode report = reportJson();
        assertThat(report.get("exitCode").asText()).isEqualTo("INFRASTRUCTURE_ERROR");
        assertThat(report.at("/metrics/unverifiedStudies/0").asText()).isEqualTo("phs000002");
        assertThat(report.at("/metrics/apiAttempts").asInt()).isEqualTo(7);
        assertThat(report.at("/metrics/apiRetries").asInt()).isEqualTo(4);
        assertThat(report.at("/metrics/discrepancies").asInt()).isEqualTo(1);
        assertThat(report.at("/outputValidation/counts/error").asInt()).isEqualTo(1);
    }

    @Test
    void a_source_that_throws_leaves_only_that_study_unverified() {
        managedInputs(study("OK", "phs000001", "v1", true), study("BOOM", "phs000002", "v1", true));
        dbCounts("phs000001", "1", 5);
        QaConsentCountsJob job = job((sid, v) -> {
            if (sid.equals("phs000002")) {
                throw new IllegalStateException("bug");
            }
            return found(sid, 1, 1, 5);
        });

        JobResult r = run(job, Map.of());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(r.getMetrics().get("unverifiedStudies")).isEqualTo(List.of("phs000002"));
    }

    @Test
    void an_outage_stops_the_lookups_after_the_give_up_count_so_the_run_ends_with_its_report() throws IOException {
        List<ManagedInputRow> rows = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            rows.add(study("S" + i, String.format("phs%06d", i), "v1", true));
        }
        when(managedInputs.read()).thenReturn(rows);
        dbCounts();
        QaConsentCountsJob job = job((sid, v) -> {
            asked.add(sid);
            return DbgapLookup.failed("timed out after 60 s", 5);
        });

        JobResult r = run(job, Map.of("concurrency", "1", "dbgap-give-up-after", "2"));

        assertThat(r.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(asked).containsExactly("phs000001", "phs000002");
        assertThat((List<?>) r.getMetrics().get("unverifiedStudies")).hasSize(6);
        assertThat(r.getMetrics().get("apiAttempts")).isEqualTo(10);
        assertThat(csv(QaConsentCountsJob.COUNTS_FILE)).anyMatch(l -> l.startsWith("phs000006,v1,,,,0,,,UNVERIFIED,")
                && l.contains("stopped asking"));
    }

    @Test
    void any_answer_resets_the_give_up_count() {
        managedInputs(study("A", "phs000001", "v1", true), study("B", "phs000002", "v1", true),
                study("C", "phs000003", "v1", true), study("D", "phs000004", "v1", true));
        dbCounts();
        QaConsentCountsJob job = job((sid, v) -> {
            asked.add(sid);
            return sid.equals("phs000002") ? DbgapLookup.notFound("no such study", 1) : DbgapLookup.failed("503", 5);
        });

        run(job, Map.of("concurrency", "1", "dbgap-give-up-after", "2"));

        // 1 fails, 2 answers (reset), 3 fails, 4 fails: never two in a row before 4.
        assertThat(asked).containsExactly("phs000001", "phs000002", "phs000003", "phs000004");
    }

    @Test
    void no_lookup_starts_after_the_deadline() {
        managedInputs(study("A", "phs000001", "v1", true), study("B", "phs000002", "v1", true));
        dbCounts();
        QaConsentCountsJob job = job((sid, v) -> {
            asked.add(sid);
            try {
                Thread.sleep(1_100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return found(sid, 1);
        });

        JobResult r = run(job, Map.of("concurrency", "1", "dbgap-deadline-seconds", "1"));

        assertThat(asked).containsExactly("phs000001");
        assertThat(r.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(r.getMetrics().get("unverifiedStudies")).isEqualTo(List.of("phs000002"));
    }

    @Test
    void padded_numbers_are_accepted_as_validation_accepted_them() {
        managedInputs(study("A", "phs000001", "v1", true));
        dbCounts("phs000001", "1", 1);
        answers.put("phs000001.v1", found("phs000001", 1, 1, 1));

        JobResult r = run(job(), Map.of("concurrency", " 2 ", "dbgap-max-attempts", "3 "));

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(configUsed.get().maxAttempts()).isEqualTo(3);
    }

    @Test
    void a_blank_consent_code_in_the_db_does_not_break_the_run() throws IOException {
        managedInputs(study("A", "phs000001", "v1", true));
        dbCounts("phs000001", "1", 5, "phs000001", " ", 2);
        answers.put("phs000001.v1", found("phs000001", 1, 1, 5));

        JobResult r = run(job(), Map.of());

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(csv(QaConsentCountsJob.DISCREPANCIES_FILE)).containsExactly(QaConsentCountsJob.CSV_HEADER,
                "phs000001,v1,,abv,0,2,,,MISSING_IN_DBGAP,");
    }

    /* ---- Bad input ---- */

    @Test
    void bad_parameters_fail_input_validation_before_anything_runs() {
        managedInputs(study("A", "phs000001", "v1", true));

        JobResult r = run(job(), Map.of("concurrency", "0", "dbgap-max-attempts", "x",
                "dbgap-base-url", "ftp://nope", "dbgap-timeout-seconds", "601"));

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(codes(r)).containsExactly("ERROR:BAD_PARAM", "ERROR:BAD_PARAM", "ERROR:BAD_PARAM",
                "ERROR:BAD_DBGAP_BASE_URL");
        assertThat(asked).isEmpty();
    }

    @Test
    void a_max_backoff_below_the_initial_backoff_is_rejected() {
        managedInputs(study("A", "phs000001", "v1", true));

        JobResult r = run(job(), Map.of("dbgap-initial-backoff-seconds", "10", "dbgap-max-backoff-seconds", "5"));

        assertThat(codes(r)).containsExactly("ERROR:BAD_BACKOFF");
    }

    @Test
    void missing_load_counts_fail_input_validation() {
        managedInputs(study("A", "phs000001", "v1", true));

        JobResult r = run(job(), Map.of("load-counts", tempDir.resolve("absent.csv").toString()));

        assertThat(r.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(codes(r)).containsExactly("ERROR:LOAD_COUNTS_NOT_FOUND");
    }

    @Test
    void a_blank_load_count_means_not_loaded_this_run_and_is_not_compared() throws IOException {
        // The migration report's migration_consent_counts.csv: extra columns, and participants_loaded blank
        // for a study (or one group) this run only split.
        managedInputs(study("A", "phs000001", "v1", true), study("B", "phs000002", "v1", true));
        dbCounts("phs000001", "1", 5, "phs000002", "1", 6, "phs000002", "2", 7);
        answers.put("phs000001.v1", found("phs000001", 1, 1, 5));
        answers.put("phs000002.v1", found("phs000002", 1, 1, 6, 2, 7));
        String loadCounts = Files.writeString(tempDir.resolve("migration_consent_counts.csv"),
                "study_id,abv,consent_code,partition,participants_loaded,participants_split\n"
                        + "phs000001,A,1,phs000001.c1,,5\n"
                        + "phs000002,B,1,phs000002.c1,6,6\n"
                        + "phs000002,B,2,phs000002.c2,,7\n").toString();

        JobResult r = run(job(), Map.of("load-counts", loadCounts));

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(csv(QaConsentCountsJob.COUNTS_FILE)).containsExactly(QaConsentCountsJob.CSV_HEADER,
                "phs000001,v1,1,G1,5,5,,,MATCH,",
                "phs000002,v1,1,G1,6,6,6,,MATCH,",
                "phs000002,v1,2,G2,7,7,,,MATCH,");
        assertThat(reportJson().at("/metrics/studiesWithLoadCounts").asInt()).isEqualTo(1);
    }

    @Test
    void malformed_load_counts_are_a_data_error() throws IOException {
        managedInputs(study("A", "phs000001", "v1", true));
        dbCounts();
        Map<String, String> files = new HashMap<>();
        files.put("no-columns.csv", "study,consent\nphs000001,1\n");
        files.put("bad-count.csv", "study_id,consent_code,participants_loaded\nphs000001,1,many\n");
        for (Map.Entry<String, String> f : files.entrySet()) {
            String path = Files.writeString(tempDir.resolve(f.getKey()), f.getValue()).toString();

            JobResult r = run(job(), Map.of("load-counts", path));

            assertThat(r.getExitCode()).as(f.getKey()).isEqualTo(ExitCode.DATA_ERROR);
        }
    }
}
