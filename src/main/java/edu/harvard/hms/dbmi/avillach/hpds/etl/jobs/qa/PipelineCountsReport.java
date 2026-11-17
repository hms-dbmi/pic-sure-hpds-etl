package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.Strings;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Measure;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Row;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.Status;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.StudyResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa.PipelineCountComparison.SummarySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The files {@link QaPipelineCountsJob} writes into the reports directory: three CSVs for machines
 * and {@value #MARKDOWN_FILE} for people.
 */
final class PipelineCountsReport {

    /** One row per (study, consent group, measure). */
    static final String COUNTS_FILE = "qa_pipeline_counts.csv";
    /** Its rows that need follow-up: every status but MATCH and NOT_LOADED_BY_DESIGN. */
    static final String DISCREPANCIES_FILE = "qa_pipeline_discrepancies.csv";
    /** One row per study. */
    static final String STUDIES_FILE = "qa_pipeline_studies.csv";
    static final String MARKDOWN_FILE = "qa-pipeline-report.md";

    static final String COUNTS_HEADER = "study_id,version,in_run,consent_code,consent_short_name,measure,"
            + "compared_to,dbgap_count,count,status,note";
    static final String STUDIES_HEADER = "study_id,abv,version,in_run,summary_source,status,consent_groups,"
            + "discrepancies,note";

    /** Studies with discrepancies detailed in the markdown; the rest are only listed. */
    private static final int MARKDOWN_STUDIES_DETAILED = 100;

    private PipelineCountsReport() {
    }

    static void write(Path dir, String runId, QaPipelineCountsJob.Output output) {
        StringBuilder all = new StringBuilder(COUNTS_HEADER).append('\n');
        StringBuilder discrepancies = new StringBuilder(COUNTS_HEADER).append('\n');
        StringBuilder studies = new StringBuilder(STUDIES_HEADER).append('\n');
        for (StudyResult r : output.results()) {
            for (Row row : r.rows()) {
                String line = countsLine(r, row);
                all.append(line);
                if (needsFollowUp(row.status())) {
                    discrepancies.append(line);
                }
            }
            studies.append(csv(r.input().studyId(), r.input().abv(), version(r), String.valueOf(r.input().inRun()),
                    r.summarySource().name(), studyStatus(r), String.valueOf(consentGroups(r).size()),
                    String.valueOf(r.discrepancies()), r.studyNote()));
        }
        writeAtomically(dir, COUNTS_FILE, all.toString());
        writeAtomically(dir, DISCREPANCIES_FILE, discrepancies.toString());
        writeAtomically(dir, STUDIES_FILE, studies.toString());
        writeAtomically(dir, MARKDOWN_FILE, markdown(runId, output));
    }

    /** A row to follow up: a discrepancy, or a study with no summary. */
    static boolean needsFollowUp(Status s) {
        return s != Status.MATCH && s != Status.NOT_LOADED_BY_DESIGN;
    }

    /** MATCH, DISCREPANCIES, UNVERIFIED, or the study-level finding. */
    static String studyStatus(StudyResult r) {
        if (r.studyStatus() != null) {
            return r.studyStatus().name();
        }
        return r.discrepancies() > 0 ? "DISCREPANCIES" : "MATCH";
    }

    private static String countsLine(StudyResult r, Row row) {
        return csv(row.studyId(), row.version(), String.valueOf(r.input().inRun()), row.consentCode(),
                row.consentShortName(), row.measure().name(), row.measure().kind().summaryField(),
                str(row.dbgapCount()), str(row.count()), row.status().name(), row.note());
    }

    private static String csv(String... values) {
        return String.join(",", Stream.of(values)
                .map(v -> Strings.csvQuote(v == null ? "" : v.replaceAll("[\r\n]+", " "))).toList()) + "\n";
    }

    private static String str(Long v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String version(StudyResult r) {
        return r.input().version() != null ? r.input().version()
                : r.input().rawVersion() == null ? "" : r.input().rawVersion().trim();
    }

    private static Set<String> consentGroups(StudyResult r) {
        Set<String> codes = new LinkedHashSet<>();
        r.rows().forEach(row -> {
            if (!row.consentCode().isEmpty()) {
                codes.add(row.consentCode());
            }
        });
        return codes;
    }

    /* ---- Markdown ---- */

    static String markdown(String runId, QaPipelineCountsJob.Output output) {
        List<StudyResult> results = output.results();
        List<StudyResult> withDiscrepancies = results.stream().filter(r -> r.verified() && r.discrepancies() > 0).toList();
        List<StudyResult> studyLevel = results.stream()
                .filter(r -> r.studyStatus() != null && r.studyStatus() != Status.UNVERIFIED).toList();
        List<StudyResult> unverified = results.stream().filter(r -> r.studyStatus() == Status.UNVERIFIED).toList();
        long matching = results.stream().filter(r -> "MATCH".equals(studyStatus(r))).count();
        long inRun = results.stream().filter(r -> r.input().inRun()).count();

        StringBuilder md = new StringBuilder();
        md.append("# Pipeline consent count QA")
                .append(output.pipeline() == null ? "" : " (" + output.pipeline() + " pipeline)").append("\n\n");
        md.append("Run `").append(runId).append("`. Every study marked ready in the managed inputs (")
                .append(results.size()).append("), ").append(inRun).append(" of them processed by this run, ")
                .append("compared with its dbGaP study summary, consent group by consent group.\n\n");

        String result = withDiscrepancies.isEmpty() && studyLevel.isEmpty() && unverified.isEmpty()
                ? "**Result: every count matches dbGaP.**"
                : "**Result: " + (withDiscrepancies.size() + studyLevel.size()) + " study/studies need follow-up"
                + (unverified.isEmpty() ? "" : ", " + unverified.size() + " could not be checked (no summary)") + ".**";
        md.append(result).append("\n\n");
        md.append("| Studies | |\n|---|---|\n")
                .append("| Every count matches | ").append(matching).append(" |\n")
                .append("| Count discrepancies | ").append(withDiscrepancies.size()).append(" |\n")
                .append("| Version problems (no version, not in dbGaP, other version) | ").append(studyLevel.size()).append(" |\n")
                .append("| Not checked: no summary (UNVERIFIED) | ").append(unverified.size()).append(" |\n")
                .append("| **Total** | **").append(results.size()).append("** |\n\n");

        long fromCache = results.stream().filter(r -> r.summarySource() == SummarySource.CACHE).count();
        long fromApi = results.stream().filter(r -> r.summarySource() == SummarySource.API).count();
        md.append("Summaries: ").append(fromCache).append(" cached, ").append(fromApi).append(" fetched from dbGaP (")
                .append(output.uploaded().size()).append(" newly cached")
                .append(output.uploadFailures().isEmpty() ? "" : ", " + output.uploadFailures().size() + " could not be cached")
                .append("), ").append(unverified.size()).append(" unavailable.");
        if (!output.cacheErrors().isEmpty()) {
            md.append(" ").append(output.cacheErrors().size()).append(" cached summary/summaries were unusable and were "
                    + "asked of dbGaP instead.");
        }
        md.append("\n\n");
        Set<String> sources = new LinkedHashSet<>();
        results.forEach(r -> r.input().counts().keySet().forEach(m -> sources.add(m.name() + " (vs "
                + m.kind().summaryField() + ")")));
        md.append("Counts compared: ").append(sources.isEmpty() ? "none" : String.join(", ", sources))
                .append(". `db:*` cover every study; the rest only the studies this run processed")
                .append(output.runCountsUri() == null ? " (no run counts were given)" : "").append(".\n\n");
        md.append("Legend: a count that differs from dbGaP is shown as **count** (dbGaP n); `-` is not counted by that "
                + "source. Consent 0 (not consented) subjects are not loaded by design.\n\n");

        if (!withDiscrepancies.isEmpty()) {
            md.append("## Count discrepancies\n\n");
            int shown = 0;
            for (StudyResult r : withDiscrepancies) {
                if (shown++ == MARKDOWN_STUDIES_DETAILED) {
                    md.append("... and ").append(withDiscrepancies.size() - MARKDOWN_STUDIES_DETAILED)
                            .append(" more study/studies in `").append(DISCREPANCIES_FILE).append("`.\n\n");
                    break;
                }
                studyTable(md, r);
            }
        }
        if (!studyLevel.isEmpty()) {
            md.append("## Version problems\n\n| Study | Version | In run | Finding | Detail |\n|---|---|---|---|---|\n");
            for (StudyResult r : studyLevel) {
                md.append("| ").append(label(r)).append(" | ").append(cell(version(r))).append(" | ")
                        .append(r.input().inRun() ? "yes" : "no").append(" | ").append(r.studyStatus().name())
                        .append(" | ").append(cell(r.studyNote())).append(" |\n");
            }
            md.append('\n');
        }
        if (!unverified.isEmpty()) {
            md.append("## Not checked (no summary)\n\nNo summary is cached for these, and dbGaP gave no usable answer "
                    + "in this run. A later run fetches them; nothing needs fixing in the data.\n\n"
                    + "| Study | Version | Why |\n|---|---|---|\n");
            for (StudyResult r : unverified) {
                md.append("| ").append(label(r)).append(" | ").append(cell(version(r))).append(" | ")
                        .append(cell(r.studyNote())).append(" |\n");
            }
            md.append('\n');
        }
        md.append("## All studies\n\n| Study | Version | In run | Summary | Consent groups | Status | Discrepancies |\n"
                + "|---|---|---|---|---|---|---|\n");
        for (StudyResult r : results) {
            md.append("| ").append(label(r)).append(" | ").append(cell(version(r))).append(" | ")
                    .append(r.input().inRun() ? "yes" : "no").append(" | ").append(r.summarySource().name().toLowerCase())
                    .append(" | ").append(consentGroups(r).size()).append(" | ").append(studyStatus(r)).append(" | ")
                    .append(r.discrepancies()).append(" |\n");
        }
        md.append("\nMachine-readable: `").append(COUNTS_FILE).append("` (every count), `").append(DISCREPANCIES_FILE)
                .append("` (those needing follow-up), `").append(STUDIES_FILE).append("` (one row per study).\n");
        return md.toString();
    }

    /** One study's consent groups as rows, its measures as columns, dbGaP's counts first. */
    private static void studyTable(StringBuilder md, StudyResult r) {
        md.append("### ").append(label(r)).append(' ').append(cell(version(r)))
                .append(r.input().inRun() ? " (processed by this run)" : " (not in this run)").append("\n\n");
        List<Measure> measures = new ArrayList<>(r.input().counts().keySet());
        Map<String, Map<Measure, Row>> byCode = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Long[]> dbgap = new LinkedHashMap<>();
        TreeSet<String> codes = new TreeSet<>(ConsentCountComparison.CONSENT_ORDER);
        for (Row row : r.rows()) {
            codes.add(row.consentCode());
            byCode.computeIfAbsent(row.consentCode(), k -> new LinkedHashMap<>()).put(row.measure(), row);
            if (row.consentShortName() != null) {
                names.putIfAbsent(row.consentCode(), row.consentShortName());
            }
            Long[] counts = dbgap.computeIfAbsent(row.consentCode(), k -> new Long[2]);
            counts[row.measure().kind().ordinal()] = row.dbgapCount();
        }
        md.append("| Consent | dbGaP subjects | dbGaP samples |");
        measures.forEach(m -> md.append(' ').append(cell(m.name())).append(" |"));
        md.append("\n|---|---|---|");
        measures.forEach(m -> md.append("---|"));
        md.append('\n');
        List<String> findings = new ArrayList<>();
        for (String code : codes) {
            Long[] d = dbgap.get(code);
            md.append("| c").append(code).append(names.containsKey(code) ? " " + cell(names.get(code)) : "")
                    .append(" | ").append(d[0] == null ? "-" : d[0]).append(" | ").append(d[1] == null ? "-" : d[1])
                    .append(" |");
            for (Measure m : measures) {
                Row row = byCode.get(code).get(m);
                if (row == null) {
                    md.append(" 0 |");
                } else if (row.status().discrepancy()) {
                    md.append(" **").append(row.count()).append("** (").append(row.dbgapCount() == null ? "?" : row.dbgapCount())
                            .append(") |");
                    findings.add("c" + code + " " + m.name() + ": " + row.status().name()
                            + (row.note() == null ? "" : " -- " + row.note()));
                } else {
                    md.append(' ').append(row.count()).append(" |");
                }
            }
            md.append('\n');
        }
        md.append('\n');
        findings.forEach(f -> md.append("- ").append(cell(f)).append('\n'));
        md.append('\n');
    }

    private static String label(StudyResult r) {
        return r.input().studyId() + (r.input().abv() == null || r.input().abv().isBlank() ? ""
                : " (" + cell(r.input().abv()) + ")");
    }

    /** Safe in a markdown table cell. */
    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replaceAll("[\r\n]+", " ");
    }

    /** Written aside and renamed: the runner uploads the reports directory while the job runs. */
    private static void writeAtomically(Path dir, String name, String content) {
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
}
