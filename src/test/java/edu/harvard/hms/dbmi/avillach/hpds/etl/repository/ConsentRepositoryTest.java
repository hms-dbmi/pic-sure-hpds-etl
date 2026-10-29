package edu.harvard.hms.dbmi.avillach.hpds.etl.repository;

import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The in-memory dedupe behind {@link ConsentRepository#batchUpsert}; {@link ConsentRepositoryIT} covers the SQL. */
class ConsentRepositoryTest {

    @Test
    void keeps_the_last_row_per_key_in_first_seen_order() {
        ConsentRepository.Deduplicated d = ConsentRepository.deduplicate(List.of(
                new Consent(2, "s", "c1", "A"),
                new Consent(1, "s", "c1", "A"),
                new Consent(2, "s", "c2", "B")));

        assertThat(d.rows()).containsExactly(new Consent(2, "s", "c2", "B"), new Consent(1, "s", "c1", "A"));
        assertThat(d.conflicting()).isEqualTo(1);
        assertThat(d.sample()).containsExactly("hpds_id=2 study_id=s codes c1 -> c2");
    }

    @Test
    void identical_repeats_are_not_conflicts() {
        ConsentRepository.Deduplicated d = ConsentRepository.deduplicate(List.of(
                new Consent(1, "s", "c1", "A"), new Consent(1, "s", "c1", "A")));

        assertThat(d.rows()).hasSize(1);
        assertThat(d.conflicting()).isZero();
        assertThat(d.sample()).isEmpty();
    }

    @Test
    void counts_every_conflict_but_samples_only_a_few() {
        List<Consent> rows = IntStream.range(0, 20)
                .boxed()
                .flatMap(i -> java.util.stream.Stream.of(new Consent(i, "s", "c1", "A"), new Consent(i, "s", "c2", "B")))
                .toList();

        ConsentRepository.Deduplicated d = ConsentRepository.deduplicate(rows);

        assertThat(d.rows()).hasSize(20);
        assertThat(d.conflicting()).isEqualTo(20);
        assertThat(d.sample()).hasSize(ConsentRepository.CONFLICT_SAMPLE_SIZE);
    }
}
