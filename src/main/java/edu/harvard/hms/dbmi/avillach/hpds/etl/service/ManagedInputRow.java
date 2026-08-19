package edu.harvard.hms.dbmi.avillach.hpds.etl.service;

/**
 * One study as described by the managed inputs (the BDC "Managed Inputs" sheet).
 *
 * <p>Only the columns the ETL acts on are modelled. The sheet carries ~25 columns of
 * curation metadata (programs, access text, gen3 authz, links); adding a field here is
 * cheap when a job actually needs one, whereas carrying all of them would make every
 * consumer depend on the sheet's exact shape.
 *
 * @param abv     "Study Abbreviated Name" -- names per-study files, e.g. {@code {ABV}_PatientMapping.v2.csv}
 * @param studyId "Study Identifier" -- the phs accession, e.g. {@code phs002451}
 * @param isReady "Data is ready to process" -- whether this study should be processed this run
 */
public record ManagedInputRow(String abv, String studyId, boolean isReady) {
}
