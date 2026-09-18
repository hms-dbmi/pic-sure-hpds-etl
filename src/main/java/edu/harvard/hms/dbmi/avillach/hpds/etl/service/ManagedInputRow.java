package edu.harvard.hms.dbmi.avillach.hpds.etl.service;

/**
 * One study as described by the managed inputs (the BDC "Managed Inputs" sheet).
 */
public record ManagedInputRow(
        String abv,
        String studyId,
        String version,
        String phase,
        String versionUpdate,
        String previousVersion,
        String previousPhase,
        String studyFullName,
        String studyType,
        String bdcPrograms,
        String dataType,
        String dccHarmonized,
        String hasMulti,
        String useManualTableMethods,
        String moreInfoLink,
        String additionalInfoUrl,
        String additionalInfoLabel,
        String requestAccessText,
        String phenoIngestNhlbiAccount,
        String phenoIngestBuckets,
        String gen3AuthzProgramName,
        String gen3AuthzProjectName,
        String subjectTypes,
        boolean isReady,
        boolean isProcessed) {

    /** Convenience factory for tests that only care about abv, studyId, and isReady. */
    public static ManagedInputRow of(String abv, String studyId, boolean isReady) {
        return new ManagedInputRow(abv, studyId, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", isReady, false);
    }
}
