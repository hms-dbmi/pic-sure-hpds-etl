package edu.harvard.hms.dbmi.avillach.hpds.etl.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persists a {@link JobResult} as a JSON report artifact. Jenkins archives the reports
 * directory so every run leaves a durable, machine-readable record of what happened,
 * what was validated, and why it failed.
 *
 * <p>Report writing must never mask the job's real outcome. A serialization failure is logged and
 * swallowed rather than thrown, so a job that succeeded is not reported as failed because its
 * report could not be written.
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
        Path target = reportsDir.resolve(result.getJobName() + "-" + result.getRunId() + ".json");
        // Written aside and renamed: the runner uploads the reports directory while the job is
        // still running, and must never pick up half a report.
        Path partial = reportsDir.resolve(target.getFileName() + ".partial");
        try {
            Files.createDirectories(reportsDir);
            mapper.writeValue(partial.toFile(), result);
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems (certain mounts) cannot rename atomically. A plain move is still
                // better than losing the report; the upload may at worst see it mid-copy once.
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Wrote report {}", target.toAbsolutePath());
            return target;
        } catch (IOException e) {
            // Record the failure without failing the run, and leave no half-written report behind
            // for the runner to upload.
            log.error("Failed to write report for job '{}' run '{}': {}",
                    result.getJobName(), result.getRunId(), e.getMessage(), e);
            try {
                Files.deleteIfExists(partial);
            } catch (IOException cleanup) {
                log.warn("Could not delete partial report {}: {}", partial, cleanup.getMessage());
            }
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
