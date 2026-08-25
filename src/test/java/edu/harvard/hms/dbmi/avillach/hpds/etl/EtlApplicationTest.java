package edu.harvard.hms.dbmi.avillach.hpds.etl;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast context sanity check (no Docker required): the Spring context wires up and every
 * job that {@code application.yml} enables auto-registers by name. This catches wiring/bean
 * regressions without spinning up containers. The datasource connects lazily, so no live DB
 * is needed here.
 *
 * <p>Runs against the real {@code application.yml}, so it also pins the shipped defaults of
 * {@code etl.jobs.*.enabled}. {@link JobEnablementTest} covers toggling them.
 */
@SpringBootTest
// application.yml takes the datasource from RDS_URL/RDS_USERNAME/RDS_PASSWORD with no defaults, so
// a context that is not given them cannot build a DataSource. Nothing here connects -- Hikari is
// lazy -- so a syntactically valid URL is enough.
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/hpds",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
})
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
        // A copy-me demonstration; shipping it enabled would make it invocable in production.
        assertThat(registry.contains("template")).isFalse();
    }
}
