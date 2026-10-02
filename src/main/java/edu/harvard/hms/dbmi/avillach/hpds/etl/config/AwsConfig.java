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
 * The default {@link S3Client} every job uses for its S3 I/O. It runs as the environment's
 * default credential chain -- on a runner, the EC2 instance role (bdc-etl-jenkins-role, via
 * IMDS) -- because the data bucket lives in the same account as the runners. Jobs that read a
 * bucket the instance role cannot (the NHLBI exchange) use {@link AssumedRoleS3Clients}.
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
