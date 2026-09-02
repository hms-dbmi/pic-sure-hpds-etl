package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.genomic;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
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
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "etl.jobs.create-vcf-indexes.enabled", havingValue = "true")
public class CreateVCFIndexesJob extends AbstractJob<CreateVCFIndexesJob.Output> {

    private static final String VCF_PATH_TEMPLATE =
            "data/vcfInput/%s.chr%s.annotated_remove_modifiers.hpds.vcf.gz";
    private static final String HEADER =
            "vcf_path\tchromosome\tisAnnotated\tisGzipped\tsample_ids\tpatient_ids\tsample_relationship\trelated_sample_ids";
    private static final List<String> CHROMOSOMES;

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
        return Boolean.parseBoolean(ctx.get("include-processed", "false"));
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

        int consentGroupsProcessed = 0;
        long totalSamples = 0;
        List<String> outputFiles = new ArrayList<>();
        List<String> skippedStudies = new ArrayList<>();
        List<String> idCountMismatches = new ArrayList<>();

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

            Map<UUID, String> uuidToConsentCode = new LinkedHashMap<>();
            for (Consent c : consents) {
                uuidToConsentCode.put(c.hpdsUuid(), c.consentCode());
            }

            Map<String, List<Sample>> samplesByConsent = new LinkedHashMap<>();
            for (Sample s : samples) {
                String code = uuidToConsentCode.get(s.hpdsUuid());
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

                String sampleIds = nwdSamples.stream()
                        .map(Sample::sourceSampleId)
                        .collect(Collectors.joining(","));
                String patientIds = nwdSamples.stream()
                        .map(s -> s.hpdsUuid().toString())
                        .collect(Collectors.joining(","));

                long sampleIdCount = sampleIds.chars().filter(c -> c == ',').count() + 1;
                long patientIdCount = patientIds.chars().filter(c -> c == ',').count() + 1;
                if (sampleIdCount != patientIdCount) {
                    idCountMismatches.add(consentGroup + ": " + sampleIdCount + " sample_ids vs " + patientIdCount + " patient_ids");
                    log.error("Count mismatch in {}: {} sample_ids vs {} patient_ids", consentGroup, sampleIdCount, patientIdCount);
                }

                String vcfIndex = buildVcfIndex(consentGroup, sampleIds, patientIds);
                String sampleCsv = buildSampleIdsCsv(nwdSamples, study.abv());

                String vcfUri = outputBase + consentGroup + "_vcfIndex.tsv";
                String csvUri = outputBase + consentGroup + "_SampleIds.csv";
                io.writeOutput(vcfUri, vcfIndex.getBytes(StandardCharsets.UTF_8));
                io.writeOutput(csvUri, sampleCsv.getBytes(StandardCharsets.UTF_8));

                outputFiles.add(vcfUri);
                outputFiles.add(csvUri);
                totalSamples += nwdSamples.size();
                consentGroupsProcessed++;

                log.info("Wrote {} ({} NWD samples)", consentGroup, nwdSamples.size());
            }
        }

        return new Output(genomicStudies.size(), consentGroupsProcessed, totalSamples, outputFiles, skippedStudies, idCountMismatches);
    }

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
            sb.append(s.hpdsUuid()).append(',')
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
                // Nothing genomic to process (already flagged as NO_GENOMIC_STUDIES or
                // SKIPPED_STUDY): an empty output is consistent, not a failure.
                report.warning("NO_FILES_WRITTEN",
                        "No VCF index files were written (no genomic studies were processed)");
            } else {
                report.error("NO_FILES_WRITTEN", "No VCF index files were written");
            }
        }
        for (String studyId : output.skippedStudies()) {
            report.warning("SKIPPED_STUDY",
                    "Study " + studyId + " was skipped (no consents or no samples)");
        }
        for (String mismatch : output.idCountMismatches()) {
            report.error("ID_COUNT_MISMATCH",
                    "sample_ids and patient_ids column counts differ: " + mismatch);
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("studiesProcessed", output.studiesProcessed())
                .metric("consentGroupsProcessed", output.consentGroupsProcessed())
                .metric("totalSamples", output.totalSamples())
                .metric("outputFiles", output.outputFiles())
                .metric("skippedStudies", output.skippedStudies());
    }

    public record Output(
            int studiesProcessed,
            int consentGroupsProcessed,
            long totalSamples,
            List<String> outputFiles,
            List<String> skippedStudies,
            List<String> idCountMismatches
    ) {}
}
