package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link IoResolver}'s S3 path against LocalStack, proving jobs can
 * read/write {@code s3://} URIs unchanged from how they handle local files.
 */
class IoResolverS3IT extends AbstractIntegrationTest {

    private static final String BUCKET = "hpds-etl-test";

    @Autowired
    private IoResolver io;
    @Autowired
    private S3Client s3;

    @BeforeEach
    void ensureBucket() {
        boolean exists = s3.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(BUCKET));
        if (!exists) {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void writes_then_reads_an_s3_object() throws Exception {
        String uri = "s3://" + BUCKET + "/round-trip.txt";
        io.writeOutput(uri, "hello hpds".getBytes(StandardCharsets.UTF_8));

        try (InputStream in = io.openInput(uri)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello hpds");
        }
    }

    @Test
    void missing_object_is_a_config_error() {
        assertThatThrownBy(() -> io.openInput("s3://" + BUCKET + "/does-not-exist.txt"))
                .isInstanceOf(ConfigException.class);
    }
}
