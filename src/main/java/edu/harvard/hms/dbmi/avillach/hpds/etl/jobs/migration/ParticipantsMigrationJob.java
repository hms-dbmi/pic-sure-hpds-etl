package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.BatchOps;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.Strings;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SingleConsentDataPopulateRdsParticipantsJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SstrPopulateRdsParticipantsJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.ConceptPaths;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * MIGRATION. Orchestrates the one-time move of every "ready" study's participant
 * identities into the {@code participants}/{@code consents}/{@code samples} RDS tables,
 * from a legacy export laid out as:
 *
 * <ul>
 *   <li>{@link ManagedInputsService}: provides the study list (columns "Study Abbreviated Name",
 *       "Study Identifier", and "Data is ready to process"), configured via
 *       {@code etl.managed-inputs.uri} or {@code --managed-inputs=<uri>}.</li>
 *   <li>{@code --data-folder}: a base URI (local or {@code s3://}) whose subfolders hold
 *       per-study data. The layout is:
 *       <ul>
 *         <li>{@code {base}/general/completed/GLOBAL_allConcepts_merged.csv} — shared across
 *             all studies (headerless, all-quoted AllConcepts format)</li>
 *         <li>{@code {base}/{abv_lowercase}/rawData/SSTR_*{studyId}*.txt} — optional dbGaP
 *             SSTR export (tab-delimited, found by prefix listing)</li>
 *         <li>{@code {base}/{abv_lowercase}/{ABV_UPPERCASE}_PatientMapping.v2.csv} — per-study
 *             patient mapping (headerless; columns: id, abv, legacy hpds id)</li>
 *       </ul>
 *       All files are staged (copied) into a local temporary directory organized by study id
 *       before processing.</li>
 * </ul>
 *
 * <p>For each ready study: if its sstr file exists, {@link SstrPopulateRdsParticipantsJob}
 * populates RDS directly and this job only resolves legacy-hpds-id &rarr; new-uuid pairs
 * for the mapping file (joining the patient mapping file's id against the sstr file's
 * {@code SUBJECT_ID}/{@code dbgap_subject_id} columns, since the mapping file's id may be
 * either). Otherwise, this job populates {@code participants}/{@code consents} itself by
 * joining the patient mapping file against {@code GLOBAL_allConcepts_merged.csv}
 * (source = study id; no samples, except {@code open_access-1000Genomes} where the subject
 * id doubles as the sample id).
 *
 * <p>Every processed study writes {@code {studyid}_hpds_id_mapping.csv} to the reports
 * directory: {@code old_hpds_id,new_uuid,common_dbgap_id} (the latter is the best
 * available cross-reference id -- a real dbgap id when resolved via an sstr file,
 * otherwise the patient mapping file's id verbatim).
 *
 * <p>Studies are processed independently: a data problem in one study (bad row, unmatched
 * id, the per-study sstr sub-job failing on bad data) is recorded as a failure for that
 * study only and does not stop the rest of the run -- surfaced as an output-validation
 * error so the run's exit code still reflects that something needs attention. An
 * infrastructure failure (RDS/S3 unreachable) aborts the whole run instead, since retrying
 * per-study would not help.
 *
 * <p>Enabled by {@code etl.jobs.participants-migration.enabled=true}, and also requires
 * {@code etl.jobs.sstr-populate-rds-participants.enabled=true} because it injects
 * {@link SstrPopulateRdsParticipantsJob}. Both are on the condition so disabling the sstr job
 * removes this bean rather than breaking context startup on a missing dependency.
 */
@Component
@ConditionalOnProperty(
        name = {"etl.jobs.participants-migration.enabled",
                "etl.jobs.sstr-populate-rds-participants.enabled"},
        havingValue = "true")
public class ParticipantsMigrationJob extends AbstractJob<ParticipantsMigrationJob.Output> {

    private static final String ALL_CONCEPTS_FILE_NAME = "GLOBAL_allConcepts_merged.csv";
    private static final String OPEN_ACCESS_1000_GENOMES_ABV = "open_access-1000Genomes";
    private static final String SSTR_COL_SUBJECT_ID = "SUBJECT_ID";
    private static final String SSTR_COL_DBGAP_SUBJECT_ID = "dbgap_subject_id";
    private static final int DEFAULT_BATCH_SIZE = 1000;

    /** {@code {studyId}.c{code}} -- a consented study. Group 1 is the consent code. */
    private static final Pattern CONSENT_VALUE_PATTERN = Pattern.compile("^.+\\.c(\\w+)$");

    private static final String CONCEPT_PATH_CONSENTS = ConceptPaths.CONSENTS;
    private static final String CONCEPT_PATH_STUDIES_CONSENTS_PREFIX = ConceptPaths.STUDIES_CONSENTS_PREFIX;

    /**
     * Marks a value as intended to name a consent group. Present without a full
     * {@link #CONSENT_VALUE_PATTERN} match ({@code phs000123.c}, {@code phs000123.c1x-2}) means a
     * malformed consented value, which is skipped and reported; absent means an open-access study,
     * which legitimately has no consent group.
     *
     * <p>Kept blunt on purpose: reading a malformed value as "public" would grant an unconsented
     * participant an open-access consent, so ambiguous values fall on the skip-and-report side.
     * An open-access study whose id contains ".c" is reported as unparseable rather than migrated.
     */
    private static final String CONSENT_GROUP_MARKER = ".c";

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final ManagedInputsService managedInputsService;
    private final ParticipantRepository participants;
    private final ConsentRepository consents;
    private final SampleRepository samples;
    private final TransactionTemplate tx;
    private final SstrPopulateRdsParticipantsJob sstrJob;
    private final JobExecutor executor;

    public ParticipantsMigrationJob(IoResolver io,
                                     DelimitedReader delimitedReader,
                                     ManagedInputsService managedInputsService,
                                     ParticipantRepository participants,
                                     ConsentRepository consents,
                                     SampleRepository samples,
                                     PlatformTransactionManager txManager,
                                     SstrPopulateRdsParticipantsJob sstrJob,
                                     JobExecutor executor) {
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.managedInputsService = managedInputsService;
        this.participants = participants;
        this.consents = consents;
        this.samples = samples;
        this.tx = new TransactionTemplate(txManager);
        this.sstrJob = sstrJob;
        this.executor = executor;
    }

    @Override
    public String name() {
        return "participants-migration";
    }

    @Override
    public JobType type() {
        return JobType.MIGRATION;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(
                        ParamSpec.required("data-folder",
                                "Base URI whose subfolders hold per-study data: "
                                        + "{base}/general/completed/GLOBAL_allConcepts_merged.csv, "
                                        + "{base}/{abv_lower}/rawData/SSTR_*{studyId}*.txt, "
                                        + "{base}/{abv_lower}/{ABV_UPPER}_PatientMapping.v2.csv "
                                        + "(local path or s3:// URI)",
                                "s3://hpds-migration/data"),
                        ParamSpec.optional("batch-size", "Rows per batch insert", "1000"),
                        ParamSpec.optional("study-filter",
                                "Comma-separated study ids to process; blank processes every ready study",
                                "phs000200,phs000280")),
                List.of("participants/consents/samples upserted in RDS for every ready study; "
                        + "one {studyid}_hpds_id_mapping.csv written per processed study to the reports dir"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        ctx.get("batch-size").ifPresent(bs -> {
            try {
                if (Integer.parseInt(bs) <= 0) {
                    report.error("BAD_BATCH_SIZE", "batch-size must be positive, got: " + bs, "--batch-size");
                }
            } catch (NumberFormatException e) {
                report.error("BAD_BATCH_SIZE", "batch-size must be an integer, got: " + bs, "--batch-size");
            }
        });
    }

    @Override
    protected Output execute(JobContext ctx) {
        String baseUri = ctx.require("data-folder");
        int batchSize = Integer.parseInt(ctx.get("batch-size", String.valueOf(DEFAULT_BATCH_SIZE)));

        List<ManagedInputRow> managedInputs = managedInputsService.read();

        Set<String> studyFilter = parseStudyFilter(ctx.get("study-filter", ""));
        if (!studyFilter.isEmpty()) {
            log.info("Study filter active: only processing {}", studyFilter);
        }

        Path stagingDir;
        try {
            stagingDir = Files.createTempDirectory("migration-staging");
        } catch (IOException e) {
            throw new InfrastructureException("Failed to create staging directory", e);
        }
        log.info("Staging data files to {}", stagingDir);

        try {
            String allConceptsUri = joinPath(joinPath(baseUri, "general/completed"), ALL_CONCEPTS_FILE_NAME);
            Path localAllConcepts = stagingDir.resolve(ALL_CONCEPTS_FILE_NAME);
            io.copyToLocal(allConceptsUri, localAllConcepts);
            ConsentData consentData = readAllConceptsCsv(localAllConcepts.toString());

            List<StudyResult> results = new ArrayList<>();
            for (ManagedInputRow row : managedInputs) {
                if (!row.isReady()) {
                    continue;
                }
                if (!studyFilter.isEmpty() && !studyFilter.contains(row.studyId())) {
                    continue;
                }
                try {
                    StagedStudyFiles staged = stageStudyFiles(baseUri, row, stagingDir);
                    results.add(processStudy(row, staged, consentData, batchSize, ctx));
                } catch (InfrastructureException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("Study '{}' failed during migration", row.studyId(), e);
                    results.add(StudyResult.failed(row.studyId(), row.abv(), e.getMessage()));
                }
            }
            return new Output(results, consentData);
        } finally {
            deleteRecursively(stagingDir);
        }
    }

    private StagedStudyFiles stageStudyFiles(String baseUri, ManagedInputRow row, Path stagingDir) {
        String studyId = row.studyId();
        String abvLower = row.abv().toLowerCase(Locale.ROOT);
        String abvUpper = row.abv().toUpperCase(Locale.ROOT);
        Path studyDir = stagingDir.resolve(studyId);

        String rawDataDir = joinPath(joinPath(baseUri, abvLower), "rawData");
        List<String> rawDataFiles = io.listFileNames(rawDataDir);
        List<String> sstrCandidates = rawDataFiles.stream()
                .filter(name -> name.startsWith("SSTR_") && name.contains(studyId) && name.endsWith(".txt"))
                .sorted()
                .toList();
        if (sstrCandidates.size() > 1) {
            log.warn("Study '{}': found {} SSTR candidates in {}: {}; using '{}'",
                    studyId, sstrCandidates.size(), rawDataDir, sstrCandidates, sstrCandidates.get(0));
        }
        String sstrFileName = sstrCandidates.isEmpty() ? null : sstrCandidates.get(0);

        Path localSstr = null;
        if (sstrFileName != null) {
            localSstr = studyDir.resolve(sstrFileName);
            io.copyToLocal(joinPath(rawDataDir, sstrFileName), localSstr);
            log.info("Study '{}': staged SSTR file '{}'", studyId, sstrFileName);
        }

        String pmFileName = abvUpper + "_PatientMapping.v2.csv";
        String pmUri = joinPath(joinPath(joinPath(baseUri, abvLower), "data"), pmFileName);
        Path localPm = studyDir.resolve(pmFileName);
        io.copyToLocal(pmUri, localPm);

        return new StagedStudyFiles(localSstr, localPm);
    }

    private StudyResult processStudy(ManagedInputRow row, StagedStudyFiles staged, ConsentData consentData,
                                      int batchSize, JobContext ctx) {
        String studyId = row.studyId();
        boolean hasSstr = staged.sstrFile() != null;
        String patientMappingPath = staged.patientMappingFile().toString();
        List<PatientMappingRow> patientMappings = readPatientMapping(patientMappingPath);
        if (patientMappings.isEmpty()) {
            throw new DataException("Patient mapping for study " + studyId + " yielded no subjects; "
                    + "refusing to proceed. Check that the file is complete.");
        }

        if (hasSstr) {
            String sstrPath = staged.sstrFile().toString();
            String runId = ctx.runId() + "-" + studyId + "-sstr";
            JobResult sstrResult = executor.run(sstrJob,
                    Map.of("input", sstrPath, "study-id", studyId,
                            "batch-size", String.valueOf(batchSize)),
                    runId);
            if (!sstrResult.isSuccess()) {
                String message = "sstr job failed for study " + studyId + ": " + sstrResult.getErrorMessage();
                if (sstrResult.getExitCode() == ExitCode.INFRASTRUCTURE_ERROR) {
                    throw new InfrastructureException(message);
                }
                throw new DataException(message);
            }
            if (sstrResult.getExitCode() == ExitCode.SUCCESS_WITH_WARNINGS) {
                log.warn("Study '{}': sstr sub-job completed with warnings: {}",
                        studyId, sstrResult.getOutputValidation().getIssues());
            }
            SstrMappingResult sstrMapping = buildSstrMapping(studyId, sstrPath, patientMappings, batchSize);
            writeMappingFile(ctx, studyId, sstrMapping.mappingRows());
            if (!sstrMapping.skippedHpdsIds().isEmpty()) {
                log.warn("Study '{}': {} of {} patient mapping row(s) could not be resolved via sstr",
                        studyId, sstrMapping.skippedHpdsIds().size(), patientMappings.size());
            }
            return StudyResult.success(studyId, row.abv(), true, sstrMapping.mappingRows().size(), sstrMapping.skippedHpdsIds());
        }

        List<PatientMappingRow> matched = new ArrayList<>();
        List<PatientMappingRow> unmatched = new ArrayList<>();
        for (PatientMappingRow pm : patientMappings) {
            if (consentData.codeByHpdsId().containsKey(pm.oldHpdsId())) {
                matched.add(pm);
            } else {
                unmatched.add(pm);
            }
        }

        List<String> skippedHpdsIds = unmatched.stream().map(PatientMappingRow::oldHpdsId).toList();
        if (!unmatched.isEmpty()) {
            writeUnmatchedReport(ctx, row.abv(), unmatched);
            log.warn("Study '{}': {} of {} patient mapping row(s) had no entry in {}; written to unmatched report",
                    studyId, unmatched.size(), patientMappings.size(), ALL_CONCEPTS_FILE_NAME);
        }

        if (matched.isEmpty()) {
            writeMappingFile(ctx, studyId, List.of());
            return StudyResult.success(studyId, row.abv(), false, 0, skippedHpdsIds);
        }

        DirectLoad load = populateDirectly(studyId, row.abv(), matched, consentData, batchSize);
        writeMappingFile(ctx, studyId, load.mappingRows());
        return StudyResult.success(studyId, row.abv(), false, load.mappingRows().size(),
                Stream.concat(skippedHpdsIds.stream(), load.skippedHpdsIdsWithNoConsent().stream()).toList());
    }

    /**
     * For sstr-driven studies: {@link SstrPopulateRdsParticipantsJob} already populated
     * RDS keyed by {@code dbgap_subject_id}. This resolves each patient-mapping row's id
     * (which may be a dbgap id or the study's own subject id) to that same dbgap id, so the
     * mapping file can report the new uuid RDS actually assigned.
     */
    private SstrMappingResult buildSstrMapping(String studyId, String sstrPath,
                                              List<PatientMappingRow> patientMappings, int batchSize) {
        Map<String, String> subjectIdToDbgapId = new HashMap<>();
        Set<String> dbgapIds = new LinkedHashSet<>();

        InputStream in = io.openInput(sstrPath);
        try (Stream<Map<String, String>> stream = delimitedReader.stream(in, DelimitedReader.TAB)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                String dbgapId = Strings.trimToNull(row.get(SSTR_COL_DBGAP_SUBJECT_ID));
                if (dbgapId == null) {
                    continue;
                }
                dbgapIds.add(dbgapId);
                String subjectId = Strings.trimToNull(row.get(SSTR_COL_SUBJECT_ID));
                if (subjectId != null) {
                    subjectIdToDbgapId.put(subjectId, dbgapId);
                }
            }
        }

        Map<String, UUID> uuidByDbgapId = participants.findUuidsChunked(
                new ArrayList<>(dbgapIds), SstrPopulateRdsParticipantsJob.SOURCE, batchSize);

        List<MappingRow> rows = new ArrayList<>();
        List<String> skippedHpdsIds = new ArrayList<>();
        for (PatientMappingRow pm : patientMappings) {
            String dbgapId = dbgapIds.contains(pm.id()) ? pm.id() : subjectIdToDbgapId.get(pm.id());
            if (dbgapId == null) {
                log.warn("Study '{}': patient mapping id '{}' (old hpds id {}) not found in sstr file; skipping",
                        studyId, pm.id(), pm.oldHpdsId());
                skippedHpdsIds.add(pm.oldHpdsId());
                continue;
            }
            UUID uuid = uuidByDbgapId.get(dbgapId);
            if (uuid == null) {
                log.warn("Study '{}': no participant uuid found for dbgap id '{}' (old hpds id {}); skipping",
                        studyId, dbgapId, pm.oldHpdsId());
                skippedHpdsIds.add(pm.oldHpdsId());
                continue;
            }
            rows.add(new MappingRow(pm.oldHpdsId(), uuid, dbgapId));
        }
        return new SstrMappingResult(rows, skippedHpdsIds);
    }

    private record SstrMappingResult(List<MappingRow> mappingRows, List<String> skippedHpdsIds) {}

    /**
     * For non-sstr studies: populates participants/consents (and, for
     * {@code open_access-1000Genomes}, samples) directly from the join of the patient
     * mapping file against {@code GLOBAL_allConcepts_merged.csv}, all in one transaction
     * for this study.
     */
    private DirectLoad populateDirectly(String studyId, String abv, List<PatientMappingRow> patientMappings,
                                         ConsentData consentData, int batchSize) {
        return tx.execute(status -> {
            Set<String> subjectIds = new LinkedHashSet<>();
            for (PatientMappingRow pm : patientMappings) {
                subjectIds.add(pm.id());
            }

            // resolveOrCreate rather than findUuids + batchUpsert; see ParticipantRepository.
            Map<String, UUID> uuidBySubject =
                    participants.resolveOrCreate(subjectIds, studyId, batchSize).uuidsBySourceId();

            boolean populateSamples = OPEN_ACCESS_1000_GENOMES_ABV.equals(abv);
            List<Consent> consentRows = new ArrayList<>();
            List<Sample> sampleRows = new ArrayList<>();
            List<MappingRow> mappingRows = new ArrayList<>();
            List<String> skippedHpdsIds = new ArrayList<>();

            for (PatientMappingRow pm : patientMappings) {
                String code = consentData.codeByHpdsId().get(pm.oldHpdsId());
                if (code == null) {
                    log.warn("Study '{}': no consent found in {} for old hpds id '{}'; skipping subject '{}'",
                            studyId, ALL_CONCEPTS_FILE_NAME, pm.oldHpdsId(), pm.id());
                    skippedHpdsIds.add(pm.oldHpdsId());
                    continue;
                }
                UUID uuid = uuidBySubject.get(pm.id());
                String abbreviation = consentData.abbreviationByHpdsId().getOrDefault(pm.oldHpdsId(), "");
                consentRows.add(new Consent(uuid, studyId, code, abbreviation));
                if (populateSamples) {
                    sampleRows.add(new Sample(uuid, pm.id(), studyId));
                }
                mappingRows.add(new MappingRow(pm.oldHpdsId(), uuid, pm.id()));
            }
            BatchOps.upsertInChunks(consents::batchUpsert, consentRows, batchSize);
            if (!sampleRows.isEmpty()) {
                BatchOps.upsertInChunks(samples::batchUpsert, sampleRows, batchSize);
            }
            return new DirectLoad(mappingRows, skippedHpdsIds);
        });
    }

    /**
     * Reads the shared {@code GLOBAL_allConcepts_merged.csv} (headerless AllConcepts format:
     * hpds_id, concept_path, numeric_value, non_numeric_value, timestamp, all quoted) and
     * extracts:
     * <ul>
     *   <li>legacy-hpds-id &rarr; consent code, from {@code µ_consentsµ} rows whose
     *       non-numeric value is {@code {studyId}.c{code}}</li>
     *   <li>legacy-hpds-id &rarr; consent abbreviation, from individual
     *       {@code µ_studies_consentsµ{study_id}µ{abbreviation}µ} rows</li>
     * </ul>
     *
     * <p>Two shapes of consent value are legitimate:
     * <ul>
     *   <li>a consented study writes {@code {studyId}.c{code}}, e.g. {@code phs000123.c1}, and the
     *       code is the suffix;</li>
     *   <li>an open-access study has no consent group and therefore no suffix — its consent value
     *       is the bare study id. Those rows take
     *       {@link SingleConsentDataPopulateRdsParticipantsJob#PUBLIC_CONSENT_CODE}.</li>
     * </ul>
     */
    private ConsentData readAllConceptsCsv(String path) {
        Map<String, String> hpdsIdToCode = new HashMap<>();
        Map<String, String> hpdsIdToAbbreviation = new HashMap<>();
        long publicRows = 0;
        long unparseableRows = 0;
        long totalRows = 0;
        long shortRows = 0;

        InputStream in = io.openInput(path);
        try (Stream<List<String>> stream = delimitedReader.streamRows(in, DelimitedReader.COMMA)) {
            for (List<String> row : (Iterable<List<String>>) stream::iterator) {
                if (row.size() < 4) {
                    shortRows++;
                    continue;
                }
                totalRows++;
                String hpdsId = Strings.trimToNull(row.get(0));
                String conceptPath = Strings.trimToNull(row.get(1));
                String nonNumericValue = Strings.trimToNull(row.get(3));
                if (hpdsId == null || conceptPath == null) {
                    continue;
                }

                if (CONCEPT_PATH_CONSENTS.equals(conceptPath)) {
                    if (nonNumericValue == null) {
                        continue;
                    }
                    Matcher m = CONSENT_VALUE_PATTERN.matcher(nonNumericValue);
                    if (m.matches()) {
                        String code = m.group(1);
                        String prev = hpdsIdToCode.put(hpdsId, code);
                        if (prev != null && !prev.equals(code)) {
                            log.warn("{}: hpds id '{}' already mapped to consent code '{}'; overwritten with '{}' "
                                    + "(from value '{}')", ALL_CONCEPTS_FILE_NAME, hpdsId, prev, code, nonNumericValue);
                        }
                        continue;
                    }
                    if (nonNumericValue.contains(CONSENT_GROUP_MARKER)) {
                        log.warn("{}: consent value '{}' for hpds id '{}' names a consent group that could not be "
                                        + "parsed; skipping", ALL_CONCEPTS_FILE_NAME, nonNumericValue, hpdsId);
                        unparseableRows++;
                        continue;
                    }
                    String prev = hpdsIdToCode.put(hpdsId, SingleConsentDataPopulateRdsParticipantsJob.PUBLIC_CONSENT_CODE);
                    if (prev != null && !prev.equals(SingleConsentDataPopulateRdsParticipantsJob.PUBLIC_CONSENT_CODE)) {
                        log.warn("{}: hpds id '{}' already mapped to consent code '{}'; overwritten with public code "
                                + "(from value '{}')", ALL_CONCEPTS_FILE_NAME, hpdsId, prev, nonNumericValue);
                    }
                    publicRows++;
                } else if (conceptPath.startsWith(CONCEPT_PATH_STUDIES_CONSENTS_PREFIX)) {
                    String[] parts = conceptPath.split("µ");
                    if (parts.length >= 4 && !parts[3].isEmpty()) {
                        String abbrev = parts[3];
                        String prev = hpdsIdToAbbreviation.put(hpdsId, abbrev);
                        if (prev != null && !prev.equals(abbrev)) {
                            log.warn("{}: hpds id '{}' already mapped to abbreviation '{}'; overwritten with '{}'",
                                    ALL_CONCEPTS_FILE_NAME, hpdsId, prev, abbrev);
                        }
                    }
                }
            }
        }

        if (shortRows > 0) {
            log.warn("{}: {} row(s) had fewer than 4 columns and were skipped", ALL_CONCEPTS_FILE_NAME, shortRows);
        }
        if (totalRows == 0 && shortRows > 0) {
            throw new DataException(ALL_CONCEPTS_FILE_NAME + ": every row had fewer than 4 columns (" + shortRows
                    + " total); the file may be malformed or use the wrong delimiter");
        }
        log.info("{}: read {} row(s), extracted {} consent code(s) and {} abbreviation(s)",
                ALL_CONCEPTS_FILE_NAME, totalRows, hpdsIdToCode.size(), hpdsIdToAbbreviation.size());
        if (publicRows > 0) {
            log.info("{}: {} row(s) had no consent-group suffix and were read as open access (code '{}')",
                    ALL_CONCEPTS_FILE_NAME, publicRows, SingleConsentDataPopulateRdsParticipantsJob.PUBLIC_CONSENT_CODE);
        }
        if (unparseableRows > 0) {
            log.warn("{}: {} row(s) were skipped as unparseable", ALL_CONCEPTS_FILE_NAME, unparseableRows);
        }
        return new ConsentData(hpdsIdToCode, hpdsIdToAbbreviation, publicRows, unparseableRows);
    }

    private List<PatientMappingRow> readPatientMapping(String path) {
        List<PatientMappingRow> rows = new ArrayList<>();
        InputStream in = io.openInput(path);
        try (Stream<List<String>> stream = delimitedReader.streamRows(in, DelimitedReader.COMMA)) {
            for (List<String> row : (Iterable<List<String>>) stream::iterator) {
                if (row.size() < 3) {
                    throw new DataException("Patient mapping row has fewer than 3 columns: " + row);
                }
                String id = Strings.trimToNull(row.get(0));
                String oldHpdsId = Strings.trimToNull(row.get(2));
                if (id == null || oldHpdsId == null) {
                    throw new DataException("Patient mapping row has a blank id or hpds id: " + row);
                }
                rows.add(new PatientMappingRow(id, oldHpdsId));
            }
        }
        return rows;
    }

    private void writeMappingFile(JobContext ctx, String studyId, List<MappingRow> mappingRows) {
        StringBuilder csv = new StringBuilder("old_hpds_id,new_uuid,common_dbgap_id\n");
        for (MappingRow row : mappingRows) {
            csv.append(Strings.csvQuote(row.oldHpdsId())).append(',').append(row.newUuid()).append(',')
                    .append(Strings.csvQuote(row.commonDbgapId())).append('\n');
        }
        try {
            Path dir = ctx.reportsDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(studyId + "_hpds_id_mapping.csv"), csv.toString());
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write mapping file for study " + studyId, e);
        }
    }

    private void writeUnmatchedReport(JobContext ctx, String abv, List<PatientMappingRow> unmatched) {
        StringBuilder csv = new StringBuilder("id,old_hpds_id\n");
        for (PatientMappingRow pm : unmatched) {
            csv.append(Strings.csvQuote(pm.id())).append(',').append(Strings.csvQuote(pm.oldHpdsId())).append('\n');
        }
        try {
            Path dir = ctx.reportsDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(abv + "_unmatched_mappings.csv"), csv.toString());
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write unmatched report for " + abv, e);
        }
    }

    private static Set<String> parseStudyFilter(String raw) {
        Set<String> filter = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                filter.add(id);
            }
        }
        return filter;
    }

    private static String joinPath(String folder, String fileName) {
        return folder.endsWith("/") ? folder + fileName : folder + "/" + fileName;
    }

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(ParticipantsMigrationJob.class);

    private static void deleteRecursively(Path dir) {
        try {
            org.springframework.util.FileSystemUtils.deleteRecursively(dir);
        } catch (IOException e) {
            LOG.warn("Failed to clean up staging directory {}: {}", dir, e.getMessage());
        }
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.studyResults().isEmpty()) {
            report.error("NO_READY_STUDIES", "No studies marked ready to process were found");
        }
        for (StudyResult result : output.studyResults()) {
            if (result.success()) {
                report.info("STUDY_MIGRATED", result.studyId() + " (" + (result.usedSstr() ? "sstr" : "direct")
                        + "): " + result.mappingRowCount() + " mapping row(s)");
                // Absent from the migration without anything failing, so report it as a warning
                // to bring the run back UNSTABLE rather than clean.
                if (!result.skippedHpdsIdsWithNoConsent().isEmpty()) {
                    List<String> sample = result.skippedHpdsIdsWithNoConsent().stream().limit(10).toList();
                    report.warning("SUBJECTS_WITHOUT_CONSENT", result.studyId() + ": "
                            + result.skippedHpdsIdsWithNoConsent().size() + " subject(s) had no row in "
                            + ALL_CONCEPTS_FILE_NAME + " and were NOT migrated. First: " + sample);
                }
            } else {
                report.error("STUDY_FAILED", result.studyId() + ": " + result.errorMessage());
            }
        }

        if (output.consentData().unparseableRows() > 0) {
            report.warning("UNPARSEABLE_CONSENT_VALUES", output.consentData().unparseableRows()
                    + " row(s) in " + ALL_CONCEPTS_FILE_NAME + " had a consent group that could not be read; "
                    + "any subject relying on them was not migrated");
        }
        if (output.consentData().publicRows() > 0) {
            report.info("OPEN_ACCESS_CONSENT_VALUES", output.consentData().publicRows()
                    + " row(s) in " + ALL_CONCEPTS_FILE_NAME + " had no consent-group suffix and were read as "
                    + "open access (code '" + SingleConsentDataPopulateRdsParticipantsJob.PUBLIC_CONSENT_CODE + "')");
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        long succeeded = output.studyResults().stream().filter(StudyResult::success).count();
        long failed = output.studyResults().size() - succeeded;
        long skipped = output.studyResults().stream()
                .mapToLong(r -> r.skippedHpdsIdsWithNoConsent().size())
                .sum();
        // Cast so every count in this report is a long. Jackson writes int 1 and long 1
        // identically; this only matters to callers reading the metrics map in-process.
        builder.metric("readyStudies", (long) output.studyResults().size())
                .metric("succeededStudies", succeeded)
                .metric("failedStudies", failed)
                .metric("failedStudyIds", output.studyResults().stream()
                        .filter(r -> !r.success())
                        .map(StudyResult::studyId)
                        .toList())
                .metric("subjectsWithoutConsent", skipped)
                .metric("openAccessConsentRows", output.consentData().publicRows())
                .metric("unparseableConsentRows", output.consentData().unparseableRows());
    }

    private record StagedStudyFiles(Path sstrFile, Path patientMappingFile) {
    }

    private record PatientMappingRow(String id, String oldHpdsId) {
    }

    private record MappingRow(String oldHpdsId, UUID newUuid, String commonDbgapId) {
    }

    /** What {@link #populateDirectly} loaded, plus what it could not. */
    private record DirectLoad(List<MappingRow> mappingRows, List<String> skippedHpdsIdsWithNoConsent) {
    }

    /**
     * Consent data extracted from the shared {@code GLOBAL_allConcepts_merged.csv}.
     *
     * @param codeByHpdsId           legacy hpds id to consent code (from {@code µ_consentsµ} rows)
     * @param abbreviationByHpdsId   legacy hpds id to consent abbreviation (from individual
     *                               {@code µ_studies_consentsµ} rows)
     * @param publicRows             rows with no {@code .c} suffix, read as open access
     * @param unparseableRows        rows with a {@code .c} marker but no usable code; these were skipped
     */
    public record ConsentData(Map<String, String> codeByHpdsId, Map<String, String> abbreviationByHpdsId,
                               long publicRows, long unparseableRows) {
    }

    /** Outcome of migrating one study; inspected by {@link #validateOutput}/{@link #report}. */
    public record StudyResult(String studyId, String abv, boolean usedSstr, boolean success, String errorMessage,
                               int mappingRowCount, List<String> skippedHpdsIdsWithNoConsent) {
        static StudyResult success(String studyId, String abv, boolean usedSstr, int mappingRowCount,
                                    List<String> skippedHpdsIdsWithNoConsent) {
            return new StudyResult(studyId, abv, usedSstr, true, null, mappingRowCount,
                    skippedHpdsIdsWithNoConsent);
        }

        static StudyResult failed(String studyId, String abv, String errorMessage) {
            return new StudyResult(studyId, abv, false, false, errorMessage, 0, List.of());
        }
    }

    /** Immutable result of {@link #execute}; inspected by {@link #validateOutput}/{@link #report}. */
    public record Output(List<StudyResult> studyResults, ConsentData consentData) {
    }
}
