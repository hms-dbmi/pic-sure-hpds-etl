package edu.harvard.hms.dbmi.avillach.hpds.etl.core.util;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

public final class BatchOps {

    private BatchOps() {}

    public static <T> long upsertInChunks(ToIntFunction<List<T>> upsert, List<T> items, int batchSize) {
        long inserted = 0;
        for (int i = 0; i < items.size(); i += batchSize) {
            inserted += upsert.applyAsInt(items.subList(i, Math.min(i + batchSize, items.size())));
        }
        return inserted;
    }

    /**
     * Upserts {@code items} in {@code keyOrder}. Transactions that write overlapping keys in one
     * global order wait on each other rather than deadlock. Which loads overlap depends on the
     * table: sample keys (and participant source ids) can be shared by two studies' loads, while
     * consent keys include study_id, so only two concurrent loads of the same study overlap there.
     */
    public static <T> long upsertInChunks(ToIntFunction<List<T>> upsert, List<T> items, int batchSize,
                                          Comparator<? super T> keyOrder) {
        return upsertInChunks(upsert, items.stream().sorted(keyOrder).toList(), batchSize);
    }
}
