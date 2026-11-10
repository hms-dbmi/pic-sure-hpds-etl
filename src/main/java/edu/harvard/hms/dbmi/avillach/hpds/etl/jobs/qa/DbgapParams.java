package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * The dbGaP lookup parameters both QA jobs take ({@code --concurrency} and {@code --dbgap-*}): their
 * specs, validation and the client settings and batch limits they make. Only the defaults for
 * concurrency and the lookup deadline differ between the jobs.
 */
final class DbgapParams {

    static final int DEFAULT_GIVE_UP_AFTER = 20;
    /** After a study fails because dbGaP could not be connected to, no new lookup starts for this long. */
    static final int DEFAULT_UNREACHABLE_PAUSE_SECONDS = 120;
    static final int MAX_CONCURRENCY = 16;

    private DbgapParams() {
    }

    static List<ParamSpec> specs(int defaultConcurrency, int defaultDeadlineSeconds) {
        DbgapStudyClient.Config d = DbgapStudyClient.Config.defaults();
        return List.of(
                ParamSpec.optional("concurrency",
                        "Studies looked up in dbGaP at once (1-" + MAX_CONCURRENCY + "; default "
                                + defaultConcurrency + "; dbGaP answers 502 when busy)", "1"),
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
                                + "(1-1000; default " + DEFAULT_GIVE_UP_AFTER + "): an outage then ends the lookups, "
                                + "the rest UNVERIFIED, instead of retrying every study", "20"),
                ParamSpec.optional("dbgap-request-spacing-seconds",
                        "Least gap between dbGaP requests, from the previous one's start and end (0-600; "
                                + "default " + d.requestSpacing().toSeconds() + "; 0: none). Doubles, up to "
                                + RequestPacer.MAX_SPACING.toSeconds() + " s, while dbGaP answers overloaded "
                                + "(429, 502, 503, 504, a timeout) and narrows back as it answers", "2"),
                ParamSpec.optional("dbgap-unreachable-pause-seconds",
                        "After a study fails because dbGaP could not be connected to (connection error or "
                                + "connect timeout, after its retries), wait this long before starting the next "
                                + "study's lookup (0-3600; default " + DEFAULT_UNREACHABLE_PAUSE_SECONDS + ")",
                        "120"),
                ParamSpec.optional("dbgap-deadline-seconds",
                        "No dbGaP lookup starts after this many seconds of lookups (1-86400; default "
                                + defaultDeadlineSeconds + "), so the run ends, with its report, inside the "
                                + "runner's monitor timeout; studies not looked up by then are UNVERIFIED",
                        String.valueOf(defaultDeadlineSeconds)));
    }

    /** Validates every parameter {@link #specs} declares. */
    static void validate(JobContext ctx, ValidationReport report) {
        intParam(ctx, report, "concurrency", 1, MAX_CONCURRENCY);
        intParam(ctx, report, "dbgap-max-attempts", 1, 20);
        intParam(ctx, report, "dbgap-initial-backoff-seconds", 0, 600);
        intParam(ctx, report, "dbgap-max-backoff-seconds", 1, 3600);
        intParam(ctx, report, "dbgap-timeout-seconds", 1, 600);
        intParam(ctx, report, "dbgap-give-up-after", 1, 1000);
        intParam(ctx, report, "dbgap-unreachable-pause-seconds", 0, 3600);
        intParam(ctx, report, "dbgap-request-spacing-seconds", 0, 600);
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
    }

    static DbgapStudyClient.Config clientConfig(JobContext ctx) {
        DbgapStudyClient.Config d = DbgapStudyClient.Config.defaults();
        return new DbgapStudyClient.Config(
                ctx.get("dbgap-base-url", d.baseUrl()).trim(),
                intValue(ctx, "dbgap-max-attempts", d.maxAttempts()),
                Duration.ofSeconds(intValue(ctx, "dbgap-initial-backoff-seconds", (int) d.initialBackoff().toSeconds())),
                Duration.ofSeconds(intValue(ctx, "dbgap-max-backoff-seconds", (int) d.maxBackoff().toSeconds())),
                Duration.ofSeconds(intValue(ctx, "dbgap-timeout-seconds", (int) d.requestTimeout().toSeconds())),
                Duration.ofSeconds(intValue(ctx, "dbgap-request-spacing-seconds", (int) d.requestSpacing().toSeconds())));
    }

    static int concurrency(JobContext ctx, int defaultConcurrency) {
        return intValue(ctx, "concurrency", defaultConcurrency);
    }

    static int giveUpAfter(JobContext ctx) {
        return intValue(ctx, "dbgap-give-up-after", DEFAULT_GIVE_UP_AFTER);
    }

    static Duration unreachablePause(JobContext ctx) {
        return Duration.ofSeconds(intValue(ctx, "dbgap-unreachable-pause-seconds", DEFAULT_UNREACHABLE_PAUSE_SECONDS));
    }

    static Duration deadline(JobContext ctx, int defaultDeadlineSeconds) {
        return Duration.ofSeconds(intValue(ctx, "dbgap-deadline-seconds", defaultDeadlineSeconds));
    }

    /** A whole-number parameter, trimmed as {@link #intParam} validated it. */
    static int intValue(JobContext ctx, String name, int defaultValue) {
        return ctx.get(name).map(v -> Integer.parseInt(v.trim())).orElse(defaultValue);
    }

    static void intParam(JobContext ctx, ValidationReport report, String name, int min, int max) {
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
}
