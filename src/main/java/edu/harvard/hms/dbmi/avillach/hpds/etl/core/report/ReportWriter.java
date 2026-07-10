package edu.harvard.hms.dbmi.avillach.hpds.etl.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists a {@link JobResult} as a JSON report artifact. Jenkins archives the reports
 * directory so every run leaves a durable, machine-readable record of what happened,
 * what was validated, and why it failed.
 *
 * <p>Report writing must never mask the job's real outcome: if serialization fails we
 * log and continue rather than throwing, so a job that actually succeeded is not
 * reported as failed because of a disk hiccup.
 */
@Component
public class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);

    private final ObjectMapper mapper;

    public ReportWriter(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Writes {@code result} to {@code <reportsDir>/<job>-<runId>.json}.
     *
     * @return the path written, or null if writing failed
     */
    public Path write(Path reportsDir, JobResult result) {
        try {
            Files.createDirectories(reportsDir);
            Path target = reportsDir.resolve(result.getJobName() + "-" + result.getRunId() + ".json");
            mapper.writeValue(target.toFile(), result);
            log.info("Wrote report {}", target.toAbsolutePath());
            return target;
        } catch (IOException e) {
            // Do not fail the run because the report could not be written; just record it.
            log.error("Failed to write report for job '{}' run '{}': {}",
                    result.getJobName(), result.getRunId(), e.getMessage(), e);
            return null;
        }
    }

    /** Renders the result to a JSON string (used in tests and for logging summaries). */
    public String toJson(JobResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
