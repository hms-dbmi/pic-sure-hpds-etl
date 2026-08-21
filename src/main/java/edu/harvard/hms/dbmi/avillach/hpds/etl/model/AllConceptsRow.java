package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

/**
 * One row in an AllConcepts CSV. Immutable with validation on construction.
 *
 * @param hpdsId        the HPDS identifier for this row
 * @param conceptPath   concept path delimited by µ (must start and end with µ)
 * @param numericValue  numeric value; empty string when non-numeric is populated
 * @param nonNumericValue non-numeric value; empty string when numeric is populated
 * @param timestamp     "0" when no timestamp is present
 */
public record AllConceptsRow(
        String hpdsId,
        String conceptPath,
        String numericValue,
        String nonNumericValue,
        String timestamp
) {

    public AllConceptsRow {
        if (hpdsId == null || hpdsId.isBlank()) {
            throw new IllegalArgumentException("hpdsId must not be null or blank");
        }
        if (conceptPath == null || conceptPath.isBlank()) {
            throw new IllegalArgumentException("conceptPath must not be null or blank");
        }
        if (!conceptPath.startsWith("µ") || !conceptPath.endsWith("µ")) {
            throw new IllegalArgumentException(
                    "conceptPath must start and end with µ, got: " + conceptPath);
        }
        if (numericValue == null) {
            numericValue = "";
        }
        if (nonNumericValue == null) {
            nonNumericValue = "";
        }
        if (timestamp == null || timestamp.isBlank()) {
            timestamp = "0";
        }
        if (!numericValue.isEmpty() && !nonNumericValue.isEmpty()) {
            throw new IllegalArgumentException(
                    "numericValue and nonNumericValue are mutually exclusive; both are populated for conceptPath: "
                            + conceptPath);
        }
    }

    public static AllConceptsRow nonNumeric(String hpdsId, String conceptPath, String value) {
        return new AllConceptsRow(hpdsId, conceptPath, "", value, "0");
    }

    public String toCsvLine() {
        return "\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"".formatted(
                escapeQuotes(hpdsId),
                escapeQuotes(conceptPath),
                escapeQuotes(numericValue),
                escapeQuotes(nonNumericValue),
                escapeQuotes(timestamp));
    }

    private static String escapeQuotes(String value) {
        return value.replace("\"", "\"\"");
    }
}
