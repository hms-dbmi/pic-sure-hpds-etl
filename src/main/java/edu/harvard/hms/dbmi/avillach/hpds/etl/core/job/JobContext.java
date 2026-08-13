package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a job needs to know about the current invocation: its resolved runtime
 * parameters (from {@code --key=value} CLI args) plus run metadata. Business
 * dependencies (repositories, S3, readers) are injected into the job bean itself --
 * this object only carries per-run inputs.
 *
 * <p>The typed accessors centralize "missing/blank required param" handling so every
 * job fails config errors the same way.
 */
public final class JobContext {

    private final String jobName;
    private final String runId;
    private final Path reportsDir;
    private final Map<String, String> params;

    public JobContext(String jobName, String runId, Path reportsDir, Map<String, String> params) {
        this.jobName = jobName;
        this.runId = runId;
        this.reportsDir = reportsDir;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public String jobName() {
        return jobName;
    }

    /** Correlation id for this run; appears in logs (MDC) and the report filename. */
    public String runId() {
        return runId;
    }

    public Path reportsDir() {
        return reportsDir;
    }

    public Map<String, String> params() {
        return params;
    }

    public Optional<String> get(String key) {
        String v = params.get(key);
        return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
    }

    public String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /** @throws ConfigException if the parameter is absent or blank. */
    public String require(String key) {
        return get(key).orElseThrow(() ->
                new ConfigException("Missing required parameter --" + key + "= for job '" + jobName + "'"));
    }

    /**
     * Reads a boolean parameter, rejecting anything that is not a recognised literal.
     *
     * <p>Not {@link Boolean#parseBoolean}, which maps every unrecognised string — {@code treu}
     * included — to {@code false}, silently turning a flag off while the job reports success.
     *
     * @throws ConfigException if the value is present but not one of the accepted literals
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Optional<String> raw = get(key);
        return raw.map(s -> parseBoolean(key, s)).orElse(defaultValue);
    }

    /** Accepted true/false literals, matched case-insensitively and after trimming. */
    private static final Set<String> TRUE_LITERALS = Set.of("true", "yes", "y", "1", "on");
    private static final Set<String> FALSE_LITERALS = Set.of("false", "no", "n", "0", "off");

    /**
     * Whether a value would be accepted by {@link #getBoolean}. Lets a job's
     * {@code validateInput} report a bad flag in the JSON report rather than leaving it to
     * surface as a thrown {@link ConfigException} later.
     */
    public static boolean isBooleanLiteral(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase();
        return TRUE_LITERALS.contains(v) || FALSE_LITERALS.contains(v);
    }

    /** Human-readable list of what {@link #getBoolean} accepts, for error messages. */
    public static String acceptedBooleanLiterals() {
        return "true/yes/y/1/on or false/no/n/0/off (case-insensitive)";
    }

    private boolean parseBoolean(String key, String raw) {
        String v = raw.trim().toLowerCase();
        if (TRUE_LITERALS.contains(v)) {
            return true;
        }
        if (FALSE_LITERALS.contains(v)) {
            return false;
        }
        throw new ConfigException("Parameter --" + key + " must be " + acceptedBooleanLiterals()
                + ", got: '" + raw + "'");
    }

}
