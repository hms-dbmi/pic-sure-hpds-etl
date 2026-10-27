package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.ManifestRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.PartitionSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The adapter's three streaming passes over a manifest's files, writing to local scratch files:
 * <ol>
 *   <li>the global file, to learn which partitions each patient holds ({@link PatientConsentIndex});</li>
 *   <li>the global file again, routing each row (see below) into the partitions' files;</li>
 *   <li>each partition file, whose rows are checked against the index and appended.</li>
 * </ol>
 * Every file is read by its manifest version id and its MD5 is computed while streaming; a mismatch is a
 * {@link DataException} naming the partition. Nothing is held per row except what the routing needs: the
 * patient index, the id rows of patients in several studies, and counters (a patient count per partition lives in the index).
 *
 * <p><b>Global routing</b> (D1: study-scoped, D2: copy):
 * <ul>
 *   <li>{@code _consents} with text {@code S.cN} and no number: that partition only;</li>
 *   <li>{@code _studies_consents\S\...}: every partition of study S that the patient holds;</li>
 *   <li>{@code _source_subject_id}, {@code _source_sample_id}: every partition the patient holds, once each. For a
 *       patient in k studies each distinct id row must occur exactly k times in the global file (the generator
 *       writes a participant's ids once per study they are in); fewer means the ids are study-specific, which
 *       {@code --multi-study-ids=copy} cannot honour and stops the run ({@code ID_ROWS_NOT_PATIENT_LEVEL});
 *       more are duplicates and are merged. A single-study patient's id rows are copied as read: they are not
 *       de-duplicated (the generator writes them once), which keeps memory flat;</li>
 *   <li>any other path: rejected {@code UNKNOWN_GLOBAL_PATH}; a patient with no consent in scope: dropped, counted.</li>
 * </ul>
 */
final class AdaptationEngine {

    private static final Logger log = LoggerFactory.getLogger(AdaptationEngine.class);

    static final String DATA_ROW_WITHOUT_CONSENT = "DATA_ROW_WITHOUT_CONSENT";
    static final String STUDY_PARTITION_MISMATCH = "STUDY_PARTITION_MISMATCH";
    static final String CONSENT_NOT_IN_MANIFEST = "CONSENT_NOT_IN_MANIFEST";
    static final String UNKNOWN_GLOBAL_PATH = "UNKNOWN_GLOBAL_PATH";
    static final String ID_ROWS_NOT_PATIENT_LEVEL = "ID_ROWS_NOT_PATIENT_LEVEL";
    static final String MD5_MISMATCH = "MD5_MISMATCH";
    static final String MULTI_STUDY_PATIENTS = "MULTI_STUDY_PATIENTS";
    static final String GLOBAL = "global";

    /** Lines kept per rejects file; the counts are always complete. */
    static final long REJECT_LINES_CAP = 100_000;
    private static final int MAX_OPEN_FILES = 64;
    private static final int EXAMPLES = 10;

    private enum Kind { CONSENTS, STUDIES_CONSENTS, IDS, NON_CANONICAL, UNKNOWN }

    private interface Handler {
        void handle(CsvRecordReader.Record rec, boolean first) throws IOException;
    }

    private final VersionedObjectSource source;
    private final Plan plan;
    private final AdapterOptions options;
    private final String manifestUri;
    private final PartitionWriter out;
    private final PartitionWriter rejects;
    private final int inScope;

    final PatientConsentIndex index;
    final Tally global = new Tally(GLOBAL);
    final Tally[] parts;
    final long[] globalRows;
    final Map<String, String> md5Seen = new TreeMap<>();
    final List<String> warnings = new ArrayList<>();
    /** The first message per file and reject code, for the report (never a patient's values). */
    final Map<String, String> rejectExamples = new TreeMap<>();
    long timestampsConverted;
    long timestampsBlanked;
    long idRowsBuffered;
    long rejectLinesDropped;

    private final AdapterRowCheck check = new AdapterRowCheck();
    private final Map<Integer, LinkedHashMap<String, int[]>> multiStudyIds = new LinkedHashMap<>();

    AdaptationEngine(VersionedObjectSource source, Plan plan, AdapterOptions options, String manifestUri, Path partitionsDir,
                     Path rejectsDir) throws IOException {
        this.source = source;
        this.plan = plan;
        this.options = options;
        this.manifestUri = manifestUri;
        this.out = new PartitionWriter(partitionsDir, "-allConcepts.csv", MAX_OPEN_FILES);
        this.rejects = new PartitionWriter(rejectsDir, ".csv", 8);
        this.index = new PatientConsentIndex(plan.studyOfPartition());
        int n = plan.partitions.size();
        this.inScope = n;
        this.parts = new Tally[n];
        this.globalRows = new long[n];
        for (PartitionSpec p : plan.partitions) {
            parts[p.index()] = new Tally(p.name());
        }
    }

    /** Runs the three passes. Throws {@link DataException} on a file that is not what the manifest says. */
    void run() throws IOException {
        log.info("Pass 1/3: reading the consents of {}", plan.global.partition());
        scan(GLOBAL, plan.global, this::indexConsents);
        log.info("{} patient(s) hold a consent in scope; {} hold consents of several studies", index.patients(),
                index.multiStudyPatients());
        if (options.refuseMultiStudy() && index.multiStudyPatients() > 0) {
            throw new DataException(MULTI_STUDY_PATIENTS + ": " + index.multiStudyPatients() + " patient(s) hold consents of more than "
                    + "one study and --multi-study-ids=refuse");
        }
        log.info("Pass 2/3: routing the global rows");
        scan(GLOBAL, plan.global, this::routeGlobal);
        flushMultiStudyIds();
        for (PartitionSpec p : plan.partitions) {
            log.info("Pass 3/3: {} ({} of {})", p.name(), p.index() + 1, plan.partitions.size());
            scan(p.name(), p.row(), (rec, first) -> partitionRow(p, rec, first));
        }
        out.flush();
        rejects.flush();
    }

    // ------------------------------------------------------------------------------------------------ reading

    private void scan(String what, ManifestRow row, Handler handler) throws IOException {
        String uri = AllConceptsManifestReader.resolve(manifestUri, row.s3Uri());
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
        try (InputStream raw = source.open(uri, row.versionId());
             DigestInputStream in = new DigestInputStream(raw, md5);
             CsvRecordReader reader = new CsvRecordReader(in)) {
            boolean first = true;
            while (true) {
                CsvRecordReader.Record rec;
                try {
                    rec = reader.next();
                } catch (CharacterCodingException e) {
                    throw new DataException("INVALID_UTF8: " + what + " (" + uri + ") is not valid UTF-8");
                } catch (IOException e) {
                    // the read itself failed (a dropped S3 connection): a read failure naming the object, not a scratch-disk one
                    throw new InfrastructureException("READ_FAILED: " + what + " (" + uri + ", version " + row.versionId()
                            + ") could not be read to the end: " + e.getMessage(), e);
                }
                if (rec == null) {
                    break;
                }
                handler.handle(rec, first);
                first = false;
            }
        } catch (SdkException e) {
            throw new InfrastructureException("READ_FAILED: " + what + " (" + uri + ", version " + row.versionId() + "): " + e.getMessage(), e);
        }
        String actual = HexFormat.of().formatHex(md5.digest());
        md5Seen.put(what, actual);
        if (!actual.equalsIgnoreCase(row.md5())) {
            throw new DataException(MD5_MISMATCH + ": " + what + " (" + uri + ", version " + row.versionId() + ") has md5 " + actual
                    + " but the manifest says " + row.md5().toLowerCase());
        }
    }

    // ------------------------------------------------------------------------------------------------ pass 1

    private void indexConsents(CsvRecordReader.Record rec, boolean first) {
        if (rec.blank() || rec.malformed() || (first && AdapterRowCheck.isHeader(rec.fields()))) {
            return;
        }
        if (!check.run(rec.fields(), rec.embeddedLineBreak()) || kind() != Kind.CONSENTS || !check.nval().isEmpty()) {
            return;
        }
        Integer partition = plan.partitionIndex.get(check.tval());
        if (partition != null) {
            index.grant(check.patient(), partition);
        }
    }

    private Kind kind() {
        String[] s = check.segments();
        String head = s[0];
        if (s.length == 1) {
            switch (head) {
                case "_consents":
                    return Kind.CONSENTS;
                case "_source_subject_id":
                case "_source_sample_id":
                    return Kind.IDS;
                default:
                    break;
            }
        } else if (head.equals("_studies_consents")) {
            return Kind.STUDIES_CONSENTS;
        }
        String lower = head.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("_consents") || lower.equals("_source_subject_id") || lower.equals("_source_sample_id")
                || lower.equals("_studies_consents")) {
            return Kind.NON_CANONICAL;
        }
        return Kind.UNKNOWN;
    }

    // ------------------------------------------------------------------------------------------------ pass 2

    private void routeGlobal(CsvRecordReader.Record rec, boolean first) throws IOException {
        global.input++;
        if (rec.blank()) {
            global.blankLines++;
            return;
        }
        if (first && !rec.malformed() && AdapterRowCheck.isHeader(rec.fields())) {
            global.headers++;
            return;
        }
        if (!accepts(rec)) {
            reject(GLOBAL, global, rec, check.code(), check.message());
            return;
        }
        int patient = check.patient();
        switch (kind()) {
            case CONSENTS -> {
                if (!check.nval().isEmpty()) {
                    reject(GLOBAL, global, rec, AdapterRowCheck.NUMERIC_NVAL, "a _consents row with a numeric value grants nothing");
                    return;
                }
                Integer partition = plan.partitionIndex.get(check.tval());
                if (partition != null && partition < inScope) {
                    deliver(patient, check.outputLine(), new int[]{partition});
                } else if (partition != null) {
                    global.excludedStudy++;
                } else {
                    reject(GLOBAL, global, rec, CONSENT_NOT_IN_MANIFEST, "the consent '" + abbreviate(check.tval())
                            + "' is not a partition of the manifest");
                }
            }
            case STUDIES_CONSENTS -> {
                String study = check.segments()[1];
                Integer studyIdx = plan.studyIndex.get(study);
                if (studyIdx == null) {
                    reject(GLOBAL, global, rec, CONSENT_NOT_IN_MANIFEST, "the study '" + abbreviate(study)
                            + "' has no partition in the manifest");
                } else if (studyIdx >= plan.studiesInScope) {
                    global.excludedStudy++;
                } else if (index.partitionsOf(patient) == null) {
                    global.noConsent++;
                } else {
                    int[] targets = index.partitionsOfStudy(patient, studyIdx);
                    if (targets.length == 0) {
                        global.studyNotHeld++;
                    } else {
                        deliver(patient, check.outputLine(), targets);
                    }
                }
            }
            case IDS -> routeIdRow(patient);
            case NON_CANONICAL -> reject(GLOBAL, global, rec, AdapterRowCheck.NON_CANONICAL_PATH,
                    "a reserved global path written other than exactly (case matters)");
            default -> reject(GLOBAL, global, rec, UNKNOWN_GLOBAL_PATH, "not a global path the adapter knows: '"
                    + abbreviate(check.path()) + "'");
        }
    }

    private void routeIdRow(int patient) throws IOException {
        int[] all = index.partitionsOf(patient);
        int[] held = inScope(all);
        if (all == null) {
            global.noConsent++;
        } else if (held.length == 0) {
            global.excludedStudy++; // the patient's consents are all in studies --studies left out
        } else if (index.studyCount(patient) == 1) {
            deliver(patient, check.outputLine(), held);
        } else {
            noteTimestamp();
            multiStudyIds.computeIfAbsent(patient, p -> new LinkedHashMap<>())
                    .computeIfAbsent(check.outputLine(), l -> new int[1])[0]++;
            idRowsBuffered++;
        }
    }

    /** Writes one global row to each of {@code targets}: that many rows written, one fewer copy. */
    private void deliver(int patient, String line, int[] targets) throws IOException {
        noteTimestamp();
        for (int t : targets) {
            out.write(plan.partitions.get(t).name(), line);
            globalRows[t]++;
        }
        global.written += targets.length;
        global.copies += targets.length - 1;
    }

    /**
     * After the whole global file: each multi-study patient's distinct id rows must have been seen once per study;
     * then each is written once to every partition the patient holds.
     */
    private void flushMultiStudyIds() throws IOException {
        List<String> examples = new ArrayList<>();
        long violations = 0;
        for (Map.Entry<Integer, LinkedHashMap<String, int[]>> e : multiStudyIds.entrySet()) {
            int k = index.studyCount(e.getKey());
            for (Map.Entry<String, int[]> row : e.getValue().entrySet()) {
                if (row.getValue()[0] < k) {
                    violations++;
                    if (examples.size() < EXAMPLES) {
                        examples.add("patient " + e.getKey() + " is in " + k + " studies but an id row under "
                                + rowPath(row.getKey()) + " occurs " + row.getValue()[0] + " time(s)");
                    }
                }
            }
        }
        if (violations > 0) {
            throw new DataException(ID_ROWS_NOT_PATIENT_LEVEL + ": " + violations + " source-id row(s) of patients in several studies "
                    + "do not occur once per study, so the ids are study-specific and copying them to every partition would "
                    + "attribute them to the wrong study. First: " + examples);
        }
        for (Map.Entry<Integer, LinkedHashMap<String, int[]>> e : multiStudyIds.entrySet()) {
            int patient = e.getKey();
            int[] held = inScope(index.partitionsOf(patient));
            int k = index.studyCount(patient);
            for (Map.Entry<String, int[]> row : e.getValue().entrySet()) {
                for (int t : held) {
                    out.write(plan.partitions.get(t).name(), row.getKey());
                    globalRows[t]++;
                        }
                global.written += held.length;
                global.copies += held.length - 1;
                int extra = Math.max(0, row.getValue()[0] - k);
                global.duplicates += extra;
                global.merged += row.getValue()[0] - 1 - extra;
            }
        }
        multiStudyIds.clear();
    }

    /** The in-scope partitions among {@code held} (the patient index also holds the left-out ones). */
    private int[] inScope(int[] held) {
        if (held == null) {
            return new int[0];
        }
        int n = 0;
        for (int p : held) {
            if (p < inScope) {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int p : held) {
            if (p < inScope) {
                out[k++] = p;
            }
        }
        return out;
    }

    private static String rowPath(String line) {
        int a = line.indexOf("\",\"");
        int b = a < 0 ? -1 : line.indexOf("\",\"", a + 3);
        return a < 0 || b < 0 ? "?" : abbreviate(line.substring(a + 3, b));
    }

    // ------------------------------------------------------------------------------------------------ pass 3

    private void partitionRow(PartitionSpec p, CsvRecordReader.Record rec, boolean first) throws IOException {
        Tally t = parts[p.index()];
        t.input++;
        if (rec.blank()) {
            t.blankLines++;
            return;
        }
        if (first && !rec.malformed() && AdapterRowCheck.isHeader(rec.fields())) {
            t.headers++;
            return;
        }
        if (!accepts(rec)) {
            reject(p.name(), t, rec, check.code(), check.message());
            return;
        }
        if (!check.segments()[0].equals(p.study())) {
            reject(p.name(), t, rec, STUDY_PARTITION_MISMATCH, "the first node '" + abbreviate(check.segments()[0])
                    + "' is not the study " + p.study());
            return;
        }
        if (!index.holds(check.patient(), p.index())) {
            reject(p.name(), t, rec, DATA_ROW_WITHOUT_CONSENT, "the patient has no _consents row for " + p.name());
            return;
        }
        noteTimestamp();
        out.write(p.name(), check.outputLine());
        t.written++;
    }

    // ------------------------------------------------------------------------------------------------ shared

    /** Runs the row check; false for a record that is not one CSV record or fails a check (see {@code check.code()}). */
    private boolean accepts(CsvRecordReader.Record rec) {
        if (rec.malformed()) {
            return check.runMalformed();
        }
        return check.run(rec.fields(), rec.embeddedLineBreak());
    }

    private void noteTimestamp() {
        if (check.timestampConverted()) {
            timestampsConverted++;
        }
        if (check.timestampBlanked()) {
            timestampsBlanked++;
        }
    }

    private void reject(String file, Tally tally, CsvRecordReader.Record rec, String code, String message) throws IOException {
        tally.reject(code);
        rejectExamples.putIfAbsent(file + " " + code, "record " + rec.number() + ": " + message);
        if (rejects.lines(file) >= REJECT_LINES_CAP) {
            rejectLinesDropped++;
            return;
        }
        String raw = rec.raw().replace("\"", "\"\"");
        rejects.write(file, "\"" + code + "\"," + rec.number() + ",\"" + raw + "\"");
    }

    static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    // ------------------------------------------------------------------------------------------------ results

    /** Closes the scratch files so they can be read or copied; failures are ignored (a failed run's report is best effort). */
    void flushQuietly() {
        try {
            out.flush();
            rejects.flush();
        } catch (IOException e) {
            log.warn("Could not flush the scratch files: {}", e.getMessage());
        }
    }

    long linesWritten(String partition) {
        return out.lines(partition);
    }

    long rejectLines(String file) {
        return rejects.lines(file);
    }

    Path partitionFile(String partition) {
        return out.file(partition);
    }

    Path rejectsFile(String file) {
        return rejects.file(file);
    }

    Plan plan() {
        return plan;
    }

    /** The patients of the partition: those granted it by a valid {@code _consents} row (see {@link PatientConsentIndex}). */
    long patientsOf(PartitionSpec p) {
        return index.patientsOf(p.index());
    }

    /** Rows written to the partition's file: its own rows plus the global rows delivered to it. */
    long rowsWritten(PartitionSpec p) {
        return parts[p.index()].written + globalRows[p.index()];
    }

    long totalInput() {
        long n = global.input;
        for (Tally t : parts) {
            n += t.input;
        }
        return n;
    }

    long totalWritten() {
        long n = global.written;
        for (Tally t : parts) {
            n += t.written;
        }
        return n;
    }

    long totalRejected() {
        long n = global.rejected;
        for (Tally t : parts) {
            n += t.rejected;
        }
        return n;
    }

    long totalDropped() {
        long n = global.dropped();
        for (Tally t : parts) {
            n += t.dropped();
        }
        return n;
    }

    long totalCopies() {
        return global.copies;
    }

    boolean balanced() {
        boolean ok = global.balanced();
        for (Tally t : parts) {
            ok &= t.balanced();
        }
        return ok && totalInput() + totalCopies() == totalWritten() + totalRejected() + totalDropped();
    }
}
