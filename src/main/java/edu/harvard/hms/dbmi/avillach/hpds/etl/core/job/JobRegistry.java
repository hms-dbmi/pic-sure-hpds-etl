package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Discovers every {@link Job} bean on the classpath and indexes it by {@link Job#name()}.
 * This is what makes new jobs "plug and play": annotate a job with {@code @Component}
 * (or extend the annotated {@link AbstractJob} subclass) and it is automatically
 * runnable via {@code --job=<name>} -- no central switch statement to edit.
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

    /** @throws ConfigException if no job with that name is registered. */
    public Job require(String name) {
        Job job = jobsByName.get(name);
        if (job == null) {
            throw new ConfigException("Unknown job '" + name + "'. Available jobs: " + names());
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
