package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.fencemapping.FenceMappingValidator;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Splits ONE merged allConcepts CSV (all studies plus the global variables such as
 * {@code \_consents\}) into per-consent partition files that the partitioned HPDS loader reads.
 *
 * <p>No database and no patient-id mapping: patient numbers stay exactly as they are in the merged
 * file. The two passes over the input are:
 * <ol>
 *   <li><b>Pass 1</b> validates every row against the CSVLoader contract (see
 *       {@code schemas/allconcepts.tableschema.json}) and records, for each patient, the consent
 *       strings from the {@code consents-path} variable ({@code phs123456.c1}).</li>
 *   <li><b>Pass 2</b> takes the concept-path node at {@code depth} (the leading empty segment is
 *       depth 0, so {@code \phs123456\pht1\v\} at depth 1 is {@code phs123456}). A node that is the
 *       study prefix of any consent string is a study: the row goes to the partition of each of
 *       that patient's consents of that study. Anything else is a global variable, written to its
 *       own partition ({@code globals=own}) or copied into each of the patient's consent partitions
 *       ({@code globals=by-patient}).</li>
 * </ol>
 * Rows are spooled to one temp file per partition and uploaded only after the whole input has
 * been read and found valid; nothing is ever deleted.
 *
 * <p><b>Auth caveat for {@code globals=own}:</b> HPDS reads only partitions whose name equals one
 * of the user's consent strings from PSAMA, and PSAMA grants only fence-mapping keys. Own-partitions
 * therefore load but stay unreachable until PSAMA grants those exact names. See
 * {@code docs/SPLIT_ALLCONCEPTS_BY_CONSENT.md}.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.split-allconcepts-by-consent.enabled", havingValue = "true")
public class SplitMergedAllConceptsJob extends AbstractJob<SplitMergedAllConceptsJob.Output> {

    public static final String DEFAULT_CONSENTS_PATH = "\\_consents\\";
    public static final String DEFAULT_FENCE_MAPPING =
            "s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/general/data/metadata_new_search.json";
    public static final String MANIFEST_NAME = "split_manifest.json";
    public static final String GLOBALS_OWN = "own";
    public static final String GLOBALS_BY_PATIENT = "by-patient";

    /** The legacy bucket and the in-boundary mirror's key prefix. Writing under it is refused. */
    static final String MIRRORED_BUCKET_MARKER = "avillach-73-bdcatalyst-etl";

    private static final Pattern TIMESTAMP = Pattern.compile("-?[0-9]*");
    private static final Pattern STUDY_ID = Pattern.compile("phs[0-9]{6}");
    private static final Pattern NUMBER =
            Pattern.compile("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?");
    private static final int MAX_OPEN_WRITERS = 128;
    private static final int MAX_SAMPLES = 5;
    private static final long SECONDS_CEILING = 100_000_000_000L; // 1e11 ms is 1973; epoch seconds stay below this until the year 5138

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final FenceMappingValidator fenceValidator;
    private final ObjectMapper objectMapper;

    public SplitMergedAllConceptsJob(IoResolver io, DelimitedReader delimitedReader,
                                     FenceMappingValidator fenceValidator, ObjectMapper objectMapper) {
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.fenceValidator = fenceValidator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "split-allconcepts-by-consent";
    }

    @Override
    public JobType type() {
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(
                        ParamSpec.required("input",
                                "Merged allConcepts CSV, headerless or with one header row (local path or s3:// URI)",
                                "s3://bdc-etl-data-d0d6191/data/transforms/all_concepts_merged/allConcepts_data_analyzer.csv"),
                        ParamSpec.required("output",
                                "Output directory/prefix for {partition}-allConcepts.csv and " + MANIFEST_NAME
                                        + ". Must not be under the avillach-73-bdcatalyst-etl bucket/prefix.",
                                "s3://bdc-etl-data-d0d6191/data/transforms/all_concepts_split/run-1/"),
                        ParamSpec.optional("depth",
                                "0-based concept-path segment that names the study; the leading empty segment is depth 0 (default 1)",
                                "1"),
                        ParamSpec.optional("consents-path",
                                "Concept path of the variable holding each patient's consent strings (default \\_consents\\)",
                                "\\_consents\\"),
                        ParamSpec.optional("globals",
                                "'own' (default): each non-study node gets its own partition; "
                                        + "'by-patient': copy global rows into each of the patient's consent partitions",
                                "own"),
                        ParamSpec.optional("fence-mapping",
                                "fence_mapping.json for the report-only coverage cross-check; 'none' skips it (default "
                                        + DEFAULT_FENCE_MAPPING + ")",
                                DEFAULT_FENCE_MAPPING),
                        ParamSpec.optional("force",
                                "Write into a non-empty output prefix (existing objects are overwritten, never deleted; default false)",
                                "false")),
                List.of("{output}/{partition}-allConcepts.csv per consent partition (headerless, all fields quoted)",
                        "{output}/" + MANIFEST_NAME + " with row accounting per partition"));
    }

    // ---------------------------------------------------------------- validation

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        parseDepth(ctx, report);
        String globals = ctx.get("globals", GLOBALS_OWN);
        if (!GLOBALS_OWN.equals(globals) && !GLOBALS_BY_PATIENT.equals(globals)) {
            report.error("INVALID_GLOBALS", "globals must be 'own' or 'by-patient', got: " + globals, "--globals");
        }
        if (normalize(ctx.get("consents-path", DEFAULT_CONSENTS_PATH)).isBlank()) {
            report.error("INVALID_CONSENTS_PATH", "consents-path must not be blank", "--consents-path");
        }
        boolean force = false;
        String forceRaw = ctx.get("force").orElse(null);
        if (forceRaw != null) {
            if (JobContext.isBooleanLiteral(forceRaw)) {
                force = ctx.getBoolean("force", false);
            } else {
                report.error("INVALID_FORCE", "force must be " + JobContext.acceptedBooleanLiterals()
                        + ", got: '" + forceRaw + "'", "--force");
            }
        }

        String output = ctx.get("output").orElse(null);
        if (output == null) {
            return; // reported as MISSING_PARAM by AbstractJob
        }
        if (output.contains(MIRRORED_BUCKET_MARKER)) {
            report.error("OUTPUT_UNDER_MIRROR", "output '" + output + "' is under " + MIRRORED_BUCKET_MARKER
                    + ", which is the legacy bucket / its nightly in-boundary mirror. Write to a sibling prefix such as "
                    + "s3://bdc-etl-data-d0d6191/data/transforms/all_concepts_split/<run>/.", "--output");
        } else if (!force) {
            List<String> existing = io.listFilesRecursive(output);
            if (!existing.isEmpty()) {
                report.error("OUTPUT_NOT_EMPTY", "output '" + output + "' already holds " + existing.size()
                        + " object(s) (e.g. " + existing.getFirst() + "); use a fresh prefix or --force=true "
                        + "(overwrites same-named files, deletes nothing)", "--output");
            }
        }
    }

    private static int parseDepth(JobContext ctx, ValidationReport report) {
        String raw = ctx.get("depth", "1");
        try {
            int depth = Integer.parseInt(raw.trim());
            if (depth >= 0) {
                return depth;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the error
        }
        if (report != null) {
            report.error("INVALID_DEPTH", "depth must be an integer >= 0, got: " + raw, "--depth");
        }
        return 1;
    }

    // ---------------------------------------------------------------- execute

    @Override
    protected Output execute(JobContext ctx) {
        String inputUri = ctx.require("input");
        String outputUri = ctx.require("output");
        int depth = parseDepth(ctx, null);
        String globals = ctx.get("globals", GLOBALS_OWN);
        String consentsPath = normalize(ctx.get("consents-path", DEFAULT_CONSENTS_PATH));
        String fenceUri = ctx.get("fence-mapping", DEFAULT_FENCE_MAPPING);

        Findings errors = new Findings();
        Pass1 p1 = pass1(inputUri, consentsPath, errors);
        validatePartitionNames(p1, errors);

        Map<String, PartitionInfo> partitions = new TreeMap<>();
        Pass2 p2 = new Pass2();
        if (errors.isEmpty()) {
            Spool spool = new Spool();
            try {
                pass2(inputUri, depth, GLOBALS_BY_PATIENT.equals(globals), p1, p2, spool, errors);
                if (errors.isEmpty()) {
                    spool.closeAll();
                    for (Map.Entry<String, PartitionStats> e : new TreeMap<>(p2.partitions).entrySet()) {
                        String name = e.getKey();
                        PartitionStats s = e.getValue();
                        String file = name + "-allConcepts.csv";
                        io.writeOutputFile(joinPath(outputUri, file), spool.file(name));
                        partitions.put(name, new PartitionInfo(name, s.kind, s.rows, s.patients.cardinality(), file));
                    }
                }
            } catch (IOException e) {
                throw new InfrastructureException("Failed spooling split output", e);
            } finally {
                spool.cleanUp();
            }
        }

        FenceCheck fence = errors.isEmpty() ? fenceCrossCheck(fenceUri, partitions.values()) : FenceCheck.skipped();

        Output out = new Output(inputUri, outputUri, depth, globals, consentsPath,
                p1.headerText, p1.rows, p1.blankRows,
                p2.studyRows, p2.globalRows, p2.droppedStudyNoConsent, p2.droppedGlobalNoConsent,
                p1.timestampZero, p1.timestampSeconds, p1.consentRows, p1.consentsByPatient.size(),
                new ArrayList<>(partitions.values()), errors.messages(), new ArrayList<>(p2.notes),
                new ArrayList<>(p1.notes), fence, null);

        if (errors.isEmpty()) {
            String manifestUri = joinPath(outputUri, MANIFEST_NAME);
            writeManifest(manifestUri, out, ctx.runId());
            out = out.withManifestUri(manifestUri);
        }
        return out;
    }

    // ---------------------------------------------------------------- pass 1

    private Pass1 pass1(String inputUri, String consentsPath, Findings errors) {
        Pass1 p = new Pass1();
        long rowNum = 0;
        try (Stream<List<String>> rows = delimitedReader.streamRows(io.openInput(inputUri), DelimitedReader.COMMA)) {
            for (List<String> row : (Iterable<List<String>>) rows::iterator) {
                if (isBlankLine(row)) {
                    p.blankRows++;
                    continue;
                }
                rowNum++;
                if (rowNum == 1 && isHeader(row)) {
                    p.headerText = String.join(",", row);
                    continue;
                }
                p.rows++;
                Integer patient = checkRow(row, rowNum, errors, p);
                if (patient == null || row.get(1).length() != consentsPath.length()
                        || !normalize(row.get(1)).equals(consentsPath)) {
                    continue;
                }
                String consent = row.get(3).trim();
                if (consent.isEmpty()) {
                    p.blankConsentRows++;
                    continue;
                }
                p.consentRows++;
                p.consentsByPatient.computeIfAbsent(patient, k -> new TreeSet<>()).add(consent);
                p.studies.add(studyOf(consent));
            }
        }
        if (p.blankConsentRows > 0) {
            p.notes.add(p.blankConsentRows + " row(s) of " + consentsPath + " had a blank tval and were ignored");
        }
        return p;
    }

    /** CSVLoader contract, applied to every data row. Returns the patient number, or null if invalid. */
    private static Integer checkRow(List<String> row, long rowNum, Findings errors, Pass1 p) {
        if (row.size() != 5) {
            errors.add("BAD_FIELD_COUNT", rowNum, "expected 5 fields, found " + row.size());
            return null;
        }
        Integer patient = parseInt(row.get(0));
        if (patient == null) {
            errors.add("BAD_PATIENT_NUM", rowNum, "patient_num is not an integer: '" + abbreviate(row.get(0)) + "'");
            return null;
        }
        boolean ok = true;
        if (row.get(1).isEmpty()) {
            errors.add("EMPTY_CONCEPT_PATH", rowNum, "concept_path is empty");
            ok = false;
        }
        String nval = row.get(2);
        if (!nval.isEmpty() && !NUMBER.matcher(nval).matches()) {
            errors.add("BAD_NVAL", rowNum, "nval is not a number: '" + abbreviate(nval) + "'");
            ok = false;
        }
        String ts = row.get(4);
        if (!TIMESTAMP.matcher(ts).matches()) {
            errors.add("BAD_TIMESTAMP", rowNum, "timestamp must be blank or an integer (CSVLoader aborts on anything else): '"
                    + abbreviate(ts) + "'");
            ok = false;
        } else if (!ts.isEmpty()) {
            try {
                long v = Long.parseLong(ts);
                if (v == 0) {
                    p.timestampZero++;
                } else if (v > 0 && v < SECONDS_CEILING) {
                    p.timestampSeconds++;
                }
            } catch (NumberFormatException e) {
                errors.add("BAD_TIMESTAMP", rowNum, "timestamp does not fit a 64-bit integer: '" + abbreviate(ts) + "'");
                ok = false;
            }
        }
        return ok ? patient : null;
    }

    private void validatePartitionNames(Pass1 p, Findings errors) {
        Set<String> seen = new HashSet<>();
        for (Set<String> consents : p.consentsByPatient.values()) {
            for (String consent : consents) {
                if (seen.add(consent) && partitionNameProblem(consent) != null) {
                    errors.add("INVALID_CONSENT_VALUE", 0, "consent '" + abbreviate(consent) + "' cannot name a partition: "
                            + partitionNameProblem(consent));
                }
            }
        }
    }

    // ---------------------------------------------------------------- pass 2

    private void pass2(String inputUri, int depth, boolean byPatient, Pass1 p1, Pass2 p2, Spool spool,
                       Findings errors) throws IOException {
        long rowNum = 0;
        Map<String, String> globalNameToNode = new HashMap<>();
        Set<String> studiesWithoutConsentsWarned = new TreeSet<>();
        try (Stream<List<String>> rows = delimitedReader.streamRows(io.openInput(inputUri), DelimitedReader.COMMA)) {
            for (List<String> row : (Iterable<List<String>>) rows::iterator) {
                if (isBlankLine(row)) {
                    continue;
                }
                rowNum++;
                if (rowNum == 1 && isHeader(row)) {
                    continue;
                }
                int patient = parseInt(row.get(0));
                String line = formatLine(row);
                String node = segment(row.get(1), depth);
                Set<String> consents = p1.consentsByPatient.getOrDefault(patient, Set.of());

                if (p1.studies.contains(node)) {
                    int written = 0;
                    for (String consent : consents) {
                        if (studyOf(consent).equals(node)) {
                            written += emit(consent, "study", patient, line, p2, spool, errors, rowNum);
                        }
                    }
                    if (written == 0) {
                        p2.droppedStudyNoConsent++;
                    } else {
                        p2.studyRows++;
                    }
                } else if (byPatient) {
                    if (consents.isEmpty()) {
                        p2.droppedGlobalNoConsent++;
                        continue;
                    }
                    for (String consent : consents) {
                        emit(consent, "study", patient, line, p2, spool, errors, rowNum);
                    }
                    p2.globalRows++;
                } else {
                    if (node.isEmpty()) {
                        errors.add("EMPTY_GLOBAL_NAME", rowNum, "global variable has no segment at depth " + depth
                                + ": '" + abbreviate(row.get(1)) + "'");
                        continue;
                    }
                    String name = node.replace('-', '_');
                    String problem = partitionNameProblem(name);
                    if (problem != null) {
                        errors.add("INVALID_GLOBAL_NAME", rowNum, "node '" + abbreviate(node) + "' cannot name a partition: " + problem);
                        continue;
                    }
                    String prior = globalNameToNode.putIfAbsent(name, node);
                    if (prior != null && !prior.equals(node)) {
                        p2.notes.add("nodes '" + prior + "' and '" + node + "' both map to partition '" + name + "' and were merged");
                    }
                    if (emit(name, "global", patient, line, p2, spool, errors, rowNum) > 0) {
                        p2.globalRows++;
                    }
                    if (node.length() == 9 && STUDY_ID.matcher(node).matches()) {
                        studiesWithoutConsentsWarned.add(node);
                    }
                }
            }
        }
        if (!studiesWithoutConsentsWarned.isEmpty()) {
            p2.notes.add("study-looking node(s) with no consent in " + p1.consentRows + " consent row(s) were treated as global variables: "
                    + studiesWithoutConsentsWarned);
        }
    }

    /** Writes one line to a partition, tracking counts. Returns 1 if written, 0 if refused. */
    private int emit(String partition, String kind, int patient, String line, Pass2 p2, Spool spool,
                     Findings errors, long rowNum) throws IOException {
        PartitionStats stats = p2.partitions.get(partition);
        if (stats == null) {
            stats = new PartitionStats(kind);
            p2.partitions.put(partition, stats);
        } else if (!stats.kind.equals(kind)) {
            errors.add("PARTITION_NAME_CONFLICT", rowNum, "partition '" + partition + "' is both a study and a global variable");
            return 0;
        }
        spool.write(partition, line);
        stats.rows++;
        stats.patients.set(patient);
        p2.partitionLines++;
        return 1;
    }

    // ---------------------------------------------------------------- fence mapping cross-check

    private FenceCheck fenceCrossCheck(String fenceUri, Collection<PartitionInfo> partitions) {
        if (fenceUri == null || fenceUri.isBlank() || "none".equalsIgnoreCase(fenceUri.trim())) {
            return FenceCheck.skipped();
        }
        try {
            FenceMappingValidator.Result result = fenceValidator.validate(io.openInput(fenceUri));
            Set<String> studyPartitions = new TreeSet<>();
            Set<String> globalPartitions = new TreeSet<>();
            for (PartitionInfo p : partitions) {
                (p.kind().equals("study") ? studyPartitions : globalPartitions).add(p.name());
            }
            List<String> all = new ArrayList<>(studyPartitions);
            all.addAll(globalPartitions);
            FenceMappingValidator.Coverage coverage = fenceValidator.coverage(result, all);
            List<String> unreachableStudy = coverage.partitionsWithoutEntry().stream()
                    .filter(studyPartitions::contains).toList();
            List<String> ungrantedGlobal = coverage.partitionsWithoutEntry().stream()
                    .filter(globalPartitions::contains).toList();
            return new FenceCheck(fenceUri, true, null, result.report().getIssues(),
                    unreachableStudy, ungrantedGlobal, coverage.entriesWithoutPartition());
        } catch (RuntimeException e) {
            return new FenceCheck(fenceUri, false, e.getMessage(), List.of(), List.of(), List.of(), List.of());
        }
    }

    // ---------------------------------------------------------------- manifest

    private void writeManifest(String manifestUri, Output o, String runId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job", name());
        m.put("runId", runId);
        m.put("generatedAt", Instant.now().toString());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("uri", o.inputUri());
        // IoResolver does not expose S3 VersionId/ETag; the URI plus the row count identify the input.
        m.put("input", input);
        m.put("output", o.outputUri());
        m.put("depth", o.depth());
        m.put("consentsPath", o.consentsPath());
        m.put("globals", o.globals());
        m.put("headerRowDropped", o.headerText() != null);
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("input", o.inputRows());
        rows.put("study", o.studyRows());
        rows.put("global", o.globalRows());
        Map<String, Object> dropped = new LinkedHashMap<>();
        dropped.put("studyNoConsent", o.droppedStudyNoConsent());
        dropped.put("globalNoConsent", o.droppedGlobalNoConsent());
        rows.put("dropped", dropped);
        rows.put("accounted", o.accountedRows());
        rows.put("balanced", o.balanced());
        rows.put("partitionLines", o.partitionLines());
        rows.put("extraCopies", o.extraCopies());
        m.put("rows", rows);
        Map<String, Object> ts = new LinkedHashMap<>();
        ts.put("zero", o.timestampZero());
        ts.put("likelySeconds", o.timestampSeconds());
        m.put("timestamps", ts);
        m.put("patientsWithConsents", o.patientsWithConsents());
        m.put("partitions", o.partitions());
        try {
            byte[] bytes = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(m);
            io.writeOutput(manifestUri, bytes);
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write " + manifestUri, e);
        }
    }

    // ---------------------------------------------------------------- output validation

    @Override
    protected void validateOutput(Output o, JobContext ctx, ValidationReport report) {
        o.errors().forEach(report::error);
        if (!o.errors().isEmpty()) {
            report.error("NOTHING_WRITTEN", "Input rows violate the CSVLoader contract or partition naming; "
                    + "no partition files were written");
            return;
        }
        if (o.partitions().isEmpty()) {
            report.error("NO_OUTPUT", "No partitions were produced (no rows, or every row dropped)");
        }
        if (!o.balanced()) {
            report.error("ROW_ACCOUNTING", "input rows " + o.inputRows() + " != study " + o.studyRows() + " + global "
                    + o.globalRows() + " + dropped " + (o.droppedStudyNoConsent() + o.droppedGlobalNoConsent()));
        }
        if (o.headerText() != null) {
            report.info("HEADER_DROPPED", "Dropped leading header row: " + abbreviate(o.headerText()));
        }
        if (o.droppedStudyNoConsent() > 0) {
            report.warning("NO_CONSENT", o.droppedStudyNoConsent() + " study row(s) belong to patients with no "
                    + o.consentsPath() + " consent for that study and were not written");
        }
        if (o.droppedGlobalNoConsent() > 0) {
            report.warning("NO_CONSENT_GLOBAL", o.droppedGlobalNoConsent() + " global row(s) belong to patients with no "
                    + "consents (globals=by-patient) and were not written");
        }
        if (o.timestampZero() > 0) {
            report.info("TIMESTAMP_ZERO", o.timestampZero() + " row(s) have timestamp 0");
        }
        if (o.timestampSeconds() > 0) {
            report.warning("TIMESTAMP_LIKELY_SECONDS", o.timestampSeconds() + " row(s) have a timestamp between 0 and 1e11, "
                    + "which looks like epoch seconds, not the milliseconds CSVLoader expects");
        }
        o.inputNotes().forEach(n -> report.warning("INPUT_NOTE", n));
        o.splitNotes().forEach(n -> report.warning("SPLIT_NOTE", n));
        report.info("PARTITIONS", o.partitions().size() + " partition(s), " + o.partitionLines() + " line(s) written, "
                + o.patientsWithConsents() + " patient(s) with consents");
        fenceReport(o, report);
    }

    /** The cross-check only reports: every finding is a warning or info, never an error. */
    private static void fenceReport(Output o, ValidationReport report) {
        FenceCheck f = o.fence();
        if (!f.performed()) {
            if (f.uri() != null) {
                report.warning("FENCE_MAPPING_UNREADABLE", "Could not read fence mapping " + f.uri() + ": " + f.error()
                        + ". Partition reachability was not checked.");
            } else {
                report.info("FENCE_MAPPING_SKIPPED", "No fence-mapping cross-check was performed");
            }
            return;
        }
        int shown = 0;
        for (ValidationIssue issue : f.issues()) {
            switch (issue.severity()) {
                case ERROR -> {
                    if (shown++ < 20) {
                        report.warning(issue.code(), "fence mapping " + f.uri() + ": " + issue.message());
                    }
                }
                case WARNING -> report.warning(issue.code(), issue.message());
                default -> report.info(issue.code(), issue.message());
            }
        }
        if (shown > 20) {
            report.warning("FENCE_SCHEMA", (shown - 20) + " more fence mapping error(s) not shown; run validate-fence-mapping");
        }
        if (!f.unreachableStudyPartitions().isEmpty()) {
            report.warning("FENCE_PARTITION_UNREACHABLE", f.unreachableStudyPartitions().size()
                    + " study partition(s) have no fence mapping entry, so no user will be granted them: "
                    + sample(f.unreachableStudyPartitions()));
        }
        if (!f.ungrantedGlobalPartitions().isEmpty()) {
            report.warning("FENCE_GLOBAL_NOT_GRANTED", f.ungrantedGlobalPartitions().size()
                    + " global partition(s) have no fence mapping entry (unreachable until PSAMA grants these exact names): "
                    + sample(f.ungrantedGlobalPartitions()));
        }
        if (!f.entriesWithoutPartition().isEmpty()) {
            report.warning("FENCE_ENTRY_WITHOUT_PARTITION", f.entriesWithoutPartition().size()
                    + " fence mapping entr" + (f.entriesWithoutPartition().size() == 1 ? "y has" : "ies have")
                    + " no partition in this split: " + sample(f.entriesWithoutPartition()));
        }
    }

    @Override
    protected void report(Output o, JobResult.Builder builder) {
        builder.metric("inputUri", o.inputUri())
                .metric("outputUri", o.outputUri())
                .metric("depth", o.depth())
                .metric("globals", o.globals())
                .metric("totalRows", o.inputRows())
                .metric("studyRows", o.studyRows())
                .metric("globalRows", o.globalRows())
                .metric("droppedStudyNoConsent", o.droppedStudyNoConsent())
                .metric("droppedGlobalNoConsent", o.droppedGlobalNoConsent())
                .metric("partitionLines", o.partitionLines())
                .metric("balanced", o.balanced())
                .metric("partitions", o.partitions().size())
                .metric("studyPartitions", o.partitions().stream().filter(p -> p.kind().equals("study")).count())
                .metric("globalPartitions", o.partitions().stream().filter(p -> p.kind().equals("global")).count())
                .metric("patientsWithConsents", o.patientsWithConsents())
                .metric("timestampZero", o.timestampZero())
                .metric("timestampLikelySeconds", o.timestampSeconds())
                .metric("manifestUri", o.manifestUri());
    }

    // ---------------------------------------------------------------- helpers

    /** Replaces the legacy mu delimiter with a backslash so both path styles compare and split alike. */
    static String normalize(String path) {
        return path.replace('µ', '\\');
    }

    /**
     * The segment at {@code depth} of a path delimited by backslash or mu. The segment before the
     * first delimiter (empty for a path that starts with one) is depth 0. Returns "" if the path
     * has fewer segments.
     */
    static String segment(String path, int depth) {
        int start = 0;
        for (int i = 0; i < depth; i++) {
            int next = indexOfDelimiter(path, start);
            if (next < 0) {
                return "";
            }
            start = next + 1;
        }
        int end = indexOfDelimiter(path, start);
        return end < 0 ? path.substring(start) : path.substring(start, end);
    }

    private static int indexOfDelimiter(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == 'µ') {
                return i;
            }
        }
        return -1;
    }

    /** {@code phs123456.c1} -> {@code phs123456}; a bare study id is its own prefix. */
    static String studyOf(String consent) {
        int dot = consent.indexOf('.');
        return dot < 0 ? consent : consent.substring(0, dot);
    }

    /** Returns why a partition name is unusable, or null. HPDS partition directories are named by this string. */
    static String partitionNameProblem(String name) {
        if (name.isBlank()) {
            return "empty";
        }
        if (name.indexOf('-') >= 0) {
            return "contains '-' (the file suffix -allConcepts.csv is split on it)";
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('µ') >= 0) {
            return "contains a path separator";
        }
        if (name.equals(".") || name.equals("..")) {
            return "reserved name";
        }
        return null;
    }

    private static boolean isBlankLine(List<String> row) {
        return row.isEmpty() || (row.size() == 1 && row.getFirst().isEmpty());
    }

    /** A first row whose first column is not an integer is a header (BOM-tolerant). */
    private static boolean isHeader(List<String> row) {
        String first = row.getFirst();
        if (first.startsWith("﻿")) {
            row.set(0, first.substring(1));
        }
        return parseInt(row.getFirst()) == null;
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isEmpty() || s.length() > 11) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String formatLine(List<String> row) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(row.get(i).replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    private static String joinPath(String base, String name) {
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }

    private static String sample(Collection<String> names) {
        List<String> head = names.stream().limit(20).toList();
        return head + (names.size() > head.size() ? " ... (+" + (names.size() - head.size()) + " more)" : "");
    }

    // ---------------------------------------------------------------- state holders

    /** Per-code row errors, counted in full but sampled for the message. */
    private static final class Findings {
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final Map<String, List<String>> samples = new LinkedHashMap<>();

        void add(String code, long rowNum, String message) {
            counts.merge(code, 1L, Long::sum);
            List<String> s = samples.computeIfAbsent(code, k -> new ArrayList<>());
            if (s.size() < MAX_SAMPLES) {
                s.add(rowNum > 0 ? "row " + rowNum + ": " + message : message);
            }
        }

        boolean isEmpty() {
            return counts.isEmpty();
        }

        Map<String, String> messages() {
            Map<String, String> out = new LinkedHashMap<>();
            counts.forEach((code, n) -> out.put(code, n + " occurrence(s). " + String.join("; ", samples.get(code))));
            return out;
        }
    }

    private static final class Pass1 {
        long rows;
        long blankRows;
        long consentRows;
        long blankConsentRows;
        long timestampZero;
        long timestampSeconds;
        String headerText;
        final Map<Integer, Set<String>> consentsByPatient = new HashMap<>();
        final Set<String> studies = new HashSet<>();
        final List<String> notes = new ArrayList<>();
    }

    private static final class Pass2 {
        long studyRows;
        long globalRows;
        long droppedStudyNoConsent;
        long droppedGlobalNoConsent;
        long partitionLines;
        final Map<String, PartitionStats> partitions = new HashMap<>();
        final Set<String> notes = new LinkedHashSet<>();
    }

    private static final class PartitionStats {
        final String kind;
        long rows;
        final PatientSet patients = new PatientSet();

        PartitionStats(String kind) {
            this.kind = kind;
        }
    }

    /**
     * Distinct patient numbers of one partition. A bit set for the realistic range (HPDS patient
     * numbers are small, dense integers), a hash set for anything outside it so a stray huge or
     * negative id costs a few bytes instead of a large allocation per partition.
     */
    private static final class PatientSet {
        private static final int BITSET_LIMIT = 1 << 26;
        private final BitSet dense = new BitSet();
        private Set<Integer> sparse;

        void set(int patient) {
            if (patient >= 0 && patient < BITSET_LIMIT) {
                dense.set(patient);
            } else {
                if (sparse == null) {
                    sparse = new HashSet<>();
                }
                sparse.add(patient);
            }
        }

        long cardinality() {
            return dense.cardinality() + (sparse == null ? 0 : sparse.size());
        }
    }

    /**
     * One temp file per partition. Open writers are capped (LRU) because a full BDC split has
     * hundreds of partitions; a reopened file is appended to so input order is kept.
     */
    private static final class Spool {
        private final Map<String, Path> files = new LinkedHashMap<>();
        private final LinkedHashMap<String, BufferedWriter> open =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, BufferedWriter> eldest) {
                        if (size() <= MAX_OPEN_WRITERS) {
                            return false;
                        }
                        try {
                            eldest.getValue().close();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        return true;
                    }
                };

        void write(String partition, String line) throws IOException {
            BufferedWriter w = open.get(partition);
            if (w == null) {
                Path file = files.get(partition);
                if (file == null) {
                    file = Files.createTempFile("split-by-consent-", ".csv");
                    files.put(partition, file);
                }
                w = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                open.put(partition, w);
            }
            w.write(line);
            w.write('\n');
        }

        Path file(String partition) {
            return files.get(partition);
        }

        void closeAll() throws IOException {
            for (BufferedWriter w : open.values()) {
                w.close();
            }
            open.clear();
        }

        void cleanUp() {
            for (BufferedWriter w : open.values()) {
                try {
                    w.close();
                } catch (IOException ignored) {
                    // temp file is deleted next
                }
            }
            open.clear();
            for (Path f : files.values()) {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // temp dir is ephemeral on the runner
                }
            }
        }
    }

    // ---------------------------------------------------------------- result types

    public record PartitionInfo(String name, String kind, long rows, long patients, String file) {
    }

    /** Result of the report-only fence_mapping cross-check. */
    public record FenceCheck(String uri, boolean performed, String error, List<ValidationIssue> issues,
                             List<String> unreachableStudyPartitions, List<String> ungrantedGlobalPartitions,
                             List<String> entriesWithoutPartition) {

        static FenceCheck skipped() {
            return new FenceCheck(null, false, null, List.of(), List.of(), List.of(), List.of());
        }
    }

    public record Output(
            String inputUri,
            String outputUri,
            int depth,
            String globals,
            String consentsPath,
            String headerText,
            long inputRows,
            long blankRows,
            long studyRows,
            long globalRows,
            long droppedStudyNoConsent,
            long droppedGlobalNoConsent,
            long timestampZero,
            long timestampSeconds,
            long consentRows,
            long patientsWithConsents,
            List<PartitionInfo> partitions,
            Map<String, String> errors,
            List<String> splitNotes,
            List<String> inputNotes,
            FenceCheck fence,
            String manifestUri) {

        long partitionLines() {
            return partitions.stream().mapToLong(PartitionInfo::rows).sum();
        }

        long accountedRows() {
            return studyRows + globalRows + droppedStudyNoConsent + droppedGlobalNoConsent;
        }

        boolean balanced() {
            return inputRows == accountedRows();
        }

        /** Lines beyond one per source row: multi-consent duplicates and by-patient copies. */
        long extraCopies() {
            return partitionLines() - studyRows - globalRows;
        }

        Output withManifestUri(String uri) {
            return new Output(inputUri, outputUri, depth, globals, consentsPath, headerText, inputRows, blankRows,
                    studyRows, globalRows, droppedStudyNoConsent, droppedGlobalNoConsent, timestampZero,
                    timestampSeconds, consentRows, patientsWithConsents, partitions, errors, splitNotes,
                    inputNotes, fence, uri);
        }
    }
}
