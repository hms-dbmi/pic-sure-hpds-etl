package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.Strings;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Temporary migration job: takes a study's legacy allConcepts CSV from S3,
 * replaces old hpds integer IDs with the new hpds IDs produced by
 * {@link ParticipantsMigrationJob}, then splits the result into per-consent
 * output files using the consent assignments already in the database.
 *
 * <p>Only the study's own concepts are kept: a row whose concept path's first node is not the
 * study id ({@code µphs000123µ...}) is dropped and reported as {@code FOREIGN_CONCEPTS}. Some legacy
 * allConcepts files carry other studies' concepts for the participants they share; those rows
 * belong to the other study's partitions, never this one's.
 *
 * <p>Delete once the migration has run in every environment.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.split-allconcepts.enabled", havingValue = "true")
public class SplitAllConceptsJob extends AbstractJob<SplitAllConceptsJob.Output> {

    /** Distinct dropped concept paths kept for the report. */
    static final int FOREIGN_SAMPLE_SIZE = 10;
    /** Foreign first nodes listed in the report, most rows first; the rest are summed under {@link #OTHER_NODES}. */
    static final int FOREIGN_NODES_REPORTED = 50;
    static final String OTHER_NODES = "(other)";
    /** A concept path's node delimiters; the path's first character says which one it uses. */
    private static final String PATH_DELIMITERS = "µ\\/";

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final ConsentRepository consentRepository;

    public SplitAllConceptsJob(IoResolver io, DelimitedReader delimitedReader,
                               ConsentRepository consentRepository) {
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.consentRepository = consentRepository;
    }

    @Override
    public String name() {
        return "split-allconcepts";
    }

    @Override
    public JobType type() {
        return JobType.MIGRATION;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(
                        ParamSpec.required("study-id",
                                "Study identifier (e.g. phs000123)",
                                "phs000123"),
                        ParamSpec.required("abbreviation",
                                "Study abbreviated name (e.g. FHS)",
                                "FHS"),
                        ParamSpec.required("input",
                                "S3 URI of the study's allConcepts CSV",
                                "s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/phs000123/legacy/allConcepts/phs000123_allConcepts_new_search_with_data_analyzer.csv"),
                        ParamSpec.required("mapping",
                                "S3 URI of the hpds_id_mapping.csv from participants-migration (columns: old_hpds_id, new_hpds_id, common_dbgap_id)",
                                "s3://bucket/phs000123/mappings/<build-tag>/phs000123_hpds_id_mapping.csv"),
                        ParamSpec.required("output",
                                "Root of the per-study folders (local path or s3:// URI). "
                                        + "Structure: {output}/{study_id}/allConcepts/c{code}/{study_id}_allConcepts_c{code}.csv",
                                "./output")),
                List.of("Per-consent allConcepts files with new hpds IDs replacing legacy hpds IDs"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        String studyId = ctx.require("study-id");
        if (!studyId.matches("^phs\\d{6}$")) {
            report.error("BAD_STUDY_ID",
                    "study-id must match phs###### (exactly 6 digits), got: " + studyId, "--study-id");
        }
        // A blank --abbreviation needs no check here: JobContext treats blank as absent, so it
        // already fails MISSING_PARAM.
    }

    @Override
    protected Output execute(JobContext ctx) {
        String studyId = ctx.require("study-id");
        String abbreviation = ctx.require("abbreviation");
        String inputUri = ctx.require("input");
        String mappingUri = ctx.require("mapping");
        String outputBase = ctx.require("output");

        Map<String, String> idMapping = readMappingCsv(mappingUri);
        if (idMapping.isEmpty()) {
            throw new DataException("Mapping CSV at " + mappingUri + " contained no data rows");
        }
        log.info("Loaded {} id mapping(s) from {}", idMapping.size(), mappingUri);

        Map<String, String> consentByHpdsId = loadConsentMap(studyId);
        if (consentByHpdsId.isEmpty()) {
            throw new DataException("No consents found in the database for study " + studyId);
        }
        log.info("Loaded {} consent assignment(s) for study {}", consentByHpdsId.size(), studyId);

        // Rows are spooled to one local temp file per consent group rather than held in
        // heap: parent studies' inputs run to tens of GB (phs000200 is 34 GB) and the
        // previous per-consent List<String> accumulation OOMed the 12 GB runner heap.
        Map<String, Path> tmpByConsent = new LinkedHashMap<>();
        Map<String, BufferedWriter> writerByConsent = new LinkedHashMap<>();
        Map<String, Long> rowsPerConsent = new LinkedHashMap<>();
        // Distinct new hpds ids written to each consent partition: the participant count that is
        // compared against production's, per consent group.
        Map<String, Set<String>> participantsByConsent = new HashMap<>();
        long totalRows = 0;
        long unmappedIds = 0;
        long noConsentRows = 0;
        long malformedRows = 0;
        long foreignConceptRows = 0;
        List<String> unmappedSample = new ArrayList<>();
        Map<String, Long> foreignRowsByFirstNode = new HashMap<>();
        Set<String> foreignConceptSample = new LinkedHashSet<>();

        try {
            InputStream in = io.openInput(inputUri);
            try (Stream<List<String>> rows = delimitedReader.streamRows(in, DelimitedReader.COMMA)) {
                for (List<String> row : (Iterable<List<String>>) rows::iterator) {
                    if (row.size() < 5) {
                        malformedRows++;
                        continue;
                    }
                    totalRows++;

                    String oldHpdsId = Strings.trimToNull(row.get(0));
                    if (oldHpdsId == null) {
                        malformedRows++;
                        continue;
                    }

                    String conceptPath = row.get(1);
                    String firstNode = firstNode(conceptPath);
                    if (!studyId.equals(firstNode)) {
                        foreignConceptRows++;
                        foreignRowsByFirstNode.merge(firstNode, 1L, Long::sum);
                        if (foreignConceptSample.size() < FOREIGN_SAMPLE_SIZE) {
                            foreignConceptSample.add(conceptPath);
                        }
                        continue;
                    }

                    String newHpdsId = idMapping.get(oldHpdsId);
                    if (newHpdsId == null) {
                        unmappedIds++;
                        if (unmappedSample.size() < 10) {
                            unmappedSample.add(oldHpdsId);
                        }
                        continue;
                    }

                    String consentCode = consentByHpdsId.get(newHpdsId);
                    if (consentCode == null) {
                        noConsentRows++;
                        continue;
                    }

                    String line = formatCsvLine(newHpdsId, row.get(1), row.get(2), row.get(3), row.get(4));
                    BufferedWriter writer = writerByConsent.get(consentCode);
                    if (writer == null) {
                        Path tmp = Files.createTempFile("split-" + studyId + "-c" + consentCode + "-", ".csv");
                        tmpByConsent.put(consentCode, tmp);
                        writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8);
                        writerByConsent.put(consentCode, writer);
                    }
                    writer.write(line);
                    writer.write('\n');
                    rowsPerConsent.merge(consentCode, 1L, Long::sum);
                    participantsByConsent.computeIfAbsent(consentCode, c -> new HashSet<>()).add(newHpdsId);
                }
            }
            for (BufferedWriter writer : writerByConsent.values()) {
                writer.close();
            }

            log.info("Read {} row(s): {} mapped to consents, {} other studies' concept row(s), {} unmapped id(s), "
                            + "{} no-consent row(s), {} malformed row(s)",
                    totalRows, rowsPerConsent.values().stream().mapToLong(Long::longValue).sum(),
                    foreignConceptRows, unmappedIds, noConsentRows, malformedRows);

            Map<String, Long> foreignFirstNodes = topCounts(foreignRowsByFirstNode);
            if (foreignConceptRows > 0) {
                log.warn("Dropped {} row(s) whose concept path does not start with {}; by first node: {}; "
                        + "sample: {}", foreignConceptRows, studyId, foreignFirstNodes, foreignConceptSample);
            }
            if (!unmappedSample.isEmpty()) {
                log.warn("Sample unmapped hpds ids (up to 10): {}", unmappedSample);
            }

            Map<String, Long> participantsPerConsent = new LinkedHashMap<>();
            rowsPerConsent.keySet().forEach(code ->
                    participantsPerConsent.put(code, (long) participantsByConsent.get(code).size()));

            Map<String, String> outputPaths = new LinkedHashMap<>();

            for (Map.Entry<String, Path> entry : tmpByConsent.entrySet()) {
                String code = entry.getKey();
                Path tmp = entry.getValue();
                String consentLabel = "c" + code;

                String outputUri = joinPath(outputBase,
                        studyId, "allConcepts", consentLabel,
                        studyId + "_allConcepts_" + consentLabel + ".csv");

                io.writeOutputFile(outputUri, tmp);
                log.info("Wrote {} row(s) for {} participant(s) ({} bytes) to {}", rowsPerConsent.get(code),
                        participantsPerConsent.get(code), Files.size(tmp), outputUri);
                outputPaths.put(code, outputUri);
            }

            return new Output(studyId, abbreviation, totalRows, unmappedIds, noConsentRows, malformedRows,
                    foreignConceptRows, foreignFirstNodes, List.copyOf(foreignConceptSample),
                    List.copyOf(unmappedSample), rowsPerConsent, participantsPerConsent, outputPaths);
        } catch (IOException e) {
            throw new edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException(
                    "Failed spooling split output for study " + studyId, e);
        } finally {
            for (BufferedWriter writer : writerByConsent.values()) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // already closed on the happy path
                }
            }
            for (Path tmp : tmpByConsent.values()) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.warn("Could not delete temp file {}", tmp);
                }
            }
        }
    }

    private Map<String, String> readMappingCsv(String uri) {
        Map<String, String> mapping = new HashMap<>();
        InputStream in = io.openInput(uri);
        try (Stream<Map<String, String>> rows = delimitedReader.stream(in, DelimitedReader.COMMA)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) rows::iterator) {
                String oldId = Strings.trimToNull(row.get("old_hpds_id"));
                String newHpdsId = Strings.trimToNull(row.get("new_hpds_id"));
                if (oldId != null && newHpdsId != null) {
                    mapping.put(oldId, newHpdsId);
                }
            }
        }
        return mapping;
    }

    private Map<String, String> loadConsentMap(String studyId) {
        Map<String, String> map = new HashMap<>();
        for (Consent c : consentRepository.findByStudyId(studyId)) {
            map.put(String.valueOf(c.hpdsId()), c.consentCode());
        }
        return map;
    }

    /**
     * The first node of a concept path: the text between its leading delimiter ({@code µ}, {@code \}
     * or {@code /}, whichever the path starts with) and the next one. A path that starts with none of
     * them has no first node, so it can never match a study id; it is returned whole for the report.
     */
    static String firstNode(String conceptPath) {
        if (conceptPath == null || conceptPath.isEmpty()
                || PATH_DELIMITERS.indexOf(conceptPath.charAt(0)) < 0) {
            return conceptPath == null ? "" : conceptPath;
        }
        char delimiter = conceptPath.charAt(0);
        int end = conceptPath.indexOf(delimiter, 1);
        return end < 0 ? conceptPath.substring(1) : conceptPath.substring(1, end);
    }

    /** The {@link #FOREIGN_NODES_REPORTED} largest counts, largest first, the rest summed under {@link #OTHER_NODES}. */
    static Map<String, Long> topCounts(Map<String, Long> counts) {
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        Map<String, Long> top = new LinkedHashMap<>();
        long other = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < FOREIGN_NODES_REPORTED) {
                top.put(sorted.get(i).getKey(), sorted.get(i).getValue());
            } else {
                other += sorted.get(i).getValue();
            }
        }
        if (other > 0) {
            top.merge(OTHER_NODES, other, Long::sum);
        }
        return top;
    }

    static String formatCsvLine(String col0, String col1, String col2, String col3, String col4) {
        return "\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"".formatted(
                escapeQuotes(col0), escapeQuotes(col1),
                escapeQuotes(col2), escapeQuotes(col3), escapeQuotes(col4));
    }

    private static String escapeQuotes(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }

    private static String joinPath(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.isEmpty()) {
                sb.append(part);
            } else {
                if (!sb.toString().endsWith("/") && !part.startsWith("/")) {
                    sb.append('/');
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.rowsPerConsent().isEmpty()) {
            report.error("NO_OUTPUT", "No consent-split files were generated");
        }
        if (output.unmappedIds() > 0) {
            double pct = 100.0 * output.unmappedIds() / Math.max(output.totalRows(), 1);
            report.add(ValidationIssue.warning("UNMAPPED_IDS",
                    output.unmappedIds() + " row(s) (" + String.format("%.1f%%", pct)
                            + ") had hpds IDs not found in the mapping CSV. First: " + output.unmappedSample(),
                    Map.of("count", output.unmappedIds(),
                            "percent", Math.round(pct * 10) / 10.0,
                            "firstOldHpdsIds", output.unmappedSample())));
        }
        if (output.foreignConceptRows() > 0) {
            double pct = 100.0 * output.foreignConceptRows() / Math.max(output.totalRows(), 1);
            report.add(ValidationIssue.warning("FOREIGN_CONCEPTS",
                    output.foreignConceptRows() + " row(s) (" + String.format("%.1f%%", pct)
                            + ") have a concept path whose first node is not " + output.studyId()
                            + " and were dropped; by first node: " + output.foreignFirstNodes(),
                    Map.of("count", output.foreignConceptRows(),
                            "percent", Math.round(pct * 10) / 10.0,
                            "firstNodes", output.foreignFirstNodes(),
                            "sampleConceptPaths", output.foreignConceptSample())));
        }
        if (output.malformedRows() > 0) {
            report.warning("MALFORMED_ROWS", output.malformedRows()
                    + " row(s) had fewer than 5 columns or a blank hpds id and were skipped");
        }
        if (output.noConsentRows() > 0) {
            report.warning("NO_CONSENT",
                    output.noConsentRows() + " row(s) had hpds IDs with no consent assignment in the database");
        }
        output.rowsPerConsent().forEach((code, count) ->
                report.info("CONSENT_ROWS", "c" + code + ": " + count + " row(s), "
                        + output.participantsPerConsent().get(code) + " participant(s)"));
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("studyId", output.studyId())
                .metric("abbreviation", output.abbreviation())
                .metric("totalRows", output.totalRows())
                .metric("unmappedIds", output.unmappedIds())
                .metric("noConsentRows", output.noConsentRows())
                .metric("malformedRows", output.malformedRows())
                .metric("foreignConceptRows", output.foreignConceptRows())
                .metric("foreignFirstNodes", output.foreignFirstNodes())
                .metric("rowsPerConsent", output.rowsPerConsent())
                .metric("participantsPerConsent", output.participantsPerConsent())
                .metric("outputPaths", output.outputPaths());
    }

    public record Output(
            String studyId,
            String abbreviation,
            long totalRows,
            long unmappedIds,
            long noConsentRows,
            long malformedRows,
            /** Rows dropped because their concept path's first node is not the study id. */
            long foreignConceptRows,
            /** Those rows per first node (see {@link #topCounts}). */
            Map<String, Long> foreignFirstNodes,
            /** Up to {@link #FOREIGN_SAMPLE_SIZE} of their distinct concept paths. */
            List<String> foreignConceptSample,
            List<String> unmappedSample,
            Map<String, Long> rowsPerConsent,
            Map<String, Long> participantsPerConsent,
            Map<String, String> outputPaths
    ) {
    }
}
