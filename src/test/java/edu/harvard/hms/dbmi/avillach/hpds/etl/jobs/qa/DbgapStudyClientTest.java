package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.StubDbgapServer.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DbgapStudyClient} against a local stub of the dbGaP API: what is retried, what is not, how
 * long it waits, and how it reads the response. No real network and no real sleeping between
 * attempts -- waits are recorded by an injected sleeper.
 */
class DbgapStudyClientTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final List<Duration> waits = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
    }

    private StubDbgapServer server() {
        StubDbgapServer s = new StubDbgapServer();
        closeables.add(s);
        return s;
    }

    private DbgapStudyClient client(String baseUrl) {
        return client(baseUrl, new DbgapStudyClient.Config(baseUrl, 5, Duration.ofSeconds(2), Duration.ofSeconds(60),
                Duration.ofSeconds(5)), d -> waits.add(d));
    }

    private DbgapStudyClient client(String baseUrl, DbgapStudyClient.Config config, DbgapStudyClient.Sleeper sleeper) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        DbgapStudyClient c = new DbgapStudyClient(http, config, new ObjectMapper(), sleeper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        closeables.add(c);
        return c;
    }

    private static String notFound(String accession) {
        return String.format(StubDbgapServer.NOT_FOUND_BODY_TEMPLATE, accession);
    }

    /* ---- The API answering ---- */

    @Test
    void a_200_is_parsed_into_the_consent_groups_in_one_attempt() {
        StubDbgapServer s = server().on("phs001412.v4", Reply.json(200, StubDbgapServer.sample()));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FOUND);
        assertThat(lookup.attempts()).isEqualTo(1);
        assertThat(lookup.summary().accession()).isEqualTo("phs001412");
        assertThat(lookup.summary().version()).isEqualTo(4);
        assertThat(lookup.summary().consentGroups()).containsExactly(
                new DbgapStudySummary.ConsentGroup("0", "NRUP", 576L),
                new DbgapStudySummary.ConsentGroup("1", "HMB-IRB-NPU", 402L),
                new DbgapStudySummary.ConsentGroup("2", "DS-DHD-IRB-NPU", 3L));
        assertThat(s.requests()).containsExactly("/study/phs001412.v4/summary");
        assertThat(waits).isEmpty();
    }

    @Test
    void an_unknown_study_version_is_not_found_after_exactly_one_attempt() {
        StubDbgapServer s = server().on("phs001412.v99", Reply.json(400, notFound("phs001412.v99")));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs001412", "v99");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.NOT_FOUND);
        assertThat(lookup.attempts()).isEqualTo(1);
        assertThat(lookup.message()).isEqualTo("Invalid or non-existent study with accession phs001412.v99");
        assertThat(s.requestsFor("phs001412.v99")).isEqualTo(1);
        assertThat(waits).isEmpty();
    }

    @Test
    void missing_and_null_fields_are_read_defensively() {
        StubDbgapServer s = server().on("phs000001.v1", Reply.json(200, """
                {"study": {"accver": null, "consent_groups": [
                    {"code": null, "subject_count": 5},
                    {"short_name": "NO-CODE"},
                    "not an object",
                    {"code": "3", "short_name": null, "subject_count": null},
                    {"code": 1, "subject_count": "12"}]}}"""));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs000001", "v1");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FOUND);
        DbgapStudySummary summary = lookup.summary();
        assertThat(summary.accession()).isNull();
        assertThat(summary.version()).isNull();
        assertThat(summary.consentGroups()).containsExactly(
                new DbgapStudySummary.ConsentGroup("3", null, null),
                new DbgapStudySummary.ConsentGroup("1", null, 12L));
        assertThat(summary.groupsWithoutCode()).isEqualTo(3);
    }

    @Test
    void a_study_object_without_consent_groups_has_none() {
        StubDbgapServer s = server().on("phs000001.v1", Reply.json(200, "{\"study\":{}}"));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs000001", "v1");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FOUND);
        assertThat(lookup.summary().consentGroups()).isEmpty();
    }

    /* ---- Retries ---- */

    @Test
    void a_429_waits_for_its_retry_after_seconds_then_succeeds() {
        StubDbgapServer s = server().on("phs001412.v4",
                Reply.json(429, "{}").withHeader("Retry-After", "7"),
                Reply.json(200, StubDbgapServer.sample()));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FOUND);
        assertThat(lookup.attempts()).isEqualTo(2);
        assertThat(waits).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void a_retry_after_http_date_is_honoured_relative_to_the_clock() {
        StubDbgapServer s = server().on("phs001412.v4",
                Reply.json(503, "").withHeader("Retry-After", "Tue, 20 Oct 2026 12:00:30 GMT"),
                Reply.json(503, "").withHeader("Retry-After", "Tue, 20 Oct 2026 11:00:00 GMT"),
                Reply.json(200, StubDbgapServer.sample()));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FOUND);
        // 30 s ahead of the clock; a date already past means retry now.
        assertThat(waits).containsExactly(Duration.ofSeconds(30), Duration.ZERO);
    }

    @Test
    void an_unreadable_retry_after_falls_back_to_backoff_and_a_huge_one_is_capped() {
        StubDbgapServer s = server().on("phs001412.v4",
                Reply.json(503, "").withHeader("Retry-After", "soon"),
                Reply.json(503, "").withHeader("Retry-After", "86400"),
                Reply.json(200, StubDbgapServer.sample()));

        client(s.baseUrl()).fetch("phs001412", "v4");

        assertThat(waits).containsExactly(Duration.ofSeconds(2), DbgapStudyClient.MAX_RETRY_AFTER);
    }

    @Test
    void two_503s_then_a_200_succeeds_on_the_third_attempt_with_exponential_backoff() {
        StubDbgapServer s = server().on("phs001412.v4",
                Reply.json(503, "Service Unavailable"), Reply.json(503, "Service Unavailable"),
                Reply.json(200, StubDbgapServer.sample()));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FOUND);
        assertThat(lookup.attempts()).isEqualTo(3);
        assertThat(waits).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(s.requestsFor("phs001412.v4")).isEqualTo(3);
    }

    @Test
    void five_503s_fail_after_five_attempts() {
        StubDbgapServer s = server().on("phs001412.v4", Reply.json(503, "Service Unavailable"));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FAILED);
        assertThat(lookup.attempts()).isEqualTo(5);
        assertThat(lookup.message()).contains("after 5 attempt(s)").contains("HTTP 503");
        // dbGaP was reached; it just could not answer.
        assertThat(lookup.unreachable()).isFalse();
        assertThat(waits).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(16));
        assertThat(s.requestsFor("phs001412.v4")).isEqualTo(5);
    }

    @Test
    void backoff_is_capped_at_the_maximum() {
        StubDbgapServer s = server().on("phs001412.v4", Reply.json(500, ""));
        DbgapStudyClient c = client(s.baseUrl(), new DbgapStudyClient.Config(s.baseUrl(), 6, Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofSeconds(5)), waits::add);

        c.fetch("phs001412", "v4");

        assertThat(waits).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Test
    void a_timeout_is_retried() {
        StubDbgapServer s = server().on("phs001412.v4",
                Reply.json(200, StubDbgapServer.sample()).delayed(2_000),
                Reply.json(200, StubDbgapServer.sample()));
        DbgapStudyClient c = client(s.baseUrl(), new DbgapStudyClient.Config(s.baseUrl(), 5, Duration.ofSeconds(2),
                Duration.ofSeconds(60), Duration.ofMillis(300)), waits::add);

        DbgapLookup lookup = c.fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FOUND);
        assertThat(lookup.attempts()).isEqualTo(2);
        assertThat(waits).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void a_refused_connection_is_retried_and_succeeds_once_the_api_is_up() throws IOException {
        int port = freePort();
        String baseUrl = "http://127.0.0.1:" + port + "/study";
        AtomicReference<StubDbgapServer> started = new AtomicReference<>();
        // Nothing listens for the first attempt; the API "comes back" during the wait before the second.
        DbgapStudyClient c = client(baseUrl, new DbgapStudyClient.Config(baseUrl, 5, Duration.ofSeconds(2),
                Duration.ofSeconds(60), Duration.ofSeconds(5)), d -> {
            waits.add(d);
            if (started.get() == null) {
                StubDbgapServer s = new StubDbgapServer(port).on("phs001412.v4",
                        Reply.json(200, StubDbgapServer.sample()));
                closeables.add(s);
                started.set(s);
            }
        });

        DbgapLookup lookup = c.fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FOUND);
        assertThat(lookup.attempts()).isEqualTo(2);
        assertThat(waits).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void a_persistently_refused_connection_fails_after_every_attempt() throws IOException {
        String baseUrl = "http://127.0.0.1:" + freePort() + "/study";

        DbgapLookup lookup = client(baseUrl).fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FAILED);
        assertThat(lookup.attempts()).isEqualTo(5);
        assertThat(lookup.message()).contains("request failed");
        assertThat(lookup.unreachable()).isTrue();
        assertThat(waits).hasSize(4);
    }

    @Test
    void a_slow_answer_that_times_out_every_time_is_not_unreachable() {
        StubDbgapServer s = server().on("phs001412.v4", Reply.json(200, StubDbgapServer.sample()).delayed(2_000));
        DbgapStudyClient c = client(s.baseUrl(), new DbgapStudyClient.Config(s.baseUrl(), 2, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofMillis(300)), waits::add);

        DbgapLookup lookup = c.fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FAILED);
        assertThat(lookup.message()).contains("timed out");
        assertThat(lookup.unreachable()).isFalse();
    }

    @Test
    void only_the_last_attempt_decides_whether_dbgap_was_unreachable() throws IOException {
        int port = freePort();
        String baseUrl = "http://127.0.0.1:" + port + "/study";
        // Refused first; then up, but answering 503.
        DbgapStudyClient c = client(baseUrl, new DbgapStudyClient.Config(baseUrl, 2, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(5)), d -> {
            waits.add(d);
            closeables.add(new StubDbgapServer(port).on("phs001412.v4", Reply.json(503, "busy")));
        });

        DbgapLookup lookup = c.fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FAILED);
        assertThat(lookup.message()).contains("HTTP 503");
        assertThat(lookup.unreachable()).isFalse();
    }

    @Test
    void a_200_with_malformed_json_is_retried() {
        StubDbgapServer s = server().on("phs001412.v4",
                Reply.json(200, "<html>upstream proxy error</html>"),
                Reply.json(200, "{\"study\": [1, 2"),
                Reply.json(200, "{\"unexpected\": true}"),
                Reply.json(200, StubDbgapServer.sample()));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FOUND);
        assertThat(lookup.attempts()).isEqualTo(4);
    }

    @Test
    void a_200_that_never_becomes_json_fails_after_every_attempt() {
        StubDbgapServer s = server().on("phs001412.v4", Reply.json(200, "not json"));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FAILED);
        assertThat(lookup.attempts()).isEqualTo(5);
        assertThat(lookup.message()).contains("not a JSON object");
    }

    /* ---- Not retried ---- */

    @Test
    void a_403_fails_at_once() {
        StubDbgapServer s = server().on("phs001412.v4", Reply.json(403, "Forbidden"));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FAILED);
        assertThat(lookup.attempts()).isEqualTo(1);
        assertThat(lookup.message()).contains("HTTP 403");
        assertThat(waits).isEmpty();
    }

    @Test
    void a_400_with_another_error_code_fails_at_once_rather_than_reading_as_not_found() {
        StubDbgapServer s = server().on("phs001412.v4",
                Reply.json(400, "{\"error\":{\"error_message\":\"Bad request\",\"error_code\":7}}"));

        DbgapLookup lookup = client(s.baseUrl()).fetch("phs001412", "v4");

        assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FAILED);
        assertThat(lookup.attempts()).isEqualTo(1);
        assertThat(lookup.message()).contains("error_code 7");
    }

    @Test
    void an_interrupted_wait_stops_retrying_and_keeps_the_interrupt() {
        StubDbgapServer s = server().on("phs001412.v4", Reply.json(503, ""));
        DbgapStudyClient c = client(s.baseUrl(), new DbgapStudyClient.Config(s.baseUrl(), 5, Duration.ofSeconds(2),
                Duration.ofSeconds(60), Duration.ofSeconds(5)), d -> {
            throw new InterruptedException();
        });
        try {
            DbgapLookup lookup = c.fetch("phs001412", "v4");

            assertThat(lookup.outcome()).isEqualTo(DbgapLookup.Outcome.FAILED);
            assertThat(lookup.attempts()).isEqualTo(1);
            assertThat(lookup.message()).contains("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void the_summary_url_follows_the_documented_shape() {
        DbgapStudyClient c = client(DbgapStudyClient.DEFAULT_BASE_URL + "/");
        assertThat(c.summaryUri("phs001412", "v4").toString())
                .isEqualTo("https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study/phs001412.v4/summary");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
