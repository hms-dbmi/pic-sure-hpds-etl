package edu.harvard.hms.dbmi.avillach.hpds.etl.support;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Small helpers for building job inputs and contexts in tests. Keeping this here means
 * adding a new test case for a new job is a few lines: write a fixture file, build a
 * context, run, assert.
 */
public final class JobTestSupport {

    private JobTestSupport() {
    }

    /** Builds a {@link JobContext} with a throwaway reports directory. */
    public static JobContext context(String jobName, Map<String, String> params) {
        return new JobContext(jobName, "test-run", tempDir("reports"), params);
    }

    /** Writes {@code content} to a temp file with the given name and returns its path as a string. */
    public static String tempFile(String name, String content) {
        try {
            Path file = tempDir("input").resolve(name);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
