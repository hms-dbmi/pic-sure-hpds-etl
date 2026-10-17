package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;

import java.math.BigDecimal;

/**
 * The opt-in data repairs of {@link SplitMergedAllConceptsJob}: whether ISO-8601 timestamps are
 * converted, whether rows that break the CSVLoader contract are quarantined instead of failing the
 * run, and how many rejected rows are tolerated. The defaults are strict: no repair of any kind.
 */
record SplitRepairSettings(boolean convertIso, boolean quarantine, double maxRejectFraction) {

    static final String TIMESTAMPS_STRICT = "strict";
    static final String TIMESTAMPS_CONVERT_ISO = "convert-iso";
    static final String BAD_ROWS_FAIL = "fail";
    static final String BAD_ROWS_QUARANTINE = "quarantine";
    static final double DEFAULT_MAX_REJECT_FRACTION = 0.01;

    String timestampsMode() {
        return convertIso ? TIMESTAMPS_CONVERT_ISO : TIMESTAMPS_STRICT;
    }

    String badRowsMode() {
        return quarantine ? BAD_ROWS_QUARANTINE : BAD_ROWS_FAIL;
    }

    /**
     * Reads the three params. With a {@code report}, a bad value is recorded as an error; without
     * one (execution, after validation passed) a bad value falls back to the strict default.
     */
    static SplitRepairSettings parse(JobContext ctx, ValidationReport report) {
        boolean convertIso = false;
        String timestamps = ctx.get("timestamps", TIMESTAMPS_STRICT).trim().toLowerCase();
        if (TIMESTAMPS_CONVERT_ISO.equals(timestamps)) {
            convertIso = true;
        } else if (!TIMESTAMPS_STRICT.equals(timestamps) && report != null) {
            report.error("INVALID_TIMESTAMPS", "timestamps must be '" + TIMESTAMPS_STRICT + "' or '"
                    + TIMESTAMPS_CONVERT_ISO + "', got: " + ctx.get("timestamps", ""), "--timestamps");
        }

        boolean quarantine = false;
        String badRows = ctx.get("bad-rows", BAD_ROWS_FAIL).trim().toLowerCase();
        if (BAD_ROWS_QUARANTINE.equals(badRows)) {
            quarantine = true;
        } else if (!BAD_ROWS_FAIL.equals(badRows) && report != null) {
            report.error("INVALID_BAD_ROWS", "bad-rows must be '" + BAD_ROWS_FAIL + "' or '" + BAD_ROWS_QUARANTINE
                    + "', got: " + ctx.get("bad-rows", ""), "--bad-rows");
        }

        double fraction = DEFAULT_MAX_REJECT_FRACTION;
        String raw = ctx.get("max-reject-fraction").orElse(null);
        if (raw != null) {
            try {
                // BigDecimal, not Double.parseDouble: no NaN, Infinity or hex floats
                BigDecimal v = new BigDecimal(raw.trim());
                if (v.signum() >= 0 && v.compareTo(BigDecimal.ONE) <= 0) {
                    fraction = v.doubleValue();
                } else if (report != null) {
                    report.error("INVALID_MAX_REJECT_FRACTION", "max-reject-fraction must be a decimal between 0 and 1, got: "
                            + raw, "--max-reject-fraction");
                }
            } catch (NumberFormatException e) {
                if (report != null) {
                    report.error("INVALID_MAX_REJECT_FRACTION", "max-reject-fraction must be a decimal between 0 and 1, got: "
                            + raw, "--max-reject-fraction");
                }
            }
        }
        return new SplitRepairSettings(convertIso, quarantine, fraction);
    }
}
