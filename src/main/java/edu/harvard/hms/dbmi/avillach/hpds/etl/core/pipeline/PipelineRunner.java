package edu.harvard.hms.dbmi.avillach.hpds.etl.core.pipeline;

import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.Job;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobRegistry;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Runs a named, ordered list of jobs in-process, stopping at the first job that does
 * not succeed. This mirrors "job A succeeds &rarr; trigger job B" for local and CI use.
 *
 * <p>In production, prefer expressing the DAG as Jenkins stages -- that gives you
 * per-stage retries, notifications, and scheduling for free. This runner exists so the
 * same chaining can be exercised on a laptop or in a single CI step.
 */
@Component
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final JobRegistry registry;
    private final JobExecutor executor;
    private final EtlProperties properties;

    public PipelineRunner(JobRegistry registry, JobExecutor executor, EtlProperties properties) {
        this.registry = registry;
        this.executor = executor;
        this.properties = properties;
    }

    /**
     * Runs the pipeline named {@code pipelineName}. All jobs share {@code params} and a
     * per-job run id derived from {@code runId}.
     *
     * @return the exit code of the first failing job, or SUCCESS if all pass
     */
    public ExitCode run(String pipelineName, Map<String, String> params, String runId) {
        List<String> jobNames = properties.getPipelines().get(pipelineName);
        if (jobNames == null || jobNames.isEmpty()) {
            throw new ConfigException("Unknown or empty pipeline '" + pipelineName
                    + "'. Configured pipelines: " + properties.getPipelines().keySet());
        }

        // Validate every referenced job exists before running any, so we fail fast.
        jobNames.forEach(registry::require);

        log.info("Running pipeline '{}' with {} step(s): {}", pipelineName, jobNames.size(), jobNames);
        for (int i = 0; i < jobNames.size(); i++) {
            String jobName = jobNames.get(i);
            Job job = registry.require(jobName);
            String stepRunId = runId + "-step" + (i + 1) + "-" + jobName;
            log.info("Pipeline '{}' step {}/{}: {}", pipelineName, i + 1, jobNames.size(), jobName);

            JobResult result = executor.run(job, params, stepRunId);
            if (!result.isSuccess()) {
                log.error("Pipeline '{}' halted at step {}/{} ('{}') with {}",
                        pipelineName, i + 1, jobNames.size(), jobName, result.getExitCode());
                return result.getExitCode();
            }
        }
        log.info("Pipeline '{}' completed successfully", pipelineName);
        return ExitCode.SUCCESS;
    }
}
