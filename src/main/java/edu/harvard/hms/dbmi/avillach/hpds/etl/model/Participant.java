package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

/**
 * A row in {@code participants}: the mapping of an HPDS id to one origin id.
 * A participant may have many origin ids; {@code (sourceId, source)} is unique.
 *
 * @param hpdsId   generated HPDS identity (sequential integer from {@code hpds_id_seq})
 * @param sourceId the origin id value (e.g. a dbGaP id or a study-specific id)
 * @param source   the category of the id, which scopes its uniqueness
 */
public record Participant(long hpdsId, String sourceId, String source) {
}
