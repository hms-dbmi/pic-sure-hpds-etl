package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.ConceptPaths;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.GlobalAllConceptsRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.GlobalAllConceptsRepository.ConsentStats;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.GlobalAllConceptsRepository.IdentityCounts;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes {@code global_AllConcepts.csv}: the concepts that describe every participant across all
 * ready studies. The participant DB builds and formats every row (see
 * {@link GlobalAllConceptsRepository}), streamed straight to the output; row order is not meaningful.
 *
 * <p>{@code _consents} and {@code _studies_consents} are per study. {@code _source_subject_id} and
 * {@code _source_sample_id} are not: one participant (one dbGaP subject, one HPDS id) can be in
 * several studies, and a sample can be loaded by more than one of them, so each
 * {@code (hpds_id, value)} is written once, and {@value #IDENTITY_STUDIES_REPORT} records, for every
 * one of those rows, how many ready studies (and which) it applies to.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.generate-global-all-concepts.enabled", havingValue = "true")
public class GenerateGlobalAllConceptsJob extends AbstractJob<GenerateGlobalAllConceptsJob.Output> {

    static final String OUTPUT_FILENAME = "global_AllConcepts.csv";
    /** Written to the reports directory: every subject/sample id row with the studies it applies to. */
    static final String IDENTITY_STUDIES_REPORT = "global_allconcepts_identity_studies.csv";
    /** Study combinations listed in the metrics, most shared rows first. */
    static final int STUDY_SETS_REPORTED = 50;

    private static final int BUFFER_BYTES = 1 << 20;

    private final ManagedInputsService managedInputsService;
    private final GlobalAllConceptsRepository repository;
    private final IoResolver io;

    public GenerateGlobalAllConceptsJob(ManagedInputsService managedInputsService,
                                         GlobalAllConceptsRepository repository,
                                         IoResolver io) {
        this.managedInputsService = managedInputsService;
        this.repository = repository;
        this.io = io;
    }

    @Override
    public String name() {
        return "generate-global-all-concepts";
    }

    @Override
    public JobType type() {
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(
                        ParamSpec.required("output",
                                "Output location for global_AllConcepts.csv (local path or s3:// URI). "
                                        + "The filename is appended automatically if the value ends with '/'.",
                                "s3://bucket/etl-output/"),
                        ParamSpec.optional("allow-empty",
                                "When true, exit 0 even if no concept rows are generated (for preflight runs). "
                                        + "Default: false; the example shows the opt-in value.",
                                "true")),
                List.of("global_AllConcepts.csv written to --output with concept rows for all ready studies"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        validateBooleanParam(ctx, report, "allow-empty");
        List<ManagedInputRow> rows = managedInputsService.read();
        long readyCount = rows.stream().filter(ManagedInputRow::isReady).count();
        if (readyCount == 0) {
            report.error("NO_READY_STUDIES",
                    "No studies are marked as ready in managed inputs (" + rows.size() + " total rows)");
        }
    }

    @Override
    protected Output execute(JobContext ctx) {
        String outputUri = resolveOutputUri(ctx.require("output"));
        List<String> readyStudies = new ArrayList<>(new LinkedHashSet<>(managedInputsService.read().stream()
                .filter(ManagedInputRow::isReady).map(ManagedInputRow::studyId).toList()));

        try (GlobalAllConceptsRepository.Session session = repository.open(readyStudies)) {
            Map<String, ConsentStats> stats = session.consentStats();
            Map<String, Long> rowsPerStudy = new LinkedHashMap<>();
            Map<String, Long> emptyAbbreviationCounts = new LinkedHashMap<>();
            List<String> skippedNoConsents = new ArrayList<>();
            for (String studyId : readyStudies) {
                ConsentStats s = stats.get(studyId);
                if (s == null) {
                    log.warn("Study {} has no consents in the database; skipping", studyId);
                    skippedNoConsents.add(studyId);
                    continue;
                }
                // µ_consentsµ and µ_studies_consentsµstudyµ for every consent, and the individual
                // path for those with an abbreviation.
                rowsPerStudy.put(studyId, 3 * s.rows() - s.emptyAbbreviations());
                if (s.emptyAbbreviations() > 0) {
                    emptyAbbreviationCounts.put(studyId, s.emptyAbbreviations());
                    log.warn("Skipping individual _studies_consents paths for {} consent row(s) of study {} "
                                    + "(consent_code {}): consent_abbreviation is empty",
                            s.emptyAbbreviations(), studyId, s.emptyAbbreviationCodes());
                }
            }

            IdentitySummary subjects = summary(session.identityCounts(ConceptPaths.SOURCE_SUBJECT_ID,
                    STUDY_SETS_REPORTED));
            IdentitySummary samples = summary(session.identityCounts(ConceptPaths.SOURCE_SAMPLE_ID,
                    STUDY_SETS_REPORTED));
            log.info("{} ready study/studies, {} with consents; {} subject id row(s) ({} in more than one study) "
                            + "and {} sample id row(s) ({} in more than one study)", readyStudies.size(),
                    stats.size(), subjects.rows(), subjects.shared(), samples.rows(), samples.shared());
            writeIdentityStudiesReport(ctx, session);

            if (stats.isEmpty()) {
                if (ctx.getBoolean("allow-empty", false)) {
                    log.warn("No concept rows generated across {} ready studies, but --allow-empty is set; "
                            + "skipping output", readyStudies.size());
                    return new Output(readyStudies.size(), 0, rowsPerStudy, subjects, samples,
                            emptyAbbreviationCounts, skippedNoConsents, outputUri);
                }
                throw new DataException("No concept rows were generated across " + readyStudies.size()
                        + " ready studies. Check that the database has been populated.");
            }

            AtomicLong written = new AtomicLong();
            io.writeOutput(outputUri, out -> written.set(session.copyConcepts(out)));
            log.info("Wrote {} rows to {}", written.get(), outputUri);

            return new Output(readyStudies.size(), written.get(), rowsPerStudy, subjects, samples,
                    emptyAbbreviationCounts, skippedNoConsents, outputUri);
        }
    }

    private static IdentitySummary summary(IdentityCounts counts) {
        long rows = 0;
        long shared = 0;
        Map<String, Long> byCount = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> e : counts.rowsByStudyCount().entrySet()) {
            rows += e.getValue();
            if (e.getKey() > 1) {
                shared += e.getValue();
            }
            byCount.put(String.valueOf(e.getKey()), e.getValue());
        }
        Map<String, Long> sets = new LinkedHashMap<>();
        counts.sharedStudySets().forEach((studies, n) -> sets.put(studies.replace(';', '+'), n));
        return new IdentitySummary(rows, shared, byCount, sets);
    }

    /**
     * How one identity concept's rows spread over the ready studies.
     *
     * @param rows             rows written, one per {@code (hpds_id, value)}
     * @param shared           rows that apply to more than one study
     * @param rowsByStudyCount number of studies &rarr; rows that apply to that many ({@code "1"}, {@code "2"}, ...)
     * @param sharedStudySets  for shared rows, the studies joined with {@code +} &rarr; rows, the
     *                         {@value #STUDY_SETS_REPORTED} most common
     */
    public record IdentitySummary(long rows, long shared, Map<String, Long> rowsByStudyCount,
                                  Map<String, Long> sharedStudySets) {
    }

    /**
     * Writes {@value #IDENTITY_STUDIES_REPORT} to the reports directory: {@code concept, hpds_id, value,
     * study_count, study_ids} for every subject and sample id row, study ids joined with {@code ;}.
     */
    private void writeIdentityStudiesReport(JobContext ctx, GlobalAllConceptsRepository.Session session) {
        Path target = ctx.reportsDir().resolve(IDENTITY_STUDIES_REPORT);
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(partial), BUFFER_BYTES)) {
                out.write("concept,hpds_id,value,study_count,study_ids\n".getBytes(StandardCharsets.UTF_8));
                session.copyIdentityStudies(out);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write report " + IDENTITY_STUDIES_REPORT, e);
        }
    }

    private String resolveOutputUri(String output) {
        if (output.endsWith("/")) {
            return output + OUTPUT_FILENAME;
        }
        return output;
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.totalRows() == 0) {
            if (ctx.getBoolean("allow-empty", false)) {
                report.warning("EMPTY_OUTPUT", "No rows were written (--allow-empty is set)");
            } else {
                report.error("EMPTY_OUTPUT", "No rows were written to the output file");
            }
        }
        for (String studyId : output.skippedNoConsents()) {
            report.warning("NO_CONSENTS",
                    "Study " + studyId + " has no consents in the database and was skipped");
        }
        output.emptyAbbreviationCounts().forEach((studyId, count) ->
                report.warning("EMPTY_CONSENT_ABBREVIATION",
                        "Study " + studyId + ": " + count + " consent(s) skipped for individual "
                                + "_studies_consents paths due to empty consent_abbreviation"));
        output.rowsPerStudy().forEach((studyId, count) ->
                report.info("STUDY_ROW_COUNT", studyId + ": " + count + " row(s)"));
        reportShared(report, "SHARED_SUBJECT_IDS", "_source_subject_id", output.subjects());
        reportShared(report, "SHARED_SAMPLE_IDS", "_source_sample_id", output.samples());
    }

    /** Info, not a warning: a participant in a parent study and its TOPMed study is expected. */
    private static void reportShared(ValidationReport report, String code, String concept, IdentitySummary s) {
        if (s.shared() > 0) {
            report.info(code, s.shared() + " of " + s.rows() + " " + concept + " row(s) apply to more than one "
                    + "ready study and were written once; by number of studies: " + s.rowsByStudyCount()
                    + ". Every row's studies are in " + IDENTITY_STUDIES_REPORT);
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("studiesProcessed", output.studiesProcessed())
                .metric("totalRows", output.totalRows())
                .metric("rowsPerStudy", output.rowsPerStudy())
                .metric("subjectIdRows", output.subjects().rows())
                .metric("subjectIdsByStudyCount", output.subjects().rowsByStudyCount())
                .metric("sharedSubjectIdStudySets", output.subjects().sharedStudySets())
                .metric("sampleIdRows", output.samples().rows())
                .metric("sampleIdsByStudyCount", output.samples().rowsByStudyCount())
                .metric("sharedSampleIdStudySets", output.samples().sharedStudySets())
                .metric("identityStudiesReport", IDENTITY_STUDIES_REPORT)
                .metric("skippedNoConsents", output.skippedNoConsents())
                .metric("outputUri", output.outputUri());
    }

    public record Output(
            int studiesProcessed,
            long totalRows,
            /** Per study, its {@code _consents} and {@code _studies_consents} rows: the rows only it has. */
            Map<String, Long> rowsPerStudy,
            /** {@code _source_subject_id}: written once per row across all studies. */
            IdentitySummary subjects,
            /** {@code _source_sample_id}: written once per row across all studies. */
            IdentitySummary samples,
            Map<String, Long> emptyAbbreviationCounts,
            List<String> skippedNoConsents,
            String outputUri
    ) {
    }
}
