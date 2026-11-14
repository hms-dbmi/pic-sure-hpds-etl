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
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository.ConsentGroupSamples;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link QaPipelineCountsJob} end to end through {@link JobExecutor}: the managed inputs and the DB
 * mocked, the summary cache a local folder, and dbGaP replaced by canned answers.
 */
class QaPipelineCountsJobTest {

    @TempDir
    Path tempDir;

    private ManagedInputsService managedInputs;
    private ConsentRepository consents;
    private SampleRepository samples;
    private JobExecutor executor;
    private Path reportsDir;
    private Path dataRoot;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** dbGaP's canned answers, by "phs.vN"; anything else FAILED (an outage). */
    private final Map<String, DbgapLookup> answers = new ConcurrentHashMap<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        managedInputs = mock(ManagedInputsService.class);
        consents = mock(ConsentRepository.class);
        samples = mock(SampleRepository.class);
        when(consents.countParticipantsByConsent(anyCollection())).thenReturn(List.of());
        when(samples.countSamplesByConsent(anyCollection(), eq("DBGap"))).thenReturn(List.of());
        reportsDir = tempDir.resolve("reports");
        dataRoot = tempDir.resolve("data");
        Files.createDirectories(reportsDir);
        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(reportsDir.toString());
        executor = new JobExecutor(new ReportWriter(mapper), properties);
    }

    private QaPipelineCountsJob job() {
        return new QaPipelineCountsJob(managedInputs, consents, samples, new IoResolver(null), new DelimitedReader(),
                mapper, config -> (sid, v) -> {
                    asked.add(sid + "." + v);
                    return answers.getOrDefault(sid + "." + v, DbgapLookup.failed("HTTP 502", 3));
                });
    }

    private static ManagedInputRow study(String abv, String studyId, String version, boolean ready) {
        return new ManagedInputRow(abv, studyId, version, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                "", "", "", "", "", ready, false);
    }

    private void managedInputs(ManagedInputRow... rows) {
        when(managedInputs.read()).thenReturn(List.of(rows));
    }

    /** DB participants as (study, code, count) triples. */
    private void dbParticipants(Object... triples) {
        List<ConsentGroupCount> rows = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            rows.add(new ConsentGroupCount((String) triples[i], (String) triples[i + 1], "ABV" + triples[i + 1],
                    ((Number) triples[i + 2]).longValue()));
        }
        when(consents.countParticipantsByConsent(anyCollection())).thenReturn(rows);
    }

    /** DB dbGaP samples as (study, code, count) triples. */
    private void dbSamples(Object... triples) {
        List<ConsentGroupSamples> rows = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            rows.add(new ConsentGroupSamples((String) triples[i], (String) triples[i + 1],
                    ((Number) triples[i + 2]).longValue()));
        }
        when(samples.countSamplesByConsent(anyCollection(), eq("DBGap"))).thenReturn(rows);
    }

    /** A summary's study object, as the cache files hold it: groups as (code, subjects, samples). */
    private JsonNode studyJson(String accession, int version, long... groups) throws IOException {
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < groups.length; i += 3) {
            g.append(g.isEmpty() ? "" : ",").append("{\"code\":").append(groups[i]).append(",\"short_name\":\"G")
                    .append(groups[i]).append("\",\"subject_count\":").append(groups[i + 1])
                    .append(",\"sample_count\":").append(groups[i + 2]).append('}');
        }
        return mapper.readTree("{\"accver\":{\"accession\":\"" + accession + "\",\"version\":" + version
                + ",\"participant_set\":1},\"name\":\"A study\",\"consent_groups\":[" + g + "]}");
    }

    private Path cacheFile(String studyId, String version) {
        return dataRoot.resolve(studyId).resolve("rawData").resolve(studyId + "_" + version + "_summary.json");
    }

    private void cached(String studyId, String version, JsonNode study) throws IOException {
        Files.createDirectories(cacheFile(studyId, version).getParent());
        Files.writeString(cacheFile(studyId, version), mapper.writeValueAsString(study));
    }

    private void answer(String studyId, String version, JsonNode study) {
        answers.put(studyId + "." + version, DbgapLookup.found(DbgapStudySummary.from(study), study, 1));
    }

    private Path runCounts(String... lines) throws IOException {
        Path p = tempDir.resolve("run_counts.csv");
        Files.writeString(p, "study_id,consent_code,source,participants\n" + String.join("\n", lines) + "\n");
        return p;
    }

    private JobResult run(Map<String, String> extra) {
        Map<String, String> params = new HashMap<>(Map.of("data-root", dataRoot.toString(), "pipeline", "permanent"));
        params.putAll(extra);
        return executor.run(job(), params, "t");
    }

    /** A run with the dbGaP fallback on: summaries with no usable file are fetched. */
    private JobResult fetching(Map<String, String> extra) {
        Map<String, String> params = new HashMap<>(extra);
        params.put("fetch-missing-summaries", "true");
        return run(params);
    }

    private List<String> file(String name) throws IOException {
        return Files.readAllLines(reportsDir.resolve(name), StandardCharsets.UTF_8);
    }

    private static List<String> codes(JobResult r) {
        List<String> codes = new ArrayList<>();
        r.getOutputValidation().getIssues().forEach(i -> codes.add(i.severity() + ":" + i.code()));
        return codes;
    }

    /* ---- Summaries ---- */

    @Test
    void a_cached_summary_is_used_without_asking_dbgap() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true));
        cached("phs000001", "v1", studyJson("phs000001", 1, 1, 10, 8));
        dbParticipants("phs000001", "1", 10);
        dbSamples("phs000001", "1", 8);

        JobResult result = run(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(asked).isEmpty();
        assertThat(result.getMetrics())
                .containsEntry("summariesFromCache", 1L)
                .containsEntry("summariesFromApi", 0L)
                .containsEntry("countsCompared", 2L)
                .containsEntry("countsMatching", 2L)
                .containsEntry("discrepancies", 0L);
        assertThat(file(PipelineCountsReport.STUDIES_FILE)).containsExactly(PipelineCountsReport.STUDIES_HEADER,
                "phs000001,ONE,v1,false,CACHE,MATCH,1,0,");
        assertThat(String.join("\n", file(PipelineCountsReport.MARKDOWN_FILE)))
                .contains("**Result: every count matches dbGaP.**");
    }

    /** The file layout and shape in the data bucket: {@code phs004477/rawData/phs004477_v1_summary.json}. */
    @Test
    void reads_a_real_cached_summary_file() throws IOException {
        managedInputs(study("HEIRS", "phs004477", "v1", true));
        Files.createDirectories(cacheFile("phs004477", "v1").getParent());
        try (var in = getClass().getResourceAsStream("/qa/phs004477_v1_summary.json")) {
            Files.copy(in, cacheFile("phs004477", "v1"));
        }
        dbParticipants("phs004477", "1", 102689);
        dbSamples("phs004477", "1", 102689);

        JobResult result = run(Map.of());

        assertThat(asked).isEmpty();
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(file(PipelineCountsReport.COUNTS_FILE)).contains(
                "phs004477,v1,false,1,DS-HH-MDS-RD,db:participants,subject_count,102689,102689,MATCH,",
                "phs004477,v1,false,1,DS-HH-MDS-RD,db:samples,sample_count,102689,102689,MATCH,");
    }

    private void stagedAs(String studyId, String fileName, JsonNode study) throws IOException {
        Path p = dataRoot.resolve(studyId).resolve("rawData").resolve(fileName);
        Files.createDirectories(p.getParent());
        Files.writeString(p, mapper.writeValueAsString(study));
    }

    @Test
    void a_summary_named_study_summary_is_read_when_the_canonical_file_is_absent() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true));
        stagedAs("phs000001", "phs000001.v1.p1_study_summary.json", studyJson("phs000001", 1, 1, 10, 8));
        dbParticipants("phs000001", "1", 10);
        dbSamples("phs000001", "1", 8);

        JobResult result = run(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(asked).isEmpty();
        assertThat(result.getMetrics()).containsEntry("summariesFromCache", 1L).containsEntry("countsMatching", 2L);
    }

    @Test
    void of_several_study_summary_files_the_one_for_the_managed_version_is_used() throws IOException {
        managedInputs(study("ONE", "phs000001", "v2", true));
        // Sorts first by name, but is the old version: skipped.
        stagedAs("phs000001", "phs000001_a_study_summary.json", studyJson("phs000001", 1, 1, 99, 99));
        stagedAs("phs000001", "phs000001_b_study_summary.json", studyJson("phs000001", 2, 1, 10, 8));
        dbParticipants("phs000001", "1", 10);
        dbSamples("phs000001", "1", 8);

        JobResult result = run(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("countsMatching", 2L).containsEntry("discrepancies", 0L);
    }

    @Test
    void a_study_summary_file_for_another_version_only_is_unusable() throws IOException {
        managedInputs(study("ONE", "phs000001", "v2", true));
        stagedAs("phs000001", "phs000001_study_summary.json", studyJson("phs000001", 1, 1, 10, 8));
        dbParticipants("phs000001", "1", 10);

        JobResult result = run(Map.of());

        assertThat(asked).isEmpty();
        assertThat(String.join("\n", file(PipelineCountsReport.STUDIES_FILE)))
                .contains("SUMMARY_MISSING")
                .contains("phs000001_study_summary.json is unusable (describes phs000001.v1");
    }

    @Test
    void a_summary_not_cached_is_fetched_once_and_cached_as_its_study_object() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true));
        JsonNode study = studyJson("phs000001", 1, 1, 10, 8);
        answer("phs000001", "v1", study);
        dbParticipants("phs000001", "1", 10);
        dbSamples("phs000001", "1", 8);

        JobResult result = fetching(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(asked).containsExactly("phs000001.v1");
        assertThat(mapper.readTree(cacheFile("phs000001", "v1").toFile())).isEqualTo(study);
        assertThat(result.getMetrics())
                .containsEntry("summariesFromApi", 1L)
                .containsEntry("summariesCached", 1);
    }

    @Test
    void cache_summaries_false_fetches_without_writing() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true));
        answer("phs000001", "v1", studyJson("phs000001", 1, 1, 10, 8));
        dbParticipants("phs000001", "1", 10);
        dbSamples("phs000001", "1", 8);

        fetching(Map.of("cache-summaries", "false"));

        assertThat(cacheFile("phs000001", "v1")).doesNotExist();
    }

    @Test
    void an_unusable_cached_summary_is_fetched_again_and_replaced() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true));
        Files.createDirectories(cacheFile("phs000001", "v1").getParent());
        Files.writeString(cacheFile("phs000001", "v1"), "<html>502 Bad Gateway</html>");
        JsonNode study = studyJson("phs000001", 1, 1, 10, 8);
        answer("phs000001", "v1", study);
        dbParticipants("phs000001", "1", 10);
        dbSamples("phs000001", "1", 8);

        JobResult result = fetching(Map.of());

        assertThat(asked).containsExactly("phs000001.v1");
        assertThat(mapper.readTree(cacheFile("phs000001", "v1").toFile())).isEqualTo(study);
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(String.valueOf(result.getMetrics().get("unusableCachedSummaries"))).startsWith("{phs000001=");
    }

    @Test
    void an_answer_for_another_version_is_neither_cached_nor_compared() throws IOException {
        managedInputs(study("ONE", "phs000001", "v2", true));
        answer("phs000001", "v2", studyJson("phs000001", 1, 1, 10, 8));
        dbParticipants("phs000001", "1", 10);

        JobResult result = fetching(Map.of());

        assertThat(cacheFile("phs000001", "v2")).doesNotExist();
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(codes(result)).contains("WARNING:VERSION_MISMATCH");
    }

    @Test
    void a_study_without_a_version_is_not_looked_up() {
        managedInputs(study("ONE", "phs000001", "", true), study("TWO", "phs000002", "v1", true));
        answers.put("phs000002.v1", DbgapLookup.notFound("Invalid or non-existent study", 1));

        JobResult result = fetching(Map.of());

        assertThat(asked).containsExactly("phs000002.v1");
        assertThat(codes(result)).contains("WARNING:NO_VERSION", "WARNING:VERSION_NOT_FOUND");
    }

    /* ---- Comparisons ---- */

    @Test
    void every_ready_study_is_checked_and_run_counts_only_for_the_studies_the_run_processed() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true), study("TWO", "phs000002", "v3", true),
                study("OFF", "phs000003", "v1", false));
        cached("phs000001", "v1", studyJson("phs000001", 1, 1, 402, 400, 2, 3, 3));
        cached("phs000002", "v3", studyJson("phs000002", 3, 1, 50, 0));
        dbParticipants("phs000001", "1", 402, "phs000001", "2", 3, "phs000002", "1", 50);
        dbSamples("phs000001", "1", 400, "phs000001", "2", 3);
        Path counts = runCounts(
                "phs000001,1,sstr-load,402", "phs000001,2,sstr-load,3",
                "phs000001,c1,all-concepts-data-generator,400", "phs000001,c2,all-concepts-data-generator,3");

        JobResult result = run(Map.of("run-counts", counts.toString()));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getMetrics())
                .containsEntry("studiesChecked", 2)
                .containsEntry("studiesInRun", 1L)
                .containsEntry("discrepancies", 1L)
                .containsEntry("discrepanciesByMeasure", Map.of("all-concepts-data-generator", 1L))
                .containsEntry("studiesWithDiscrepancies", List.of("phs000001"))
                .containsEntry("runCountSources", List.of("sstr-load", "all-concepts-data-generator"));
        ValidationIssue issue = result.getOutputValidation().getIssues().stream()
                .filter(i -> i.code().equals("COUNT_MISMATCH")).findFirst().orElseThrow();
        assertThat(issue.message()).isEqualTo("phs000001.v1: 1 count(s): c1 all-concepts-data-generator 400 vs dbGaP 402");

        assertThat(file(PipelineCountsReport.DISCREPANCIES_FILE)).containsExactly(PipelineCountsReport.COUNTS_HEADER,
                "phs000001,v1,true,1,G1,all-concepts-data-generator,subject_count,402,400,COUNT_MISMATCH,"
                        + "all-concepts-data-generator 400 vs dbGaP 402 (-2)");
        // The study not in the run: the DB only, participants and samples.
        assertThat(file(PipelineCountsReport.COUNTS_FILE)).contains(
                "phs000002,v3,false,1,G1,db:participants,subject_count,50,50,MATCH,",
                "phs000002,v3,false,1,G1,db:samples,sample_count,0,0,MATCH,");
        String md = String.join("\n", file(PipelineCountsReport.MARKDOWN_FILE));
        assertThat(md)
                .contains("### phs000001 (ONE) v1 (processed by this run)")
                .contains("| Consent | dbGaP subjects | dbGaP samples | db:participants | db:samples | sstr-load | "
                        + "all-concepts-data-generator |")
                .contains("| c1 G1 | 402 | 400 | 402 | 400 | 402 | **400** (402) |")
                .contains("| phs000002 (TWO) | v3 | no | cache | 1 | MATCH | 0 |");
    }

    @Test
    void db_samples_are_compared_with_the_sample_count() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true));
        cached("phs000001", "v1", studyJson("phs000001", 1, 1, 10, 8));
        dbParticipants("phs000001", "1", 10);
        dbSamples("phs000001", "1", 16);

        JobResult result = run(Map.of());

        assertThat(result.getMetrics()).containsEntry("discrepanciesByMeasure", Map.of("db:samples", 1L));
        assertThat(file(PipelineCountsReport.DISCREPANCIES_FILE)).contains(
                "phs000001,v1,false,1,G1,db:samples,sample_count,8,16,COUNT_MISMATCH,db:samples 16 vs dbGaP 8 (+8)");
    }

    @Test
    void consent_0_not_loaded_is_by_design_and_a_group_dbgap_lacks_is_flagged() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true));
        cached("phs000001", "v1", studyJson("phs000001", 1, 0, 576, 0, 1, 10, 0));
        dbParticipants("phs000001", "1", 10, "phs000001", "7", 2);

        JobResult result = run(Map.of());

        assertThat(file(PipelineCountsReport.COUNTS_FILE)).contains(
                "phs000001,v1,false,0,G0,db:participants,subject_count,576,0,NOT_LOADED_BY_DESIGN,"
                        + "consent 0 (not consented) is not loaded",
                "phs000001,v1,false,7,ABV7,db:participants,subject_count,0,2,NOT_IN_DBGAP,"
                        + "dbGaP does not list consent group 7");
        assertThat(result.getMetrics()).containsEntry("discrepanciesByType", Map.of("NOT_IN_DBGAP", 1L));
    }

    /* ---- dbGaP unavailable ---- */

    @Test
    void a_study_with_no_summary_is_unverified_while_the_rest_are_compared() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true), study("TWO", "phs000002", "v1", true));
        cached("phs000001", "v1", studyJson("phs000001", 1, 1, 10, 0));
        dbParticipants("phs000001", "1", 10, "phs000002", "1", 5);

        JobResult result = fetching(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(codes(result)).containsExactly("WARNING:DBGAP_UNAVAILABLE");
        assertThat(result.getMetrics()).containsEntry("unverifiedStudies", List.of("phs000002"));
        assertThat(String.join("\n", file(PipelineCountsReport.MARKDOWN_FILE)))
                .contains("## Not checked (no summary)");
    }

    @Test
    void no_summary_for_any_study_is_an_infrastructure_error_with_the_files_still_written() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true));
        dbParticipants("phs000001", "1", 10);

        JobResult result = fetching(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(result.getMetrics()).containsEntry("unverifiedStudies", List.of("phs000001"));
        assertThat(file(PipelineCountsReport.COUNTS_FILE)).hasSize(2);
    }

    /* ---- Fetching missing summaries off (the default) ---- */

    @Test
    void without_fetching_a_study_with_no_summary_file_fails_and_dbgap_is_not_asked() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true), study("TWO", "phs000002", "v1", true));
        cached("phs000001", "v1", studyJson("phs000001", 1, 1, 10, 0));
        answer("phs000002", "v1", studyJson("phs000002", 1, 1, 5, 0));
        dbParticipants("phs000001", "1", 10, "phs000002", "1", 5);

        JobResult result = run(Map.of());

        assertThat(asked).isEmpty();
        assertThat(cacheFile("phs000002", "v1")).doesNotExist();
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(codes(result)).containsExactly("WARNING:SUMMARY_MISSING");
        assertThat(result.getMetrics())
                .containsEntry("fetchMissingSummaries", false)
                .containsEntry("summariesFromCache", 1L)
                .containsEntry("apiLookups", 0)
                .containsEntry("discrepanciesByType", Map.of("SUMMARY_MISSING", 1L))
                .containsEntry("unverifiedStudies", List.of());
        assertThat(file(PipelineCountsReport.STUDIES_FILE)).anyMatch(l -> l.startsWith("phs000002,TWO,v1,false,NONE,SUMMARY_MISSING,"));
        assertThat(String.join("\n", file(PipelineCountsReport.MARKDOWN_FILE)))
                .contains("SUMMARY_MISSING", "fetching missing summaries from dbGaP is off");
    }

    @Test
    void without_fetching_an_unusable_summary_file_fails_the_study() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true));
        Files.createDirectories(cacheFile("phs000001", "v1").getParent());
        Files.writeString(cacheFile("phs000001", "v1"), "<html>502 Bad Gateway</html>");
        dbParticipants("phs000001", "1", 10);

        JobResult result = run(Map.of("fetch-missing-summaries", "false"));

        assertThat(asked).isEmpty();
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues()).singleElement()
                .satisfies(i -> assertThat(i.message()).contains("is unusable", "fetching missing summaries"));
    }

    /** Not retryable when nothing was asked of dbGaP: each study has failed on its own. */
    @Test
    void without_fetching_no_summary_file_at_all_fails_every_study_not_the_run() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true), study("TWO", "phs000002", "v1", true));
        dbParticipants("phs000001", "1", 10);

        JobResult result = run(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(codes(result)).containsExactly("WARNING:SUMMARY_MISSING", "WARNING:SUMMARY_MISSING");
    }

    @Test
    void rejects_a_fetch_missing_summaries_that_is_not_a_boolean() {
        managedInputs(study("ONE", "phs000001", "v1", true));

        JobResult result = run(Map.of("fetch-missing-summaries", "sometimes"));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
    }

    /* ---- Inputs ---- */

    @Test
    void malformed_run_counts_are_a_data_error() throws IOException {
        managedInputs(study("ONE", "phs000001", "v1", true));
        cached("phs000001", "v1", studyJson("phs000001", 1, 1, 10, 0));
        Path counts = runCounts("phs000001,1,sstr-load,ten");

        JobResult result = run(Map.of("run-counts", counts.toString()));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.DATA_ERROR);
    }

    @Test
    void rejects_an_unknown_pipeline_before_running() {
        managedInputs(study("ONE", "phs000001", "v1", true));

        JobResult result = run(Map.of("pipeline", "nightly"));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
    }
}
