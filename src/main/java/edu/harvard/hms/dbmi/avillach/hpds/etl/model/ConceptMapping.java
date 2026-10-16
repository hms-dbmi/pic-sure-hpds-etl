package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * One row in a mapping CSV: ties a (filename, columnIndex) pair to a concept path and
 * data type. The mapping CSV is headerless (a header row is tolerated), comma-separated, with 5 positional columns:
 * key (filename:colIndex), rootNode (concept path), supPath, dataType (TEXT/NUMERIC), options.
 *
 * @param fileName    the decoded-data CSV filename (extracted from key)
 * @param columnIndex the 0-based column index within that file (extracted from key)
 * @param conceptPath the concept path (µ-delimited in the new system)
 * @param dataType    TEXT or NUMERIC
 * @param patientCol  the column index of the patient id (from options, default 0)
 */
public record ConceptMapping(
        String fileName,
        int columnIndex,
        String conceptPath,
        DataType dataType,
        int patientCol
) {

    public enum DataType {
        TEXT, NUMERIC;

        public static DataType parse(String s) {
            if (s == null || s.isBlank()) {
                return TEXT;
            }
            return switch (s.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "NUMERIC" -> NUMERIC;
                default -> TEXT;
            };
        }
    }

    public ConceptMapping {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (columnIndex < 0) {
            throw new IllegalArgumentException("columnIndex must be >= 0, got: " + columnIndex);
        }
        if (conceptPath == null || conceptPath.isBlank()) {
            throw new IllegalArgumentException("conceptPath must not be blank");
        }
        if (dataType == null) {
            dataType = DataType.TEXT;
        }
    }

    private static final java.util.regex.Pattern HEADER_KEY = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_ ]*");

    /** Mappings parsed from a mapping CSV, plus how many rows were dropped as unusable. */
    public record Parsed(List<ConceptMapping> mappings, long droppedRows) {
    }

    public static List<ConceptMapping> parse(InputStream in, DelimitedReader reader) {
        return parseWithStats(in, reader).mappings();
    }

    /**
     * Like {@link #parse}, but also counts the rows it drops: fewer than 4 columns, a key that is
     * not {@code file:int}, or a blank root node. Blank lines are skipped without being counted,
     * and so is a header row: the first non-blank row, when its key is a bare column title
     * (letters, digits, spaces, underscores -- no {@code :} or {@code .}).
     */
    public static Parsed parseWithStats(InputStream in, DelimitedReader reader) {
        List<ConceptMapping> mappings = new ArrayList<>();
        long droppedRows = 0;
        boolean firstRow = true;
        try (Stream<List<String>> rows = reader.streamRows(in, DelimitedReader.COMMA)) {
            for (List<String> row : (Iterable<List<String>>) rows::iterator) {
                if (row.stream().allMatch(cell -> cell.replace("\"", "").isBlank())) {
                    continue;
                }
                boolean headerCandidate = firstRow;
                firstRow = false;

                String key = row.get(0).replace("\"", "").trim();
                String[] keyParts = key.split(":");
                int colIdx;
                try {
                    colIdx = keyParts.length == 2 ? Integer.parseInt(keyParts[1].trim()) : -1;
                } catch (NumberFormatException e) {
                    colIdx = -1;
                }
                if (colIdx < 0) {
                    // Only a column title (a bare word, e.g. KEY) is taken for a header; a mistyped
                    // first mapping like "file.csv:x" is still counted as dropped.
                    if (!(headerCandidate && HEADER_KEY.matcher(key).matches())) {
                        droppedRows++;
                    }
                    continue;
                }
                if (row.size() < 4) {
                    droppedRows++;
                    continue;
                }
                String fileName = keyParts[0];
                String rootNode = row.get(1).replace("\"", "").trim();
                String dataTypeStr = row.get(3).replace("\"", "").trim();
                String options = row.size() > 4 ? row.get(4).replace("\"", "").trim() : "";

                if (fileName.isBlank() || rootNode.isEmpty()) {
                    droppedRows++;
                    continue;
                }

                String conceptPath = normalizeConceptPath(rootNode);
                DataType dataType = DataType.parse(dataTypeStr);
                int patientCol = extractPatientCol(options);

                mappings.add(new ConceptMapping(fileName, colIdx, conceptPath, dataType, patientCol));
            }
        }
        if (mappings.isEmpty()) {
            throw new DataException("Mapping file produced no valid mappings (" + droppedRows + " row(s) dropped)");
        }
        return new Parsed(mappings, droppedRows);
    }

    static String normalizeConceptPath(String path) {
        String normalized = path.replace('\\', 'µ');
        if (!normalized.startsWith("µ")) {
            normalized = "µ" + normalized;
        }
        if (!normalized.endsWith("µ")) {
            normalized = normalized + "µ";
        }
        return normalized;
    }

    private static int extractPatientCol(String options) {
        if (options == null || options.isEmpty()) {
            return 0;
        }
        for (String opt : options.split(";")) {
            String[] kv = opt.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("patientcol")) {
                try {
                    return Integer.parseInt(kv[1].trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }
}
