package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants;

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
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.Strings;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PERMANENT. Loads a dbGaP SSTR subject/sample mapping TSV into the {@code participants},
 * {@code consents}, and {@code samples} RDS tables.
 *
 * <p>One {@code participants} row is created per distinct {@code dbgap_subject_id}
 * (reusing the existing HPDS uuid if that subject was already loaded by a prior run or a
 * different file), one {@code consents} row per participant per file (study id comes from
 * {@code --study-id}; consent/abbreviation come from that subject's first row), and one
 * {@code samples} row per input row that has a non-blank {@code dbgap_sample_id}.
 *
 * <p>Every run first purges existing {@code consents} rows for {@code --study-id} so the
 * study's consent groups are fully repopulated from the given file, not merged with stale
 * data. Purge + load happen in one transaction: a bad row rolls the whole run back.
 *
 * <p>Enabled by {@code etl.jobs.sstr-populate-rds-participants.enabled=true}; without it the bean
 * is never created and {@link edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobRegistry} does not
 * know the job exists.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.sstr-populate-rds-participants.enabled", havingValue = "true")
public class SstrPopulateRdsParticipantsJob extends AbstractJob<SstrPopulateRdsParticipantsJob.Output> {

    public static final String SOURCE = "DBGap";
    static final String COL_DBGAP_SUBJECT_ID = "dbgap_subject_id";
    static final String COL_DBGAP_SAMPLE_ID = "dbgap_sample_id";
    static final String COL_CONSENT = "CONSENT";
    static final String COL_CONSENT_ABBREVIATION = "consent_abbreviation";
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final Pattern STUDY_ID_PATTERN = Pattern.compile("phs\\d{6}");

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final ParticipantRepository participants;
    private final ConsentRepository consents;
    private final SampleRepository samples;
    private final TransactionTemplate tx;

    public SstrPopulateRdsParticipantsJob(IoResolver io,
                                           DelimitedReader delimitedReader,
                                           ParticipantRepository participants,
                                           ConsentRepository consents,
                                           SampleRepository samples,
                                           PlatformTransactionManager txManager) {
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.participants = participants;
        this.consents = consents;
        this.samples = samples;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public String name() {
        return "sstr-populate-rds-participants";
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
                                "dbGaP SSTR subject/sample mapping TSV, tab-delimited (local path or s3:// URI)",
                                "/data/phs001412.sstr.txt"),
                        ParamSpec.required("study-id",
                                "dbGaP study id these rows belong to, format phs###### (6 digits)", "phs001412"),
                        ParamSpec.optional("batch-size", "Rows per batch insert", "1000")),
                List.of("participants/consents/samples upserted in RDS from the mapping file; "
                        + "existing consents for --study-id are purged and repopulated"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        ctx.get("study-id").ifPresent(studyId -> {
            if (!STUDY_ID_PATTERN.matcher(studyId).matches()) {
                report.error("BAD_STUDY_ID",
                        "study-id must match phs###### (6 digits), got: " + studyId, "--study-id");
            }
        });
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
        String input = ctx.require("input");
        String studyId = ctx.require("study-id");
        int batchSize = Integer.parseInt(ctx.get("batch-size", String.valueOf(DEFAULT_BATCH_SIZE)));

        // The whole purge + load is one transaction: any DataException rolls it all back.
        return tx.execute(status -> load(input, studyId, batchSize));
    }

    private Output load(String input, String studyId, int batchSize) {
        Set<String> subjectIds = new LinkedHashSet<>();
        Map<String, Telemetry> firstRowBySubject = new LinkedHashMap<>();
        List<String[]> samplePairs = new ArrayList<>();
        boolean headerChecked = false;
        long rowsRead = 0;

        InputStream in = io.openInput(input);
        try (Stream<Map<String, String>> stream = delimitedReader.stream(in, DelimitedReader.TAB)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                if (!headerChecked) {
                    requireColumns(row);
                    headerChecked = true;
                }
                rowsRead++;
                String dbgapSubjectId = Strings.trimToNull(row.get(COL_DBGAP_SUBJECT_ID));
                if (dbgapSubjectId == null) {
                    throw new DataException("Row " + rowsRead + " has a blank " + COL_DBGAP_SUBJECT_ID);
                }
                String consent = Strings.trimToNull(row.get(COL_CONSENT));
                if (consent == null) {
                    throw new DataException("Row " + rowsRead + " has a blank " + COL_CONSENT);
                }
                String consentAbbreviation = row.get(COL_CONSENT_ABBREVIATION);
                consentAbbreviation = (consentAbbreviation == null || consentAbbreviation.isBlank()) ? "" : consentAbbreviation.trim();
                String dbgapSampleId = Strings.trimToNull(row.get(COL_DBGAP_SAMPLE_ID));

                subjectIds.add(dbgapSubjectId);
                firstRowBySubject.putIfAbsent(dbgapSubjectId,
                        new Telemetry(dbgapSubjectId, dbgapSampleId != null ? dbgapSampleId : "", consent, consentAbbreviation));
                if (dbgapSampleId != null) {
                    samplePairs.add(new String[]{dbgapSubjectId, dbgapSampleId});
                }
            }
        }

        if (subjectIds.isEmpty()) {
            throw new DataException("Input yielded no subjects (" + rowsRead + " data row(s) read); refusing to "
                    + "purge existing consents for study " + studyId + ". Check that the file is complete.");
        }

        consents.deleteByStudyId(studyId);

        ParticipantRepository.Resolution resolution = participants.resolveOrCreate(subjectIds, SOURCE, batchSize);
        Map<String, UUID> uuidBySubject = resolution.uuidsBySourceId();
        long participantsInserted = resolution.inserted();

        List<Consent> consentRows = new ArrayList<>();
        Map<String, Long> countsByConsentGroup = new LinkedHashMap<>();
        for (Telemetry row : firstRowBySubject.values()) {
            consentRows.add(new Consent(uuidBySubject.get(row.dbgapSubjectId()), studyId,
                    row.consent(), row.consentAbbreviation()));
            countsByConsentGroup.merge(row.consent(), 1L, Long::sum);
        }
        long consentsWritten = BatchOps.upsertInChunks(consents::batchUpsert, consentRows, batchSize);

        List<Sample> sampleRows = samplePairs.stream()
                .map(pair -> new Sample(uuidBySubject.get(pair[0]), pair[1], SOURCE))
                .toList();
        long samplesInserted = BatchOps.upsertInChunks(samples::batchUpsert, sampleRows, batchSize);

        log.info("Read {} row(s) for {} participant(s); {} new participant(s), {} consent row(s), "
                        + "{} sample row(s) inserted",
                rowsRead, subjectIds.size(), participantsInserted, consentsWritten, samplesInserted);
        return new Output(rowsRead, subjectIds.size(), participantsInserted, consentsWritten, samplesInserted,
                countsByConsentGroup);
    }

    private void requireColumns(Map<String, String> firstRow) {
        List<String> missing = new ArrayList<>();
        if (!firstRow.containsKey(COL_DBGAP_SUBJECT_ID)) missing.add(COL_DBGAP_SUBJECT_ID);
        if (!firstRow.containsKey(COL_CONSENT)) missing.add(COL_CONSENT);
        if (!firstRow.containsKey(COL_CONSENT_ABBREVIATION)) missing.add(COL_CONSENT_ABBREVIATION);
        if (!firstRow.containsKey(COL_DBGAP_SAMPLE_ID)) missing.add(COL_DBGAP_SAMPLE_ID);
        if (!missing.isEmpty()) {
            throw new DataException("Input is missing required column(s): " + missing
                    + ". Found: " + firstRow.keySet());
        }
    }


    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.rowsRead() == 0) {
            report.error("EMPTY_INPUT", "Input contained no data rows");
        }
        output.countsByConsentGroup().forEach((group, count) ->
                report.info("consent_code_COUNT", group + ": " + count + " participant(s)"));
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("rowsRead", output.rowsRead())
                .metric("distinctParticipants", output.distinctParticipants())
                .metric("participantsInserted", output.participantsInserted())
                .metric("consentsWritten", output.consentsWritten())
                .metric("samplesInserted", output.samplesInserted())
                .metric("countsByConsentGroup", output.countsByConsentGroup());
    }

    /** Immutable result of {@link #execute}; inspected by {@link #validateOutput}/{@link #report}. */
    public record Output(long rowsRead,
                          long distinctParticipants,
                          long participantsInserted,
                          long consentsWritten,
                          long samplesInserted,
                          Map<String, Long> countsByConsentGroup) {
    }
}
