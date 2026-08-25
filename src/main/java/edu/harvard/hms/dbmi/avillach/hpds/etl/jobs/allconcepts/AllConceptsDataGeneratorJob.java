package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.AllConceptsCsvBuilder;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.AllConceptsRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.ConceptMapping;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.ConceptMapping.DataType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "etl.jobs.all-concepts-data-generator.enabled", havingValue = "true")
public class AllConceptsDataGeneratorJob extends AbstractJob<AllConceptsDataGeneratorJob.Output> {

    private static final Pattern STUDY_ID_PATTERN = Pattern.compile("phs\\d{6}");
    private static final Set<String> NULL_EQUIVALENTS = Set.of(
            "null", "na", "n/a", "nan", "nil", "nill");

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final ConsentRepository consentRepository;
    private final ParticipantRepository participantRepository;

    public AllConceptsDataGeneratorJob(IoResolver io,
                                       DelimitedReader delimitedReader,
                                       ConsentRepository consentRepository,
                                       ParticipantRepository participantRepository) {
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.consentRepository = consentRepository;
        this.participantRepository = participantRepository;
    }

    @Override
    public String name() {
        return "all-concepts-data-generator";
    }

    @Override
    public JobType type() {
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(
                        ParamSpec.required("study-id",
                                "dbGaP study id, format phs###### (6 digits)", "phs001412"),
                        ParamSpec.required("data-dir",
                                "Directory or S3 prefix containing decoded data CSVs (local path or s3:// URI)",
                                "s3://bucket/study/decoded_data/"),
                        ParamSpec.required("mapping",
                                "Mapping CSV file (local path or s3:// URI)",
                                "s3://bucket/study/mappings/mapping2.csv"),
                        ParamSpec.required("output",
                                "Output directory for per-consent allConcepts files (local path or s3:// URI)",
                                "s3://bucket/study/completed/"),
                        ParamSpec.optional("skip-analysis",
                                "Skip data type re-analysis and use mapping types as-is (default: false)",
                                "false")),
                List.of("One {study_id}.c{consent_code}_allConcepts.csv per consent group, "
                        + "written to --output"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        ctx.get("study-id").ifPresent(studyId -> {
            if (!STUDY_ID_PATTERN.matcher(studyId).matches()) {
                report.error("BAD_STUDY_ID",
                        "study-id must match phs###### (6 digits), got: " + studyId, "--study-id");
            }
        });
        ctx.get("skip-analysis").ifPresent(v -> {
            if (!JobContext.isBooleanLiteral(v)) {
                report.error("BAD_SKIP_ANALYSIS",
                        "skip-analysis must be " + JobContext.acceptedBooleanLiterals() + ", got: " + v,
                        "--skip-analysis");
            }
        });
    }

    @Override
    protected Output execute(JobContext ctx) {
        String studyId = ctx.require("study-id");
        String dataDir = normalizeDir(ctx.require("data-dir"));
        String mappingUri = ctx.require("mapping");
        String outputDir = normalizeDir(ctx.require("output"));
        boolean skipAnalysis = ctx.getBoolean("skip-analysis", false);

        List<Consent> consents = consentRepository.findByStudyId(studyId);
        if (consents.isEmpty()) {
            throw new DataException("No consents found for study " + studyId + " in the database");
        }

        Map<UUID, Consent> consentByUuid = new LinkedHashMap<>();
        for (Consent c : consents) {
            consentByUuid.put(c.hpdsUuid(), c);
        }

        List<Participant> participants = participantRepository.findByStudyId(studyId);
        if (participants.isEmpty()) {
            throw new DataException("No participants found for study " + studyId + " in the database");
        }

        Map<String, UUID> uuidBySourceId = new LinkedHashMap<>();
        for (Participant p : participants) {
            uuidBySourceId.put(p.sourceId(), p.hpdsUuid());
        }

        log.info("Study {} has {} consent group(s) and {} participant(s)",
                studyId, consents.size(), participants.size());

        List<ConceptMapping> mappings = parseMappings(mappingUri);
        log.info("Loaded {} mapping(s) from {}", mappings.size(), mappingUri);

        if (!skipAnalysis) {
            mappings = analyzeDataTypes(mappings, dataDir);
            log.info("Data type analysis complete; {} mapping(s) remain after filtering empty columns",
                    mappings.size());
        }

        Map<String, List<ConceptMapping>> mappingsByFile = new LinkedHashMap<>();
        for (ConceptMapping m : mappings) {
            mappingsByFile.computeIfAbsent(m.fileName(), k -> new ArrayList<>()).add(m);
        }

        List<FileResult> fileResults = processFilesInParallel(
                mappingsByFile, dataDir, uuidBySourceId, consentByUuid);

        Map<String, AllConceptsCsvBuilder> buildersByConsent = new LinkedHashMap<>();
        for (Consent c : consents) {
            buildersByConsent.put(c.consentCode(), new AllConceptsCsvBuilder());
        }

        long rowsProcessed = 0;
        long rowsSkipped = 0;
        Set<String> unmappedPatients = new LinkedHashSet<>();

        for (FileResult fr : fileResults) {
            rowsProcessed += fr.rowsProcessed;
            rowsSkipped += fr.rowsSkipped;
            unmappedPatients.addAll(fr.unmappedPatients);
            for (Map.Entry<String, List<AllConceptsRow>> e : fr.rowsByConsent.entrySet()) {
                AllConceptsCsvBuilder builder = buildersByConsent.get(e.getKey());
                if (builder != null) {
                    builder.addAll(e.getValue());
                }
            }
        }

        if (rowsProcessed == 0) {
            throw new DataException("No concept rows were generated for study " + studyId
                    + ". Processed " + mappingsByFile.size() + " file(s) with " + mappings.size()
                    + " mapping(s).");
        }

        Map<String, Long> rowsPerConsent = new LinkedHashMap<>();
        List<String> outputFiles = new ArrayList<>();

        for (Map.Entry<String, AllConceptsCsvBuilder> entry : buildersByConsent.entrySet()) {
            String consentCode = entry.getKey();
            AllConceptsCsvBuilder builder = entry.getValue();

            if (builder.isEmpty()) {
                log.warn("Consent group c{} for study {} produced no rows", consentCode, studyId);
                continue;
            }

            String outputFile = outputDir + studyId + ".c" + consentCode + "_allConcepts.csv";
            byte[] csv = builder.build();
            io.writeOutput(outputFile, csv);
            rowsPerConsent.put("c" + consentCode, (long) builder.size());
            outputFiles.add(outputFile);
            log.info("Wrote {} rows ({} bytes) to {}", builder.size(), csv.length, outputFile);
        }

        return new Output(studyId, consents.size(), participants.size(), mappings.size(),
                rowsProcessed, rowsSkipped, unmappedPatients.size(), rowsPerConsent, outputFiles);
    }

    private AllConceptsRow buildConceptRow(String hpdsId, ConceptMapping mapping, String cellValue) {
        if (cellValue.isEmpty()) {
            return null;
        }
        if (isNullEquivalent(cellValue)) {
            return null;
        }

        if (mapping.dataType() == DataType.NUMERIC) {
            if (isCreatableNumber(cellValue)) {
                return AllConceptsRow.numeric(hpdsId, mapping.conceptPath(), cellValue);
            }
            return null;
        }

        cellValue = cellValue.replace("\"", "'");
        return AllConceptsRow.nonNumeric(hpdsId, mapping.conceptPath(), cellValue);
    }

    private List<FileResult> processFilesInParallel(
            Map<String, List<ConceptMapping>> mappingsByFile,
            String dataDir,
            Map<String, UUID> uuidBySourceId,
            Map<UUID, Consent> consentByUuid) {

        List<FileResult> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FileResult>> futures = new ArrayList<>();

            for (Map.Entry<String, List<ConceptMapping>> entry : mappingsByFile.entrySet()) {
                String fileName = entry.getKey();
                List<ConceptMapping> fileMappings = entry.getValue();
                String fileUri = dataDir + fileName;

                if (!io.exists(fileUri)) {
                    log.warn("Data file {} does not exist; skipping {} mapping(s)",
                            fileUri, fileMappings.size());
                    continue;
                }

                futures.add(executor.submit(() ->
                        processFile(fileUri, fileMappings, uuidBySourceId, consentByUuid)));
            }

            for (Future<FileResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    throw new DataException("File processing failed: " + cause.getMessage(), cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DataException("File processing interrupted");
                }
            }
        }

        log.info("Processed {} file(s) in parallel", results.size());
        return results;
    }

    private FileResult processFile(String fileUri,
                                   List<ConceptMapping> fileMappings,
                                   Map<String, UUID> uuidBySourceId,
                                   Map<UUID, Consent> consentByUuid) {
        Map<String, List<AllConceptsRow>> rowsByConsent = new LinkedHashMap<>();
        Set<String> unmappedPatients = new LinkedHashSet<>();
        long rowsProcessed = 0;
        long rowsSkipped = 0;

        InputStream in = io.openInput(fileUri);
        try (Stream<List<String>> rows = delimitedReader.streamRows(in, DelimitedReader.COMMA)) {
            List<String> headers = null;
            for (List<String> row : (Iterable<List<String>>) rows::iterator) {
                if (headers == null) {
                    headers = row;
                    continue;
                }
                if (row.size() != headers.size()) {
                    continue;
                }

                for (ConceptMapping mapping : fileMappings) {
                    if (mapping.columnIndex() >= row.size() || mapping.patientCol() >= row.size()) {
                        continue;
                    }

                    String patientId = row.get(mapping.patientCol()).trim();
                    if (patientId.isEmpty() || patientId.charAt(0) == '#') {
                        continue;
                    }
                    if (patientId.toLowerCase(Locale.ROOT).contains("dbgap")) {
                        continue;
                    }

                    UUID uuid = uuidBySourceId.get(patientId);
                    if (uuid == null) {
                        unmappedPatients.add(patientId);
                        rowsSkipped++;
                        continue;
                    }

                    Consent consent = consentByUuid.get(uuid);
                    if (consent == null) {
                        rowsSkipped++;
                        continue;
                    }

                    String cellValue = row.get(mapping.columnIndex()).trim();
                    AllConceptsRow conceptRow = buildConceptRow(uuid.toString(), mapping, cellValue);
                    if (conceptRow != null) {
                        rowsByConsent.computeIfAbsent(consent.consentCode(), k -> new ArrayList<>())
                                .add(conceptRow);
                        rowsProcessed++;
                    }
                }
            }
        }

        log.info("File {} produced {} row(s), skipped {}", fileUri, rowsProcessed, rowsSkipped);
        return new FileResult(rowsByConsent, rowsProcessed, rowsSkipped, unmappedPatients);
    }

    private List<ConceptMapping> parseMappings(String mappingUri) {
        InputStream in = io.openInput(mappingUri);
        return ConceptMapping.parse(in, delimitedReader);
    }

    List<ConceptMapping> analyzeDataTypes(List<ConceptMapping> mappings, String dataDir) {
        Map<String, List<ConceptMapping>> byFile = new LinkedHashMap<>();
        for (ConceptMapping m : mappings) {
            byFile.computeIfAbsent(m.fileName(), k -> new ArrayList<>()).add(m);
        }

        List<ConceptMapping> analyzed = new ArrayList<>();

        for (Map.Entry<String, List<ConceptMapping>> entry : byFile.entrySet()) {
            String fileName = entry.getKey();
            List<ConceptMapping> fileMappings = entry.getValue();
            String fileUri = dataDir + fileName;

            if (!io.exists(fileUri)) {
                log.warn("Data file {} not found during analysis; keeping mapping types as-is", fileUri);
                analyzed.addAll(fileMappings);
                continue;
            }

            Map<Integer, ColumnStats> stats = new LinkedHashMap<>();
            for (ConceptMapping m : fileMappings) {
                stats.put(m.columnIndex(), new ColumnStats());
            }

            InputStream in = io.openInput(fileUri);
            try (Stream<List<String>> rows = delimitedReader.streamRows(in, DelimitedReader.COMMA)) {
                boolean firstRow = true;
                for (List<String> row : (Iterable<List<String>>) rows::iterator) {
                    if (firstRow) {
                        firstRow = false;
                        continue;
                    }
                    for (Map.Entry<Integer, ColumnStats> se : stats.entrySet()) {
                        int col = se.getKey();
                        if (col < row.size()) {
                            se.getValue().observe(row.get(col).trim());
                        }
                    }
                }
            }

            for (ConceptMapping m : fileMappings) {
                ColumnStats cs = stats.get(m.columnIndex());
                if (cs.totalNonNull == 0) {
                    log.debug("Removing mapping {} (column {} of {}): all values null/empty",
                            m.conceptPath(), m.columnIndex(), fileName);
                    continue;
                }
                DataType resolved = cs.hasAnyNonNumeric ? DataType.TEXT : DataType.NUMERIC;
                analyzed.add(new ConceptMapping(m.fileName(), m.columnIndex(),
                        m.conceptPath(), resolved, m.patientCol()));
            }
        }

        return analyzed;
    }

    private static String normalizeDir(String dir) {
        if (!dir.endsWith("/")) {
            return dir + "/";
        }
        return dir;
    }

    static boolean isNullEquivalent(String value) {
        return NULL_EQUIVALENTS.contains(value.toLowerCase(Locale.ROOT));
    }

    static boolean isCreatableNumber(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.rowsProcessed() == 0) {
            report.error("EMPTY_OUTPUT", "No concept rows were generated");
        }
        if (output.unmappedPatientCount() > 0) {
            report.warning("UNMAPPED_PATIENTS",
                    output.unmappedPatientCount() + " patient(s) in data files could not be resolved "
                            + "to an hpds_uuid via the participants table");
        }
        output.rowsPerConsent().forEach((consent, count) ->
                report.info("CONSENT_ROW_COUNT", consent + ": " + count + " row(s)"));
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("studyId", output.studyId())
                .metric("consentGroups", output.consentGroups())
                .metric("participants", output.participants())
                .metric("mappingsUsed", output.mappingsUsed())
                .metric("rowsProcessed", output.rowsProcessed())
                .metric("rowsSkipped", output.rowsSkipped())
                .metric("unmappedPatients", output.unmappedPatientCount())
                .metric("rowsPerConsent", output.rowsPerConsent())
                .metric("outputFiles", output.outputFiles());
    }

    public record Output(
            String studyId,
            int consentGroups,
            int participants,
            int mappingsUsed,
            long rowsProcessed,
            long rowsSkipped,
            int unmappedPatientCount,
            Map<String, Long> rowsPerConsent,
            List<String> outputFiles
    ) {
    }

    record FileResult(
            Map<String, List<AllConceptsRow>> rowsByConsent,
            long rowsProcessed,
            long rowsSkipped,
            Set<String> unmappedPatients
    ) {}

    static final class ColumnStats {
        int totalNonNull;
        boolean hasAnyNonNumeric;

        void observe(String value) {
            if (value.isEmpty() || isNullEquivalent(value)) {
                return;
            }
            totalNonNull++;
            if (!isCreatableNumber(value)) {
                hasAnyNonNumeric = true;
            }
        }
    }
}
