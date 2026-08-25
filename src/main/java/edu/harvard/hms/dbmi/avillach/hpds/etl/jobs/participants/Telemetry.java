package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants;

/**
 * One row of a subject/sample TSV mapping file, keyed by the relevant columns found in
 * the source. Instances of this are collected into the input set for the
 * {@code sstr-populate-rds-participants} job.
 */
public record Telemetry(String dbgapSubjectId,
                         String dbgapSampleId,
                         String consent,
                         String consentAbbreviation) {
}
