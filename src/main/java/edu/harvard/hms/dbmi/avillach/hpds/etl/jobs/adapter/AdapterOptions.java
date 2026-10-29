package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The adapter's parameters, parsed once. {@link #parse} reports every bad value to the validation report (exit 2)
 * and still returns usable defaults for them.
 *
 * @param studies          optional allowlist of study ids; empty means every study of the manifest
 * @param maxRejectFraction largest share of the input rows that may be rejected (0 = none)
 * @param allowCodes       consent codes accepted although they are not positive integers
 * @param refuseMultiStudy {@code --multi-study-ids=refuse}: any patient in two studies stops the run
 * @param dropForeignStudyRows {@code --foreign-study-rows=drop}: a data row under another study's tree is dropped with a
 *                         warning instead of rejected (it is never written either way)
 * @param dropUnmatchedConsentRows {@code --unmatched-consent-rows=drop}: a global {@code _consents} row naming no manifest
 *                         partition is dropped with a warning instead of rejected
 */
record AdapterOptions(Set<String> studies, double maxRejectFraction, Set<String> allowCodes, boolean refuseMultiStudy,
                      boolean dropForeignStudyRows, boolean dropUnmatchedConsentRows) {

    static AdapterOptions parse(JobContext ctx, ValidationReport report) {
        Set<String> studies = list(ctx, "studies", AllConceptsManifestReader.STUDY_TOKEN.pattern(), report, "BAD_STUDY_ID");
        Set<String> codes = list(ctx, "allow-consent-codes", AllConceptsManifestReader.CODE_TOKEN.pattern(), report, "BAD_CONSENT_CODE");

        double fraction = 0;
        String rawFraction = ctx.get("max-reject-fraction", "0");
        try {
            fraction = Double.parseDouble(rawFraction.trim());
            if (!(fraction >= 0 && fraction <= 1)) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            fraction = 0;
            report.error("BAD_MAX_REJECT_FRACTION", "max-reject-fraction must be a number from 0 to 1, got: '" + rawFraction + "'",
                    "--max-reject-fraction");
        }

        String multi = ctx.get("multi-study-ids", "copy");
        boolean refuse = false;
        if (multi.equals("refuse")) {
            refuse = true;
        } else if (!multi.equals("copy")) {
            report.error("BAD_MULTI_STUDY_IDS", "multi-study-ids must be 'copy' or 'refuse', got: '" + multi + "'", "--multi-study-ids");
        }

        boolean dropForeign = rejectOrDrop(ctx, report, "foreign-study-rows", "BAD_FOREIGN_STUDY_ROWS");
        boolean dropConsents = rejectOrDrop(ctx, report, "unmatched-consent-rows", "BAD_UNMATCHED_CONSENT_ROWS");

        String zero = ctx.get("zero-timestamps", "blank");
        if (!zero.equals("blank")) {
            report.error("BAD_ZERO_TIMESTAMPS", "zero-timestamps must be 'blank' (the only supported value; it is a parameter so the "
                    + "choice shows in the logs), got: '" + zero + "'", "--zero-timestamps");
        }
        return new AdapterOptions(studies, fraction, codes, refuse, dropForeign, dropConsents);
    }

    /** {@code reject} (default) or {@code drop}; true for drop. */
    private static boolean rejectOrDrop(JobContext ctx, ValidationReport report, String name, String code) {
        String v = ctx.get(name, "reject");
        if (v.equals("drop")) {
            return true;
        }
        if (!v.equals("reject")) {
            report.error(code, name + " must be 'reject' or 'drop', got: '" + v + "'", "--" + name);
        }
        return false;
    }

    private static Set<String> list(JobContext ctx, String name, String regex, ValidationReport report, String code) {
        Set<String> out = new TreeSet<>();
        for (String raw : ctx.get(name, "").split(",")) {
            String v = raw.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (!v.matches(regex)) {
                report.error(code, name + " holds '" + v + "', which must match " + regex, "--" + name);
            } else {
                out.add(v);
            }
        }
        return new LinkedHashSet<>(out);
    }
}
