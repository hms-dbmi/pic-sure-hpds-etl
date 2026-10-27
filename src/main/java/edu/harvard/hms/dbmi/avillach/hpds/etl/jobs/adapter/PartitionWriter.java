package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appends lines to one local file per name, holding at most {@code maxOpen} files open at once (the least
 * recently used is closed and reopened for append when needed), because a run writes to as many partitions as
 * the manifest lists, more than a process should keep open. UTF-8, LF after every line.
 */
final class PartitionWriter implements AutoCloseable {

    private final Path dir;
    private final String suffix;
    private final int maxOpen;
    private final Map<String, OutputStream> open = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Long> lines = new HashMap<>();

    PartitionWriter(Path dir, String suffix, int maxOpen) throws IOException {
        this.dir = dir;
        this.suffix = suffix;
        this.maxOpen = maxOpen;
        Files.createDirectories(dir);
    }

    void write(String name, String line) throws IOException {
        OutputStream out = open.get(name);
        if (out == null) {
            if (open.size() >= maxOpen) {
                Iterator<Map.Entry<String, OutputStream>> oldest = open.entrySet().iterator();
                Map.Entry<String, OutputStream> e = oldest.next();
                oldest.remove();
                e.getValue().close();
            }
            out = new BufferedOutputStream(Files.newOutputStream(file(name),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), 1 << 16);
            open.put(name, out);
        }
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        lines.merge(name, 1L, Long::sum);
    }

    Path file(String name) {
        return dir.resolve(name + suffix);
    }

    /** Lines written to {@code name} so far. */
    long lines(String name) {
        return lines.getOrDefault(name, 0L);
    }

    /** Names that have at least one line, in no particular order. */
    Iterable<String> names() {
        return lines.keySet();
    }

    /** Flushes and closes every open file; the writer may be used again afterwards. */
    void flush() throws IOException {
        for (OutputStream out : open.values()) {
            out.close();
        }
        open.clear();
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
