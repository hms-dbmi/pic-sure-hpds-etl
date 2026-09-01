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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Temporary migration job: takes a study's legacy allConcepts CSV from S3,
 * replaces old hpds integer IDs with the new UUIDs produced by
 * {@link ParticipantsMigrationJob}, then splits the result into per-consent
 * output files using the consent assignments already in the database.
 *
 * <p>Delete once the migration has run in every environment.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.split-allconcepts.enabled", havingValue = "true")
public class SplitAllConceptsJob extends AbstractJob<SplitAllConceptsJob.Output> {

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
                                "s3://avillach-73-bdcatalyst-etl/fhs/completed/phs000123/phs000123_allConcepts_new_search_with_data_analyzer.csv"),
                        ParamSpec.required("mapping",
                                "S3 URI of the hpds_id_mapping.csv from participants-migration",
                                "s3://bucket/reports/phs000123_hpds_id_mapping.csv"),
                        ParamSpec.required("output",
                                "Output directory for split files (local path or s3:// URI). "
                                        + "Structure: {output}/split_allconcepts/{study_id}/c{code}/{ABV}_allConcepts_c{code}.csv",
                                "./output")),
                List.of("Per-consent allConcepts files with UUIDs replacing legacy hpds IDs"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        String studyId = ctx.require("study-id");
        if (!studyId.matches("^phs\\d{6}$")) {
            report.error("INVALID_STUDY_ID",
                    "study-id must match phs###### (exactly 6 digits), got: " + studyId, "--study-id");
        }
        String abbreviation = ctx.require("abbreviation");
        if (abbreviation.isBlank()) {
            report.error("BLANK_ABBREVIATION", "abbreviation must not be blank", "--abbreviation");
        }
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

        Map<String, String> consentByUuid = loadConsentMap(studyId);
        if (consentByUuid.isEmpty()) {
            throw new DataException("No consents found in the database for study " + studyId);
        }
        log.info("Loaded {} consent assignment(s) for study {}", consentByUuid.size(), studyId);

        // Rows are spooled to one local temp file per consent group rather than held in
        // heap: parent studies' inputs run to tens of GB (phs000200 is 34 GB) and the
        // previous per-consent List<String> accumulation OOMed the 12 GB runner heap.
        Map<String, Path> tmpByConsent = new LinkedHashMap<>();
        Map<String, BufferedWriter> writerByConsent = new LinkedHashMap<>();
        Map<String, Long> rowsPerConsent = new LinkedHashMap<>();
        long totalRows = 0;
        long unmappedIds = 0;
        long noConsentRows = 0;
        List<String> unmappedSample = new ArrayList<>();

        try {
            InputStream in = io.openInput(inputUri);
            try (Stream<List<String>> rows = delimitedReader.streamRows(in, DelimitedReader.COMMA)) {
                for (List<String> row : (Iterable<List<String>>) rows::iterator) {
                    if (row.size() < 5) {
                        continue;
                    }
                    totalRows++;

                    String oldHpdsId = Strings.trimToNull(row.get(0));
                    if (oldHpdsId == null) {
                        continue;
                    }

                    String newUuid = idMapping.get(oldHpdsId);
                    if (newUuid == null) {
                        unmappedIds++;
                        if (unmappedSample.size() < 10) {
                            unmappedSample.add(oldHpdsId);
                        }
                        continue;
                    }

                    String consentCode = consentByUuid.get(newUuid);
                    if (consentCode == null) {
                        noConsentRows++;
                        continue;
                    }

                    String line = formatCsvLine(newUuid, row.get(1), row.get(2), row.get(3), row.get(4));
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
                }
            }
            for (BufferedWriter writer : writerByConsent.values()) {
                writer.close();
            }

            log.info("Read {} row(s): {} mapped to consents, {} unmapped id(s), {} no-consent row(s)",
                    totalRows, rowsPerConsent.values().stream().mapToLong(Long::longValue).sum(),
                    unmappedIds, noConsentRows);

            if (!unmappedSample.isEmpty()) {
                log.warn("Sample unmapped hpds ids (up to 10): {}", unmappedSample);
            }

            String abvUpper = abbreviation.toUpperCase(Locale.ROOT);
            Map<String, String> outputPaths = new LinkedHashMap<>();

            for (Map.Entry<String, Path> entry : tmpByConsent.entrySet()) {
                String code = entry.getKey();
                Path tmp = entry.getValue();
                String consentLabel = "c" + code;

                String outputUri = joinPath(outputBase,
                        "split_allconcepts", studyId, consentLabel,
                        abvUpper + "_allConcepts_" + consentLabel + ".csv");

                io.writeOutputFile(outputUri, tmp);
                log.info("Wrote {} row(s) ({} bytes) to {}", rowsPerConsent.get(code), Files.size(tmp), outputUri);
                outputPaths.put(code, outputUri);
            }

            return new Output(studyId, abbreviation, totalRows, unmappedIds, noConsentRows,
                    rowsPerConsent, outputPaths);
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
                String newUuid = Strings.trimToNull(row.get("new_uuid"));
                if (oldId != null && newUuid != null) {
                    mapping.put(oldId, newUuid);
                }
            }
        }
        return mapping;
    }

    private Map<String, String> loadConsentMap(String studyId) {
        Map<String, String> map = new HashMap<>();
        for (Consent c : consentRepository.findByStudyId(studyId)) {
            map.put(c.hpdsUuid().toString(), c.consentCode());
        }
        return map;
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
            report.warning("UNMAPPED_IDS",
                    output.unmappedIds() + " row(s) (" + String.format("%.1f%%", pct)
                            + ") had hpds IDs not found in the mapping CSV");
        }
        if (output.noConsentRows() > 0) {
            report.warning("NO_CONSENT",
                    output.noConsentRows() + " row(s) had UUIDs with no consent assignment in the database");
        }
        output.rowsPerConsent().forEach((code, count) ->
                report.info("CONSENT_ROWS", "c" + code + ": " + count + " row(s)"));
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("studyId", output.studyId())
                .metric("abbreviation", output.abbreviation())
                .metric("totalRows", output.totalRows())
                .metric("unmappedIds", output.unmappedIds())
                .metric("noConsentRows", output.noConsentRows())
                .metric("rowsPerConsent", output.rowsPerConsent())
                .metric("outputPaths", output.outputPaths());
    }

    public record Output(
            String studyId,
            String abbreviation,
            long totalRows,
            long unmappedIds,
            long noConsentRows,
            Map<String, Long> rowsPerConsent,
            Map<String, String> outputPaths
    ) {
    }
}
