package edu.harvard.hms.dbmi.avillach.hpds.etl.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.net.URI;
import java.nio.file.Path;

@Configuration
public class AwsConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AwsConfig.class);

    @Bean
    public S3Client s3Client(EtlProperties props) {
        Region region = Region.of(props.getAws().getRegion());
        S3ClientBuilder builder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider(region));

        String endpoint = props.getAws().getS3().getEndpointOverride();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    private AwsCredentialsProvider credentialsProvider(Region region) {
        String profile = System.getenv("AWS_PROFILE");
        String configFile = System.getenv("AWS_CONFIG_FILE");

        if (profile == null || profile.isBlank()) {
            LOG.info("AWS_PROFILE not set, using default credential chain");
            return DefaultCredentialsProvider.create();
        }

        LOG.info("AWS_PROFILE={}, AWS_CONFIG_FILE={} — building explicit profile credentials", profile, configFile);

        ProfileFile profileFile;
        if (configFile != null && !configFile.isBlank()) {
            profileFile = ProfileFile.builder()
                    .content(Path.of(configFile))
                    .type(ProfileFile.Type.CONFIGURATION)
                    .build();
        } else {
            profileFile = ProfileFile.defaultProfileFile();
        }

        var profileObj = profileFile.profile(profile)
                .orElseThrow(() -> new IllegalStateException(
                        "AWS_PROFILE=" + profile + " but profile not found in " + configFile));

        String roleArn = profileObj.property("role_arn")
                .orElseThrow(() -> new IllegalStateException(
                        "Profile " + profile + " has no role_arn"));

        String sessionName = profileObj.property("role_session_name").orElse("etl-session");

        LOG.info("Assuming role {} with session {}", roleArn, sessionName);

        StsClient stsClient = StsClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();

        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(() -> AssumeRoleRequest.builder()
                        .roleArn(roleArn)
                        .roleSessionName(sessionName)
                        .build())
                .build();
    }
}
