package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * One row in a mapping CSV: ties a (filename, columnIndex) pair to a concept path and
 * data type. The mapping CSV is headerless, comma-separated, with 5 positional columns:
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

    public static List<ConceptMapping> parse(InputStream in, DelimitedReader reader) {
        List<ConceptMapping> mappings = new ArrayList<>();
        try (Stream<List<String>> rows = reader.streamRows(in, DelimitedReader.COMMA)) {
            for (List<String> row : (Iterable<List<String>>) rows::iterator) {
                if (row.size() < 4) {
                    continue;
                }
                String key = row.get(0).replace("\"", "").trim();
                String rootNode = row.get(1).replace("\"", "").trim();
                String dataTypeStr = row.get(3).replace("\"", "").trim();
                String options = row.size() > 4 ? row.get(4).replace("\"", "").trim() : "";

                String[] keyParts = key.split(":");
                if (keyParts.length != 2) {
                    continue;
                }

                String fileName = keyParts[0];
                int colIdx;
                try {
                    colIdx = Integer.parseInt(keyParts[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }

                if (rootNode.isEmpty()) {
                    continue;
                }

                String conceptPath = normalizeConceptPath(rootNode);
                DataType dataType = DataType.parse(dataTypeStr);
                int patientCol = extractPatientCol(options);

                mappings.add(new ConceptMapping(fileName, colIdx, conceptPath, dataType, patientCol));
            }
        }
        if (mappings.isEmpty()) {
            throw new DataException("Mapping file produced no valid mappings");
        }
        return mappings;
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
