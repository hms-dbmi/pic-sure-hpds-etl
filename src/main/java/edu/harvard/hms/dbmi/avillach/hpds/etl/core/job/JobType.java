package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

/**
 * Distinguishes jobs that are part of the permanent ingestion surface from
 * one-off migrations off the old system. Surfaced in reports and useful for
 * housekeeping (temporary jobs are expected to be deleted once their migration
 * has run in every environment).
 */
public enum JobType {
    /** A long-lived job that is part of the ongoing ingestion pipeline. */
    PERMANENT,
    /** A temporary migration from the legacy system; delete once fully rolled out. */
    MIGRATION
}
