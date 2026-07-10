package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

import java.util.UUID;

/**
 * A row in {@code consents}: the mapping of an HPDS uuid to a study_id/consent_group.
 * A participant never belongs to more than one consent group within the same study,
 * so {@code (hpdsUuid, studyId)} is unique.
 */
public record Consent(UUID hpdsUuid, String studyId, String consentGroup) {
}
