package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.Strings;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.Severity;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * MIGRATION. Reads the dbGaP phenotype-release files that stand in for a study's SSTR when it
 * has none, or a malformed one:
 *
 * <ul>
 *   <li>{@code {studyid}*Subject.MULTI.txt}: one row per subject; {@code dbGaP_Subject_ID}
 *       and a numeric consent column (see {@link #CONSENT_COLUMNS}).</li>
 *   <li>{@code {studyid}*Sample.MULTI.txt} (optional): one row per sample;
 *       {@code dbGaP_Subject_ID}, {@code dbGaP_Sample_ID}, and usually the submitted
 *       {@code SAMPLE_ID}.</li>
 *   <li>{@code {studyid}*Subject.data_dict.xml} (optional): decodes the numeric consent codes
 *       into labels such as {@code General Research Use (GRU)}.</li>
 * </ul>
 *
 * <p>Both MULTI files are tab-delimited with a {@code #}-prefixed preamble before the header.
 * Headers are matched case-insensitively. Structural problems (a required column missing, an
 * unreadable file) throw {@link DataException}; row-level problems are skipped and returned as
 * {@link ValidationIssue}s so the caller can report them.
 *
 * <p>Not a Spring bean: only the migration uses it, so it is never created in a permanent run.
 */
public class DbgapMultiFileReader {

    static final String COL_DBGAP_SUBJECT_ID = "dbGaP_Subject_ID";
    static final String COL_DBGAP_SAMPLE_ID = "dbGaP_Sample_ID";
    static final String COL_SUBMITTED_SAMPLE_ID = "SAMPLE_ID";
    /** Consent column names seen across studies, most preferred first. Matched case-insensitively. */
    static final List<String> CONSENT_COLUMNS = List.of("consent", "consent_1217", "consentcol", "gencons");
    /** The study's own subject id, which a patient mapping file may use instead of the dbGaP id. */
    static final List<String> SUBJECT_ID_COLUMNS = List.of("SUBJECT_ID", "SUBJID");
    /** dbGaP's "did not consent / did not participate" group; never loaded. */
    static final String NOT_CONSENTED_CODE = "0";

    private static final int MAX_EXAMPLES = 10;
    private static final Pattern NUMERIC = Pattern.compile("\\d+");
    /** The last parenthesized group of a label: {@code General Research Use (GRU)} &rarr; {@code GRU}. */
    private static final Pattern TRAILING_PARENS = Pattern.compile("\\(([^()]+)\\)\\s*$");

    private final IoResolver io;
    private final DelimitedReader delimitedReader;

    public DbgapMultiFileReader(IoResolver io, DelimitedReader delimitedReader) {
        this.io = io;
        this.delimitedReader = delimitedReader;
    }

    /**
     * Reads a Subject MULTI file. A subject is loaded with the consent of the first row that has a
     * usable (numeric, non-zero) consent; consent {@code 0} rows are dropped.
     *
     * @throws DataException if the file has no {@code dbGaP_Subject_ID} or consent column, or no data rows
     */
    public SubjectTable readSubjects(String path) {
        String file = fileName(path);
        Map<String, Subject> consented = new LinkedHashMap<>();
        Set<String> notConsented = new LinkedHashSet<>();
        Map<String, String> notConsentedSubjectIds = new LinkedHashMap<>();
        IssueTally tally = new IssueTally(file);
        String dbgapCol = null;
        String consentCol = null;
        String subjectIdCol = null;
        long rowsRead = 0;

        InputStream in = io.openInput(path);
        try (Stream<Map<String, String>> stream = delimitedReader.streamSkippingComments(in, DelimitedReader.TAB)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                if (dbgapCol == null) {
                    Map<String, String> headers = headerIndex(row);
                    dbgapCol = requireColumn(headers, COL_DBGAP_SUBJECT_ID, file);
                    List<String> consentCols = CONSENT_COLUMNS.stream()
                            .map(c -> headers.get(c.toLowerCase(Locale.ROOT)))
                            .filter(c -> c != null)
                            .toList();
                    if (consentCols.isEmpty()) {
                        throw new DataException(file + ": no consent column; expected one of " + CONSENT_COLUMNS
                                + " (case-insensitive). Found: " + row.keySet());
                    }
                    consentCol = consentCols.get(0);
                    if (consentCols.size() > 1) {
                        tally.add(ValidationIssue.warning("MULTIPLE_CONSENT_COLUMNS", file + ": found consent columns "
                                        + consentCols + "; using '" + consentCol + "'",
                                context(file, consentCols.size(), consentCols)));
                    }
                    subjectIdCol = SUBJECT_ID_COLUMNS.stream()
                            .map(c -> headers.get(c.toLowerCase(Locale.ROOT)))
                            .filter(c -> c != null)
                            .findFirst().orElse(null);
                    if (subjectIdCol == null) {
                        tally.add(ValidationIssue.info("NO_SUBJECT_ID_COLUMN", file + ": no " + SUBJECT_ID_COLUMNS
                                + " column; patient mapping rows can only be matched by " + COL_DBGAP_SUBJECT_ID));
                    }
                }
                rowsRead++;
                String dbgapId = Strings.trimToNull(row.get(dbgapCol));
                if (dbgapId == null) {
                    tally.count("BLANK_DBGAP_SUBJECT_ID", "row " + rowsRead);
                    continue;
                }
                String rawConsent = Strings.trimToNull(row.get(consentCol));
                if (rawConsent == null) {
                    tally.count("BLANK_CONSENT", dbgapId);
                    continue;
                }
                String code = normalizeCode(rawConsent);
                if (code == null) {
                    tally.count("NON_NUMERIC_CONSENT", dbgapId + "=" + rawConsent);
                    continue;
                }
                if (NOT_CONSENTED_CODE.equals(code)) {
                    tally.count("CONSENT_ZERO_DROPPED", dbgapId);
                    notConsented.add(dbgapId);
                    String subjectId = subjectIdCol == null ? null : Strings.trimToNull(row.get(subjectIdCol));
                    if (subjectId != null) {
                        notConsentedSubjectIds.putIfAbsent(dbgapId, subjectId);
                    }
                    continue;
                }
                Subject existing = consented.get(dbgapId);
                if (existing != null) {
                    if (!existing.consentCode().equals(code)) {
                        tally.count("CONFLICTING_CONSENT", dbgapId + "=" + existing.consentCode() + "/" + code);
                    }
                    continue;
                }
                String subjectId = subjectIdCol == null ? null : Strings.trimToNull(row.get(subjectIdCol));
                consented.put(dbgapId, new Subject(dbgapId, subjectId, code));
            }
        }

        if (rowsRead == 0) {
            throw new DataException(file + ": no data rows");
        }
        // A subject consented on any row is loaded; only subjects never consented count as dropped.
        notConsented.removeAll(consented.keySet());
        notConsentedSubjectIds.keySet().retainAll(notConsented);
        return new SubjectTable(file, consentCol, subjectIdCol, consented, notConsented,
                new LinkedHashSet<>(notConsentedSubjectIds.values()), rowsRead, tally.issues());
    }

    /**
     * Reads a Sample MULTI file.
     *
     * @throws DataException if the file has no {@code dbGaP_Subject_ID} or {@code dbGaP_Sample_ID} column
     */
    public SampleTable readSamples(String path) {
        String file = fileName(path);
        List<SampleRow> rows = new ArrayList<>();
        IssueTally tally = new IssueTally(file);
        String subjectCol = null;
        String sampleCol = null;
        String submittedCol = null;
        long rowsRead = 0;

        InputStream in = io.openInput(path);
        try (Stream<Map<String, String>> stream = delimitedReader.streamSkippingComments(in, DelimitedReader.TAB)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                if (subjectCol == null) {
                    Map<String, String> headers = headerIndex(row);
                    subjectCol = requireColumn(headers, COL_DBGAP_SUBJECT_ID, file);
                    sampleCol = requireColumn(headers, COL_DBGAP_SAMPLE_ID, file);
                    submittedCol = headers.get(COL_SUBMITTED_SAMPLE_ID.toLowerCase(Locale.ROOT));
                    if (submittedCol == null) {
                        tally.add(ValidationIssue.info("NO_SUBMITTED_SAMPLE_ID_COLUMN", file + ": no "
                                + COL_SUBMITTED_SAMPLE_ID + " column; only dbGaP sample ids will be loaded"));
                    }
                }
                rowsRead++;
                String dbgapSubjectId = Strings.trimToNull(row.get(subjectCol));
                if (dbgapSubjectId == null) {
                    tally.count("BLANK_SAMPLE_SUBJECT_ID", "row " + rowsRead);
                    continue;
                }
                String dbgapSampleId = Strings.trimToNull(row.get(sampleCol));
                String submitted = submittedCol == null ? null : Strings.trimToNull(row.get(submittedCol));
                if (dbgapSampleId == null) {
                    tally.count("BLANK_DBGAP_SAMPLE_ID", dbgapSubjectId);
                }
                if (dbgapSampleId == null && submitted == null) {
                    continue;
                }
                rows.add(new SampleRow(dbgapSubjectId, dbgapSampleId, submitted));
            }
        }
        if (rowsRead == 0) {
            tally.add(ValidationIssue.warning("NO_SAMPLE_ROWS", file + ": no data rows; no samples loaded",
                    context(file, 0, List.of())));
        }
        return new SampleTable(file, rows, rowsRead, tally.issues());
    }

    /**
     * Reads the {@code <value code="N">label</value>} decodings of the variable named
     * {@code consentColumn} (case-insensitive) from a dbGaP {@code data_dict.xml}.
     *
     * @return code &rarr; label, codes normalized as {@link #normalizeCode}; empty if the variable is absent
     * @throws DataException if the file is not well-formed XML
     */
    public Map<String, String> readConsentDecodings(String path, String consentColumn) {
        Document doc;
        try (InputStream in = io.openInput(path)) {
            doc = newDocumentBuilder().parse(in);
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException(fileName(path) + ": not readable as XML: " + e.getMessage(), e);
        }
        Map<String, String> decodings = new LinkedHashMap<>();
        NodeList variables = doc.getElementsByTagName("variable");
        for (int i = 0; i < variables.getLength(); i++) {
            Element variable = (Element) variables.item(i);
            NodeList names = variable.getElementsByTagName("name");
            if (names.getLength() == 0 || !names.item(0).getTextContent().trim().equalsIgnoreCase(consentColumn)) {
                continue;
            }
            NodeList values = variable.getElementsByTagName("value");
            for (int j = 0; j < values.getLength(); j++) {
                Element value = (Element) values.item(j);
                String code = normalizeCode(value.getAttribute("code"));
                String label = Strings.trimToNull(value.getTextContent());
                if (code != null && label != null) {
                    decodings.putIfAbsent(code, label);
                }
            }
            break;
        }
        return decodings;
    }

    /**
     * The abbreviation in a decoded consent label: the text of its trailing parentheses
     * ({@code Disease-Specific (CVD, IRB) (DS-CVD-IRB)} &rarr; {@code DS-CVD-IRB}), or null if it has none.
     */
    static String abbreviationOf(String label) {
        Matcher m = TRAILING_PARENS.matcher(label);
        return m.find() ? Strings.trimToNull(m.group(1)) : null;
    }

    /** {@code "01"} &rarr; {@code "1"}; null if not a non-negative integer. */
    static String normalizeCode(String raw) {
        String trimmed = Strings.trimToNull(raw);
        if (trimmed == null || !NUMERIC.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed.replaceFirst("^0+(?=\\d)", "");
    }

    /** Lower-cased, trimmed header &rarr; the header as it appears in the row map. */
    private static Map<String, String> headerIndex(Map<String, String> row) {
        Map<String, String> index = new LinkedHashMap<>();
        for (String header : row.keySet()) {
            index.putIfAbsent(header.trim().toLowerCase(Locale.ROOT), header);
        }
        return index;
    }

    private static String requireColumn(Map<String, String> headers, String column, String file) {
        String actual = headers.get(column.toLowerCase(Locale.ROOT));
        if (actual == null) {
            throw new DataException(file + ": missing required column " + column + " (case-insensitive). Found: "
                    + headers.values());
        }
        return actual;
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // The dictionaries are third-party files: refuse DTDs and external entities outright.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static Map<String, Object> context(String file, long count, List<String> examples) {
        return Map.of("file", file, "count", count, "examples", examples);
    }

    /** Collapses per-row problems into one issue per code, with a count and the first few examples. */
    private static final class IssueTally {
        private static final Map<String, String> DESCRIPTIONS = Map.of(
                "BLANK_DBGAP_SUBJECT_ID", "row(s) with a blank " + COL_DBGAP_SUBJECT_ID + " were skipped",
                "BLANK_CONSENT", "subject row(s) with a blank consent were skipped",
                "NON_NUMERIC_CONSENT", "subject row(s) with a non-numeric consent were skipped",
                "CONSENT_ZERO_DROPPED", "subject row(s) with consent 0 (not consented) were dropped",
                "CONFLICTING_CONSENT", "subject row(s) repeated a subject with a different consent; the first was kept",
                "BLANK_SAMPLE_SUBJECT_ID", "sample row(s) with a blank " + COL_DBGAP_SUBJECT_ID + " were skipped",
                "BLANK_DBGAP_SAMPLE_ID", "sample row(s) had a blank " + COL_DBGAP_SAMPLE_ID);

        private final String file;
        private final List<ValidationIssue> fixed = new ArrayList<>();
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final Map<String, List<String>> examples = new LinkedHashMap<>();

        IssueTally(String file) {
            this.file = file;
        }

        void add(ValidationIssue issue) {
            fixed.add(issue);
        }

        void count(String code, String example) {
            counts.merge(code, 1L, Long::sum);
            List<String> list = examples.computeIfAbsent(code, k -> new ArrayList<>());
            if (list.size() < MAX_EXAMPLES) {
                list.add(example);
            }
        }

        List<ValidationIssue> issues() {
            List<ValidationIssue> all = new ArrayList<>(fixed);
            counts.forEach((code, n) -> {
                String message = file + ": " + n + " " + DESCRIPTIONS.get(code) + ". First: " + examples.get(code);
                Map<String, Object> ctx = context(file, n, List.copyOf(examples.get(code)));
                // Dropping consent 0 is the expected treatment, not a defect in the file.
                all.add("CONSENT_ZERO_DROPPED".equals(code)
                        ? new ValidationIssue(Severity.INFO, code, message, null, ctx)
                        : ValidationIssue.warning(code, message, ctx));
            });
            return all;
        }
    }

    /** One loadable subject: a usable, non-zero consent code. */
    public record Subject(String dbgapSubjectId, String subjectId, String consentCode) {
    }

    /**
     * @param consentColumn    the header the consent was read from, as it appears in the file
     * @param subjectIdColumn  the study's own subject id header, or null if the file has none
     * @param consented        dbGaP subject id &rarr; subject, for every subject with a usable consent
     * @param notConsented     dbGaP subject ids seen only with consent 0
     * @param notConsentedSubjectIds  the study's own subject ids of those subjects, where the file has them
     */
    public record SubjectTable(String file, String consentColumn, String subjectIdColumn,
                               Map<String, Subject> consented, Set<String> notConsented,
                               Set<String> notConsentedSubjectIds,
                               long rowsRead, List<ValidationIssue> issues) {
    }

    /** One sample row; either id may be null, not both. */
    public record SampleRow(String dbgapSubjectId, String dbgapSampleId, String submittedSampleId) {
    }

    public record SampleTable(String file, List<SampleRow> rows, long rowsRead, List<ValidationIssue> issues) {
    }
}
