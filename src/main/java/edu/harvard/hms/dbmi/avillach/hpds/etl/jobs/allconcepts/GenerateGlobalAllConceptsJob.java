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
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.AllConceptsCsvBuilder;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.AllConceptsRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.ConceptPaths;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Participant;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes {@code global_AllConcepts.csv}: the concepts that describe every participant across all
 * ready studies.
 *
 * <p>{@code _consents} and {@code _studies_consents} are per study, so they are written study by
 * study. {@code _source_subject_id} and {@code _source_sample_id} are not: one participant (one dbGaP
 * subject, one HPDS id) can be in several studies, and a sample can be loaded by more than one of
 * them. Those two are collected from every ready study first and each {@code (hpds_id, value)} is
 * written once, then {@value #IDENTITY_STUDIES_REPORT} records, for every one of those rows, how
 * many ready studies (and which) it applies to.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.generate-global-all-concepts.enabled", havingValue = "true")
public class GenerateGlobalAllConceptsJob extends AbstractJob<GenerateGlobalAllConceptsJob.Output> {

    static final String OUTPUT_FILENAME = "global_AllConcepts.csv";
    /** Written to the reports directory: every subject/sample id row with the studies it applies to. */
    static final String IDENTITY_STUDIES_REPORT = "global_allconcepts_identity_studies.csv";
    /** Study combinations listed in the metrics, most shared rows first. */
    static final int STUDY_SETS_REPORTED = 50;

    private final ManagedInputsService managedInputsService;
    private final ConsentRepository consentRepository;
    private final ParticipantRepository participantRepository;
    private final SampleRepository sampleRepository;
    private final IoResolver io;

    public GenerateGlobalAllConceptsJob(ManagedInputsService managedInputsService,
                                         ConsentRepository consentRepository,
                                         ParticipantRepository participantRepository,
                                         SampleRepository sampleRepository,
                                         IoResolver io) {
        this.managedInputsService = managedInputsService;
        this.consentRepository = consentRepository;
        this.participantRepository = participantRepository;
        this.sampleRepository = sampleRepository;
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
        List<ManagedInputRow> allRows = managedInputsService.read();
        List<ManagedInputRow> readyStudies = allRows.stream().filter(ManagedInputRow::isReady).toList();

        AllConceptsCsvBuilder builder = new AllConceptsCsvBuilder();
        Identities subjects = new Identities(ConceptPaths.SOURCE_SUBJECT_ID);
        Identities samples = new Identities(ConceptPaths.SOURCE_SAMPLE_ID);
        Map<String, Long> rowsPerStudy = new LinkedHashMap<>();
        Map<String, Long> emptyAbbreviationCounts = new LinkedHashMap<>();
        List<String> skippedNoConsents = new ArrayList<>();

        for (ManagedInputRow study : readyStudies) {
            String studyId = study.studyId();
            log.info("Processing study {} ({})", studyId, study.abv());
            long before = builder.size();

            List<Consent> consents = consentRepository.findByStudyId(studyId);
            if (consents.isEmpty()) {
                log.warn("Study {} has no consents in the database; skipping", studyId);
                skippedNoConsents.add(studyId);
                continue;
            }

            addConsentsConcept(builder, consents, studyId);
            long emptyAbbrevs = addStudiesConsentsConcepts(builder, consents, studyId);
            for (Participant p : participantRepository.findByStudyId(studyId)) {
                subjects.add(p.hpdsId(), p.sourceId(), studyId);
            }
            for (Sample s : sampleRepository.findByStudyId(studyId)) {
                samples.add(s.hpdsId(), s.sourceSampleId(), studyId);
            }

            long added = builder.size() - before;
            rowsPerStudy.put(studyId, added);
            if (emptyAbbrevs > 0) {
                emptyAbbreviationCounts.put(studyId, emptyAbbrevs);
            }
            log.info("Study {} contributed {} consent row(s); {} subject id(s) and {} sample id(s) collected so far",
                    studyId, added, subjects.size(), samples.size());
        }

        // Each subject/sample id once, however many studies it was found in.
        subjects.addRowsTo(builder);
        samples.addRowsTo(builder);
        IdentitySummary subjectSummary = subjects.summary();
        IdentitySummary sampleSummary = samples.summary();
        log.info("Wrote {} subject id row(s) ({} in more than one study) and {} sample id row(s) ({} in more "
                        + "than one study)", subjectSummary.rows(), subjectSummary.shared(),
                sampleSummary.rows(), sampleSummary.shared());
        writeIdentityStudiesReport(ctx, List.of(subjects, samples));

        if (builder.isEmpty()) {
            if (ctx.getBoolean("allow-empty", false)) {
                log.warn("No concept rows generated across {} ready studies, but --allow-empty is set; skipping output",
                        readyStudies.size());
                return new Output(readyStudies.size(), 0, rowsPerStudy, subjectSummary, sampleSummary,
                        emptyAbbreviationCounts, skippedNoConsents, outputUri);
            }
            throw new DataException("No concept rows were generated across " + readyStudies.size()
                    + " ready studies. Check that the database has been populated.");
        }

        io.writeOutput(outputUri, builder::writeTo);
        log.info("Wrote {} rows to {}", builder.size(), outputUri);

        return new Output(readyStudies.size(), builder.size(), rowsPerStudy, subjectSummary, sampleSummary,
                emptyAbbreviationCounts, skippedNoConsents, outputUri);
    }

    private void addConsentsConcept(AllConceptsCsvBuilder builder, List<Consent> consents, String studyId) {
        for (Consent c : consents) {
            builder.add(AllConceptsRow.nonNumeric(
                    String.valueOf(c.hpdsId()),
                    ConceptPaths.CONSENTS,
                    studyId + ".c" + c.consentCode()));
        }
    }

    /** One row of an identity concept: a participant and one of its ids. */
    record Identity(long hpdsId, String value) {
    }

    /**
     * One identity concept's rows across all ready studies, in first-seen order, each with the
     * studies it was found in. Studies are added one after another, so a row found again within the
     * same study (a sample id loaded under both sample sources) is recorded once.
     */
    static final class Identities {
        private final String conceptPath;
        private final Map<Identity, List<String>> studiesByIdentity = new LinkedHashMap<>();

        Identities(String conceptPath) {
            this.conceptPath = conceptPath;
        }

        void add(long hpdsId, String value, String studyId) {
            List<String> studies = studiesByIdentity.computeIfAbsent(new Identity(hpdsId, value),
                    k -> new ArrayList<>(1));
            if (studies.isEmpty() || !studies.get(studies.size() - 1).equals(studyId)) {
                studies.add(studyId);
            }
        }

        int size() {
            return studiesByIdentity.size();
        }

        void addRowsTo(AllConceptsCsvBuilder builder) {
            studiesByIdentity.keySet().forEach(id ->
                    builder.add(AllConceptsRow.nonNumeric(String.valueOf(id.hpdsId()), conceptPath, id.value())));
        }

        IdentitySummary summary() {
            Map<Integer, Long> byCount = new TreeMap<>();
            Map<String, Long> bySet = new HashMap<>();
            long shared = 0;
            for (List<String> studies : studiesByIdentity.values()) {
                byCount.merge(studies.size(), 1L, Long::sum);
                if (studies.size() > 1) {
                    shared++;
                    bySet.merge(String.join("+", studies.stream().sorted().toList()), 1L, Long::sum);
                }
            }
            Map<String, Long> rowsByStudyCount = new LinkedHashMap<>();
            byCount.forEach((n, rows) -> rowsByStudyCount.put(String.valueOf(n), rows));
            Map<String, Long> sharedStudySets = new LinkedHashMap<>();
            bySet.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(STUDY_SETS_REPORTED)
                    .forEach(e -> sharedStudySets.put(e.getKey(), e.getValue()));
            return new IdentitySummary(studiesByIdentity.size(), shared, rowsByStudyCount, sharedStudySets);
        }
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
    private void writeIdentityStudiesReport(JobContext ctx, List<Identities> concepts) {
        Path target = ctx.reportsDir().resolve(IDENTITY_STUDIES_REPORT);
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        try {
            Files.createDirectories(target.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
                out.write("concept,hpds_id,value,study_count,study_ids\n");
                for (Identities concept : concepts) {
                    for (Map.Entry<Identity, List<String>> e : concept.studiesByIdentity.entrySet()) {
                        out.write(csvField(concept.conceptPath) + "," + e.getKey().hpdsId() + ","
                                + csvField(e.getKey().value()) + "," + e.getValue().size() + ","
                                + csvField(String.join(";", e.getValue())) + "\n");
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write report " + IDENTITY_STUDIES_REPORT, e);
        }
    }

    private static String csvField(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * Adds both study-level and individual consent concept paths. Returns the count of
     * consents skipped due to empty abbreviation (for individual paths only).
     */
    private long addStudiesConsentsConcepts(AllConceptsCsvBuilder builder, List<Consent> consents, String studyId) {
        long emptyAbbrevCount = 0;

        for (Consent c : consents) {
            builder.add(AllConceptsRow.nonNumeric(
                    String.valueOf(c.hpdsId()),
                    ConceptPaths.STUDIES_CONSENTS_PREFIX + studyId + "µ",
                    "TRUE"));

            if (c.consentAbbreviation() == null || c.consentAbbreviation().isBlank()) {
                emptyAbbrevCount++;
                log.warn("Skipping individual _studies_consents path for study {} consent_code {}: "
                        + "consent_abbreviation is empty", studyId, c.consentCode());
                continue;
            }

            builder.add(AllConceptsRow.nonNumeric(
                    String.valueOf(c.hpdsId()),
                    ConceptPaths.STUDIES_CONSENTS_PREFIX + studyId + "µ" + c.consentAbbreviation() + "µ",
                    "TRUE"));
        }

        return emptyAbbrevCount;
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
