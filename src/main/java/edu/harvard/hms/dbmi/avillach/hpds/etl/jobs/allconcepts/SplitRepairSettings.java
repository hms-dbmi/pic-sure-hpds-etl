package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * The opt-in data repairs of {@link SplitMergedAllConceptsJob}: whether ISO-8601 timestamps are
 * converted, whether a timestamp of 0 is blanked, whether rows that break the CSVLoader contract are quarantined instead of failing the
 * run, and how many rejected rows are tolerated. The defaults are strict: no repair of any kind.
 */
record SplitRepairSettings(boolean convertIso, boolean blankZeroTimestamps, boolean quarantine, double maxRejectFraction) {

    static final String TIMESTAMPS_STRICT = "strict";
    static final String TIMESTAMPS_CONVERT_ISO = "convert-iso";
    static final String ZERO_TIMESTAMPS_KEEP = "keep";
    static final String ZERO_TIMESTAMPS_BLANK = "blank";
    static final String BAD_ROWS_FAIL = "fail";
    static final String BAD_ROWS_QUARANTINE = "quarantine";
    static final double DEFAULT_MAX_REJECT_FRACTION = 0.01;

    String timestampsMode() {
        return convertIso ? TIMESTAMPS_CONVERT_ISO : TIMESTAMPS_STRICT;
    }

    String zeroTimestampsMode() {
        return blankZeroTimestamps ? ZERO_TIMESTAMPS_BLANK : ZERO_TIMESTAMPS_KEEP;
    }

    String badRowsMode() {
        return quarantine ? BAD_ROWS_QUARANTINE : BAD_ROWS_FAIL;
    }

    /**
     * Reads the four params. With a {@code report}, a bad value is recorded as an error; without
     * one (execution, after validation passed) a bad value falls back to the strict default.
     */
    static SplitRepairSettings parse(JobContext ctx, ValidationReport report) {
        boolean convertIso = false;
        String timestamps = ctx.get("timestamps", TIMESTAMPS_STRICT).trim().toLowerCase(Locale.ROOT);
        if (TIMESTAMPS_CONVERT_ISO.equals(timestamps)) {
            convertIso = true;
        } else if (!TIMESTAMPS_STRICT.equals(timestamps) && report != null) {
            report.error("INVALID_TIMESTAMPS", "timestamps must be '" + TIMESTAMPS_STRICT + "' or '"
                    + TIMESTAMPS_CONVERT_ISO + "', got: " + ctx.get("timestamps", ""), "--timestamps");
        }

        boolean blankZero = false;
        String zeroTimestamps = ctx.get("zero-timestamps", ZERO_TIMESTAMPS_KEEP).trim().toLowerCase(Locale.ROOT);
        if (ZERO_TIMESTAMPS_BLANK.equals(zeroTimestamps)) {
            blankZero = true;
        } else if (!ZERO_TIMESTAMPS_KEEP.equals(zeroTimestamps) && report != null) {
            report.error("INVALID_ZERO_TIMESTAMPS", "zero-timestamps must be '" + ZERO_TIMESTAMPS_KEEP + "' or '"
                    + ZERO_TIMESTAMPS_BLANK + "', got: " + ctx.get("zero-timestamps", ""), "--zero-timestamps");
        }

        boolean quarantine = false;
        String badRows = ctx.get("bad-rows", BAD_ROWS_FAIL).trim().toLowerCase(Locale.ROOT);
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
        return new SplitRepairSettings(convertIso, blankZero, quarantine, fraction);
    }
}
