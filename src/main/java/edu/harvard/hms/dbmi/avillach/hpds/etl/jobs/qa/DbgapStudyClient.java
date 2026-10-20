package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Reads study summaries from the dbGaP SSTR API,
 * {@code GET {baseUrl}/{phs}.{v}/summary} (by default
 * {@code https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study/phs001412.v4/summary}), retrying
 * what a retry can fix.
 *
 * <table>
 *   <caption>Response handling</caption>
 *   <tr><th>Response</th><th>Result</th></tr>
 *   <tr><td>200 with a {@code study} object</td><td>FOUND</td></tr>
 *   <tr><td>400 (or 200) whose body is {@code {"error":{"error_code":2,...}}} -- "Invalid or
 *       non-existent study with accession phs..."</td><td>NOT_FOUND, never retried</td></tr>
 *   <tr><td>429, 408, 5xx, a timeout, a connection error, or a 200 whose body is not JSON or has
 *       no {@code study} object</td><td>retried; FAILED once attempts run out</td></tr>
 *   <tr><td>any other status (403, 404, ...)</td><td>FAILED at once: a retry would get the same</td></tr>
 * </table>
 *
 * <p>Retries wait {@code initialBackoff * 2^(n-1)} after the n-th failed attempt, capped at
 * {@code maxBackoff} (2 s, 4 s, 8 s, 16 s with the defaults), except that a {@code Retry-After}
 * header -- seconds or an HTTP-date -- is honoured instead, up to {@link #MAX_RETRY_AFTER}. The
 * wait and the clock are injected, so tests never sleep.
 *
 * <p>Thread-safe: one instance serves every study of a run.
 */
public final class DbgapStudyClient implements DbgapStudySource {

    public static final String DEFAULT_BASE_URL = "https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study";
    /** dbGaP's error_code for "Invalid or non-existent study with accession ...". */
    static final int ERROR_CODE_NO_SUCH_STUDY = 2;
    /** The longest Retry-After honoured, so one study cannot stall the whole run. */
    static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(5);
    /** How much of an unexpected response body a failure message quotes. */
    private static final int BODY_EXCERPT = 200;

    private static final Logger log = LoggerFactory.getLogger(DbgapStudyClient.class);

    /**
     * @param baseUrl        the API's study endpoint, without a trailing slash
     * @param maxAttempts    requests per study, the first included (at least 1)
     * @param initialBackoff the wait after the first failed attempt
     * @param maxBackoff     the longest exponential wait
     * @param requestTimeout how long one request may take, connecting included
     */
    public record Config(String baseUrl, int maxAttempts, Duration initialBackoff, Duration maxBackoff,
                         Duration requestTimeout) {

        public Config {
            baseUrl = baseUrl.replaceAll("/+$", "");
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
            }
        }

        public static Config defaults() {
            return new Config(DEFAULT_BASE_URL, 5, Duration.ofSeconds(2), Duration.ofSeconds(60),
                    Duration.ofSeconds(60));
        }
    }

    /** Waits between attempts; {@code Thread::sleep} in production. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Config config;
    private final Sleeper sleeper;
    private final Clock clock;

    public DbgapStudyClient(Config config, ObjectMapper mapper) {
        this(HttpClient.newBuilder()
                        .connectTimeout(config.requestTimeout())
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                config, mapper, Thread::sleep, Clock.systemUTC());
    }

    DbgapStudyClient(HttpClient http, Config config, ObjectMapper mapper, Sleeper sleeper, Clock clock) {
        this.http = http;
        this.config = config;
        this.mapper = mapper;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** The summary URL for a study version, e.g. {@code .../study/phs001412.v4/summary}. */
    URI summaryUri(String studyId, String version) {
        String accession = URLEncoder.encode(studyId + "." + version, StandardCharsets.UTF_8);
        return URI.create(config.baseUrl() + "/" + accession + "/summary");
    }

    @Override
    public DbgapLookup fetch(String studyId, String version) {
        URI uri = summaryUri(studyId, version);
        String lastFailure = null;
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            Attempt result;
            try {
                result = attempt(uri);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return DbgapLookup.failed("interrupted while calling " + uri, attempt);
            }
            switch (result.kind) {
                case FOUND:
                    return DbgapLookup.found(result.summary, attempt);
                case NOT_FOUND:
                    return DbgapLookup.notFound(result.message, attempt);
                case FATAL:
                    log.warn("dbGaP {}.{}: {} (not retried)", studyId, version, result.message);
                    return DbgapLookup.failed(result.message, attempt);
                default:
                    lastFailure = result.message;
            }
            if (attempt == config.maxAttempts()) {
                break;
            }
            Duration wait = result.retryAfter != null ? min(result.retryAfter, MAX_RETRY_AFTER) : backoff(attempt);
            log.warn("dbGaP {}.{}: attempt {}/{} failed ({}); retrying in {} ms", studyId, version, attempt,
                    config.maxAttempts(), result.message, wait.toMillis());
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return DbgapLookup.failed("interrupted while waiting to retry " + uri + " (last failure: "
                        + lastFailure + ")", attempt);
            }
        }
        String message = "no usable answer from " + uri + " after " + config.maxAttempts()
                + " attempt(s); last failure: " + lastFailure;
        log.warn("dbGaP {}.{}: {}", studyId, version, message);
        return DbgapLookup.failed(message, config.maxAttempts());
    }

    @Override
    public void close() {
        http.close();
    }

    /** The exponential wait after the {@code attempt}-th failure, capped at {@code maxBackoff}. */
    Duration backoff(int attempt) {
        Duration wait = config.initialBackoff();
        for (int i = 1; i < attempt && wait.compareTo(config.maxBackoff()) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return min(wait, config.maxBackoff());
    }

    private Attempt attempt(URI uri) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            return Attempt.retry("timed out after " + config.requestTimeout().toSeconds() + " s", null);
        } catch (IOException e) {
            return Attempt.retry("request failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), null);
        }

        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        JsonNode json = parse(body);
        if (json != null && json.path("error").isObject()) {
            JsonNode error = json.get("error");
            String message = error.path("error_message").asText("dbGaP error");
            if (error.path("error_code").asInt(-1) == ERROR_CODE_NO_SUCH_STUDY && (status == 200 || status == 400)) {
                return Attempt.notFound(message);
            }
            if (!retryable(status)) {
                return Attempt.fatal("HTTP " + status + ": " + message + " (error_code "
                        + error.path("error_code").asText("?") + ")");
            }
        }
        if (status == 200) {
            if (json == null || !json.isObject()) {
                return Attempt.retry("HTTP 200 with a body that is not a JSON object: " + excerpt(body), null);
            }
            JsonNode study = json.get("study");
            if (study == null || !study.isObject()) {
                return Attempt.retry("HTTP 200 without a 'study' object: " + excerpt(body), null);
            }
            return Attempt.found(DbgapStudySummary.from(study));
        }
        if (retryable(status)) {
            return Attempt.retry("HTTP " + status + (body.isBlank() ? "" : ": " + excerpt(body)),
                    retryAfter(response.headers().firstValue("Retry-After").orElse(null)));
        }
        return Attempt.fatal("HTTP " + status + (body.isBlank() ? "" : ": " + excerpt(body)));
    }

    private static boolean retryable(int status) {
        return status == 429 || status == 408 || status >= 500;
    }

    /** A Retry-After value -- delay-seconds or an HTTP-date -- as a wait; null if absent or unreadable. */
    Duration retryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.matches("\\d{1,9}")) {
            return Duration.ofSeconds(Long.parseLong(value));
        }
        try {
            Duration until = Duration.between(clock.instant(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return until.isNegative() ? Duration.ZERO : until;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private JsonNode parse(String body) {
        if (body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static String excerpt(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= BODY_EXCERPT ? flat : flat.substring(0, BODY_EXCERPT) + "...";
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private enum Kind { FOUND, NOT_FOUND, RETRY, FATAL }

    private record Attempt(Kind kind, DbgapStudySummary summary, String message, Duration retryAfter) {
        static Attempt found(DbgapStudySummary summary) {
            return new Attempt(Kind.FOUND, summary, null, null);
        }

        static Attempt notFound(String message) {
            return new Attempt(Kind.NOT_FOUND, null, message, null);
        }

        static Attempt retry(String message, Duration retryAfter) {
            return new Attempt(Kind.RETRY, null, message, retryAfter);
        }

        static Attempt fatal(String message) {
            return new Attempt(Kind.FATAL, null, message, null);
        }
    }
}
