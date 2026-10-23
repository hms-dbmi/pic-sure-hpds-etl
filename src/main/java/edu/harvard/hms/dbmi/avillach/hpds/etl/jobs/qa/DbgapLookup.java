package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

/**
 * What dbGaP said about one study version, after any retries.
 *
 * @param outcome  FOUND (summary set), NOT_FOUND (dbGaP has no such study version: a data
 *                 discrepancy, not an outage) or FAILED (no usable answer after every attempt)
 * @param summary  the parsed summary when FOUND, else null
 * @param message  dbGaP's error message (NOT_FOUND) or why the lookup failed (FAILED); null when FOUND
 * @param attempts HTTP requests made for this study, retries included
 * @param unreachable FAILED because the last attempt could not connect to dbGaP at all (a
 *                 connection error or connect timeout), rather than getting a bad answer
 */
public record DbgapLookup(Outcome outcome, DbgapStudySummary summary, String message, int attempts,
                          boolean unreachable) {

    public enum Outcome { FOUND, NOT_FOUND, FAILED }

    public static DbgapLookup found(DbgapStudySummary summary, int attempts) {
        return new DbgapLookup(Outcome.FOUND, summary, null, attempts, false);
    }

    public static DbgapLookup notFound(String message, int attempts) {
        return new DbgapLookup(Outcome.NOT_FOUND, null, message, attempts, false);
    }

    public static DbgapLookup failed(String message, int attempts) {
        return new DbgapLookup(Outcome.FAILED, null, message, attempts, false);
    }

    /** FAILED because dbGaP could not be connected to on the last attempt. */
    public static DbgapLookup unreachable(String message, int attempts) {
        return new DbgapLookup(Outcome.FAILED, null, message, attempts, true);
    }
}
