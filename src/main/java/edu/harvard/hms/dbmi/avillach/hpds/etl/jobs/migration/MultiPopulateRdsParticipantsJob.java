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
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.BatchOps;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.Severity;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration.DbgapMultiFileReader.SampleRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration.DbgapMultiFileReader.SampleTable;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration.DbgapMultiFileReader.Subject;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration.DbgapMultiFileReader.SubjectTable;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SstrPopulateRdsParticipantsJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MIGRATION. The fallback for a study with no SSTR, or a malformed one: loads the dbGaP
 * {@code Subject.MULTI} / {@code Sample.MULTI} phenotype tables into the {@code participants},
 * {@code consents}, and {@code samples} RDS tables, writing the same rows
 * {@link SstrPopulateRdsParticipantsJob} would have.
 *
 * <ul>
 *   <li>One {@code participants} row per subject with a usable consent, keyed by
 *       {@code dbGaP_Subject_ID} under {@link SstrPopulateRdsParticipantsJob#SOURCE}. Consent
 *       {@code 0} (not consented) subjects are dropped.</li>
 *   <li>One {@code consents} row per participant. The code is the numeric consent; the
 *       abbreviation is decoded from {@code --data-dict} (the text in the label's trailing
 *       parentheses), or blank when the dictionary is absent or doesn't decode that code.</li>
 *   <li>From {@code --sample-input}, when given: one {@link SstrPopulateRdsParticipantsJob#SOURCE}
 *       sample per {@code dbGaP_Sample_ID} and one {@link SstrPopulateRdsParticipantsJob#SOURCE_SUBMITTED}
 *       sample per {@code SAMPLE_ID}, for loaded subjects only. An unreadable sample file skips
 *       samples with a warning rather than failing the study.</li>
 * </ul>
 *
 * <p>Like the SSTR job, a run first purges the study's {@code consents} rows, and purge + load
 * are one transaction. Every problem found in the files is reported as an output-validation
 * issue carrying {@code file}, {@code count}, and {@code examples}.
 *
 * <p>Invoked by {@link ParticipantsMigrationJob}; never part of the permanent pipeline. Enabled by
 * {@code etl.jobs.multi-populate-rds-participants.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.multi-populate-rds-participants.enabled", havingValue = "true")
public class MultiPopulateRdsParticipantsJob extends AbstractJob<MultiPopulateRdsParticipantsJob.Output> {

    private static final int DEFAULT_BATCH_SIZE = 1000;

    private final DbgapMultiFileReader reader;
    private final ParticipantRepository participants;
    private final ConsentRepository consents;
    private final SampleRepository samples;
    private final TransactionTemplate tx;

    public MultiPopulateRdsParticipantsJob(IoResolver io,
                                            DelimitedReader delimitedReader,
                                            ParticipantRepository participants,
                                            ConsentRepository consents,
                                            SampleRepository samples,
                                            PlatformTransactionManager txManager) {
        this.reader = new DbgapMultiFileReader(io, delimitedReader);
        this.participants = participants;
        this.consents = consents;
        this.samples = samples;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public String name() {
        return "multi-populate-rds-participants";
    }

    @Override
    public JobType type() {
        return JobType.MIGRATION;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(
                        ParamSpec.required("subject-input",
                                "dbGaP Subject.MULTI.txt: '#' preamble, then a tab-delimited table with "
                                        + "dbGaP_Subject_ID and a consent column (local path or s3:// URI)",
                                "/data/phs000007.v33.pht000182.v14.p14.Framingham_Subject.MULTI.txt"),
                        ParamSpec.required("study-id", "Study id these subjects belong to", "phs000007"),
                        ParamSpec.optional("sample-input",
                                "dbGaP Sample.MULTI.txt with dbGaP_Subject_ID and dbGaP_Sample_ID; omit to load no samples",
                                "/data/phs000007.v33.pht001415.v1.p14.Framingham_Sample.MULTI.txt"),
                        ParamSpec.optional("data-dict",
                                "dbGaP Subject.data_dict.xml decoding the consent codes; omit to leave abbreviations blank",
                                "/data/phs000007.v33.pht000182.v14.Framingham_Subject.data_dict.xml"),
                        ParamSpec.optional("batch-size", "Rows per batch insert", "1000")),
                List.of("participants/consents/samples upserted in RDS from the MULTI files; "
                        + "existing consents for --study-id are purged and repopulated"));
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
        String studyId = ctx.require("study-id");
        int batchSize = Integer.parseInt(ctx.get("batch-size", String.valueOf(DEFAULT_BATCH_SIZE)));
        List<ValidationIssue> issues = new ArrayList<>();

        SubjectTable subjects = reader.readSubjects(ctx.require("subject-input"));
        issues.addAll(subjects.issues());
        if (subjects.consented().isEmpty()) {
            throw new DataException(subjects.file() + ": no subject has a usable consent (" + subjects.rowsRead()
                    + " data row(s) read, " + subjects.notConsented().size() + " with consent 0); refusing to purge "
                    + "existing consents for study " + studyId);
        }

        Map<String, String> abbreviationByCode = abbreviations(ctx.get("data-dict").orElse(null), subjects, issues);

        SampleTable sampleTable = null;
        String sampleInput = ctx.get("sample-input").orElse(null);
        if (sampleInput != null) {
            try {
                sampleTable = reader.readSamples(sampleInput);
                issues.addAll(sampleTable.issues());
            } catch (DataException e) {
                issues.add(ValidationIssue.warning("SAMPLE_MULTI_UNREADABLE",
                        e.getMessage() + "; no samples loaded for study " + studyId,
                        Map.of("file", DbgapMultiFileReader.fileName(sampleInput), "count", 0L, "examples", List.of())));
            }
        }

        SampleTable finalSampleTable = sampleTable;
        return tx.execute(status -> load(studyId, batchSize, subjects, abbreviationByCode, finalSampleTable, issues));
    }

    /**
     * Consent code &rarr; abbreviation for every code the subject file uses. A code the dictionary
     * can't decode maps to "", and so does every code when there is no usable dictionary.
     */
    private Map<String, String> abbreviations(String dataDict, SubjectTable subjects, List<ValidationIssue> issues) {
        Set<String> codes = new LinkedHashSet<>();
        subjects.consented().values().forEach(s -> codes.add(s.consentCode()));
        Map<String, String> byCode = new LinkedHashMap<>();
        codes.forEach(c -> byCode.put(c, ""));

        if (dataDict == null) {
            issues.add(ValidationIssue.warning("NO_DATA_DICT", "No Subject.data_dict.xml; consent abbreviations "
                    + "left blank for code(s) " + codes, Map.of("file", "", "count", (long) codes.size(),
                    "examples", List.copyOf(codes))));
            return byCode;
        }
        String file = DbgapMultiFileReader.fileName(dataDict);
        Map<String, String> labels;
        try {
            labels = reader.readConsentDecodings(dataDict, subjects.consentColumn());
        } catch (DataException e) {
            issues.add(ValidationIssue.warning("DATA_DICT_UNREADABLE", e.getMessage()
                            + "; consent abbreviations left blank",
                    Map.of("file", file, "count", (long) codes.size(), "examples", List.copyOf(codes))));
            return byCode;
        }
        if (labels.isEmpty()) {
            issues.add(ValidationIssue.warning("CONSENT_VARIABLE_NOT_IN_DATA_DICT", file + ": no variable named '"
                            + subjects.consentColumn() + "' with coded values; consent abbreviations left blank",
                    Map.of("file", file, "count", (long) codes.size(), "examples", List.copyOf(codes))));
            return byCode;
        }

        List<String> undecoded = new ArrayList<>();
        List<String> withoutParens = new ArrayList<>();
        for (String code : codes) {
            String label = labels.get(code);
            if (label == null) {
                undecoded.add(code);
                continue;
            }
            String abbreviation = DbgapMultiFileReader.abbreviationOf(label);
            if (abbreviation == null) {
                withoutParens.add(code + "=" + label);
                abbreviation = label;
            }
            byCode.put(code, abbreviation);
        }
        if (!undecoded.isEmpty()) {
            issues.add(ValidationIssue.warning("CONSENT_CODE_NOT_DECODED", file + ": consent code(s) " + undecoded
                            + " used in " + subjects.file() + " are not decoded; their abbreviations are blank",
                    Map.of("file", file, "count", (long) undecoded.size(), "examples", undecoded)));
        }
        if (!withoutParens.isEmpty()) {
            issues.add(ValidationIssue.warning("CONSENT_LABEL_WITHOUT_ABBREVIATION", file + ": label(s) with no "
                            + "trailing (ABBREVIATION); the full label was used: " + withoutParens,
                    Map.of("file", file, "count", (long) withoutParens.size(), "examples", withoutParens)));
        }
        return byCode;
    }

    private Output load(String studyId, int batchSize, SubjectTable subjects, Map<String, String> abbreviationByCode,
                        SampleTable sampleTable, List<ValidationIssue> issues) {
        consents.deleteByStudyId(studyId);

        String source = SstrPopulateRdsParticipantsJob.SOURCE;
        ParticipantRepository.Resolution resolution =
                participants.resolveOrCreate(subjects.consented().keySet(), source, batchSize);
        Map<String, Long> idBySubject = resolution.idsBySourceId();

        List<Consent> consentRows = new ArrayList<>();
        Map<String, Long> countsByConsentGroup = new LinkedHashMap<>();
        for (Subject s : subjects.consented().values()) {
            consentRows.add(new Consent(idBySubject.get(s.dbgapSubjectId()), studyId, s.consentCode(),
                    abbreviationByCode.getOrDefault(s.consentCode(), "")));
            countsByConsentGroup.merge(s.consentCode(), 1L, Long::sum);
        }
        long consentsWritten = BatchOps.upsertInChunks(consents::batchUpsert, consentRows, batchSize);

        List<Sample> dbgapSamples = new ArrayList<>();
        List<Sample> submittedSamples = new ArrayList<>();
        if (sampleTable != null) {
            List<String> notConsented = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (SampleRow row : sampleTable.rows()) {
                Long hpdsId = idBySubject.get(row.dbgapSubjectId());
                if (hpdsId == null) {
                    (subjects.notConsented().contains(row.dbgapSubjectId()) ? notConsented : unknown)
                            .add(row.dbgapSubjectId());
                    continue;
                }
                if (row.dbgapSampleId() != null) {
                    dbgapSamples.add(new Sample(hpdsId, row.dbgapSampleId(), source));
                }
                if (row.submittedSampleId() != null) {
                    submittedSamples.add(new Sample(hpdsId, row.submittedSampleId(),
                            SstrPopulateRdsParticipantsJob.SOURCE_SUBMITTED));
                }
            }
            if (!notConsented.isEmpty()) {
                issues.add(sampleSkipIssue(Severity.INFO, "SAMPLES_OF_UNCONSENTED_SUBJECTS",
                        sampleTable.file(), notConsented, "belong to consent-0 subjects and were not loaded"));
            }
            if (!unknown.isEmpty()) {
                issues.add(sampleSkipIssue(Severity.WARNING, "SAMPLES_OF_UNKNOWN_SUBJECTS",
                        sampleTable.file(), unknown, "belong to subjects with no usable consent in "
                                + subjects.file() + " and were not loaded"));
            }
        }
        long samplesInserted = BatchOps.upsertInChunks(samples::batchUpsert, dbgapSamples, batchSize);
        long submittedSamplesInserted = BatchOps.upsertInChunks(samples::batchUpsert, submittedSamples, batchSize);

        log.info("Study '{}': {} consented subject(s) from {} ({} new participant(s)), {} consent row(s), "
                        + "{} dbGaP sample row(s) and {} submitted sample row(s)",
                studyId, idBySubject.size(), subjects.file(), resolution.inserted(), consentsWritten,
                samplesInserted, submittedSamplesInserted);
        return new Output(subjects.rowsRead(), idBySubject.size(), resolution.inserted(), consentsWritten,
                samplesInserted, submittedSamplesInserted, subjects.notConsented().size(), countsByConsentGroup,
                subjects.consentColumn(), sampleTable != null, issues);
    }

    private static ValidationIssue sampleSkipIssue(Severity severity, String code, String file,
                                                   List<String> subjectIds, String reason) {
        List<String> examples = subjectIds.stream().limit(10).toList();
        return new ValidationIssue(severity, code, file + ": " + subjectIds.size() + " sample row(s) " + reason
                + ". First subjects: " + examples, null,
                Map.of("file", file, "count", (long) subjectIds.size(), "examples", examples));
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        output.issues().forEach(report::add);
        output.countsByConsentGroup().forEach((group, count) ->
                report.info("CONSENT_CODE_COUNT", group + ": " + count + " participant(s)"));
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("rowsRead", output.rowsRead())
                .metric("distinctParticipants", output.distinctParticipants())
                .metric("participantsInserted", output.participantsInserted())
                .metric("consentsWritten", output.consentsWritten())
                .metric("samplesInserted", output.samplesInserted())
                .metric("submittedSamplesInserted", output.submittedSamplesInserted())
                .metric("notConsentedSubjects", output.notConsentedSubjects())
                .metric("countsByConsentGroup", output.countsByConsentGroup())
                .metric("consentColumn", output.consentColumn())
                .metric("sampleFileLoaded", output.sampleFileLoaded());
    }

    /** Immutable result of {@link #execute}; inspected by {@link #validateOutput}/{@link #report}. */
    public record Output(long rowsRead,
                         long distinctParticipants,
                         long participantsInserted,
                         long consentsWritten,
                         long samplesInserted,
                         long submittedSamplesInserted,
                         long notConsentedSubjects,
                         Map<String, Long> countsByConsentGroup,
                         String consentColumn,
                         boolean sampleFileLoaded,
                         List<ValidationIssue> issues) {
    }
}
