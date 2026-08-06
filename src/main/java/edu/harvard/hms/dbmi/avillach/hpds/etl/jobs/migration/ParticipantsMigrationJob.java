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
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SstrPopulateRdsParticipantsJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * MIGRATION. Orchestrates the one-time move of every "ready" study's participant
 * identities into the {@code participants}/{@code consents}/{@code samples} RDS tables,
 * from a legacy export laid out as:
 *
 * <ul>
 *   <li>{@code --managed-inputs}: a CSV of studies with columns "Study Abbreviated Name",
 *       "Study Identifier", and "Data is ready to process".</li>
 *   <li>{@code --data-folder}: a folder (local or {@code s3://}) containing, for every
 *       study: an optional {@code {studyid}_sstr.tsv} (dbGaP SSTR export, tab-delimited,
 *       same shape {@link SstrPopulateRdsParticipantsJob} reads, plus a {@code SUBJECT_ID}
 *       column), a single {@code consents.csv} shared by every study (headerless, quoted,
 *       columns: legacy hpds id, consent value formatted {@code {studyid}.c{code}}), and a
 *       headerless {@code {ABV}_PatientMapping.v2.csv} per study (uppercased abv; columns:
 *       dbgap id or the study's own subject id, abv, legacy hpds id).</li>
 * </ul>
 *
 * <p>For each ready study: if its sstr file exists, {@link SstrPopulateRdsParticipantsJob}
 * populates RDS directly and this job only resolves legacy-hpds-id &rarr; new-uuid pairs
 * for the mapping file (joining the patient mapping file's id against the sstr file's
 * {@code SUBJECT_ID}/{@code dbgap_subject_id} columns, since the mapping file's id may be
 * either). Otherwise, this job populates {@code participants}/{@code consents} itself by
 * joining the patient mapping file against {@code consents.csv} (source = study id; no
 * samples, except {@code open_access-1000Genomes} where the subject id doubles as the
 * sample id).
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
 */
@Component
public class ParticipantsMigrationJob extends AbstractJob<ParticipantsMigrationJob.Output> {

    private static final String COL_ABV = "Study Abbreviated Name";
    private static final String COL_STUDY_ID = "Study Identifier";
    private static final String COL_IS_READY = "Data is ready to process";
    private static final String CONSENTS_FILE_NAME = "consents.csv";
    private static final String OPEN_ACCESS_1000_GENOMES_ABV = "open_access-1000Genomes";
    private static final String SSTR_COL_SUBJECT_ID = "SUBJECT_ID";
    private static final String SSTR_COL_DBGAP_SUBJECT_ID = "dbgap_subject_id";
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final Pattern CONSENT_VALUE_PATTERN = Pattern.compile("^.+\\.c(\\w+)$");

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final ParticipantRepository participants;
    private final ConsentRepository consents;
    private final SampleRepository samples;
    private final TransactionTemplate tx;
    private final SstrPopulateRdsParticipantsJob sstrJob;
    private final JobExecutor executor;

    public ParticipantsMigrationJob(IoResolver io,
                                     DelimitedReader delimitedReader,
                                     ParticipantRepository participants,
                                     ConsentRepository consents,
                                     SampleRepository samples,
                                     PlatformTransactionManager txManager,
                                     SstrPopulateRdsParticipantsJob sstrJob,
                                     JobExecutor executor) {
        this.io = io;
        this.delimitedReader = delimitedReader;
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
                        ParamSpec.required("managed-inputs",
                                "CSV of studies with columns 'Study Abbreviated Name', 'Study Identifier', "
                                        + "'Data is ready to process' (local path or s3:// URI)",
                                "/data/managed_inputs.csv"),
                        ParamSpec.required("data-folder",
                                "Folder containing {studyid}_sstr.tsv files, a shared consents.csv, and "
                                        + "{ABV}_PatientMapping.v2.csv files per study (local path or s3:// URI)",
                                "s3://hpds-migration/2026-08-06"),
                        ParamSpec.optional("batch-size", "Rows per batch insert", "1000")),
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
        String managedInputsPath = ctx.require("managed-inputs");
        String dataFolder = ctx.require("data-folder");
        int batchSize = Integer.parseInt(ctx.get("batch-size", String.valueOf(DEFAULT_BATCH_SIZE)));

        List<ManagedInputRow> managedInputs = readManagedInputs(managedInputsPath);
        Map<String, String> hpdsIdToConsentCode = readConsentsCsv(joinPath(dataFolder, CONSENTS_FILE_NAME));

        List<StudyResult> results = new ArrayList<>();
        for (ManagedInputRow row : managedInputs) {
            if (!row.isReady()) {
                continue;
            }
            try {
                results.add(processStudy(row, dataFolder, hpdsIdToConsentCode, batchSize, ctx));
            } catch (InfrastructureException e) {
                // Retrying per-study would not help if RDS/S3 itself is unreachable.
                throw e;
            } catch (Exception e) {
                log.error("Study '{}' failed during migration", row.studyId(), e);
                results.add(StudyResult.failed(row.studyId(), row.abv(), e.getMessage()));
            }
        }
        return new Output(results);
    }

    private StudyResult processStudy(ManagedInputRow row, String dataFolder, Map<String, String> hpdsIdToConsentCode,
                                      int batchSize, JobContext ctx) {
        String studyId = row.studyId();
        String sstrPath = joinPath(dataFolder, studyId + "_sstr.tsv");
        boolean hasSstr = io.exists(sstrPath);
        String patientMappingPath = joinPath(dataFolder, row.abv().toUpperCase() + "_PatientMapping.v2.csv");
        List<PatientMappingRow> patientMappings = readPatientMapping(patientMappingPath);

        List<MappingRow> mappingRows;
        if (hasSstr) {
            String runId = ctx.runId() + "-" + studyId + "-sstr";
            JobResult sstrResult = executor.run(sstrJob, Map.of("input", sstrPath, "study-id", studyId), runId);
            if (!sstrResult.isSuccess()) {
                String message = "sstr job failed for study " + studyId + ": " + sstrResult.getErrorMessage();
                if (sstrResult.getExitCode() == ExitCode.INFRASTRUCTURE_ERROR) {
                    throw new InfrastructureException(message);
                }
                throw new DataException(message);
            }
            mappingRows = buildSstrMapping(studyId, sstrPath, patientMappings);
        } else {
            mappingRows = populateDirectly(studyId, row.abv(), patientMappings, hpdsIdToConsentCode, batchSize);
        }

        writeMappingFile(ctx, studyId, mappingRows);
        return StudyResult.success(studyId, row.abv(), hasSstr, mappingRows.size());
    }

    /**
     * For sstr-driven studies: {@link SstrPopulateRdsParticipantsJob} already populated
     * RDS keyed by {@code dbgap_subject_id}. This resolves each patient-mapping row's id
     * (which may be a dbgap id or the study's own subject id) to that same dbgap id, so the
     * mapping file can report the new uuid RDS actually assigned.
     */
    private List<MappingRow> buildSstrMapping(String studyId, String sstrPath, List<PatientMappingRow> patientMappings) {
        Map<String, String> subjectIdToDbgapId = new HashMap<>();
        Set<String> dbgapIds = new LinkedHashSet<>();

        InputStream in = io.openInput(sstrPath);
        try (Stream<Map<String, String>> stream = delimitedReader.stream(in, DelimitedReader.TAB)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                String dbgapId = trimToNull(row.get(SSTR_COL_DBGAP_SUBJECT_ID));
                if (dbgapId == null) {
                    continue;
                }
                dbgapIds.add(dbgapId);
                String subjectId = trimToNull(row.get(SSTR_COL_SUBJECT_ID));
                if (subjectId != null) {
                    subjectIdToDbgapId.put(subjectId, dbgapId);
                }
            }
        }

        Map<String, UUID> uuidByDbgapId = participants.findUuids(dbgapIds, SstrPopulateRdsParticipantsJob.SOURCE);

        List<MappingRow> rows = new ArrayList<>();
        for (PatientMappingRow pm : patientMappings) {
            String dbgapId = dbgapIds.contains(pm.id()) ? pm.id() : subjectIdToDbgapId.get(pm.id());
            if (dbgapId == null) {
                log.warn("Study '{}': patient mapping id '{}' (old hpds id {}) not found in sstr file; skipping",
                        studyId, pm.id(), pm.oldHpdsId());
                continue;
            }
            UUID uuid = uuidByDbgapId.get(dbgapId);
            if (uuid == null) {
                log.warn("Study '{}': no participant uuid found for dbgap id '{}' (old hpds id {}); skipping",
                        studyId, dbgapId, pm.oldHpdsId());
                continue;
            }
            rows.add(new MappingRow(pm.oldHpdsId(), uuid, dbgapId));
        }
        return rows;
    }

    /**
     * For non-sstr studies: populates participants/consents (and, for
     * {@code open_access-1000Genomes}, samples) directly from the join of the patient
     * mapping file against {@code consents.csv}, all in one transaction for this study.
     */
    private List<MappingRow> populateDirectly(String studyId, String abv, List<PatientMappingRow> patientMappings,
                                               Map<String, String> hpdsIdToConsentCode, int batchSize) {
        return tx.execute(status -> {
            Set<String> subjectIds = new LinkedHashSet<>();
            for (PatientMappingRow pm : patientMappings) {
                subjectIds.add(pm.id());
            }

            Map<String, UUID> uuidBySubject = new LinkedHashMap<>(participants.findUuids(subjectIds, studyId));
            List<Participant> newParticipants = new ArrayList<>();
            for (String id : subjectIds) {
                uuidBySubject.computeIfAbsent(id, k -> {
                    UUID uuid = UUID.randomUUID();
                    newParticipants.add(new Participant(uuid, k, studyId));
                    return uuid;
                });
            }
            batchUpsertInChunks(participants::batchUpsert, newParticipants, batchSize);

            boolean populateSamples = OPEN_ACCESS_1000_GENOMES_ABV.equals(abv);
            List<Consent> consentRows = new ArrayList<>();
            List<Sample> sampleRows = new ArrayList<>();
            List<MappingRow> mappingRows = new ArrayList<>();

            for (PatientMappingRow pm : patientMappings) {
                String code = hpdsIdToConsentCode.get(pm.oldHpdsId());
                if (code == null) {
                    log.warn("Study '{}': no consent found in consents.csv for old hpds id '{}'; skipping subject '{}'",
                            studyId, pm.oldHpdsId(), pm.id());
                    continue;
                }
                UUID uuid = uuidBySubject.get(pm.id());
                consentRows.add(new Consent(uuid, studyId, code, ""));
                if (populateSamples) {
                    sampleRows.add(new Sample(uuid, pm.id(), studyId));
                }
                mappingRows.add(new MappingRow(pm.oldHpdsId(), uuid, pm.id()));
            }
            batchUpsertInChunks(consents::batchUpsert, consentRows, batchSize);
            if (!sampleRows.isEmpty()) {
                batchUpsertInChunks(samples::batchUpsert, sampleRows, batchSize);
            }
            return mappingRows;
        });
    }

    private List<ManagedInputRow> readManagedInputs(String path) {
        List<ManagedInputRow> rows = new ArrayList<>();
        InputStream in = io.openInput(path);
        try (Stream<Map<String, String>> stream = delimitedReader.stream(in, DelimitedReader.COMMA)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                String abv = trimToNull(row.get(COL_ABV));
                String studyId = trimToNull(row.get(COL_STUDY_ID));
                if (abv == null || studyId == null) {
                    log.warn("managed-inputs: skipping row with a blank '{}' or '{}': {}", COL_ABV, COL_STUDY_ID, row);
                    continue;
                }
                rows.add(new ManagedInputRow(abv, studyId, parseReady(row.get(COL_IS_READY))));
            }
        }
        return rows;
    }

    private Map<String, String> readConsentsCsv(String path) {
        Map<String, String> hpdsIdToCode = new HashMap<>();
        InputStream in = io.openInput(path);
        try (Stream<List<String>> stream = delimitedReader.streamRows(in, DelimitedReader.COMMA)) {
            for (List<String> row : (Iterable<List<String>>) stream::iterator) {
                if (row.size() < 2) {
                    continue;
                }
                String hpdsId = trimToNull(row.get(0));
                String consentValue = trimToNull(row.get(1));
                if (hpdsId == null || consentValue == null) {
                    continue;
                }
                Matcher m = CONSENT_VALUE_PATTERN.matcher(consentValue);
                if (!m.matches()) {
                    log.warn("{}: could not parse consent value '{}' for hpds id '{}'; skipping",
                            CONSENTS_FILE_NAME, consentValue, hpdsId);
                    continue;
                }
                hpdsIdToCode.put(hpdsId, m.group(1));
            }
        }
        return hpdsIdToCode;
    }

    private List<PatientMappingRow> readPatientMapping(String path) {
        List<PatientMappingRow> rows = new ArrayList<>();
        InputStream in = io.openInput(path);
        try (Stream<List<String>> stream = delimitedReader.streamRows(in, DelimitedReader.COMMA)) {
            for (List<String> row : (Iterable<List<String>>) stream::iterator) {
                if (row.size() < 3) {
                    throw new DataException("Patient mapping row has fewer than 3 columns: " + row);
                }
                String id = trimToNull(row.get(0));
                String oldHpdsId = trimToNull(row.get(2));
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
            csv.append(row.oldHpdsId()).append(',').append(row.newUuid()).append(',')
                    .append(row.commonDbgapId()).append('\n');
        }
        try {
            Path dir = ctx.reportsDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(studyId + "_hpds_id_mapping.csv"), csv.toString());
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write mapping file for study " + studyId, e);
        }
    }

    private static String joinPath(String folder, String fileName) {
        return folder.endsWith("/") ? folder + fileName : folder + "/" + fileName;
    }

    private static boolean parseReady(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim();
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1");
    }

    private static <T> long batchUpsertInChunks(ToIntFunction<List<T>> upsert, List<T> items, int batchSize) {
        long inserted = 0;
        for (int i = 0; i < items.size(); i += batchSize) {
            inserted += upsert.applyAsInt(items.subList(i, Math.min(i + batchSize, items.size())));
        }
        return inserted;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
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
            } else {
                report.error("STUDY_FAILED", result.studyId() + ": " + result.errorMessage());
            }
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        long succeeded = output.studyResults().stream().filter(StudyResult::success).count();
        long failed = output.studyResults().size() - succeeded;
        builder.metric("readyStudies", output.studyResults().size())
                .metric("succeededStudies", succeeded)
                .metric("failedStudies", failed)
                .metric("failedStudyIds", output.studyResults().stream()
                        .filter(r -> !r.success())
                        .map(StudyResult::studyId)
                        .toList());
    }

    private record ManagedInputRow(String abv, String studyId, boolean isReady) {
    }

    private record PatientMappingRow(String id, String oldHpdsId) {
    }

    private record MappingRow(String oldHpdsId, UUID newUuid, String commonDbgapId) {
    }

    /** Outcome of migrating one study; inspected by {@link #validateOutput}/{@link #report}. */
    public record StudyResult(String studyId, String abv, boolean usedSstr, boolean success, String errorMessage,
                               int mappingRowCount) {
        static StudyResult success(String studyId, String abv, boolean usedSstr, int mappingRowCount) {
            return new StudyResult(studyId, abv, usedSstr, true, null, mappingRowCount);
        }

        static StudyResult failed(String studyId, String abv, String errorMessage) {
            return new StudyResult(studyId, abv, false, false, errorMessage, 0);
        }
    }

    /** Immutable result of {@link #execute}; inspected by {@link #validateOutput}/{@link #report}. */
    public record Output(List<StudyResult> studyResults) {
    }
}
