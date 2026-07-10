package edu.harvard.hms.dbmi.avillach.hpds.etl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Single entry point for the ETL job JAR. The process runs one job (or one pipeline),
 * then exits with a code that reflects the outcome so Jenkins can gate on it.
 *
 * <p>{@code SpringApplication.exit(...)} drives the process exit code from
 * {@code JobLauncher} (an {@code ExitCodeGenerator}); wrapping it in {@code System.exit}
 * makes that the actual JVM exit status.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EtlApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(EtlApplication.class, args)));
    }
}
