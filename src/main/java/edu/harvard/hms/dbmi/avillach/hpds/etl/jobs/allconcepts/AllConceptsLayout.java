package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import java.util.regex.Pattern;

/**
 * The one definition of the per-study allConcepts layout under an output root:
 * {@code {root}/{study_id}/allConcepts/c{code}/{study_id}_allConcepts_c{code}.csv}.
 * Every job that writes, cleans up, or merges these files resolves paths here, so they cannot
 * drift apart.
 */
public final class AllConceptsLayout {

    /** A consent-group folder name under a study's allConcepts folder: {@code c} plus the code. */
    public static final Pattern CONSENT_FOLDER_PATTERN = Pattern.compile("c[^/]+");

    private AllConceptsLayout() {
    }

    /** The consent-group label, and folder name, for a consent code: {@code c{code}}. */
    public static String consentLabel(String consentCode) {
        return "c" + consentCode;
    }

    /** {@code {root}/{study_id}/allConcepts/}; {@code root} must end with {@code /}. */
    public static String studyAllConceptsDir(String root, String studyId) {
        return root + studyId + "/allConcepts/";
    }

    /** {@code {study_id}_allConcepts_{consentLabel}.csv}. */
    public static String consentFileName(String studyId, String consentLabel) {
        return studyId + "_allConcepts_" + consentLabel + ".csv";
    }

    /** {@code {root}/{study_id}/allConcepts/{consentLabel}/{study_id}_allConcepts_{consentLabel}.csv}. */
    public static String consentFile(String root, String studyId, String consentLabel) {
        return studyAllConceptsDir(root, studyId) + consentLabel + "/" + consentFileName(studyId, consentLabel);
    }
}
