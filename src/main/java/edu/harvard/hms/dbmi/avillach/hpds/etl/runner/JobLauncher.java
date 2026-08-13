package edu.harvard.hms.dbmi.avillach.hpds.etl.runner;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.EtlException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.Job;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobRegistry;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.pipeline.PipelineRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The application entry point once Spring has started: reads {@code --job}/{@code --pipeline}
 * from the command line, dispatches to {@link JobExecutor}/{@link PipelineRunner}, and
 * records the resulting {@link ExitCode}. Spring Boot calls {@link #getExitCode()} after
 * this runner finishes and {@code EtlApplication} turns it into the process exit status.
 *
 * <p>Usage:
 * <pre>
 *   java -jar hpds-etl.jar --job=&lt;name&gt; [--param=value ...]
 *   java -jar hpds-etl.jar --pipeline=&lt;name&gt; [--param=value ...]
 *   java -jar hpds-etl.jar --help
 * </pre>
 */
@Component
public class JobLauncher implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(JobLauncher.class);

    /** Option names consumed by the launcher itself; everything else is a job parameter. */
    private static final Set<String> RESERVED = Set.of("job", "pipeline", "run-id", "help");

    private final JobRegistry registry;
    private final JobExecutor executor;
    private final PipelineRunner pipelineRunner;

    private volatile int exitCode = ExitCode.SUCCESS.code();

    public JobLauncher(JobRegistry registry, JobExecutor executor, PipelineRunner pipelineRunner) {
        this.registry = registry;
        this.executor = executor;
        this.pipelineRunner = pipelineRunner;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String job = single(args, "job");
            String pipeline = single(args, "pipeline");

            if (args.containsOption("help") || (job == null && pipeline == null)) {
                printUsage();
                return;
            }
            if (job != null && pipeline != null) {
                log.error("Specify only one of --job or --pipeline");
                exitCode = ExitCode.CONFIG_ERROR.code();
                return;
            }

            String runId = single(args, "run-id") != null ? single(args, "run-id") : newRunId();
            Map<String, String> params = jobParams(args);

            if (job != null) {
                Job resolved = registry.require(job); // ConfigException if unknown
                JobResult result = executor.run(resolved, params, runId);
                exitCode = result.getExitCode().code();
            } else {
                exitCode = pipelineRunner.run(pipeline, params, runId).code();
            }
        } catch (EtlException e) {
            // Dispatch-level failure (unknown job, bad pipeline). Job-level failures are
            // already turned into exit codes inside JobExecutor.
            log.error("{}", e.getMessage());
            exitCode = e.exitCode().code();
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /** Collects every {@code --key=value} that is not a reserved option into the param map. */
    private Map<String, String> jobParams(ApplicationArguments args) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String name : args.getOptionNames()) {
            if (RESERVED.contains(name)) {
                continue;
            }
            String value = single(args, name);
            if (value != null) {
                params.put(name, value);
            }
        }
        return params;
    }

    /** Returns the last value for an option, or null if absent/empty. */
    private String single(ApplicationArguments args, String name) {
        var values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(values.size() - 1);
    }

    private String newRunId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void printUsage() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nHPDS ETL job runner\n")
          .append("  java -jar hpds-etl.jar --job=<name> [--param=value ...]\n")
          .append("  java -jar hpds-etl.jar --pipeline=<name> [--param=value ...]\n\n")
          // Only enabled jobs exist as beans, so this lists what this environment can run --
          // not every job in the JAR. See etl.jobs.* in application.yml.
          .append("Jobs enabled in this environment:\n");
        for (String name : registry.names()) {
            sb.append("  - ").append(name).append('\n');
        }
        for (String name : registry.names()) {
            // name is "<job> (<TYPE>)"; strip the suffix to look up the job.
            String jobName = name.substring(0, name.indexOf(' '));
            Job job = registry.require(jobName);
            sb.append("\n  ").append(jobName).append(" parameters:\n");
            for (ParamSpec p : job.expectations().inputs()) {
                sb.append("    --").append(p.name())
                  .append(p.required() ? " (required) " : " (optional) ")
                  .append(p.description());
                if (p.example() != null && !p.example().isBlank()) {
                    sb.append("  e.g. ").append(p.example());
                }
                sb.append('\n');
            }
        }
        log.info(sb.toString());
    }
}
