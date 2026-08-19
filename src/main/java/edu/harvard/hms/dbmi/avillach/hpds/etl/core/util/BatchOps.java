package edu.harvard.hms.dbmi.avillach.hpds.etl.core.util;

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
}
