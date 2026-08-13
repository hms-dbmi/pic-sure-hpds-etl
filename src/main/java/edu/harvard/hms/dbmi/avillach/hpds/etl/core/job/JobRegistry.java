package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Indexes every {@link Job} bean Spring created, by {@link Job#name()}. This is what makes
 * new jobs "plug and play": annotate a job with {@code @Component} (or extend the annotated
 * {@link AbstractJob} subclass) and it is automatically runnable via {@code --job=<name>} --
 * no central switch statement to edit.
 *
 * <p>Jobs are also opt-in. Each job carries
 * {@code @ConditionalOnProperty("etl.jobs.<job-name>.enabled", havingValue = "true")}, so a
 * disabled job is never instantiated and therefore never reaches this registry -- the
 * enable/disable decision is made once, in {@code application.yml}, and needs no code here.
 * "Registered" consequently means "enabled in this environment", which is why
 * {@link #require(String)} cannot tell a wrong name from a disabled job and says so.
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
            // Naming the flag matters: a disabled job is indistinguishable from a typo here,
            // and "unknown job" alone sends people hunting for a misspelling that isn't there.
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
