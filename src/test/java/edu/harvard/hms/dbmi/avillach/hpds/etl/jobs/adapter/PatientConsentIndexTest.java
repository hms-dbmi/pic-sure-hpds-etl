package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatientConsentIndexTest {

    // partitions 0,1 belong to study 0; partition 2 to study 1
    private final PatientConsentIndex index = new PatientConsentIndex(new int[]{0, 0, 1});

    @Test
    void a_patient_holds_the_partitions_granted_once_each_in_order() {
        index.grant(7, 2);
        index.grant(7, 0);
        index.grant(7, 0);
        assertThat(index.partitionsOf(7)).containsExactly(0, 2);
        assertThat(index.holds(7, 0)).isTrue();
        assertThat(index.holds(7, 1)).isFalse();
        assertThat(index.partitionsOf(8)).isNull();
        assertThat(index.holds(8, 0)).isFalse();
        assertThat(index.patients()).isEqualTo(1);
    }

    @Test
    void studies_are_counted_distinct_and_multi_study_patients_once() {
        index.grant(1, 0);
        assertThat(index.studyCount(1)).isEqualTo(1);
        index.grant(1, 1); // second consent of the same study
        assertThat(index.studyCount(1)).isEqualTo(1);
        assertThat(index.multiStudyPatients()).isZero();
        index.grant(1, 2);
        assertThat(index.studyCount(1)).isEqualTo(2);
        assertThat(index.multiStudyPatients()).isEqualTo(1);
        index.grant(1, 2);
        assertThat(index.multiStudyPatients()).isEqualTo(1);
        assertThat(index.studyCount(99)).isZero();
    }

    @Test
    void partitions_of_a_study_are_those_the_patient_holds() {
        index.grant(1, 1);
        index.grant(1, 2);
        assertThat(index.partitionsOfStudy(1, 0)).containsExactly(1);
        assertThat(index.partitionsOfStudy(1, 1)).containsExactly(2);
        assertThat(index.partitionsOfStudy(2, 0)).isEmpty();
    }
}
