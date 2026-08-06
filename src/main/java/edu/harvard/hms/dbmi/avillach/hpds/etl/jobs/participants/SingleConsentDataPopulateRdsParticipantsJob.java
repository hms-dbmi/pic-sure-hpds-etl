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
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
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
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

/**
 * PERMANENT. Loads a simple list of subject ids (one per CSV row; header row skipped,
 * first column used regardless of its name) into the {@code participants} and
 * {@code consents} RDS tables under a single, uniform consent for the whole file --
 * unlike {@link SstrPopulateRdsParticipantsJob}, every subject in one run shares the same
 * {@code --consent-type}.
 *
 * <p>{@code source} (on {@code participants}/{@code samples}) is simply {@code --study-id}
 * verbatim -- it is not format-validated here, unlike the dbGaP study ids used elsewhere.
 *
 * <p>Every run first purges existing {@code consents} rows for {@code --study-id} so the
 * study's consent is fully repopulated from the given file, not merged with stale data.
 * Purge + load happen in one transaction: a bad row rolls the whole run back.
 *
 * <p>One {@code participants} row is created per distinct subject id not already present
 * for that {@code source}; existing ones are reused. One {@code consents} row is written
 * per subject using the fixed code/abbreviation for {@code --consent-type} (matched
 * case-insensitively): {@code single} &rarr; {@code (1, GRU)}, {@code public} &rarr;
 * {@code (public, "")}. If {@code --subject-id-is-sample-id=true}, one {@code samples} row
 * per subject is also written, using the subject id itself as the sample id.
 */
@Component
public class SingleConsentDataPopulateRdsParticipantsJob
        extends AbstractJob<SingleConsentDataPopulateRdsParticipantsJob.Output> {

    static final String CONSENT_TYPE_SINGLE = "single";
    static final String CONSENT_TYPE_PUBLIC = "public";
    private static final int DEFAULT_BATCH_SIZE = 1000;

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final ParticipantRepository participants;
    private final ConsentRepository consents;
    private final SampleRepository samples;
    private final TransactionTemplate tx;

    public SingleConsentDataPopulateRdsParticipantsJob(IoResolver io,
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
        return "single-consent-data-populate-rds-participants";
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
                                "Local CSV of subject ids: header row is skipped, first column is the "
                                        + "subject id regardless of its name (local path or s3:// URI)",
                                "/data/study.subjects.csv"),
                        ParamSpec.required("study-id",
                                "Used verbatim as the participants/samples 'source' and the consents "
                                        + "'study_id'; not format-validated", "my-study-01"),
                        ParamSpec.required("consent-type",
                                "'single' (consent code=1, abbreviation=GRU) or 'public' "
                                        + "(consent code=public, abbreviation=''); matched case-insensitively",
                                "single"),
                        ParamSpec.optional("subject-id-is-sample-id",
                                "If true, also insert a samples row per subject using the subject id as "
                                        + "the sample id", "false"),
                        ParamSpec.optional("batch-size", "Rows per batch insert", "1000")),
                List.of("participants/consents (and optionally samples) upserted in RDS for --study-id"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        ctx.get("consent-type").ifPresent(consentType -> {
            if (!CONSENT_TYPE_SINGLE.equalsIgnoreCase(consentType) && !CONSENT_TYPE_PUBLIC.equalsIgnoreCase(consentType)) {
                report.error("BAD_CONSENT_TYPE",
                        "consent-type must be 'single' or 'public' (case-insensitive), got: " + consentType,
                        "--consent-type");
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
        String consentType = ctx.require("consent-type");
        boolean subjectIdIsSampleId = ctx.getBoolean("subject-id-is-sample-id", false);
        int batchSize = Integer.parseInt(ctx.get("batch-size", String.valueOf(DEFAULT_BATCH_SIZE)));

        // The whole load is one transaction: any DataException rolls it all back.
        return tx.execute(status -> load(input, studyId, consentType, subjectIdIsSampleId, batchSize));
    }

    private Output load(String input, String studyId, String consentType, boolean subjectIdIsSampleId,
                         int batchSize) {
        Set<String> subjectIds = new LinkedHashSet<>();
        long rowsRead = 0;

        // stream.close() (below) closes the underlying InputStream via its onClose hook.
        InputStream in = io.openInput(input);
        try (Stream<Map<String, String>> stream = delimitedReader.stream(in, DelimitedReader.COMMA)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                rowsRead++;
                subjectIds.add(firstColumnValue(row, rowsRead));
            }
        }

        // Purge first so a fully-successful run always reflects exactly this file; the
        // surrounding transaction rolls this back too if a later step fails.
        consents.deleteByStudyId(studyId);

        Map<String, UUID> uuidBySubject = new LinkedHashMap<>(participants.findUuids(subjectIds, studyId));
        List<Participant> newParticipants = new ArrayList<>();
        for (String subjectId : subjectIds) {
            uuidBySubject.computeIfAbsent(subjectId, id -> {
                UUID uuid = UUID.randomUUID();
                newParticipants.add(new Participant(uuid, id, studyId));
                return uuid;
            });
        }
        long participantsInserted = batchUpsertInChunks(participants::batchUpsert, newParticipants, batchSize);

        boolean isSingle = CONSENT_TYPE_SINGLE.equalsIgnoreCase(consentType);
        String consentCode = isSingle ? "1" : "public";
        String consentAbbreviation = isSingle ? "GRU" : "";
        List<Consent> consentRows = subjectIds.stream()
                .map(id -> new Consent(uuidBySubject.get(id), studyId, consentCode, consentAbbreviation))
                .toList();
        long consentsWritten = batchUpsertInChunks(consents::batchUpsert, consentRows, batchSize);

        long samplesInserted = 0;
        if (subjectIdIsSampleId) {
            List<Sample> sampleRows = subjectIds.stream()
                    .map(id -> new Sample(uuidBySubject.get(id), id, studyId))
                    .toList();
            samplesInserted = batchUpsertInChunks(samples::batchUpsert, sampleRows, batchSize);
        }

        log.info("Read {} row(s) for {} distinct subject(s); {} new participant(s), {} consent row(s), "
                        + "{} sample row(s) inserted",
                rowsRead, subjectIds.size(), participantsInserted, consentsWritten, samplesInserted);
        return new Output(rowsRead, subjectIds.size(), participantsInserted, consentsWritten, samplesInserted,
                consentCode, consentAbbreviation);
    }

    /** Takes the first column's value regardless of its header name; the header row itself is skipped. */
    private static String firstColumnValue(Map<String, String> row, long rowNum) {
        if (row.isEmpty()) {
            throw new DataException("Row " + rowNum + " has no columns");
        }
        String value = trimToNull(row.values().iterator().next());
        if (value == null) {
            throw new DataException("Row " + rowNum + " has a blank subject id");
        }
        return value;
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
        if (output.rowsRead() == 0) {
            report.error("EMPTY_INPUT", "Input contained no data rows");
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("rowsRead", output.rowsRead())
                .metric("distinctSubjects", output.distinctSubjects())
                .metric("participantsInserted", output.participantsInserted())
                .metric("consentsWritten", output.consentsWritten())
                .metric("samplesInserted", output.samplesInserted())
                .metric("consentCode", output.consentCode())
                .metric("consentAbbreviation", output.consentAbbreviation());
    }

    /** Immutable result of {@link #execute}; inspected by {@link #validateOutput}/{@link #report}. */
    public record Output(long rowsRead,
                          long distinctSubjects,
                          long participantsInserted,
                          long consentsWritten,
                          long samplesInserted,
                          String consentCode,
                          String consentAbbreviation) {
    }
}
