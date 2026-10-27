package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.EtlException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ValidationFailedException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Shared helpers of the adapter tests: running the job, reading what it wrote, and the runner's own gates in Java. */
final class AdapterTestSupport {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Five double-quoted fields, as the job writes them. */
    static final Pattern ALL_QUOTED = Pattern.compile("^\"(?:[^\"]|\"\")*\"(?:,\"(?:[^\"]|\"\")*\"){4}$");

    private AdapterTestSupport() {
    }

    static String q(Object... fields) {
        StringBuilder b = new StringBuilder();
        for (Object f : fields) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append('"').append(String.valueOf(f).replace("\"", "\"\"")).append('"');
        }
        return b.toString();
    }

    static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    /** What a job run did: the result, or the exception that ended it. */
    record Outcome(JobResult result, EtlException error, Path reportsDir) {

        int exit() {
            return result != null ? result.getExitCode().code() : error.exitCode().code();
        }

        String message() {
            if (error != null) {
                return String.valueOf(error.getMessage());
            }
            return String.valueOf(result.getErrorMessage());
        }

        List<String> issueCodes() {
            List<String> codes = new ArrayList<>();
            if (result != null) {
                for (var r : new edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport[]{
                        result.getInputValidation(), result.getOutputValidation()}) {
                    if (r != null) {
                        r.getIssues().stream().map(ValidationIssue::code).forEach(codes::add);
                    }
                }
            }
            if (error instanceof ValidationFailedException v) {
                v.report().getIssues().stream().map(ValidationIssue::code).forEach(codes::add);
            }
            return codes;
        }

        List<String> warnings() {
            List<String> w = new ArrayList<>();
            if (result != null && result.getOutputValidation() != null) {
                result.getOutputValidation().getIssues().stream()
                        .filter(i -> i.severity() == edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.Severity.WARNING)
                        .map(ValidationIssue::message).forEach(w::add);
            }
            return w;
        }
    }

    static Outcome run(AdaptAllConceptsForCsvLoaderJob job, Path reportsDir, Map<String, String> params) {
        JobContext ctx = new JobContext(job.name(), "test-run", reportsDir, params);
        try {
            return new Outcome(job.run(ctx), null, reportsDir);
        } catch (EtlException e) {
            return new Outcome(null, e, reportsDir);
        } catch (Exception e) {
            throw new AssertionError("unexpected " + e, e);
        }
    }

    /** Every output row as {@code "partition",<five quoted fields>}, sorted; also checks each line is five quoted fields. */
    static List<String> outputRows(Path outputDir) throws IOException {
        List<String> rows = new ArrayList<>();
        try (Stream<Path> files = Files.list(outputDir)) {
            for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith("-allConcepts.csv")) {
                    continue;
                }
                String partition = name.substring(0, name.length() - "-allConcepts.csv".length());
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (!ALL_QUOTED.matcher(line).matches()) {
                        throw new AssertionError(name + " has a line that is not five quoted fields: " + line);
                    }
                    rows.add("\"" + partition + "\"," + line);
                }
            }
        }
        Collections.sort(rows);
        return rows;
    }

    static JsonNode json(Path file) throws IOException {
        return JSON.readTree(file.toFile());
    }

    /** True if every field of {@code expected} is in {@code actual}: objects by key, everything else by equality. */
    static boolean subset(JsonNode expected, JsonNode actual) {
        if (expected.isObject()) {
            if (!actual.isObject()) {
                return false;
            }
            var it = expected.fields();
            while (it.hasNext()) {
                var e = it.next();
                if (!actual.has(e.getKey()) || !subset(e.getValue(), actual.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
            for (int i = 0; i < expected.size(); i++) {
                if (!subset(expected.get(i), actual.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return expected.equals(actual) || (expected.isNumber() && actual.isNumber() && expected.asDouble() == actual.asDouble());
    }

    /**
     * The runner's gates on a split output, as Java: {@code stage-inputs.sh} (a non-empty partitions array, a safe
     * name, kind study, file {@code <name>-allConcepts.csv}, rows &gt; 0, no global kind) and the container check of
     * {@code flow-lib.sh} (the file's CSV record count equals rows, and so does its count of non-empty lines).
     * The shell versions run in {@code etl-runners/adapt-allconcepts-csvloader/test/run-tests.sh} on the vendored scripts.
     */
    static List<String> runnerContractProblems(Path outputDir) throws IOException {
        List<String> problems = new ArrayList<>();
        JsonNode m = json(outputDir.resolve("split_manifest.json"));
        JsonNode parts = m.get("partitions");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            return List.of("partitions must be a non-empty array");
        }
        Pattern name = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
        java.util.Set<String> lower = new java.util.HashSet<>();
        for (JsonNode p : parts) {
            String n = p.path("name").asText("");
            if (!name.matcher(n).matches() || n.contains("..")) {
                problems.add("bad name " + n);
            }
            if (!lower.add(n.toLowerCase())) {
                problems.add("duplicate name " + n);
            }
            if (!"study".equals(p.path("kind").asText())) {
                problems.add(n + ": kind is not study");
            }
            if (!(n + "-allConcepts.csv").equals(p.path("file").asText())) {
                problems.add(n + ": file name");
            }
            if (!p.path("rows").isIntegralNumber() || p.path("rows").asLong() <= 0) {
                problems.add(n + ": rows must be a whole number > 0");
            }
            Path file = outputDir.resolve(n + "-allConcepts.csv");
            if (!Files.isRegularFile(file) || Files.size(file) == 0) {
                problems.add(n + ": file missing or empty");
                continue;
            }
            long records = 0;
            try (CsvRecordReader r = new CsvRecordReader(Files.newInputStream(file))) {
                while (r.next() != null) {
                    records++;
                }
            }
            long nonEmpty = Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isEmpty()).count();
            if (records != p.path("rows").asLong()) {
                problems.add(n + ": " + records + " records but rows says " + p.path("rows").asLong());
            }
            if (nonEmpty != records) {
                problems.add(n + ": " + nonEmpty + " non-empty lines but " + records + " records");
            }
        }
        for (String field : List.of("job", "runId", "generatedAt", "input", "rows", "rejects", "multiStudyPatients",
                "consentCodesSeen", "warnings")) {
            if (!m.has(field)) {
                problems.add("missing top-level field " + field);
            }
        }
        for (String field : List.of("uri", "versionId", "md5", "rowCount")) {
            if (!m.path("input").has(field)) {
                problems.add("missing input." + field);
            }
        }
        for (String field : List.of("input", "globalCopies", "written", "rejected", "dropped", "balanced")) {
            if (!m.path("rows").has(field)) {
                problems.add("missing rows." + field);
            }
        }
        return problems;
    }

    static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
