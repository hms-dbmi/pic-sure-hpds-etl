package edu.harvard.hms.dbmi.avillach.hpds.etl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Builds the S3 client. Credentials come from the default AWS provider chain
 * (IAM role on the Jenkins agent, env vars, or a profile) -- never hard-coded.
 *
 * <p>When {@code etl.aws.s3.endpoint-override} is set (tests / LocalStack), the client
 * targets that endpoint with path-style access. Building the client does not make any
 * network call, so this bean is safe to create even for jobs that never touch S3.
 */
@Configuration
public class AwsConfig {

    @Bean
    public S3Client s3Client(EtlProperties props) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(props.getAws().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());

        String endpoint = props.getAws().getS3().getEndpointOverride();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }
}
