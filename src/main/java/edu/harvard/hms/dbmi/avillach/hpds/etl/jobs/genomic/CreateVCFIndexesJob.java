package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.genomic;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "etl.jobs.create-vcf-indexes.enabled", havingValue = "true")
public class CreateVCFIndexesJob extends AbstractJob<CreateVCFIndexesJob.Output> {

    private static final String VCF_PATH_TEMPLATE =
            "data/vcfInput/%s.chr%s.annotated_remove_modifiers.hpds.vcf.gz";
    private static final String HEADER =
            "vcf_path\tchromosome\tisAnnotated\tisGzipped\tsample_ids\tpatient_ids\tsample_relationship\trelated_sample_ids";
    private static final List<String> CHROMOSOMES;

    /** Characters that would break a sample id out of its list (comma) or row/column (tab, line break). */
    private static final String SEPARATORS = ",\t\n\r";
    /** Characters that would shift (comma) or split (line break) a _SampleIds.csv line. */
    private static final String ABV_SEPARATORS = ",\n\r";
    static final String REASON_BAD_STUDY_ABV = "BAD_STUDY_ABV";
    static final String REASON_ID_COUNT_MISMATCH = "ID_COUNT_MISMATCH";
    /** How many offending sample ids a held-back group's report entry carries. */
    private static final int BAD_ID_SAMPLE_SIZE = 10;

    static {
        List<String> chroms = new ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            chroms.add(String.valueOf(i));
        }
        chroms.add("X");
        CHROMOSOMES = List.copyOf(chroms);
    }

    private final ManagedInputsService managedInputsService;
    private final ConsentRepository consentRepository;
    private final SampleRepository sampleRepository;
    private final IoResolver io;

    public CreateVCFIndexesJob(ManagedInputsService managedInputsService,
                               ConsentRepository consentRepository,
                               SampleRepository sampleRepository,
                               IoResolver io) {
        this.managedInputsService = managedInputsService;
        this.consentRepository = consentRepository;
        this.sampleRepository = sampleRepository;
        this.io = io;
    }

    @Override
    public String name() {
        return "create-vcf-indexes";
    }

    @Override
    public JobType type() {
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(ParamSpec.required("output",
                        "Output location for VCF index and sample ID files (local path or s3:// URI).",
                        "s3://bucket/etl-output/"),
                        ParamSpec.optional("include-processed",
                                "When true, studies marked 'Data Processed' in managed inputs are included. "
                                        + "The migration pipeline sets this: its inputs were all processed by the "
                                        + "legacy system, which is exactly what is being regenerated.",
                                "false")),
                List.of("Per-consent-group _vcfIndex.tsv and _SampleIds.csv files for genomic studies"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        validateBooleanParam(ctx, report, "include-processed");
        if (report.hasErrors()) {
            // includeProcessed() below would throw on the same bad value.
            return;
        }
        List<ManagedInputRow> rows = managedInputsService.read();
        boolean includeProcessed = includeProcessed(ctx);
        long genomicCount = rows.stream()
                .filter(ManagedInputRow::isReady)
                .filter(r -> includeProcessed || !r.isProcessed())
                .filter(r -> isGenomic(r))
                .count();
        if (genomicCount == 0) {
            // An empty workload is a legitimate state (e.g. a phenotype-only sweep or a
            // single-study run), not a configuration mistake: flag it, don't fail on it.
            report.warning("NO_GENOMIC_STUDIES",
                    "No unprocessed ready studies with genomic data type found in managed inputs (" + rows.size() + " total rows)");
        }
    }

    private static boolean includeProcessed(JobContext ctx) {
        return ctx.getBoolean("include-processed", false);
    }

    @Override
    protected Output execute(JobContext ctx) {
        String outputBase = normalizeOutputBase(ctx.require("output"));
        List<ManagedInputRow> allRows = managedInputsService.read();
        boolean includeProcessed = includeProcessed(ctx);
        List<ManagedInputRow> genomicStudies = allRows.stream()
                .filter(ManagedInputRow::isReady)
                .filter(r -> includeProcessed || !r.isProcessed())
                .filter(r -> isGenomic(r))
                .toList();

        List<PlannedGroup> planned = new ArrayList<>();
        List<String> skippedStudies = new ArrayList<>();
        List<HeldBackGroup> heldBackGroups = new ArrayList<>();

        for (ManagedInputRow study : genomicStudies) {
            String studyId = study.studyId();
            log.info("Processing genomic study {} ({})", studyId, study.abv());

            List<Consent> consents = consentRepository.findByStudyId(studyId);
            if (consents.isEmpty()) {
                log.warn("Study {} has no consents; skipping", studyId);
                skippedStudies.add(studyId);
                continue;
            }

            List<Sample> samples = sampleRepository.findByStudyId(studyId);
            if (samples.isEmpty()) {
                log.warn("Study {} has no samples; skipping", studyId);
                skippedStudies.add(studyId);
                continue;
            }

            boolean badAbv = study.abv() != null && containsAny(study.abv(), ABV_SEPARATORS);

            Map<Long, String> idToConsentCode = new LinkedHashMap<>();
            for (Consent c : consents) {
                idToConsentCode.put(c.hpdsId(), c.consentCode());
            }

            Map<String, List<Sample>> samplesByConsent = new LinkedHashMap<>();
            for (Sample s : samples) {
                String code = idToConsentCode.get(s.hpdsId());
                if (code == null || code.equals("0")) {
                    continue;
                }
                samplesByConsent.computeIfAbsent(code, k -> new ArrayList<>()).add(s);
            }

            for (Map.Entry<String, List<Sample>> entry : samplesByConsent.entrySet()) {
                String consentCode = entry.getKey();
                String consentGroup = studyId + ".c" + consentCode;

                List<Sample> nwdSamples = entry.getValue().stream()
                        .filter(s -> s.sourceSampleId().startsWith("NWD"))
                        .toList();
                if (nwdSamples.isEmpty()) {
                    log.info("Consent group {} has no NWD samples; skipping", consentGroup);
                    continue;
                }

                // A bad group is held back: not written, and its previously published files are
                // deleted (below) so stale data cannot sit beside the freshly written groups, while
                // every good group is still written. Each held-back group is an output-validation
                // error, so the run still fails and the data gets fixed.
                List<String> reasons = new ArrayList<>();
                // _SampleIds.csv lines are "hpds_id,abv,sample_id": an abv holding a comma or line
                // break would shift or split every line of every group of the study. Checked only for
                // groups that would be written, so a study with nothing to index cannot fail the run.
                if (badAbv) {
                    reasons.add(REASON_BAD_STUDY_ABV);
                }
                // sample_ids and patient_ids are built from the same samples, one entry each, so they
                // can only fall out of step when a sample id contains the list or column separator.
                List<String> badSampleIds = nwdSamples.stream()
                        .map(Sample::sourceSampleId)
                        .filter(id -> containsAny(id, SEPARATORS))
                        .toList();
                if (!badSampleIds.isEmpty()) {
                    reasons.add(REASON_ID_COUNT_MISMATCH);
                }
                if (!reasons.isEmpty()) {
                    HeldBackGroup held = new HeldBackGroup(consentGroup, studyId, consentCode, study.abv(),
                            List.copyOf(reasons), badSampleIds.size(),
                            badSampleIds.stream().limit(BAD_ID_SAMPLE_SIZE).toList(), List.of());
                    heldBackGroups.add(held);
                    log.error("Holding back {} (not written; its previously published files will be deleted): {}",
                            consentGroup, held.describe());
                    continue;
                }

                String sampleIds = nwdSamples.stream()
                        .map(Sample::sourceSampleId)
                        .collect(Collectors.joining(","));
                String patientIds = nwdSamples.stream()
                        .map(s -> String.valueOf(s.hpdsId()))
                        .collect(Collectors.joining(","));

                planned.add(new PlannedGroup(consentGroup,
                        outputBase + consentGroup + "_vcfIndex.tsv", buildVcfIndex(consentGroup, sampleIds, patientIds),
                        outputBase + consentGroup + "_SampleIds.csv", buildSampleIdsCsv(nwdSamples, study.abv()),
                        nwdSamples.size()));
            }
        }

        // Held-back groups' earlier files are deleted below, on the promise that a versioned bucket
        // keeps them recoverable: check that before writing or deleting anything.
        if (!heldBackGroups.isEmpty() && io.isS3(outputBase)) {
            io.requireVersioning(outputBase);
        }

        int consentGroupsProcessed = 0;
        long totalSamples = 0;
        List<String> outputFiles = new ArrayList<>();
        for (PlannedGroup g : planned) {
            io.writeOutput(g.vcfUri(), g.vcfIndex().getBytes(StandardCharsets.UTF_8));
            io.writeOutput(g.csvUri(), g.sampleCsv().getBytes(StandardCharsets.UTF_8));
            outputFiles.add(g.vcfUri());
            outputFiles.add(g.csvUri());
            totalSamples += g.sampleCount();
            consentGroupsProcessed++;
            log.info("Wrote {} ({} NWD samples)", g.consentGroup(), g.sampleCount());
        }
        // A held-back group's earlier files describe data now known to be bad and, left in place, would
        // be picked up beside this run's groups as if current. Delete them (a versioned bucket keeps
        // them recoverable), only where they exist, and record exactly what was removed.
        List<HeldBackGroup> heldBackReported = new ArrayList<>();
        int heldBackFilesDeleted = 0;
        for (HeldBackGroup g : heldBackGroups) {
            List<String> deleted = new ArrayList<>();
            for (String uri : List.of(outputBase + g.consentGroup() + "_vcfIndex.tsv",
                    outputBase + g.consentGroup() + "_SampleIds.csv")) {
                if (io.exists(uri)) {
                    io.delete(uri);
                    deleted.add(uri);
                    log.warn("Deleted previously published {} of held-back group {}", uri, g.consentGroup());
                }
            }
            heldBackFilesDeleted += deleted.size();
            heldBackReported.add(g.withDeletedFiles(List.copyOf(deleted)));
        }
        if (!heldBackGroups.isEmpty()) {
            log.error("Held back {} consent group(s) for bad data (deleting {} previously published file(s)) "
                            + "and wrote {} good group(s); held back: {}",
                    heldBackGroups.size(), heldBackFilesDeleted, consentGroupsProcessed,
                    heldBackGroups.stream().map(HeldBackGroup::consentGroup).toList());
        }

        return new Output(genomicStudies.size(), consentGroupsProcessed, totalSamples, outputFiles, skippedStudies,
                List.copyOf(heldBackReported), heldBackFilesDeleted);
    }

    private static boolean containsAny(String value, String chars) {
        for (int i = 0; i < chars.length(); i++) {
            if (value.indexOf(chars.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * A consent group whose files were not written because its data would produce unparseable
     * output; its previously published files are deleted. Serialized into the report as
     * {@code metrics.heldBackGroups}.
     *
     * @param reasons          {@value #REASON_BAD_STUDY_ABV} and/or {@value #REASON_ID_COUNT_MISMATCH}
     * @param badSampleIdCount how many of the group's NWD sample ids contain a separator
     * @param badSampleIds     up to {@value #BAD_ID_SAMPLE_SIZE} of those ids
     * @param deletedFiles     the group's previously published _vcfIndex.tsv / _SampleIds.csv URIs this
     *                         run deleted; empty when it had none
     */
    public record HeldBackGroup(String consentGroup, String studyId, String consent, String studyAbv,
                                List<String> reasons, int badSampleIdCount, List<String> badSampleIds,
                                List<String> deletedFiles) {

        HeldBackGroup withDeletedFiles(List<String> files) {
            return new HeldBackGroup(consentGroup, studyId, consent, studyAbv, reasons, badSampleIdCount,
                    badSampleIds, files);
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            if (reasons.contains(REASON_BAD_STUDY_ABV)) {
                parts.add(abvProblem());
            }
            if (reasons.contains(REASON_ID_COUNT_MISMATCH)) {
                parts.add(sampleIdProblem());
            }
            return String.join("; ", parts);
        }

        String abvProblem() {
            return "study abbreviation '" + visible(studyAbv) + "' contains a comma or line break, "
                    + "so _SampleIds.csv would not parse";
        }

        String sampleIdProblem() {
            return badSampleIdCount + " sample id(s) contain a comma, tab, or line break, so "
                    + "sample_ids and patient_ids would not line up: "
                    + badSampleIds.stream().map(CreateVCFIndexesJob::visible).toList();
        }
    }

    /** Escapes tabs and line breaks so a bad value cannot split the log or report line quoting it. */
    private static String visible(String value) {
        return value.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    /** One consent group's files, built in full before anything is written. */
    private record PlannedGroup(String consentGroup, String vcfUri, String vcfIndex,
                                String csvUri, String sampleCsv, int sampleCount) {}

    private String buildVcfIndex(String consentGroup, String sampleIds, String patientIds) {
        StringBuilder sb = new StringBuilder();
        sb.append(consentGroup).append('\n');
        sb.append(HEADER).append('\n');
        for (String chrom : CHROMOSOMES) {
            sb.append(String.format(VCF_PATH_TEMPLATE, consentGroup, chrom)).append('\t')
                    .append(chrom).append('\t')
                    .append("TRUE").append('\t')
                    .append("TRUE").append('\t')
                    .append(sampleIds).append('\t')
                    .append(patientIds).append('\t')
                    .append("").append('\t')
                    .append("")
                    .append('\n');
        }
        return sb.toString();
    }

    private String buildSampleIdsCsv(List<Sample> nwdSamples, String studyAbv) {
        StringBuilder sb = new StringBuilder();
        for (Sample s : nwdSamples) {
            sb.append(s.hpdsId()).append(',')
                    .append(studyAbv).append(',')
                    .append(s.sourceSampleId())
                    .append('\n');
        }
        return sb.toString();
    }

    private String normalizeOutputBase(String output) {
        return output.endsWith("/") ? output : output + "/";
    }

    private static boolean isGenomic(ManagedInputRow row) {
        if (row.dataType() == null || row.dataType().isBlank()) {
            return false;
        }
        for (String part : row.dataType().split("/")) {
            if (part.trim().equalsIgnoreCase("G")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.outputFiles().isEmpty()) {
            if (output.studiesProcessed() == 0) {
                // Nothing genomic to process (already flagged as NO_GENOMIC_STUDIES): an empty
                // output is consistent, not a failure.
                report.warning("NO_FILES_WRITTEN",
                        "No VCF index files were written (no genomic studies were processed)");
            } else if (!output.heldBackGroups().isEmpty()) {
                report.error("NO_FILES_WRITTEN", "No VCF index files were written: every consent group with "
                        + "NWD samples was held back for bad data (" + output.heldBackGroups().size()
                        + " group(s); see ID_COUNT_MISMATCH / BAD_STUDY_ABV); " + output.heldBackFilesDeleted()
                        + " of their previously published file(s) were deleted");
            } else {
                // Genomic studies were selected but none produced an index: each was skipped for
                // missing consents/samples (SKIPPED_STUDY) or had no NWD samples. A genomic study
                // with nothing to index points at an incomplete load, so this stays a failure.
                report.error("NO_FILES_WRITTEN", "No VCF index files were written although "
                        + output.studiesProcessed() + " genomic study(ies) were selected ("
                        + output.skippedStudies().size() + " skipped for missing consents or samples; "
                        + "the rest had no NWD samples)");
            }
        }
        for (String studyId : output.skippedStudies()) {
            report.warning("SKIPPED_STUDY",
                    "Study " + studyId + " was skipped (no consents or no samples)");
        }
        // Each held-back group is an error, so the run fails even though the good groups were written.
        for (HeldBackGroup g : output.heldBackGroups()) {
            String heldBack = g.consentGroup() + " held back (not written; "
                    + (g.deletedFiles().isEmpty()
                            ? "it had no previously published files to delete"
                            : "its previously published files were deleted: " + String.join(", ", g.deletedFiles()))
                    + "): ";
            if (g.reasons().contains(REASON_BAD_STUDY_ABV)) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("studyId", g.studyId());
                context.put("consent", g.consent());
                context.put("reason", REASON_BAD_STUDY_ABV);
                context.put("studyAbv", g.studyAbv());
                context.put("deletedFiles", g.deletedFiles());
                report.add(ValidationIssue.error(REASON_BAD_STUDY_ABV, heldBack + g.abvProblem(),
                        g.consentGroup(), context));
            }
            if (g.reasons().contains(REASON_ID_COUNT_MISMATCH)) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("studyId", g.studyId());
                context.put("consent", g.consent());
                context.put("reason", REASON_ID_COUNT_MISMATCH);
                context.put("badSampleIdCount", g.badSampleIdCount());
                context.put("badSampleIds", g.badSampleIds());
                context.put("deletedFiles", g.deletedFiles());
                report.add(ValidationIssue.error(REASON_ID_COUNT_MISMATCH, heldBack + g.sampleIdProblem(),
                        g.consentGroup(), context));
            }
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("studiesProcessed", output.studiesProcessed())
                .metric("consentGroupsProcessed", output.consentGroupsProcessed())
                .metric("totalSamples", output.totalSamples())
                .metric("outputFiles", output.outputFiles())
                .metric("skippedStudies", output.skippedStudies())
                .metric("heldBackGroups", output.heldBackGroups())
                .metric("heldBackFilesDeleted", output.heldBackFilesDeleted());
    }

    public record Output(
            int studiesProcessed,
            int consentGroupsProcessed,
            long totalSamples,
            List<String> outputFiles,
            List<String> skippedStudies,
            /** Consent groups not written because of bad data; each is also an output-validation error. */
            List<HeldBackGroup> heldBackGroups,
            /** How many of the held-back groups' previously published files were deleted. */
            int heldBackFilesDeleted
    ) {}
}
