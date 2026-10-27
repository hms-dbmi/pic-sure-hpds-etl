package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobRegistry;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The job is wired with the shipped configuration: its flag in application.yml is on, and it needs no database. */
@SpringBootTest
@TestPropertySource(properties = {
        // The datasource has no defaults in application.yml; nothing here connects.
        "spring.datasource.url=jdbc:postgresql://localhost:5432/hpds",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
})
class AdaptJobEnablementTest {

    @Autowired
    private JobRegistry registry;

    @Test
    void the_adapter_is_registered_by_the_default_configuration_as_a_temporary_job() {
        assertThat(registry.contains("adapt-allconcepts-csvloader")).isTrue();
        assertThat(registry.require("adapt-allconcepts-csvloader").type()).isEqualTo(JobType.MIGRATION);
    }
}
