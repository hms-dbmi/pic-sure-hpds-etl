package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

/**
 * A row in {@code consents}: the mapping of an HPDS id to a study_id/consent_code.
 * A participant never belongs to more than one consent group within the same study,
 * so {@code (hpdsId, studyId)} is unique.
 */
public record Consent(long hpdsId, String studyId, String consentCode, String consentAbbreviation) {
}
