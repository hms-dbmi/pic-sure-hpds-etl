package edu.harvard.hms.dbmi.avillach.hpds.etl.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the shared {@link ObjectMapper}. This is a non-web app (no {@code spring-web}
 * on the classpath), so Boot's Jackson auto-configuration cannot build one for us --
 * we configure it here for report serialization and JSON job inputs.
 *
 * <p>Java time types serialize as ISO-8601 strings, not numeric timestamps, so report
 * timestamps are human-readable.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
