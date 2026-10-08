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
    private static final Pattern CONSENT_FOLDER_PATTERN = Pattern.compile("c[^/]+");
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
                                "Root of the per-study folders; files go under {output}/{study_id}/allConcepts/ "
                                        + "(local path or s3:// URI)",
                                "s3://bucket/root/"),
                        ParamSpec.optional("skip-analysis",
                                "Skip data type re-analysis and use mapping types as-is (default: false)",
                                "false")),
                List.of("One {output}/{study_id}/allConcepts/c{consent_code}/{study_id}_allConcepts_c{consent_code}.csv "
                        + "per consent group"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        ctx.get("study-id").ifPresent(studyId -> {
            if (!STUDY_ID_PATTERN.matcher(studyId).matches()) {
                report.error("BAD_STUDY_ID",
                        "study-id must match phs###### (6 digits), got: " + studyId, "--study-id");
            }
        });
        validateBooleanParam(ctx, report, "skip-analysis");
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

        Map<Long, Consent> consentById = new LinkedHashMap<>();
        for (Consent c : consents) {
            consentById.put(c.hpdsId(), c);
        }

        List<Participant> participants = participantRepository.findByStudyId(studyId);
        if (participants.isEmpty()) {
            throw new DataException("No participants found for study " + studyId + " in the database");
        }

        Map<String, Long> idBySourceId = new LinkedHashMap<>();
        for (Participant p : participants) {
            idBySourceId.put(p.sourceId(), p.hpdsId());
        }

        log.info("Study {} has {} consent group(s) and {} participant(s)",
                studyId, consents.size(), participants.size());

        ConceptMapping.Parsed parsed = parseMappings(mappingUri);
        List<ConceptMapping> mappings = parsed.mappings();
        log.info("Loaded {} mapping(s) from {} ({} row(s) dropped as unusable)",
                mappings.size(), mappingUri, parsed.droppedRows());

        if (!skipAnalysis) {
            mappings = analyzeDataTypes(mappings, dataDir);
            log.info("Data type analysis complete; {} mapping(s) remain after filtering empty columns",
                    mappings.size());
        }

        Map<String, List<ConceptMapping>> mappingsByFile = new LinkedHashMap<>();
        for (ConceptMapping m : mappings) {
            mappingsByFile.computeIfAbsent(m.fileName(), k -> new ArrayList<>()).add(m);
        }

        List<String> missingDataFiles = new ArrayList<>();
        List<FileResult> fileResults = processFilesInParallel(
                mappingsByFile, dataDir, idBySourceId, consentById, missingDataFiles);

        Map<String, AllConceptsCsvBuilder> buildersByConsent = new LinkedHashMap<>();
        for (Consent c : consents) {
            buildersByConsent.put(c.consentCode(), new AllConceptsCsvBuilder());
        }

        long rowsProcessed = 0;
        long rowsSkipped = 0;
        long malformedRows = 0;
        Set<String> unmappedPatients = new LinkedHashSet<>();

        for (FileResult fr : fileResults) {
            rowsProcessed += fr.rowsProcessed;
            rowsSkipped += fr.rowsSkipped;
            malformedRows += fr.malformedRows;
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

            String consentLabel = "c" + consentCode;
            String outputFile = outputFileFor(outputDir, studyId, consentLabel);
            byte[] csv = builder.build();
            io.writeOutput(outputFile, csv);
            rowsPerConsent.put("c" + consentCode, (long) builder.size());
            outputFiles.add(outputFile);
            log.info("Wrote {} rows ({} bytes) to {}", builder.size(), csv.length, outputFile);
        }

        List<String> staleFilesRemoved = removeStaleOutputs(outputDir, studyId, rowsPerConsent.keySet());

        return new Output(studyId, consents.size(), participants.size(), mappings.size(),
                rowsProcessed, rowsSkipped, unmappedPatients.size(), rowsPerConsent, outputFiles,
                staleFilesRemoved, parsed.droppedRows(), malformedRows, missingDataFiles);
    }

    static String outputFileFor(String outputDir, String studyId, String consentLabel) {
        return studyAllConceptsDir(outputDir, studyId) + consentLabel + "/"
                + studyId + "_allConcepts_" + consentLabel + ".csv";
    }

    static String studyAllConceptsDir(String outputDir, String studyId) {
        return outputDir + studyId + "/allConcepts/";
    }

    /**
     * Each run overwrites this study's per-consent files in place (the output bucket is
     * versioned, so the previous version stays recoverable). A consent group that produced
     * rows last time but none now -- emptied by the data, or gone after an SSTR reload -- would
     * otherwise keep last run's file and keep being merged. Removes exactly this job's own file
     * in every other consent folder of the study; files other sources write into the same
     * folders are never touched.
     */
    private List<String> removeStaleOutputs(String outputDir, String studyId, Set<String> writtenLabels) {
        List<String> removed = new ArrayList<>();
        for (String folder : io.listDirectoryNames(studyAllConceptsDir(outputDir, studyId))) {
            if (!CONSENT_FOLDER_PATTERN.matcher(folder).matches() || writtenLabels.contains(folder)) {
                continue;
            }
            String stale = outputFileFor(outputDir, studyId, folder);
            if (io.exists(stale)) {
                io.delete(stale);
                removed.add(stale);
                log.warn("Removed stale {}: consent group {} produced no rows this run", stale, folder);
            }
        }
        return removed;
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
            Map<String, Long> idBySourceId,
            Map<Long, Consent> consentById,
            List<String> missingDataFiles) {

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
                    missingDataFiles.add(fileUri);
                    continue;
                }

                futures.add(executor.submit(() ->
                        processFile(fileUri, fileMappings, idBySourceId, consentById)));
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
                                   Map<String, Long> idBySourceId,
                                   Map<Long, Consent> consentById) {
        Map<String, List<AllConceptsRow>> rowsByConsent = new LinkedHashMap<>();
        Set<String> unmappedPatients = new LinkedHashSet<>();
        long rowsProcessed = 0;
        long rowsSkipped = 0;
        long malformedRows = 0;

        InputStream in = io.openInput(fileUri);
        try (Stream<List<String>> rows = delimitedReader.streamRows(in, DelimitedReader.COMMA)) {
            List<String> headers = null;
            for (List<String> row : (Iterable<List<String>>) rows::iterator) {
                if (headers == null) {
                    headers = row;
                    continue;
                }
                if (row.size() != headers.size()) {
                    malformedRows++;
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

                    Long hpdsId = idBySourceId.get(patientId);
                    if (hpdsId == null) {
                        unmappedPatients.add(patientId);
                        rowsSkipped++;
                        continue;
                    }

                    Consent consent = consentById.get(hpdsId);
                    if (consent == null) {
                        rowsSkipped++;
                        continue;
                    }

                    String cellValue = row.get(mapping.columnIndex()).trim();
                    AllConceptsRow conceptRow = buildConceptRow(String.valueOf(hpdsId), mapping, cellValue);
                    if (conceptRow != null) {
                        rowsByConsent.computeIfAbsent(consent.consentCode(), k -> new ArrayList<>())
                                .add(conceptRow);
                        rowsProcessed++;
                    }
                }
            }
        }

        if (malformedRows > 0) {
            log.warn("File {}: {} row(s) had a column count different from the header and were skipped",
                    fileUri, malformedRows);
        }
        log.info("File {} produced {} row(s), skipped {}", fileUri, rowsProcessed, rowsSkipped);
        return new FileResult(rowsByConsent, rowsProcessed, rowsSkipped, malformedRows, unmappedPatients);
    }

    private ConceptMapping.Parsed parseMappings(String mappingUri) {
        InputStream in = io.openInput(mappingUri);
        return ConceptMapping.parseWithStats(in, delimitedReader);
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
        // rowsProcessed == 0 never gets here: execute() throws before writing or removing any file.
        if (output.unmappedPatientCount() > 0) {
            report.warning("UNMAPPED_PATIENTS",
                    output.unmappedPatientCount() + " patient(s) in data files could not be resolved "
                            + "to an hpds_id via the participants table");
        }
        output.rowsPerConsent().forEach((consent, count) ->
                report.info("CONSENT_ROW_COUNT", consent + ": " + count + " row(s)"));
        output.staleFilesRemoved().forEach(file ->
                report.warning("STALE_OUTPUT_REMOVED",
                        "removed " + file + ": its consent group produced no rows this run"));
        // INFO rather than WARNING: a missing data file is tolerated by design (the mapping may name
        // files a study's decoded data does not include), but it should still be visible.
        output.missingDataFiles().forEach(file ->
                report.info("MISSING_DATA_FILE",
                        file + " is named in the mapping but does not exist; its concepts were not generated"));
        if (output.malformedRows() > 0) {
            report.warning("MALFORMED_ROWS", output.malformedRows()
                    + " data row(s) had a column count different from their file's header and were skipped");
        }
        // INFO rather than WARNING: a mapping file may deliberately leave a column's root node blank
        // to exclude it, so dropped rows are not by themselves a sign of lost data.
        if (output.mappingRowsDropped() > 0) {
            report.info("DROPPED_MAPPING_ROWS", output.mappingRowsDropped()
                    + " mapping row(s) were unusable (fewer than 4 columns, a key not 'file:int', "
                    + "or a blank root node) and were ignored");
        }
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
                .metric("outputFiles", output.outputFiles())
                .metric("staleFilesRemoved", output.staleFilesRemoved())
                .metric("mappingRowsDropped", output.mappingRowsDropped())
                .metric("malformedRows", output.malformedRows())
                .metric("missingDataFiles", output.missingDataFiles());
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
            List<String> outputFiles,
            List<String> staleFilesRemoved,
            long mappingRowsDropped,
            long malformedRows,
            List<String> missingDataFiles
    ) {
    }

    record FileResult(
            Map<String, List<AllConceptsRow>> rowsByConsent,
            long rowsProcessed,
            long rowsSkipped,
            long malformedRows,
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
