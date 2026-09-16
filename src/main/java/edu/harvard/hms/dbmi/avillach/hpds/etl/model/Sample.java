package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

/**
 * A row in {@code samples}: the mapping of an HPDS id to a source sample id and the
 * source that sample came from. A participant may have many samples.
 */
public record Sample(long hpdsId, String sourceSampleId, String sampleSource) {
}
