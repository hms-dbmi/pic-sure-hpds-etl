package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of a job run. Serialized to JSON as the run's report artifact
 * (see ReportWriter) so Jenkins can archive it and humans/dashboards can inspect it.
 */
public final class JobResult {

    private final String jobName;
    private final JobType jobType;
    private final String runId;
    private final ExitCode exitCode;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final ValidationReport inputValidation;
    private final ValidationReport outputValidation;
    private final Map<String, Object> metrics;
    private final String errorMessage;

    private JobResult(Builder b) {
        this.jobName = b.jobName;
        this.jobType = b.jobType;
        this.runId = b.runId;
        this.exitCode = b.exitCode;
        this.startedAt = b.startedAt;
        this.finishedAt = b.finishedAt;
        this.inputValidation = b.inputValidation;
        this.outputValidation = b.outputValidation;
        this.metrics = b.metrics;
        this.errorMessage = b.errorMessage;
    }

    public String getJobName() { return jobName; }
    public JobType getJobType() { return jobType; }
    public String getRunId() { return runId; }
    public ExitCode getExitCode() { return exitCode; }
    public String getStatus() { return exitCode.isSuccess() ? "SUCCESS" : "FAILED"; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public long getDurationMillis() {
        return (startedAt != null && finishedAt != null) ? Duration.between(startedAt, finishedAt).toMillis() : 0;
    }
    public ValidationReport getInputValidation() { return inputValidation; }
    public ValidationReport getOutputValidation() { return outputValidation; }
    public Map<String, Object> getMetrics() { return metrics; }
    public String getErrorMessage() { return errorMessage; }

    public boolean isSuccess() {
        return exitCode.isSuccess();
    }

    public static Builder builder(String jobName, JobType jobType, String runId) {
        return new Builder(jobName, jobType, runId);
    }

    public static final class Builder {
        private final String jobName;
        private final JobType jobType;
        private final String runId;
        private ExitCode exitCode = ExitCode.SUCCESS;
        private Instant startedAt;
        private Instant finishedAt;
        private ValidationReport inputValidation;
        private ValidationReport outputValidation;
        private final Map<String, Object> metrics = new LinkedHashMap<>();
        private String errorMessage;

        private Builder(String jobName, JobType jobType, String runId) {
            this.jobName = jobName;
            this.jobType = jobType;
            this.runId = runId;
        }

        public Builder exitCode(ExitCode exitCode) { this.exitCode = exitCode; return this; }
        public Builder startedAt(Instant t) { this.startedAt = t; return this; }
        public Builder finishedAt(Instant t) { this.finishedAt = t; return this; }
        public Builder inputValidation(ValidationReport r) { this.inputValidation = r; return this; }
        public Builder outputValidation(ValidationReport r) { this.outputValidation = r; return this; }
        public Builder metric(String key, Object value) { this.metrics.put(key, value); return this; }
        public Builder metrics(Map<String, Object> m) { this.metrics.putAll(m); return this; }
        public Builder errorMessage(String m) { this.errorMessage = m; return this; }

        public JobResult build() {
            return new JobResult(this);
        }
    }
}
