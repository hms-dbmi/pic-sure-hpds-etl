package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.StubDbgapServer.Reply;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring-wired job against a real Postgres (Testcontainers) and the real HTTP client talking to
 * a local stub of the dbGaP API: DB counts are taken with the job's own query, and the stub serves
 * retries, an unknown version and an outage.
 */
class QaConsentCountsJobIT extends AbstractIntegrationTest {

    @Autowired
    private QaConsentCountsJob job;
    @Autowired
    private JobExecutor executor;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EtlProperties properties;
    @Autowired
    private ObjectMapper mapper;

    private StubDbgapServer dbgap;
    private Path reportsDir;
    private Path tmp;
    private String previousManagedInputs;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.execute("TRUNCATE TABLE participants, consents, samples");
        dbgap = new StubDbgapServer();
        reportsDir = Path.of(properties.getReports().getDir());
        tmp = Files.createTempDirectory("qa-consent-counts-it");
        previousManagedInputs = properties.getManagedInputs().getUri();
    }

    @AfterEach
    void tearDown() throws IOException {
        dbgap.close();
        properties.getManagedInputs().setUri(previousManagedInputs);
        for (String f : List.of(QaConsentCountsJob.COUNTS_FILE, QaConsentCountsJob.DISCREPANCIES_FILE,
                "qa-consent-counts-it.json")) {
            Files.deleteIfExists(reportsDir.resolve(f));
        }
    }

    private void managedInputs(String... rows) throws IOException {
        StringBuilder csv = new StringBuilder("Study Abbreviated Name,Study Identifier,Version,Data is ready to process\n");
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        Path file = tmp.resolve("managed_inputs.csv");
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        properties.getManagedInputs().setUri(file.toString());
    }

    /** {@code n} participants of {@code studyId} in consent {@code code} (a "c" prefix is fine). */
    private void consents(String studyId, String code, int n) {
        for (int i = 0; i < n; i++) {
            Long id = jdbc.queryForObject("SELECT nextval('hpds_id_seq')", Long.class);
            jdbc.update("INSERT INTO consents (hpds_id, study_id, consent_code, consent_abbreviation) VALUES (?, ?, ?, ?)",
                    id, studyId, code, "ABV-" + code);
        }
    }

    private Map<String, String> params(String... extra) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dbgap-base-url", dbgap.baseUrl());
        p.put("dbgap-initial-backoff-seconds", "0");
        p.put("dbgap-max-attempts", "3");
        p.put("dbgap-timeout-seconds", "5");
        for (int i = 0; i < extra.length; i += 2) {
            p.put(extra[i], extra[i + 1]);
        }
        return p;
    }

    private List<String> csv(String name) throws IOException {
        return Files.readAllLines(reportsDir.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    void db_counts_are_compared_with_dbgap_and_the_load_report_end_to_end() throws IOException {
        managedInputs("DHS,phs001412,v4,Yes", "MATCHED,phs000007,v33,Yes", "GONE,phs000099,v2,Yes",
                "OFF,phs000100,v1,No");
        consents("phs001412", "1", 402);
        consents("phs001412", "c2", 2);
        consents("phs000007", "1", 3);
        consents("phs000099", "1", 1);
        // dbGaP blips twice for phs000007, then answers.
        dbgap.on("phs001412.v4", Reply.json(200, StubDbgapServer.sample()))
                .on("phs000007.v33", Reply.json(503, ""), Reply.json(502, ""),
                        Reply.json(200, StubDbgapServer.summary("phs000007", 33, 1, 3)));
        Path load = tmp.resolve("participants_migration_consent_counts.csv");
        Files.writeString(load, "study_id,abv,outcome,source,consent_code,participants_loaded\n"
                + "phs001412,DHS,success,multi,1,402\nphs001412,DHS,success,multi,2,2\n");

        JobResult r = executor.run(job, params("load-counts", load.toString()), "it");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(csv(QaConsentCountsJob.COUNTS_FILE)).containsExactly(QaConsentCountsJob.CSV_HEADER,
                "phs001412,v4,0,NRUP,576,0,0,multi,MISSING_IN_DB,source MULTI drops consent 0",
                "phs001412,v4,1,HMB-IRB-NPU,402,402,402,multi,MATCH,",
                "phs001412,v4,2,DS-DHD-IRB-NPU,3,2,2,multi,COUNT_MISMATCH,",
                "phs000007,v33,1,G1,3,3,,,MATCH,",
                "phs000099,v2,1,ABV-1,,1,,,VERSION_NOT_FOUND,dbGaP has no phs000099.v2: "
                        + "Invalid or non-existent study with accession phs000099.v2");
        assertThat(csv(QaConsentCountsJob.DISCREPANCIES_FILE)).hasSize(4);
        assertThat(dbgap.requestsFor("phs000007.v33")).isEqualTo(3);
        assertThat(dbgap.requestsFor("phs000099.v2")).isEqualTo(1);
        assertThat(dbgap.requests()).noneMatch(p -> p.contains("phs000100"));

        JsonNode m = mapper.readTree(reportsDir.resolve("qa-consent-counts-it.json").toFile()).get("metrics");
        assertThat(m.get("studiesChecked").asInt()).isEqualTo(3);
        assertThat(m.get("discrepancies").asInt()).isEqualTo(3);
        assertThat(m.get("apiAttempts").asInt()).isEqualTo(5);
        assertThat(m.get("apiRetries").asInt()).isEqualTo(2);
    }

    @Test
    void an_outage_exits_4_with_the_report_and_csvs_written() throws IOException {
        managedInputs("DHS,phs001412,v4,Yes", "DOWN,phs000007,v33,Yes");
        consents("phs001412", "0", 576);
        consents("phs001412", "1", 402);
        consents("phs001412", "2", 3);
        consents("phs000007", "1", 3);
        dbgap.on("phs001412.v4", Reply.json(200, StubDbgapServer.sample()))
                .on("phs000007.v33", Reply.json(503, "maintenance"));

        JobResult r = executor.run(job, params(), "it");

        assertThat(r.getExitCode()).isEqualTo(ExitCode.INFRASTRUCTURE_ERROR);
        assertThat(dbgap.requestsFor("phs000007.v33")).isEqualTo(3);
        assertThat(csv(QaConsentCountsJob.COUNTS_FILE)).contains(
                "phs001412,v4,1,HMB-IRB-NPU,402,402,,,MATCH,")
                .anyMatch(l -> l.startsWith("phs000007,v33,1,ABV-1,,3,,,UNVERIFIED,"));
        JsonNode report = mapper.readTree(reportsDir.resolve("qa-consent-counts-it.json").toFile());
        assertThat(report.get("exitCode").asText()).isEqualTo("INFRASTRUCTURE_ERROR");
        assertThat(report.at("/metrics/unverifiedStudies/0").asText()).isEqualTo("phs000007");
        assertThat(report.at("/metrics/consentsMatching").asInt()).isEqualTo(3);
        assertThat(report.at("/outputValidation/issues/0/code").asText()).isEqualTo("DBGAP_UNAVAILABLE");
    }
}
