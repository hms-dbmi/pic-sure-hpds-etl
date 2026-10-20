package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link IoResolver#requireVersioning}: only a bucket with versioning {@code Enabled} passes. */
class IoResolverVersioningTest {

    private final S3Client s3 = mock(S3Client.class);
    private final IoResolver io = new IoResolver(s3);

    private void bucketStatus(BucketVersioningStatus status) {
        when(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(GetBucketVersioningResponse.builder().status(status).build());
    }

    @Test
    void enabled_bucket_passes() {
        bucketStatus(BucketVersioningStatus.ENABLED);

        assertThatCode(() -> io.requireVersioning("s3://out-bucket/root/")).doesNotThrowAnyException();
        verify(s3).getBucketVersioning(GetBucketVersioningRequest.builder().bucket("out-bucket").build());
    }

    @Test
    void suspended_bucket_is_a_config_error() {
        bucketStatus(BucketVersioningStatus.SUSPENDED);

        assertThatThrownBy(() -> io.requireVersioning("s3://out-bucket/root/"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("out-bucket must have versioning Enabled (is Suspended)");
    }

    @Test
    void never_versioned_bucket_is_a_config_error() {
        bucketStatus(null);

        assertThatThrownBy(() -> io.requireVersioning("s3://out-bucket"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("(is never enabled)");
    }

    @Test
    void unreadable_status_is_an_infrastructure_error() {
        when(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenThrow(new RuntimeException("access denied"));

        assertThatThrownBy(() -> io.requireVersioning("s3://out-bucket/root/"))
                .isInstanceOf(InfrastructureException.class);
    }

    @Test
    void local_location_is_rejected() {
        assertThatThrownBy(() -> io.requireVersioning("/tmp/out/"))
                .isInstanceOf(ConfigException.class);
    }
}
