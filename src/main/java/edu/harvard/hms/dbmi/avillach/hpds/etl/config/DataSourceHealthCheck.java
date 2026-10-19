package edu.harvard.hms.dbmi.avillach.hpds.etl.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class DataSourceHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(DataSourceHealthCheck.class);

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @EventListener(ApplicationReadyEvent.class)
    void checkDatasource() {
        if (datasourceUrl.contains("//unset")) {
            log.warn("Neither DB_URL nor RDS_URL is set — database jobs will fail with INFRASTRUCTURE_ERROR. "
                    + "Set DB_URL, DB_USERNAME, and DB_PASSWORD (or the transitional RDS_* equivalents) "
                    + "to connect to Postgres.");
        }
    }
}
