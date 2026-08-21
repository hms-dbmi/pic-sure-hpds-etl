package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "etl.jobs.generate-global-all-concepts.enabled", havingValue = "true")
public class GenerateGlobalAllConceptsJob extends AbstractJob<GenerateGlobalAllConceptsJob.Output> {

    static final String OUTPUT_FILENAME = "global_AllConcepts.csv";

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
                        ParamSpec.optional("managed-inputs",
                                "Override for the managed inputs URI (default: etl.managed-inputs.uri config)",
                                "s3://bucket/managed_inputs.csv")),
                List.of("global_AllConcepts.csv written to --output with concept rows for all ready studies"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
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
        Map<String, Long> rowsPerStudy = new LinkedHashMap<>();
        Map<String, Long> emptyAbbreviationCounts = new LinkedHashMap<>();

        for (ManagedInputRow study : readyStudies) {
            String studyId = study.studyId();
            log.info("Processing study {} ({})", studyId, study.abv());
            long before = builder.size();

            List<Consent> consents = consentRepository.findByStudyId(studyId);
            if (consents.isEmpty()) {
                log.warn("Study {} has no consents in the database; skipping", studyId);
                continue;
            }

            addConsentsConcept(builder, consents, studyId);
            addParticipantsConcept(builder, studyId);
            addSamplesConcept(builder, studyId);
            long emptyAbbrevs = addStudiesConsentsConcepts(builder, consents, studyId);

            long added = builder.size() - before;
            rowsPerStudy.put(studyId, added);
            if (emptyAbbrevs > 0) {
                emptyAbbreviationCounts.put(studyId, emptyAbbrevs);
            }
            log.info("Study {} contributed {} rows", studyId, added);
        }

        if (builder.isEmpty()) {
            throw new DataException("No concept rows were generated across " + readyStudies.size()
                    + " ready studies. Check that the database has been populated.");
        }

        byte[] csv = builder.build();
        io.writeOutput(outputUri, csv);
        log.info("Wrote {} rows ({} bytes) to {}", builder.size(), csv.length, outputUri);

        return new Output(readyStudies.size(), builder.size(), rowsPerStudy, emptyAbbreviationCounts, outputUri);
    }

    private void addConsentsConcept(AllConceptsCsvBuilder builder, List<Consent> consents, String studyId) {
        for (Consent c : consents) {
            builder.add(AllConceptsRow.nonNumeric(
                    c.hpdsUuid().toString(),
                    "µ_consentsµ",
                    studyId + ".c" + c.consentCode()));
        }
    }

    private void addParticipantsConcept(AllConceptsCsvBuilder builder, String studyId) {
        List<Participant> participants = participantRepository.findByStudyId(studyId);
        for (Participant p : participants) {
            builder.add(AllConceptsRow.nonNumeric(
                    p.hpdsUuid().toString(),
                    "µ_source_subject_idµ",
                    p.sourceId()));
        }
    }

    private void addSamplesConcept(AllConceptsCsvBuilder builder, String studyId) {
        List<Sample> samples = sampleRepository.findByStudyId(studyId);
        for (Sample s : samples) {
            builder.add(AllConceptsRow.nonNumeric(
                    s.hpdsUuid().toString(),
                    "µ_source_sample_idµ",
                    s.sourceSampleId()));
        }
    }

    /**
     * Adds both study-level and individual consent concept paths. Returns the count of
     * consents skipped due to empty abbreviation (for individual paths only).
     */
    private long addStudiesConsentsConcepts(AllConceptsCsvBuilder builder, List<Consent> consents, String studyId) {
        long emptyAbbrevCount = 0;

        for (Consent c : consents) {
            builder.add(AllConceptsRow.nonNumeric(
                    c.hpdsUuid().toString(),
                    "µ_studies_consentsµ" + studyId + "µ",
                    "TRUE"));

            if (c.consentAbbreviation() == null || c.consentAbbreviation().isBlank()) {
                emptyAbbrevCount++;
                log.warn("Skipping individual _studies_consents path for study {} consent_code {}: "
                        + "consent_abbreviation is empty", studyId, c.consentCode());
                continue;
            }

            builder.add(AllConceptsRow.nonNumeric(
                    c.hpdsUuid().toString(),
                    "µ_studies_consentsµ" + studyId + "µ" + c.consentAbbreviation() + "µ",
                    "TRUE"));
        }

        return emptyAbbrevCount;
    }

    private String resolveOutputUri(String output) {
        if (output.endsWith("/") || output.endsWith(Path.of(output).getFileSystem().getSeparator())) {
            return output + OUTPUT_FILENAME;
        }
        return output;
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.totalRows() == 0) {
            report.error("EMPTY_OUTPUT", "No rows were written to the output file");
        }
        output.emptyAbbreviationCounts().forEach((studyId, count) ->
                report.warning("EMPTY_CONSENT_ABBREVIATION",
                        "Study " + studyId + ": " + count + " consent(s) skipped for individual "
                                + "_studies_consents paths due to empty consent_abbreviation"));
        output.rowsPerStudy().forEach((studyId, count) ->
                report.info("STUDY_ROW_COUNT", studyId + ": " + count + " row(s)"));
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("studiesProcessed", output.studiesProcessed())
                .metric("totalRows", output.totalRows())
                .metric("rowsPerStudy", output.rowsPerStudy())
                .metric("outputUri", output.outputUri());
    }

    public record Output(
            int studiesProcessed,
            long totalRows,
            Map<String, Long> rowsPerStudy,
            Map<String, Long> emptyAbbreviationCounts,
            String outputUri
    ) {
    }
}
