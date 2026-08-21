package edu.harvard.hms.dbmi.avillach.hpds.etl.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates {@link AllConceptsRow}s and serializes them to a headerless, all-quoted CSV.
 * Reusable across any job that needs to produce an AllConcepts file.
 */
public class AllConceptsCsvBuilder {

    private final List<AllConceptsRow> rows = new ArrayList<>();

    public AllConceptsCsvBuilder add(AllConceptsRow row) {
        rows.add(row);
        return this;
    }

    public AllConceptsCsvBuilder addAll(List<AllConceptsRow> batch) {
        rows.addAll(batch);
        return this;
    }

    public List<AllConceptsRow> rows() {
        return Collections.unmodifiableList(rows);
    }

    public int size() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public byte[] build() {
        StringBuilder sb = new StringBuilder();
        for (AllConceptsRow row : rows) {
            sb.append(row.toCsvLine()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
