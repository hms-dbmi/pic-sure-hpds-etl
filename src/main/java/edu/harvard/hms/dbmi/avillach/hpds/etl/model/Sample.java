package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

/**
 * A row in {@code samples}: the mapping of an HPDS id to a source sample id and the
 * source that sample came from, attributed to the study whose load wrote it. A participant may
 * have many samples, and a participant in several studies (one dbGaP subject, one HPDS id) has
 * each study's samples under that study.
 */
public record Sample(long hpdsId, String studyId, String sourceSampleId, String sampleSource) {

    public Sample {
        if (studyId == null || studyId.isBlank()) {
            throw new IllegalArgumentException("studyId must not be blank");
        }
    }
}
