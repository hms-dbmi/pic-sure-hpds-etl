package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Compares one study's counts per consent group -- from the participant DB and from every report of
 * the run that produced them -- with its dbGaP study summary, for {@link QaPipelineCountsJob}. Each
 * count source is a {@link Measure}, compared with the summary's {@code subject_count} or
 * {@code sample_count}. Pure: no I/O.
 *
 * <p>Consent codes are matched after {@linkplain ConsentCountComparison#normalizeConsent normalizing}
 * ("c1", "01" and 1 are all "1"). A measure that covers the study but does not list a consent group
 * counts 0 for it.
 */
final class PipelineCountComparison {

    private PipelineCountComparison() {
    }

    /** Which of a consent group's dbGaP counts a measure is compared with. */
    enum Kind {
        SUBJECTS("subject_count"),
        SAMPLES("sample_count");

        private final String summaryField;

        Kind(String summaryField) {
            this.summaryField = summaryField;
        }

        String summaryField() {
            return summaryField;
        }
    }

    /**
     * A source of counts per consent group: the DB, a job's report or the run's final report.
     *
     * @param name e.g. "db:participants", "split-allconcepts", "migration-report:loaded"
     */
    record Measure(String name, Kind kind) {
    }

    static final Measure DB_PARTICIPANTS = new Measure("db:participants", Kind.SUBJECTS);
    /** The study's dbGaP samples ({@code sample_source = 'DBGap'}): what dbGaP's sample_count counts. */
    static final Measure DB_SAMPLES = new Measure("db:samples", Kind.SAMPLES);

    /** A row's finding. */
    enum Status {
        MATCH(false, false),
        /** dbGaP lists consent 0 (not consented) subjects; loading drops them by design. */
        NOT_LOADED_BY_DESIGN(false, false),
        /** dbGaP and the source both have the group, with different counts. */
        COUNT_MISMATCH(true, false),
        /** dbGaP lists the group; the source has none of it. */
        MISSING(true, false),
        /** The source has the group; dbGaP does not list it. */
        NOT_IN_DBGAP(true, false),
        /** dbGaP lists the group without the count this measure is compared with. */
        DBGAP_COUNT_MISSING(true, false),
        /** The managed inputs give the study no usable Version, so it has no summary. */
        NO_VERSION(true, true),
        /** dbGaP has no such study version. */
        VERSION_NOT_FOUND(true, true),
        /** The summary is for another accession or version than the one requested. */
        VERSION_MISMATCH(true, true),
        /** No summary: none cached, and dbGaP gave no usable answer in this run. */
        UNVERIFIED(false, true),
        /** No usable summary file, and fetching missing summaries from dbGaP is off: the study fails QA. */
        SUMMARY_MISSING(true, true);

        private final boolean discrepancy;
        private final boolean studyLevel;

        Status(boolean discrepancy, boolean studyLevel) {
            this.discrepancy = discrepancy;
            this.studyLevel = studyLevel;
        }

        /** A finding to follow up; MATCH, NOT_LOADED_BY_DESIGN and UNVERIFIED are not. */
        boolean discrepancy() {
            return discrepancy;
        }

        boolean studyLevel() {
            return studyLevel;
        }
    }

    /** Where a study's summary came from. */
    enum SummarySource { CACHE, API, NONE }

    /**
     * One study's counts.
     *
     * @param version          normalized ("v4"), or null when {@code rawVersion} is unusable
     * @param counts           per measure, normalized consent code to count; a measure is present only
     *                         when its source covers the study (the DB always does)
     * @param dbAbbreviations  normalized consent code to the DB's abbreviation, for groups dbGaP does not name
     */
    record StudyInput(String studyId, String abv, String version, String rawVersion,
                      Map<Measure, Map<String, Long>> counts, Map<String, String> dbAbbreviations) {

        /** Counted by a source other than the DB: this run processed the study. */
        boolean inRun() {
            return counts.keySet().stream().anyMatch(m -> !m.equals(DB_PARTICIPANTS) && !m.equals(DB_SAMPLES));
        }
    }

    /** One (study, consent group, measure). Counts are null where unknown. */
    record Row(String studyId, String version, String consentCode, String consentShortName, Measure measure,
               Long dbgapCount, Long count, Status status, String note) {
    }

    /**
     * @param summarySource where the summary came from; NONE when there is none to compare with
     * @param studyStatus   the study-level finding, or null
     * @param studyNote     why, for a study-level finding
     */
    record StudyResult(StudyInput input, SummarySource summarySource, Status studyStatus, String studyNote,
                       List<Row> rows) {

        boolean verified() {
            return studyStatus == null;
        }

        long discrepancies() {
            return rows.stream().filter(r -> r.status().discrepancy() && !r.status().studyLevel()).count()
                    + (studyStatus != null && studyStatus.discrepancy() ? 1 : 0);
        }
    }

    /**
     * What is known of a study's summary: the summary (null when there is none) and where it came from,
     * else why there is none.
     */
    record SummaryOutcome(DbgapStudySummary summary, SummarySource source, Status status, String note) {

        static SummaryOutcome of(DbgapStudySummary summary, SummarySource source) {
            return new SummaryOutcome(summary, source, null, null);
        }

        static SummaryOutcome none(Status status, String note) {
            return new SummaryOutcome(null, SummarySource.NONE, status, note);
        }
    }

    static StudyResult compare(StudyInput in, SummaryOutcome outcome) {
        Status studyStatus = outcome.status();
        String studyNote = outcome.note();
        DbgapStudySummary summary = outcome.summary();
        String requested = in.studyId() + "." + in.version();
        if (in.version() == null) {
            studyStatus = Status.NO_VERSION;
            studyNote = "managed inputs Version is "
                    + (in.rawVersion() == null || in.rawVersion().isBlank() ? "blank" : "'" + in.rawVersion().trim() + "'")
                    + " (expected e.g. v4); no summary to compare with";
            summary = null;
        } else if (summary != null) {
            boolean otherAccession = summary.accession() != null && !summary.accession().equalsIgnoreCase(in.studyId());
            boolean otherVersion = summary.version() != null && !("v" + summary.version()).equals(in.version());
            if (otherAccession || otherVersion) {
                // Its counts describe another study version, so comparing them would mislead.
                studyStatus = Status.VERSION_MISMATCH;
                studyNote = "requested " + requested + " but the " + outcome.source().name().toLowerCase()
                        + " summary is for " + (summary.accession() == null ? in.studyId() : summary.accession())
                        + "." + (summary.version() == null ? in.version() : "v" + summary.version())
                        + "; counts not compared";
                summary = null;
            }
        }

        Map<String, DbgapStudySummary.ConsentGroup> groups = new LinkedHashMap<>();
        if (summary != null) {
            for (DbgapStudySummary.ConsentGroup g : summary.consentGroups()) {
                groups.put(ConsentCountComparison.normalizeConsent(g.code()), g);
            }
        }
        TreeSet<String> codes = new TreeSet<>(ConsentCountComparison.CONSENT_ORDER);
        codes.addAll(groups.keySet());
        in.counts().values().forEach(byCode -> codes.addAll(byCode.keySet()));

        String version = in.version() != null ? in.version() : blankToNull(in.rawVersion());
        List<Row> rows = new ArrayList<>();
        for (String code : codes) {
            DbgapStudySummary.ConsentGroup group = groups.get(code);
            String shortName = group != null && group.shortName() != null ? group.shortName()
                    : blankToNull(in.dbAbbreviations().get(code));
            for (Map.Entry<Measure, Map<String, Long>> e : in.counts().entrySet()) {
                Measure measure = e.getKey();
                Long counted = e.getValue().get(code);
                long count = counted == null ? 0 : counted;
                if (summary == null) {
                    if (count != 0) {
                        rows.add(new Row(in.studyId(), version, code, shortName, measure, null, count,
                                studyStatus, studyNote));
                    }
                    continue;
                }
                if (group == null) {
                    if (count != 0) {
                        rows.add(new Row(in.studyId(), version, code, shortName, measure, 0L, count,
                                Status.NOT_IN_DBGAP, "dbGaP does not list consent group " + code));
                    }
                    continue;
                }
                Long expected = measure.kind() == Kind.SUBJECTS ? group.subjectCount() : group.sampleCount();
                Status status;
                String note = null;
                if (expected == null) {
                    status = Status.DBGAP_COUNT_MISSING;
                    note = "dbGaP lists the group without a " + measure.kind().summaryField();
                } else if (expected == count) {
                    status = Status.MATCH;
                } else if (count == 0 && "0".equals(code)) {
                    status = Status.NOT_LOADED_BY_DESIGN;
                    note = "consent 0 (not consented) is not loaded";
                } else if (count == 0) {
                    status = Status.MISSING;
                    note = measure.name() + " has none of dbGaP's " + expected;
                } else {
                    status = Status.COUNT_MISMATCH;
                    note = measure.name() + " " + count + " vs dbGaP " + expected
                            + " (" + (count > expected ? "+" : "") + (count - expected) + ")";
                }
                rows.add(new Row(in.studyId(), version, code, shortName, measure, expected, count, status, note));
            }
        }
        if (rows.isEmpty()) {
            // Nothing to compare on any side: still one row, so the study is visibly accounted for.
            rows.add(new Row(in.studyId(), version, "", null, DB_PARTICIPANTS, summary != null ? 0L : null, 0L,
                    studyStatus == null ? Status.MATCH : studyStatus,
                    studyNote != null ? studyNote : "dbGaP lists no consent groups and no source counts any"));
        }
        return new StudyResult(in, outcome.source(), studyStatus, studyNote, List.copyOf(rows));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
