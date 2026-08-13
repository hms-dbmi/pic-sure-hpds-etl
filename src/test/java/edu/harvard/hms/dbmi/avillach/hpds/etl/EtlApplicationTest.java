package edu.harvard.hms.dbmi.avillach.hpds.etl;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast context sanity check (no Docker required): the Spring context wires up and every
 * job that {@code application.yml} enables auto-registers by name. This catches wiring/bean
 * regressions without spinning up containers. The datasource connects lazily, so no live DB
 * is needed here.
 *
 * <p>This runs against the real {@code application.yml}, so it also pins the shipped defaults
 * of {@code etl.jobs.*.enabled}. {@link JobEnablementTest} covers toggling them.
 */
@SpringBootTest
class EtlApplicationTest {

    @Autowired
    private JobRegistry registry;

    @Test
    void context_loads_and_enabled_jobs_are_registered() {
        assertThat(registry.contains("participants-migration")).isTrue();
        assertThat(registry.contains("sstr-populate-rds-participants")).isTrue();
        assertThat(registry.contains("single-consent-data-populate-rds-participants")).isTrue();
    }

    @Test
    void template_job_is_disabled_by_default() {
        // It is a copy-me demonstration; shipping it runnable would make it invocable in prod.
        assertThat(registry.contains("template")).isFalse();
    }
}
