package edu.harvard.hms.dbmi.avillach.hpds.etl;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@code etl.jobs.<name>.enabled} actually controls what can run: a job whose
 * flag is false is never instantiated, so {@link JobRegistry} never sees it and
 * {@code --job=<name>} cannot reach it.
 *
 * <p>The property overrides here invert the shipped defaults ({@code template} on, the two
 * loaders off), which also proves the flags are read from configuration rather than baked in.
 *
 * <p>Note which flags move together: {@code participants-migration} injects
 * {@link edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.participants.SstrPopulateRdsParticipantsJob},
 * so its condition requires the sstr flag too. Disabling sstr must therefore also drop the
 * migration job -- if it did not, this context would fail to start on a missing bean instead,
 * which is exactly the failure the compound condition exists to prevent. That this test's
 * context starts at all is the assertion.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "etl.jobs.template.enabled=true",
        "etl.jobs.sstr-populate-rds-participants.enabled=false",
        "etl.jobs.single-consent-data-populate-rds-participants.enabled=false",
        "etl.jobs.participants-migration.enabled=true",
})
class JobEnablementTest {

    @Autowired
    private JobRegistry registry;

    @Test
    void an_explicitly_enabled_job_is_registered() {
        assertThat(registry.contains("template")).isTrue();
    }

    @Test
    void a_disabled_job_is_not_registered() {
        assertThat(registry.contains("sstr-populate-rds-participants")).isFalse();
        assertThat(registry.contains("single-consent-data-populate-rds-participants")).isFalse();
    }

    @Test
    void migration_job_is_dropped_when_the_sstr_job_it_depends_on_is_disabled() {
        // Its own flag is true above; the sstr flag is not. Dropping the bean is the designed
        // outcome -- the alternative is an unstartable context.
        assertThat(registry.contains("participants-migration")).isFalse();
    }

    @Test
    void requiring_a_disabled_job_fails_as_a_config_error_that_names_the_flag() {
        assertThatThrownBy(() -> registry.require("sstr-populate-rds-participants"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("etl.jobs.sstr-populate-rds-participants.enabled=true")
                // The message must not read as a typo, or people go looking for a misspelling.
                .hasMessageContaining("the job is disabled");
    }

    @Test
    void a_disabled_job_maps_to_the_config_error_exit_code() {
        // Exit 5, not 1: Jenkins must be able to tell "misconfigured" from "crashed", and a
        // retry cannot fix a disabled job.
        assertThatThrownBy(() -> registry.require("participants-migration"))
                .isInstanceOf(ConfigException.class)
                .extracting(e -> ((ConfigException) e).exitCode())
                .isEqualTo(ExitCode.CONFIG_ERROR);
    }

    @Test
    void only_enabled_jobs_are_listed() {
        assertThat(registry.names()).anyMatch(n -> n.startsWith("template "));
        assertThat(registry.names()).noneMatch(n -> n.startsWith("sstr-populate-rds-participants "));
    }
}
