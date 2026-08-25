package edu.harvard.hms.dbmi.avillach.hpds.etl.config;

import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one indirection {@link ManagedInputsService} relies on: {@code --managed-inputs=<uri>}
 * on the command line reaches {@code etl.managed-inputs.uri}. Spring publishes every
 * {@code --key=value} argument as a property and application.yml binds this one through, which is
 * how the flag every migration run already passes keeps working now that the job no longer reads
 * it as a job parameter.
 */
@SpringBootTest(args = "--managed-inputs=s3://hpds-migration/managed_inputs.csv")
@TestPropertySource(properties = {
        // The datasource has no defaults in application.yml; nothing here connects.
        "spring.datasource.url=jdbc:postgresql://localhost:5432/hpds",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
})
class ManagedInputsConfigurationTest {

    @Autowired
    private EtlProperties properties;

    @Test
    void the_managed_inputs_command_line_argument_configures_the_source() {
        assertThat(properties.getManagedInputs().getUri()).isEqualTo("s3://hpds-migration/managed_inputs.csv");
    }
}
