package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Kind;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Measure;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Row;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Status;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.StudyInput;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.StudyResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.SummaryOutcome;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.SummarySource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The comparison rules on their own: one study, its measures, and a summary. */
class PipelineCountComparisonTest {

    private static final Measure SPLIT = new Measure("split-allconcepts", Kind.SUBJECTS);

    private static StudyInput input(String version, Map<Measure, Map<String, Long>> counts) {
        return new StudyInput("phs000001", "ONE", version, version, counts, Map.of());
    }

    private static DbgapStudySummary summary(DbgapStudySummary.ConsentGroup... groups) {
        return new DbgapStudySummary("phs000001", 1, List.of(groups), 0);
    }

    private static Map<Measure, Map<String, Long>> counts(Object... measureCodeCount) {
        Map<Measure, Map<String, Long>> m = new LinkedHashMap<>();
        for (int i = 0; i < measureCodeCount.length; i += 3) {
            m.computeIfAbsent((Measure) measureCodeCount[i], k -> new LinkedHashMap<>())
                    .put((String) measureCodeCount[i + 1], ((Number) measureCodeCount[i + 2]).longValue());
        }
        return m;
    }

    private static Status status(StudyResult r, String code, Measure m) {
        return r.rows().stream().filter(row -> row.consentCode().equals(code) && row.measure().equals(m))
                .map(Row::status).findFirst().orElse(null);
    }

    @Test
    void a_source_with_none_of_a_group_is_missing_and_a_group_without_a_count_is_flagged() {
        StudyResult r = PipelineCountComparison.compare(
                input("v1", counts(PipelineCountComparison.DB_PARTICIPANTS, "1", 10L,
                        PipelineCountComparison.DB_SAMPLES, "1", 10L, SPLIT, "2", 4L)),
                SummaryOutcome.of(summary(new DbgapStudySummary.ConsentGroup("1", "HMB", 10L, null),
                        new DbgapStudySummary.ConsentGroup("2", "GRU", 4L, 4L)), SummarySource.CACHE));

        assertThat(status(r, "1", PipelineCountComparison.DB_PARTICIPANTS)).isEqualTo(Status.MATCH);
        assertThat(status(r, "1", PipelineCountComparison.DB_SAMPLES)).isEqualTo(Status.DBGAP_COUNT_MISSING);
        assertThat(status(r, "1", SPLIT)).isEqualTo(Status.MISSING);
        assertThat(status(r, "2", PipelineCountComparison.DB_PARTICIPANTS)).isEqualTo(Status.MISSING);
        assertThat(status(r, "2", SPLIT)).isEqualTo(Status.MATCH);
        assertThat(r.discrepancies()).isEqualTo(4);
        assertThat(r.input().inRun()).isTrue();
    }

    @Test
    void consent_codes_are_matched_after_normalizing() {
        StudyResult r = PipelineCountComparison.compare(
                input("v1", counts(PipelineCountComparison.DB_PARTICIPANTS, "1", 10L)),
                SummaryOutcome.of(summary(new DbgapStudySummary.ConsentGroup("01", "HMB", 10L, 0L)), SummarySource.API));

        assertThat(status(r, "1", PipelineCountComparison.DB_PARTICIPANTS)).isEqualTo(Status.MATCH);
        assertThat(r.verified()).isTrue();
    }

    @Test
    void without_a_summary_every_counted_group_carries_the_study_finding() {
        StudyResult r = PipelineCountComparison.compare(
                input("v1", counts(PipelineCountComparison.DB_PARTICIPANTS, "1", 10L, PipelineCountComparison.DB_SAMPLES, "1", 0L)),
                SummaryOutcome.none(Status.UNVERIFIED, "dbGaP gave no usable answer"));

        assertThat(r.verified()).isFalse();
        assertThat(r.discrepancies()).isZero();
        // The zero sample count is not a row: nothing to say about it without a summary.
        assertThat(r.rows()).extracting(Row::measure, Row::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(PipelineCountComparison.DB_PARTICIPANTS, Status.UNVERIFIED));
    }

    @Test
    void a_study_with_nothing_anywhere_still_gets_a_row() {
        StudyResult r = PipelineCountComparison.compare(input("v1", counts()),
                SummaryOutcome.of(summary(), SummarySource.CACHE));

        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).status()).isEqualTo(Status.MATCH);
        assertThat(r.rows().get(0).note()).contains("no consent groups");
    }
}
