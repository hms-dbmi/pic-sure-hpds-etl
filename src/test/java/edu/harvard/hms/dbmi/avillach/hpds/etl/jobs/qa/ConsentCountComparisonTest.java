package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.ConsentCountComparison.DbConsent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.ConsentCountComparison.Row;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.ConsentCountComparison.Status;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.ConsentCountComparison.StudyInput;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.ConsentCountComparison.StudyResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The comparison rules, one per test, with no I/O. */
class ConsentCountComparisonTest {

    private static final String SID = "phs001412";

    private static Map<String, DbConsent> db(Object... codeAndCount) {
        Map<String, DbConsent> m = new LinkedHashMap<>();
        for (int i = 0; i < codeAndCount.length; i += 2) {
            m.put((String) codeAndCount[i], new DbConsent(((Number) codeAndCount[i + 1]).longValue(), "abv" + codeAndCount[i]));
        }
        return m;
    }

    private static Map<String, Long> load(Object... codeAndCount) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < codeAndCount.length; i += 2) {
            m.put((String) codeAndCount[i], ((Number) codeAndCount[i + 1]).longValue());
        }
        return m;
    }

    private static DbgapLookup dbgap(int version, Object... codeAndCount) {
        List<DbgapStudySummary.ConsentGroup> groups = new java.util.ArrayList<>();
        for (int i = 0; i < codeAndCount.length; i += 2) {
            Number n = (Number) codeAndCount[i + 1];
            groups.add(new DbgapStudySummary.ConsentGroup((String) codeAndCount[i], "G" + codeAndCount[i],
                    n == null ? null : n.longValue()));
        }
        return DbgapLookup.found(new DbgapStudySummary(SID, version, groups, 0), 1);
    }

    private static StudyInput study(Map<String, DbConsent> db, Map<String, Long> load, String source) {
        return new StudyInput(SID, "DHS", "v4", "v4", db, load, source);
    }

    private static Row row(StudyResult r, String code) {
        return r.rows().stream().filter(x -> x.consentCode().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void every_consent_matching_including_consent_0_is_a_match() {
        StudyResult r = ConsentCountComparison.compare(study(db("0", 576, "1", 402, "2", 3), null, null),
                dbgap(4, "0", 576, "1", 402, "2", 3));

        assertThat(r.verified()).isTrue();
        assertThat(r.rows()).extracting(Row::consentCode).containsExactly("0", "1", "2");
        assertThat(r.rows()).allMatch(Row::matches);
        assertThat(row(r, "1").status()).isEqualTo("MATCH");
        assertThat(row(r, "1").dbgapSubjects()).isEqualTo(402L);
        assertThat(row(r, "1").consentShortName()).isEqualTo("G1");
        assertThat(row(r, "1").loadParticipants()).isNull();
    }

    @Test
    void different_counts_are_a_count_mismatch() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 400), null, null), dbgap(4, "1", 402));

        assertThat(row(r, "1").statuses()).containsExactly(Status.COUNT_MISMATCH);
        assertThat(row(r, "1").dbParticipants()).isEqualTo(400);
        assertThat(row(r, "1").dbgapSubjects()).isEqualTo(402L);
    }

    @Test
    void a_dbgap_group_the_db_lacks_is_missing_in_db_with_db_0_and_consent_0_is_not_exempt() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 402), null, null), dbgap(4, "0", 576, "1", 402));

        assertThat(row(r, "0").statuses()).containsExactly(Status.MISSING_IN_DB);
        assertThat(row(r, "0").dbParticipants()).isZero();
        assertThat(row(r, "1").matches()).isTrue();
    }

    @Test
    void a_dbgap_group_with_no_subjects_the_db_also_lacks_matches() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 402), null, null), dbgap(4, "1", 402, "3", 0));

        assertThat(row(r, "3").matches()).isTrue();
    }

    @Test
    void a_db_group_dbgap_does_not_list_is_missing_in_dbgap_with_dbgap_0() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 402, "9", 5), null, null), dbgap(4, "1", 402));

        assertThat(row(r, "9").statuses()).containsExactly(Status.MISSING_IN_DBGAP);
        assertThat(row(r, "9").dbgapSubjects()).isZero();
        assertThat(row(r, "9").consentShortName()).isEqualTo("abv9");
    }

    @Test
    void a_dbgap_group_without_a_subject_count_is_flagged() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 402), null, null), dbgap(4, "1", null));

        assertThat(row(r, "1").statuses()).containsExactly(Status.DBGAP_COUNT_MISSING);
        assertThat(row(r, "1").dbgapSubjects()).isNull();
    }

    @Test
    void a_load_report_disagreeing_with_the_db_is_a_report_db_mismatch_even_when_dbgap_matches() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 402, "2", 3), load("1", 400), "sstr"),
                dbgap(4, "1", 402, "2", 3));

        assertThat(row(r, "1").statuses()).containsExactly(Status.REPORT_DB_MISMATCH);
        assertThat(row(r, "1").loadParticipants()).isEqualTo(400L);
        assertThat(row(r, "1").note()).contains("load report says 400 but the DB holds 402");
        // The load covered the study but not consent 2: it loaded 0 of the DB's 3.
        assertThat(row(r, "2").statuses()).containsExactly(Status.REPORT_DB_MISMATCH);
        assertThat(row(r, "2").loadParticipants()).isZero();
        assertThat(row(r, "2").loadSource()).isEqualTo("sstr");
    }

    @Test
    void a_count_mismatch_and_a_report_db_mismatch_are_both_recorded() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 400), load("1", 401), "sstr"), dbgap(4, "1", 402));

        assertThat(row(r, "1").statuses()).containsExactly(Status.COUNT_MISMATCH, Status.REPORT_DB_MISMATCH);
        assertThat(row(r, "1").status()).isEqualTo("COUNT_MISMATCH;REPORT_DB_MISMATCH");
    }

    @Test
    void a_study_the_load_report_does_not_cover_is_compared_against_dbgap_only() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 402), null, null), dbgap(4, "1", 402));

        assertThat(row(r, "1").matches()).isTrue();
        assertThat(row(r, "1").loadParticipants()).isNull();
    }

    @Test
    void consent_0_dropped_by_a_multi_load_is_still_a_discrepancy_but_says_why() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 402), load("1", 402), "multi"),
                dbgap(4, "0", 576, "1", 402));

        assertThat(row(r, "0").statuses()).containsExactly(Status.MISSING_IN_DB);
        assertThat(row(r, "0").note()).isEqualTo("source MULTI drops consent 0");
        assertThat(row(r, "1").matches()).isTrue();
    }

    @Test
    void no_usable_version_is_no_version_on_every_db_row() {
        StudyResult r = ConsentCountComparison.compare(
                new StudyInput(SID, "DHS", null, "four", db("1", 402, "2", 3), null, null), null);

        assertThat(r.studyStatus()).isEqualTo(Status.NO_VERSION);
        assertThat(r.rows()).hasSize(2).allMatch(x -> x.statuses().equals(List.of(Status.NO_VERSION)));
        assertThat(r.rows().get(0).note()).contains("'four'");
        assertThat(r.rows().get(0).version()).isEqualTo("four");
    }

    @Test
    void a_study_with_nothing_anywhere_still_gets_one_row() {
        StudyResult r = ConsentCountComparison.compare(
                new StudyInput(SID, "DHS", null, "", Map.of(), null, null), null);

        assertThat(r.rows()).singleElement().satisfies(x -> {
            assertThat(x.consentCode()).isEmpty();
            assertThat(x.statuses()).containsExactly(Status.NO_VERSION);
            assertThat(x.note()).contains("blank");
        });
    }

    @Test
    void an_unknown_study_version_is_version_not_found() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 402), null, null),
                DbgapLookup.notFound("Invalid or non-existent study with accession phs001412.v4", 1));

        assertThat(r.studyStatus()).isEqualTo(Status.VERSION_NOT_FOUND);
        assertThat(row(r, "1").statuses()).containsExactly(Status.VERSION_NOT_FOUND);
        assertThat(row(r, "1").dbgapSubjects()).isNull();
        assertThat(r.verified()).isFalse();
    }

    @Test
    void an_answer_for_another_version_is_a_version_mismatch_and_its_counts_are_not_compared() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 1), null, null), dbgap(5, "1", 402));

        assertThat(r.studyStatus()).isEqualTo(Status.VERSION_MISMATCH);
        assertThat(r.studyNote()).contains("requested phs001412.v4").contains("phs001412.v5");
        assertThat(row(r, "1").statuses()).containsExactly(Status.VERSION_MISMATCH);
        assertThat(row(r, "1").dbgapSubjects()).isNull();
    }

    @Test
    void a_failed_lookup_leaves_the_study_unverified_with_its_db_counts() {
        StudyResult r = ConsentCountComparison.compare(study(db("1", 402), load("1", 402), "sstr"),
                DbgapLookup.failed("HTTP 503", 5));

        assertThat(r.studyStatus()).isEqualTo(Status.UNVERIFIED);
        assertThat(row(r, "1").statuses()).containsExactly(Status.UNVERIFIED);
        assertThat(row(r, "1").dbParticipants()).isEqualTo(402);
        assertThat(row(r, "1").note()).contains("HTTP 503");
    }

    @Test
    void consent_codes_and_versions_are_normalized() {
        assertThat(ConsentCountComparison.normalizeConsent("c1")).isEqualTo("1");
        assertThat(ConsentCountComparison.normalizeConsent(" C01 ")).isEqualTo("1");
        assertThat(ConsentCountComparison.normalizeConsent("0")).isEqualTo("0");
        assertThat(ConsentCountComparison.normalizeConsent("HMB")).isEqualTo("HMB");
        assertThat(ConsentCountComparison.normalizeVersion("v4")).isEqualTo("v4");
        assertThat(ConsentCountComparison.normalizeVersion("V04")).isEqualTo("v4");
        assertThat(ConsentCountComparison.normalizeVersion("4")).isEqualTo("v4");
        assertThat(ConsentCountComparison.normalizeVersion("")).isNull();
        assertThat(ConsentCountComparison.normalizeVersion("v4.p1")).isNull();
    }

    @Test
    void consents_are_ordered_numerically() {
        StudyResult r = ConsentCountComparison.compare(study(db("10", 1, "2", 1, "x", 1), null, null),
                dbgap(4, "10", 1, "2", 1));

        assertThat(r.rows()).extracting(Row::consentCode).containsExactly("2", "10", "x");
    }
}
