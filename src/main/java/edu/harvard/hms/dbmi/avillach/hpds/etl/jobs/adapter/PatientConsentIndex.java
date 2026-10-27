package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Which partitions each patient holds, built from the global file's {@code _consents} rows before any other row
 * is routed. A partition is a small integer (its position in the sorted list of in-scope partitions) and a
 * study is another, so a patient costs one map entry and a short sorted {@code int[]}.
 *
 * <p>Memory bound: about 100 bytes per patient that holds a consent (a boxed key, an entry and the array); a
 * million patients is on the order of 100 MB. It is the only structure that grows with the number of patients,
 * besides the per-patient id rows of multi-study patients that the job buffers.
 */
final class PatientConsentIndex {

    private final int[] studyOfPartition;
    private final Map<Integer, int[]> held = new HashMap<>();
    private long multiStudyPatients;

    /** @param studyOfPartition for partition i, the index of its study */
    PatientConsentIndex(int[] studyOfPartition) {
        this.studyOfPartition = studyOfPartition.clone();
    }

    /** Records that {@code patient} holds {@code partition}; a repeated grant changes nothing. */
    void grant(int patient, int partition) {
        int[] now = held.get(patient);
        if (now == null) {
            held.put(patient, new int[]{partition});
            return;
        }
        int at = Arrays.binarySearch(now, partition);
        if (at >= 0) {
            return;
        }
        int studiesBefore = studyCount(now);
        int[] next = new int[now.length + 1];
        int insert = -at - 1;
        System.arraycopy(now, 0, next, 0, insert);
        next[insert] = partition;
        System.arraycopy(now, insert, next, insert + 1, now.length - insert);
        held.put(patient, next);
        if (studiesBefore == 1 && studyCount(next) == 2) {
            multiStudyPatients++;
        }
    }

    /** The patient's partitions in ascending order, or null if it holds none. Not to be modified. */
    int[] partitionsOf(int patient) {
        return held.get(patient);
    }

    boolean holds(int patient, int partition) {
        int[] now = held.get(patient);
        return now != null && Arrays.binarySearch(now, partition) >= 0;
    }

    /** The partitions of {@code study} the patient holds (possibly none). */
    int[] partitionsOfStudy(int patient, int study) {
        int[] now = held.get(patient);
        if (now == null) {
            return new int[0];
        }
        int n = 0;
        for (int p : now) {
            if (studyOfPartition[p] == study) {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int p : now) {
            if (studyOfPartition[p] == study) {
                out[k++] = p;
            }
        }
        return out;
    }

    /** The number of distinct studies among the patient's partitions. */
    int studyCount(int patient) {
        int[] now = held.get(patient);
        return now == null ? 0 : studyCount(now);
    }

    private int studyCount(int[] partitions) {
        if (partitions.length == 1) {
            return 1;
        }
        int distinct = 0;
        for (int i = 0; i < partitions.length; i++) {
            boolean seen = false;
            for (int j = 0; j < i && !seen; j++) {
                seen = studyOfPartition[partitions[j]] == studyOfPartition[partitions[i]];
            }
            if (!seen) {
                distinct++;
            }
        }
        return distinct;
    }

    long patients() {
        return held.size();
    }

    /** Patients holding partitions of two or more studies. */
    long multiStudyPatients() {
        return multiStudyPatients;
    }
}
