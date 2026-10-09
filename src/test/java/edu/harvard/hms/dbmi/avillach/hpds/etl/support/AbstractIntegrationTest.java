package edu.harvard.hms.dbmi.avillach.hpds.etl.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests that need real infrastructure: a Postgres container initialized
 * with the reference schema and a LocalStack S3 container, with the Spring context pointed at
 * both.
 *
 * <p>Extend this and {@code @Autowired} the beans under test. Requires Docker to be
 * running -- these tests exercise the real JDBC/S3 paths, not mocks.
 *
 * <p>The containers are started in a static initializer rather than with {@code @Testcontainers}
 * and {@code @Container}. That extension stops static containers when each test class finishes and
 * starts replacements on new random ports, while Spring caches one context across every subclass —
 * so the datasource URL stays pinned to the first container's port and later classes fail with
 * "Connection refused". A static initializer gives one set of containers for the whole JVM,
 * matching the one cached context; Testcontainers' ryuk sidecar removes them at JVM exit.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.4"))
                    .withServices("s3");

    static {
        POSTGRES.start();
        try (var conn = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS etl");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to create etl schema in test container", e);
        }
        LOCALSTACK.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Database: point Spring at the container and run the reference schema on startup.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:repository/schema.sql");

        // S3: target LocalStack. Credentials come from the default provider chain, so
        // expose LocalStack's test credentials via the standard system properties.
        registry.add("etl.aws.region", LOCALSTACK::getRegion);
        registry.add("etl.aws.s3.endpoint-override", () -> LOCALSTACK.getEndpoint().toString());
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
        System.setProperty("aws.region", LOCALSTACK.getRegion());
    }
}
