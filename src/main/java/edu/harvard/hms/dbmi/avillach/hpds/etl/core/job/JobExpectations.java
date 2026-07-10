package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import java.util.List;

/**
 * The declared input/output contract for a job. Every job publishes this so that:
 * <ul>
 *   <li>required inputs are auto-validated before the job body runs;</li>
 *   <li>{@code --job=name --help} can print a usage summary;</li>
 *   <li>Jenkins/README documentation can be generated from a single source of truth.</li>
 * </ul>
 *
 * @param inputs  parameters the job reads (see {@link ParamSpec})
 * @param outputs human-readable descriptions of what the job produces (rows written,
 *                files emitted, S3 markers, etc.) -- for documentation and review
 */
public record JobExpectations(List<ParamSpec> inputs, List<String> outputs) {

    public static JobExpectations of(List<ParamSpec> inputs, List<String> outputs) {
        return new JobExpectations(List.copyOf(inputs), List.copyOf(outputs));
    }
}
