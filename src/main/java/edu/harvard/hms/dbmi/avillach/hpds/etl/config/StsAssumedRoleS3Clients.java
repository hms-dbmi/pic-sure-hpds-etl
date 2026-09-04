package edu.harvard.hms.dbmi.avillach.hpds.etl.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.net.URI;

/**
 * {@link AssumedRoleS3Clients} backed by STS: the environment's default credentials
 * assume the given role, and the returned client auto-refreshes the session for the
 * life of the job. Honors the same region and endpoint-override configuration as the
 * default {@link AwsConfig} client so LocalStack-based tests can exercise it.
 */
@Component
public class StsAssumedRoleS3Clients implements AssumedRoleS3Clients {

    private static final Logger log = LoggerFactory.getLogger(StsAssumedRoleS3Clients.class);

    private final EtlProperties props;

    public StsAssumedRoleS3Clients(EtlProperties props) {
        this.props = props;
    }

    @Override
    public S3Client create(String roleArn) {
        Region region = Region.of(props.getAws().getRegion());
        log.info("Building S3 client under assumed role {}", roleArn);

        StsClient stsClient = StsClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();

        S3ClientBuilder builder = S3Client.builder()
                .region(region)
                .credentialsProvider(StsAssumeRoleCredentialsProvider.builder()
                        .stsClient(stsClient)
                        .refreshRequest(() -> AssumeRoleRequest.builder()
                                .roleArn(roleArn)
                                .roleSessionName("etl-assumed-role")
                                .build())
                        .build());

        String endpoint = props.getAws().getS3().getEndpointOverride();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }
}
