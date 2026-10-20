package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares one study's participants per consent group -- in the participant DB (authoritative), in
 * this run's load report, and in dbGaP -- and turns the result into one {@link Row} per consent code.
 * Pure: no I/O, so every rule is unit-testable on its own.
 *
 * <p>Consent codes are matched after {@link #normalizeConsent normalizing} ("c1", "01" and 1 are all
 * "1"). A consent present on only one side counts as 0 on the other. Consent 0 is compared like any
 * other code.
 */
final class ConsentCountComparison {

    private ConsentCountComparison() {
    }

    /** A row's finding. MATCH is the absence of every other one. */
    enum Status {
        MATCH(false),
        /** dbGaP and the DB both have the consent group, with different counts. */
        COUNT_MISMATCH(false),
        /** dbGaP lists subjects in a consent group the DB has no participants in. */
        MISSING_IN_DB(false),
        /** The DB has participants in a consent group dbGaP does not list. */
        MISSING_IN_DBGAP(false),
        /** dbGaP lists the consent group without a usable subject_count. */
        DBGAP_COUNT_MISSING(false),
        /** This run's load report disagrees with the DB (which is authoritative). */
        REPORT_DB_MISMATCH(false),
        /** The managed inputs give the study no usable Version, so dbGaP was not asked. */
        NO_VERSION(true),
        /** dbGaP has no such study version (HTTP 400, error_code 2). */
        VERSION_NOT_FOUND(true),
        /** dbGaP answered for a different accession or version than the one requested. */
        VERSION_MISMATCH(true),
        /** dbGaP could not be reached after every retry; the study was not checked. */
        UNVERIFIED(true);

        private final boolean studyLevel;

        Status(boolean studyLevel) {
            this.studyLevel = studyLevel;
        }

        /** True for a finding about the whole study (applied to each of its rows), not one consent group. */
        boolean studyLevel() {
            return studyLevel;
        }
    }

    /** A consent group in the DB: its distinct participants and abbreviation. */
    record DbConsent(long participants, String abbreviation) {
    }

    /**
     * Everything known about one study before dbGaP is asked.
     *
     * @param version    normalized ("v4"), or null when {@code rawVersion} is not a usable version
     * @param rawVersion the managed inputs' Version cell as given
     * @param db         normalized consent code to its DB count; empty if the DB holds none
     * @param load       normalized consent code to participants loaded this run, or null when this run's
     *                   load report does not cover the study (then nothing is compared against it)
     * @param loadSource where the load report says the participants came from (e.g. "sstr", "multi"), or null
     */
    record StudyInput(String studyId, String abv, String version, String rawVersion, Map<String, DbConsent> db,
                      Map<String, Long> load, String loadSource) {
    }

    /** One line of qa_consent_counts.csv. Counts are null where unknown. */
    record Row(String studyId, String version, String consentCode, String consentShortName, Long dbgapSubjects,
               long dbParticipants, Long loadParticipants, String loadSource, List<Status> statuses, String note) {

        String status() {
            return statuses.isEmpty() ? Status.MATCH.name()
                    : String.join(";", statuses.stream().map(Status::name).toList());
        }

        boolean matches() {
            return statuses.isEmpty();
        }
    }

    /**
     * @param lookup      dbGaP's answer, or null when it was not asked (no usable version)
     * @param studyStatus the study-level finding, or null
     * @param studyNote   why, for a study-level finding
     */
    record StudyResult(StudyInput input, DbgapLookup lookup, Status studyStatus, String studyNote, List<Row> rows) {

        /** dbGaP answered for exactly the requested study version. */
        boolean verified() {
            return lookup != null && lookup.outcome() == DbgapLookup.Outcome.FOUND && studyStatus == null;
        }
    }

    private static final Pattern CONSENT = Pattern.compile("[cC]?(\\d{1,18})");
    private static final Pattern VERSION = Pattern.compile("[vV]?(\\d{1,9})");

    /** Numeric codes in numeric order, then any others alphabetically. */
    static final Comparator<String> CONSENT_ORDER = Comparator
            .comparing((String c) -> !isNumber(c))
            .thenComparing(c -> isNumber(c) ? Long.parseLong(c) : 0L)
            .thenComparing(Comparator.naturalOrder());

    /** Digits only, and short enough to parse; never true for "" (a blank code sorts with the text ones). */
    private static boolean isNumber(String c) {
        return c.matches("\\d{1,18}");
    }

    /** "c1", "C01", " 1 " -&gt; "1"; anything else is returned trimmed, unchanged. */
    static String normalizeConsent(String raw) {
        String t = raw == null ? "" : raw.trim();
        Matcher m = CONSENT.matcher(t);
        return m.matches() ? String.valueOf(Long.parseLong(m.group(1))) : t;
    }

    /** "v4", "V4", "4" -&gt; "v4"; null for a blank or unusable value. */
    static String normalizeVersion(String raw) {
        Matcher m = VERSION.matcher(raw == null ? "" : raw.trim());
        return m.matches() ? "v" + Integer.parseInt(m.group(1)) : null;
    }

    static StudyResult compare(StudyInput in, DbgapLookup lookup) {
        Status studyStatus = null;
        String studyNote = null;
        DbgapStudySummary summary = null;
        String requested = in.studyId() + "." + in.version();
        if (in.version() == null) {
            studyStatus = Status.NO_VERSION;
            studyNote = "managed inputs Version is "
                    + (in.rawVersion() == null || in.rawVersion().isBlank() ? "blank" : "'" + in.rawVersion().trim() + "'")
                    + " (expected e.g. v4); not checked against dbGaP";
        } else if (lookup.outcome() == DbgapLookup.Outcome.NOT_FOUND) {
            studyStatus = Status.VERSION_NOT_FOUND;
            studyNote = "dbGaP has no " + requested + ": " + lookup.message();
        } else if (lookup.outcome() == DbgapLookup.Outcome.FAILED) {
            studyStatus = Status.UNVERIFIED;
            studyNote = "dbGaP gave no usable answer: " + lookup.message();
        } else {
            DbgapStudySummary s = lookup.summary();
            boolean otherAccession = s.accession() != null && !s.accession().equalsIgnoreCase(in.studyId());
            boolean otherVersion = s.version() != null && !("v" + s.version()).equals(in.version());
            if (otherAccession || otherVersion) {
                // Its counts describe another study version, so comparing them would mislead.
                studyStatus = Status.VERSION_MISMATCH;
                studyNote = "requested " + requested + " but dbGaP answered for "
                        + (s.accession() == null ? in.studyId() : s.accession()) + "."
                        + (s.version() == null ? in.version() : "v" + s.version()) + "; counts not compared";
            } else {
                summary = s;
            }
        }

        Map<String, DbgapStudySummary.ConsentGroup> groups = new LinkedHashMap<>();
        if (summary != null) {
            for (DbgapStudySummary.ConsentGroup g : summary.consentGroups()) {
                groups.put(normalizeConsent(g.code()), g);
            }
        }
        TreeSet<String> codes = new TreeSet<>(CONSENT_ORDER);
        codes.addAll(in.db().keySet());
        codes.addAll(groups.keySet());
        if (in.load() != null) {
            codes.addAll(in.load().keySet());
        }

        String version = in.version() != null ? in.version() : blankToNull(in.rawVersion());
        List<Row> rows = new ArrayList<>();
        for (String code : codes) {
            List<Status> statuses = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            DbConsent db = in.db().get(code);
            long dbCount = db == null ? 0 : db.participants();
            DbgapStudySummary.ConsentGroup group = groups.get(code);
            Long dbgap = null;
            if (summary != null) {
                if (group == null) {
                    dbgap = 0L;
                    if (dbCount != 0) {
                        statuses.add(Status.MISSING_IN_DBGAP);
                    }
                } else if (group.subjectCount() == null) {
                    statuses.add(Status.DBGAP_COUNT_MISSING);
                    notes.add("dbGaP lists the group without a subject_count");
                } else {
                    dbgap = group.subjectCount();
                    if (dbgap != dbCount) {
                        statuses.add(dbCount == 0 ? Status.MISSING_IN_DB : Status.COUNT_MISMATCH);
                    }
                }
            } else {
                statuses.add(studyStatus);
                notes.add(studyNote);
            }

            Long loaded = null;
            if (in.load() != null) {
                loaded = in.load().getOrDefault(code, 0L);
                if (loaded != dbCount) {
                    statuses.add(Status.REPORT_DB_MISMATCH);
                    notes.add("this run's load report says " + loaded + " but the DB holds " + dbCount);
                }
            }
            if ("0".equals(code) && "multi".equalsIgnoreCase(in.loadSource())
                    && (statuses.contains(Status.MISSING_IN_DB) || statuses.contains(Status.COUNT_MISMATCH))) {
                notes.add("source MULTI drops consent 0");
            }

            String shortName = group != null && group.shortName() != null ? group.shortName()
                    : db != null ? blankToNull(db.abbreviation()) : null;
            rows.add(new Row(in.studyId(), version, code, shortName, dbgap, dbCount, loaded, in.loadSource(),
                    List.copyOf(statuses), notes.isEmpty() ? null : String.join("; ", notes)));
        }
        if (rows.isEmpty()) {
            // Nothing on any side: still one row, so the study is visibly accounted for.
            rows.add(new Row(in.studyId(), version, "", null, summary != null ? 0L : null, 0, null, in.loadSource(),
                    studyStatus == null ? List.of() : List.of(studyStatus),
                    studyNote != null ? studyNote : "dbGaP lists no consent groups and the DB holds none"));
        }
        return new StudyResult(in, lookup, studyStatus, studyNote, List.copyOf(rows));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
