package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

import java.util.UUID;

/**
 * A row in {@code samples}: the mapping of an HPDS uuid to a source sample id and the
 * source that sample came from. A participant may have many samples.
 */
public record Sample(UUID hpdsUuid, String sourceSampleId, String sampleSource) {
}
