package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

import java.util.UUID;

/**
 * A row in {@code participants}: the mapping of an HPDS uuid to one origin id.
 * A participant may have many origin ids; {@code (sourceId, source)} is unique.
 *
 * @param hpdsUuid generated HPDS identity
 * @param sourceId the origin id value (e.g. a dbGaP id or a study-specific id)
 * @param source   the category of the id, which scopes its uniqueness
 */
public record Participant(UUID hpdsUuid, String sourceId, String source) {
}
