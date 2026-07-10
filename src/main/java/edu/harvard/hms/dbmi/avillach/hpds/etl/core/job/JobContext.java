package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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

    public boolean getBoolean(String key, boolean defaultValue) {
        return get(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    public int requireInt(String key) {
        String raw = require(key);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("Parameter --" + key + " must be an integer, got: " + raw);
        }
    }
}
