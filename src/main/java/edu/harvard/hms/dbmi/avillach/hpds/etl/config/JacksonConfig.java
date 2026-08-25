package edu.harvard.hms.dbmi.avillach.hpds.etl.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the shared {@link ObjectMapper}. With no {@code spring-web} on the classpath, Boot's
 * Jackson auto-configuration does not apply, so the mapper used for report serialization and JSON
 * job inputs is configured here.
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
