package edu.harvard.hms.dbmi.avillach.hpds.etl;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast context sanity check (no Docker required): the Spring context wires up and every
 * job auto-registers by name. This catches wiring/bean regressions without spinning up
 * containers. The datasource connects lazily, so no live DB is needed here.
 */
@SpringBootTest
class EtlApplicationTest {

    @Autowired
    private JobRegistry registry;

    @Test
    void context_loads_and_jobs_are_registered() {
        assertThat(registry.contains("template")).isTrue();
        assertThat(registry.contains("participants-migration")).isTrue();
    }
}
