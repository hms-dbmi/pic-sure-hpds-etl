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
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.db.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * TEMPORARY MIGRATION. Loads participant origin-ids from a delimited export off the
 * legacy system into the {@code participants} table, minting a fresh HPDS uuid for each
 * new {@code (source_id, source)} pair. Re-running is idempotent (existing pairs are
 * left untouched) and the whole load is transactional -- a malformed row rolls back the
 * entire run so RDS is never left half-migrated.
 *
 * <p><b>Scope note:</b> this example treats every input row as its own participant
 * identity. Linking many origin ids to a single shared uuid (true identity resolution)
 * is a separate concern and intentionally out of scope for this migration template.
 *
 * <p><b>Delete me</b> once the migration has run in every environment (this is why it is
 * marked {@link JobType#MIGRATION}).
 */
@Component
public class ParticipantsMigrationJob extends AbstractJob<ParticipantsMigrationJob.Output> {

    static final String COL_SOURCE_ID = "source_id";
    static final String COL_SOURCE = "source";
    private static final int DEFAULT_BATCH_SIZE = 1000;

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final ParticipantRepository participants;
    private final TransactionTemplate tx;

    public ParticipantsMigrationJob(IoResolver io,
                                    DelimitedReader delimitedReader,
                                    ParticipantRepository participants,
                                    PlatformTransactionManager txManager) {
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.participants = participants;
        this.tx = new TransactionTemplate(txManager);
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
                        ParamSpec.required("input",
                                "Delimited export with '" + COL_SOURCE_ID + "' and '" + COL_SOURCE
                                        + "' columns (local path or s3:// URI)",
                                "s3://hpds-migration/participants.csv"),
                        ParamSpec.optional("delimiter", "Field delimiter: 'comma' or 'tab'", "comma"),
                        ParamSpec.optional("batch-size", "Rows per batch insert", "1000")),
                List.of("Rows inserted into the participants table (idempotent upsert on (source_id, source))"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        String delimiter = ctx.get("delimiter", "comma");
        if (!delimiter.equals("comma") && !delimiter.equals("tab")) {
            report.error("BAD_DELIMITER", "delimiter must be 'comma' or 'tab', got: " + delimiter, "--delimiter");
        }
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
        char sep = "tab".equals(ctx.get("delimiter", "comma")) ? DelimitedReader.TAB : DelimitedReader.COMMA;
        int batchSize = Integer.parseInt(ctx.get("batch-size", String.valueOf(DEFAULT_BATCH_SIZE)));

        // The whole load is one transaction: any DataException rolls the load back.
        return tx.execute(status -> load(input, sep, batchSize));
    }

    private Output load(String input, char sep, int batchSize) {
        long rowsRead = 0;
        long inserted = 0;
        boolean headerChecked = false;
        List<Participant> batch = new ArrayList<>(batchSize);

        // stream.close() (below) closes the underlying InputStream via its onClose hook.
        InputStream in = io.openInput(input);
        try (Stream<Map<String, String>> stream = delimitedReader.stream(in, sep)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                if (!headerChecked) {
                    requireColumns(row);
                    headerChecked = true;
                }
                rowsRead++;
                String sourceId = trimToNull(row.get(COL_SOURCE_ID));
                String source = trimToNull(row.get(COL_SOURCE));
                if (sourceId == null || source == null) {
                    throw new DataException("Row " + rowsRead + " has a blank required field ("
                            + COL_SOURCE_ID + "/" + COL_SOURCE + ")");
                }
                batch.add(new Participant(UUID.randomUUID(), sourceId, source));
                if (batch.size() >= batchSize) {
                    inserted += participants.batchUpsert(batch);
                    batch.clear();
                }
            }
            inserted += participants.batchUpsert(batch);
        }
        log.info("Read {} row(s); inserted {} new participant(s)", rowsRead, inserted);
        return new Output(rowsRead, inserted);
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.rowsRead() == 0) {
            report.error("EMPTY_INPUT", "Input contained no data rows");
        }
        long skipped = output.rowsRead() - output.inserted();
        if (skipped > 0) {
            report.info("PRE_EXISTING", skipped + " row(s) already existed and were left unchanged");
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("rowsRead", output.rowsRead())
                .metric("inserted", output.inserted())
                .metric("preExisting", output.rowsRead() - output.inserted());
    }

    private void requireColumns(Map<String, String> firstRow) {
        List<String> missing = new ArrayList<>();
        if (!firstRow.containsKey(COL_SOURCE_ID)) missing.add(COL_SOURCE_ID);
        if (!firstRow.containsKey(COL_SOURCE)) missing.add(COL_SOURCE);
        if (!missing.isEmpty()) {
            throw new DataException("Input is missing required column(s): " + missing
                    + ". Found: " + firstRow.keySet());
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public record Output(long rowsRead, long inserted) {
    }
}
