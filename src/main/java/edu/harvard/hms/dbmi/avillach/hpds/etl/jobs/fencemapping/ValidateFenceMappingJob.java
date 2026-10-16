package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.fencemapping;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.fencemapping.FenceMappingValidator;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates a PSAMA {@code fence_mapping.json} against {@code schemas/fence_mapping.schema.json}
 * plus the coded cross-entry checks in {@link FenceMappingValidator}. Writes nothing; any error
 * ends the run with {@code VALIDATION_FAILED} (exit 2), so it can gate a deploy of the file.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.validate-fence-mapping.enabled", havingValue = "true")
public class ValidateFenceMappingJob extends AbstractJob<ValidateFenceMappingJob.Output> {

    private final IoResolver io;
    private final FenceMappingValidator validator;

    public ValidateFenceMappingJob(IoResolver io, FenceMappingValidator validator) {
        this.io = io;
        this.validator = validator;
    }

    @Override
    public String name() {
        return "validate-fence-mapping";
    }

    @Override
    public JobType type() {
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(ParamSpec.required("input",
                        "fence_mapping.json to validate (local path or s3:// URI)",
                        "s3://my-bucket/config/fence_mapping.json")),
                List.of("Validation report only; exit 2 if the file would be rejected or mis-mapped by PSAMA"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        // The only parameter is checked for presence by AbstractJob.
    }

    @Override
    protected Output execute(JobContext ctx) {
        String input = ctx.require("input");
        FenceMappingValidator.Result result = validator.validate(io.openInput(input));
        return new Output(input, result);
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        report.merge(output.result().report());
        report.info("FENCE_ENTRIES", output.result().entries().size() + " entr"
                + (output.result().entries().size() == 1 ? "y" : "ies") + " interpreted in " + output.input());
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("input", output.input())
                .metric("entries", output.result().entries().size())
                .metric("publicEntries", output.result().entries().stream()
                        .filter(FenceMappingValidator.Entry::isPublic).count());
    }

    public record Output(String input, FenceMappingValidator.Result result) {
    }
}
