package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionWriterTest {

    @TempDir
    Path dir;

    @Test
    void appends_lines_across_evictions_when_more_files_than_the_open_limit_are_written() throws IOException {
        try (PartitionWriter w = new PartitionWriter(dir, "-allConcepts.csv", 2)) {
            for (int round = 0; round < 3; round++) {
                for (String name : new String[]{"a.c1", "b.c1", "c.c1", "d.c1"}) {
                    w.write(name, name + "-" + round);
                }
            }
            w.flush();
            assertThat(w.lines("a.c1")).isEqualTo(3);
            assertThat(Files.readString(w.file("c.c1"))).isEqualTo("c.c1-0\nc.c1-1\nc.c1-2\n");
            assertThat(w.file("a.c1").getFileName().toString()).isEqualTo("a.c1-allConcepts.csv");
        }
    }

    @Test
    void writes_utf8_and_continues_after_a_flush() throws IOException {
        try (PartitionWriter w = new PartitionWriter(dir, ".csv", 4)) {
            w.write("x", "µ");
            w.flush();
            w.write("x", "ü");
        }
        assertThat(Files.readString(dir.resolve("x.csv"))).isEqualTo("µ\nü\n");
    }
}
