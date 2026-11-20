package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.Strings;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What {@link QaConsentCountsJob} and {@link QaPipelineCountsJob} share: which studies to check,
 * reading a run's count CSV, writing report files, whether a summary describes the study version
 * asked for, and the consent 0 rule.
 */
final class QaSupport {

    private QaSupport() {
    }

    /** Ready studies (each once), restricted to {@code filter} when it is not empty. */
    static List<ManagedInputRow> select(List<ManagedInputRow> rows, Set<String> filter) {
        Map<String, ManagedInputRow> byId = new LinkedHashMap<>();
        for (ManagedInputRow row : rows) {
            if (row.isReady() && (filter.isEmpty() || filter.contains(row.studyId()))) {
                byId.putIfAbsent(row.studyId(), row);
            }
        }
        return List.copyOf(byId.values());
    }

    /** The {@code --study-filter} ids; empty when blank. */
    static Set<String> studyFilter(JobContext ctx) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : ctx.get("study-filter", "").split(",")) {
            if (!id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return ids;
    }

    /** One data row of a CSV read by {@link #readCsv}: its line number and its cells by column. */
    @FunctionalInterface
    interface RowHandler {
        void row(long line, Map<String, String> row);
    }

    /**
     * Reads a comma-separated file with a header, handing each data row to {@code handler}. The header
     * is checked for {@code required} even when no data row follows, so a header-only file with the
     * wrong columns is rejected rather than read as empty. {@code what} names the file in errors
     * (e.g. "--run-counts s3://..."). Closes {@code in}.
     */
    static void readCsv(DelimitedReader reader, InputStream in, String what, List<String> required,
                        RowHandler handler) {
        try (Stream<List<String>> rows = reader.streamRows(in, DelimitedReader.COMMA)) {
            Iterator<List<String>> it = rows.iterator();
            if (!it.hasNext()) {
                throw new DataException(what + " is empty: expected a header with column(s) " + required);
            }
            List<String> header = it.next().stream().map(String::trim).toList();
            List<String> missing = required.stream().filter(c -> !header.contains(c)).toList();
            if (!missing.isEmpty()) {
                throw new DataException(what + " is missing column(s) " + missing + "; found " + header);
            }
            long line = 1;
            while (it.hasNext()) {
                List<String> cells = it.next();
                line++;
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), i < cells.size() ? cells.get(i) : "");
                }
                handler.row(line, row);
            }
        }
    }

    /** {@code values} as one CSV line, each quoted as needed and kept on one line. */
    static String csvLine(String... values) {
        return String.join(",", Stream.of(values)
                .map(v -> Strings.csvQuote(v == null ? "" : v.replaceAll("[\r\n]+", " "))).toList()) + "\n";
    }

    static String str(Long v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** Written aside and renamed: the runner uploads the reports directory while the job runs. */
    static void writeAtomically(Path dir, String name, String content) {
        Path target = dir.resolve(name);
        Path partial = dir.resolve(name + ".partial");
        try {
            Files.createDirectories(dir);
            Files.writeString(partial, content, StandardCharsets.UTF_8);
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new InfrastructureException("Could not write " + target + ": " + e.getMessage(), e);
        }
    }

    /**
     * Whether {@code summary} names exactly {@code studyId}.{@code version} ("v4") in its {@code accver}.
     * A summary that names no accession or no version could describe any study version, so it does not.
     */
    static boolean describes(DbgapStudySummary summary, String studyId, String version) {
        return summary.accession() != null && summary.accession().equalsIgnoreCase(studyId)
                && summary.version() != null && ("v" + summary.version()).equalsIgnoreCase(version);
    }

    /** The study version a summary names, for messages: e.g. "phs000001.v2", with "?" for a part it lacks. */
    static String named(DbgapStudySummary summary) {
        return (summary.accession() == null ? "?" : summary.accession()) + "."
                + (summary.version() == null ? "v?" : "v" + summary.version());
    }

    /**
     * The consent 0 rule, the same in both jobs: consent 0 (not consented) is compared like any other
     * consent group, except where it is dropped by contract -- by a study loaded from its MULTI files
     * ({@code loadSource} "multi"; an SSTR load keeps consent 0), or by a source that only counts
     * consented participants ({@code consentedOnly}: the allConcepts generator and split, and the
     * report columns taken from them). There dbGaP's consent 0 subjects are not loaded by design.
     */
    static boolean consentZeroDropped(boolean consentedOnly, String loadSource) {
        return consentedOnly || "multi".equalsIgnoreCase(loadSource);
    }
}
