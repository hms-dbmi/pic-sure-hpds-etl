package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Indexes every {@link Job} bean Spring created, by {@link Job#name()}. Annotating a job with
 * {@code @Component} makes it runnable via {@code --job=<name>} with no central switch statement
 * to edit.
 *
 * <p>Jobs are opt-in: each carries
 * {@code @ConditionalOnProperty("etl.jobs.<job-name>.enabled", havingValue = "true")}, so a
 * disabled job is never instantiated and never reaches this registry. "Registered" therefore means
 * "enabled in this environment", and {@link #require(String)} cannot distinguish a wrong name from
 * a disabled job.
 */
@Component
public class JobRegistry {

    private final Map<String, Job> jobsByName;

    public JobRegistry(List<Job> jobs) {
        Map<String, Job> map = new LinkedHashMap<>();
        for (Job job : jobs) {
            Job existing = map.putIfAbsent(job.name(), job);
            if (existing != null) {
                throw new IllegalStateException("Duplicate job name '" + job.name() + "' declared by "
                        + existing.getClass().getName() + " and " + job.getClass().getName());
            }
        }
        this.jobsByName = map;
    }

    /** @throws ConfigException if no job with that name is enabled. */
    public Job require(String name) {
        Job job = jobsByName.get(name);
        if (job == null) {
            // Name the flag: a disabled job is indistinguishable from a typo at this point.
            throw new ConfigException("No enabled job named '" + name + "'. Either the name is wrong, or the "
                    + "job is disabled -- jobs are opt-in via etl.jobs." + name + ".enabled=true "
                    + "(see application.yml). Currently enabled: " + names());
        }
        return job;
    }

    public boolean contains(String name) {
        return jobsByName.containsKey(name);
    }

    public List<String> names() {
        return jobsByName.values().stream()
                .map(j -> j.name() + " (" + j.type() + ")")
                .sorted()
                .collect(Collectors.toList());
    }
}
