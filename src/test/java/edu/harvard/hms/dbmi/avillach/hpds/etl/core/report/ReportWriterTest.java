package edu.harvard.hms.dbmi.avillach.hpds.etl.core.report;

import edu.harvard.hms.dbmi.avillach.hpds.etl.config.JacksonConfig;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ReportWriter} writes aside and renames, and never leaves a {@code .partial} behind. */
class ReportWriterTest {

    private final ReportWriter writer = new ReportWriter(new JacksonConfig().objectMapper());

    private static JobResult result() {
        return JobResult.builder("demo", JobType.values()[0], "run1").metric("rows", 3).build();
    }

    private static List<String> namesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void writes_the_report_and_no_partial(@TempDir Path dir) throws IOException {
        Path written = writer.write(dir, result());

        assertThat(written).isEqualTo(dir.resolve("demo-run1.json"));
        assertThat(Files.readString(written)).contains("\"rows\" : 3");
        assertThat(namesIn(dir)).containsExactly("demo-run1.json");
    }

    /** A target the rename cannot replace (a non-empty directory) fails the move after the write. */
    @Test
    void a_failed_rename_returns_null_and_removes_the_partial(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo-run1.json").resolve("blocker"));

        assertThat(writer.write(dir, result())).isNull();

        assertThat(namesIn(dir)).containsExactly("demo-run1.json");
        assertThat(Files.isDirectory(dir.resolve("demo-run1.json"))).isTrue();
    }
}
