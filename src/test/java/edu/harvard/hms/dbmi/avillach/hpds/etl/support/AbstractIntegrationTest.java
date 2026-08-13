package edu.harvard.hms.dbmi.avillach.hpds.etl.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests that need real infrastructure. Spins up (once per JVM,
 * reused across subclasses) a Postgres container initialized with the reference schema
 * and a LocalStack S3 container, then points the Spring context at both.
 *
 * <p>Extend this and {@code @Autowired} the beans under test. Requires Docker to be
 * running -- these tests exercise the real JDBC/S3 paths, not mocks.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @Container
    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.4"))
                    .withServices("s3");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Database: point Spring at the container and run the reference schema on startup.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/schema.sql");

        // S3: target LocalStack. Credentials come from the default provider chain, so
        // expose LocalStack's test credentials via the standard system properties.
        registry.add("etl.aws.region", LOCALSTACK::getRegion);
        registry.add("etl.aws.s3.endpoint-override", () -> LOCALSTACK.getEndpoint().toString());
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
        System.setProperty("aws.region", LOCALSTACK.getRegion());
    }
}
