package edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation;

/** Severity of a single validation issue. Only ERROR fails a job by default. */
public enum Severity {
    /** A problem that must stop the job (missing input, malformed required field). */
    ERROR,
    /** A suspicious-but-tolerable condition (unexpected extra column, high null rate). */
    WARNING,
    /** Purely informational (row counts, distributions) surfaced in the report. */
    INFO
}
