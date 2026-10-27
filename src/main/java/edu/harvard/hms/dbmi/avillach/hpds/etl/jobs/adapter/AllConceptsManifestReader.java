package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads {@code allConcepts_manifest.csv} as {@code GenerateAllConceptsManifestJob} writes it (header
 * {@code partition,s3_uri,version_id,last_updated,md5}; row 1 {@code global}, then {@code {study}.c{code}}) and
 * turns it into the plan the adapter works to. Columns beyond the fifth are ignored, so a later manifest that adds
 * {@code rows} or {@code excluded} still reads.
 */
final class AllConceptsManifestReader {

    static final String HEADER = "partition,s3_uri,version_id,last_updated,md5";
    static final String GLOBAL_PARTITION = "global";
    /** The runner's partition-name rule ({@code PARTITION_NAME_RE} of its lib.sh). */
    static final Pattern RUNNER_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    static final Pattern CODE_TOKEN = Pattern.compile("[A-Za-z0-9_]+");
    static final Pattern STUDY_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._]*");
    /** A consent code of a real consent group: a positive integer. Anything else is refused unless allowed. */
    static final Pattern STANDARD_CODE = Pattern.compile("[1-9][0-9]*");

    private AllConceptsManifestReader() {
    }

    /** One manifest row. */
    record ManifestRow(String partition, String s3Uri, String versionId, String lastUpdated, String md5) {
    }

    /** An in-scope consent partition. {@code index} and {@code studyIndex} are positions in the plan's sorted lists. */
    record PartitionSpec(String name, String study, String code, ManifestRow row, int index, int studyIndex) {
        String file() {
            return name + "-allConcepts.csv";
        }
    }

    /**
     * What the adapter works to: the global row, the in-scope partitions, and what was left out. Partition and study
     * indexes number the in-scope ones first ({@code 0 .. partitions.size()-1}, {@code 0 .. studiesInScope-1}) and the
     * ones {@code --studies} left out after them, so the patient index can see a patient's every consent: the
     * number of studies a patient is in decides how its id rows are read, whatever is adapted.
     */
    static final class Plan {
        ManifestRow global;
        /** The in-scope partitions, sorted by name. */
        final List<PartitionSpec> partitions = new ArrayList<>();
        /** Name to index, in-scope and left-out partitions. */
        final Map<String, Integer> partitionIndex = new HashMap<>();
        /** In-scope studies, sorted, then the left-out ones. */
        final List<String> studies = new ArrayList<>();
        final Map<String, Integer> studyIndex = new HashMap<>();
        int studiesInScope;
        /** Study of every partition, in-scope and left-out, by partition index. */
        final List<Integer> studyOfAllPartitions = new ArrayList<>();
        /** Partitions and studies of the manifest that {@code --studies} left out. */
        final Set<String> excludedPartitions = new TreeSet<>();
        final Set<String> excludedStudies = new TreeSet<>();
        /** Every consent code of every row of the manifest, in scope or not. */
        final Set<String> codesSeen = new TreeSet<>();
        int manifestRows;

        boolean knownStudy(String study) {
            return studyIndex.containsKey(study) || excludedStudies.contains(study);
        }

        int[] studyOfPartition() {
            return studyOfAllPartitions.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** A manifest that cannot be read as one. */
    static final class ManifestFormatException extends RuntimeException {
        ManifestFormatException(String message) {
            super(message);
        }
    }

    /** Parses the manifest's rows. */
    static List<ManifestRow> read(InputStream in) throws IOException {
        List<ManifestRow> rows = new ArrayList<>();
        try (CsvRecordReader reader = new CsvRecordReader(in)) {
            CsvRecordReader.Record header = reader.next();
            if (header == null || header.malformed() || header.fields().size() < 5
                    || !String.join(",", header.fields().subList(0, 5)).equals(HEADER)) {
                throw new ManifestFormatException("the manifest's header must start with " + HEADER);
            }
            CsvRecordReader.Record rec;
            while ((rec = reader.next()) != null) {
                if (rec.blank()) {
                    continue;
                }
                if (rec.malformed() || rec.fields().size() < 5) {
                    throw new ManifestFormatException("manifest record " + rec.number() + " is not a row of at least 5 columns");
                }
                List<String> f = rec.fields();
                rows.add(new ManifestRow(f.get(0).trim(), f.get(1).trim(), f.get(2).trim(), f.get(3).trim(), f.get(4).trim()));
            }
        }
        return rows;
    }

    /**
     * Applies the manifest rules (ADR design 2026-10-27) and {@code --studies}; every problem found is added to
     * {@code report} as an error, so one run shows them all. The plan is meaningful only when the report has none.
     *
     * @param studiesAllowed  empty for every study, else the only studies to adapt
     * @param codesAllowed    consent codes accepted although they are not positive integers
     */
    static Plan plan(List<ManifestRow> rows, String manifestUri, Set<String> studiesAllowed, Set<String> codesAllowed,
                     ValidationReport report) {
        Plan plan = new Plan();
        plan.manifestRows = rows.size();
        Set<String> namesLower = new TreeSet<>();
        Map<String, ManifestRow> inScope = new TreeMap<>();
        Map<String, String[]> parsed = new HashMap<>();
        for (ManifestRow r : rows) {
            if (!namesLower.add(r.partition().toLowerCase(Locale.ROOT))) {
                report.error("DUPLICATE_PARTITION", "partition '" + r.partition() + "' appears twice in the manifest (names are compared "
                        + "without regard to case, as the runner does)", "partition " + r.partition());
                continue;
            }
            if (r.partition().equals(GLOBAL_PARTITION)) {
                plan.global = r;
                continue;
            }
            String[] sc = studyAndCode(r.partition());
            if (sc != null) {
                plan.codesSeen.add(sc[1]);
                parsed.put(r.partition(), sc);
                if (!studiesAllowed.isEmpty() && !studiesAllowed.contains(sc[0])) {
                    plan.excludedPartitions.add(r.partition());
                    plan.excludedStudies.add(sc[0]);
                    continue;
                }
            }
            inScope.put(r.partition(), r);
        }
        if (plan.global == null) {
            report.error("GLOBAL_ROW_MISSING", "the manifest has no '" + GLOBAL_PARTITION + "' row", "manifest");
        } else {
            checkRow(plan.global, manifestUri, report);
        }
        for (ManifestRow r : inScope.values()) {
            checkPartitionName(r.partition(), parsed.get(r.partition()), codesAllowed, report);
            checkRow(r, manifestUri, report);
        }
        Set<String> inScopeStudies = new TreeSet<>();
        for (String name : inScope.keySet()) {
            String[] sc = parsed.get(name);
            if (sc != null) {
                inScopeStudies.add(sc[0]);
            }
        }
        for (String s : studiesAllowed) {
            if (!inScopeStudies.contains(s)) {
                report.error("STUDY_NOT_IN_MANIFEST", "--studies names '" + s + "', which has no partition in the manifest", "--studies");
            }
        }
        if (inScope.isEmpty()) {
            report.error("NO_PARTITIONS", "no consent partition is left to adapt", "manifest");
        }
        if (report.hasErrors()) {
            return plan;
        }
        plan.studies.addAll(inScopeStudies);
        plan.studiesInScope = plan.studies.size();
        plan.studies.addAll(plan.excludedStudies);
        for (int i = 0; i < plan.studies.size(); i++) {
            plan.studyIndex.put(plan.studies.get(i), i);
        }
        int idx = 0;
        for (Map.Entry<String, ManifestRow> e : inScope.entrySet()) {
            String[] sc = parsed.get(e.getKey());
            plan.partitions.add(new PartitionSpec(e.getKey(), sc[0], sc[1], e.getValue(), idx, plan.studyIndex.get(sc[0])));
            plan.partitionIndex.put(e.getKey(), idx);
            plan.studyOfAllPartitions.add(plan.studyIndex.get(sc[0]));
            idx++;
        }
        for (String name : plan.excludedPartitions) {
            plan.partitionIndex.put(name, idx);
            plan.studyOfAllPartitions.add(plan.studyIndex.get(parsed.get(name)[0]));
            idx++;
        }
        return plan;
    }

    /** {study, code} of {@code {study}.c{code}} (split at the last ".c"), or null if the name has no such form. */
    static String[] studyAndCode(String partition) {
        int at = partition.lastIndexOf(".c");
        if (at < 1 || at + 2 >= partition.length()) {
            return null;
        }
        return new String[]{partition.substring(0, at), partition.substring(at + 2)};
    }

    private static void checkPartitionName(String name, String[] sc, Set<String> codesAllowed, ValidationReport report) {
        String where = "partition " + name;
        if (!RUNNER_NAME.matcher(name).matches() || name.contains("..")) {
            report.error("BAD_PARTITION_NAME", "partition name '" + name + "' is refused by the runner (it must match "
                    + RUNNER_NAME.pattern() + " and hold no '..')", where);
            return;
        }
        if (name.indexOf('-') >= 0) {
            report.error("HYPHENATED_PARTITION", "partition name '" + name + "' holds '-': an old CSVLoader would cut the partition "
                    + "at the first '-'", where);
            return;
        }
        if (sc == null) {
            report.error("BAD_PARTITION_NAME", "partition '" + name + "' is not of the form {study}.c{code}", where);
            return;
        }
        if (!STUDY_TOKEN.matcher(sc[0]).matches()) {
            report.error("BAD_PARTITION_NAME", "the study of partition '" + name + "' is not a plain identifier", where);
        }
        if (!CODE_TOKEN.matcher(sc[1]).matches()) {
            report.error("BAD_CONSENT_CODE", "the consent code '" + sc[1] + "' of partition '" + name + "' must match "
                    + CODE_TOKEN.pattern(), where);
        } else if (!STANDARD_CODE.matcher(sc[1]).matches() && !codesAllowed.contains(sc[1])) {
            report.error("CONSENT_CODE_NOT_ALLOWED", "the consent code '" + sc[1] + "' of partition '" + name
                    + "' is not a positive integer; list it in --allow-consent-codes to adapt it anyway", where);
        }
    }

    private static void checkRow(ManifestRow r, String manifestUri, ValidationReport report) {
        String where = "partition " + r.partition();
        if (r.s3Uri().isEmpty()) {
            report.error("MANIFEST_BAD_URI", "the manifest row of '" + r.partition() + "' has no s3_uri", where);
        } else if (r.s3Uri().startsWith("s3://")) {
            if (r.versionId().isEmpty() || r.versionId().equals("null")) {
                report.error("MANIFEST_VERSION_MISSING", "the manifest row of '" + r.partition() + "' names an S3 object but no "
                        + "version id, so its content cannot be pinned", where);
            }
        } else if (manifestUri.startsWith("s3://") && !Path.of(r.s3Uri()).isAbsolute()) {
            report.error("MANIFEST_BAD_URI", "the manifest row of '" + r.partition() + "' is neither an s3:// URI nor an absolute path",
                    where);
        }
        if (!r.md5().matches("[0-9a-fA-F]{32}")) {
            report.error("MANIFEST_MD5_MISSING", "the manifest row of '" + r.partition() + "' has no 32-digit hex md5", where);
        }
    }

    /**
     * The location to read for a manifest row: an {@code s3://} URI or absolute path as it is; a relative local
     * path is taken relative to the manifest's directory (the committed test vectors).
     */
    static String resolve(String manifestUri, String rowUri) {
        if (rowUri.startsWith("s3://") || rowUri.startsWith("file://") || Path.of(rowUri).isAbsolute()) {
            return rowUri;
        }
        Path manifest = Path.of(manifestUri.startsWith("file://") ? manifestUri.substring(7) : manifestUri).toAbsolutePath();
        return manifest.getParent().resolve(rowUri).normalize().toString();
    }
}
