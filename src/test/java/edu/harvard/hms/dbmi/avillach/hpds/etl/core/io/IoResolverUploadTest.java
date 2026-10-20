package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IoResolver}'s upload bookkeeping against a mocked {@link S3Client}: a streamed upload
 * whose content does not match its declared length fails as a {@link DataException} on both
 * paths, the multipart part size grows to stay inside S3's 10,000-part limit, and delete/size
 * classify S3 rejections the way writes do. {@link IoResolverS3IT} covers the real SDK.
 */
class IoResolverUploadTest {

    private static final String URI = "s3://bucket/key.bin";

    private S3Client s3;
    private IoResolver io;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        io = new IoResolver(s3);
        // Like the SDK: read exactly the declared length from the body and stop, wrapping any
        // IOException in an SdkClientException.
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(inv -> {
            RequestBody body = inv.getArgument(1);
            try (InputStream in = body.contentStreamProvider().newStream()) {
                in.readNBytes(Math.toIntExact(body.optionalContentLength().orElseThrow()));
            } catch (IOException e) {
                throw SdkClientException.builder().message("Unable to execute HTTP request").cause(e).build();
            }
            return PutObjectResponse.builder().versionId("v1").build();
        });
        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("u1").build());
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("e").build());
        when(s3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().versionId("v2").build());
    }

    private static IoResolver.ContentSource bytes(int n) {
        return () -> new ByteArrayInputStream(new byte[n]);
    }

    @Test
    void single_put_of_the_declared_length_succeeds() {
        assertThat(io.writeOutputStream(URI, 100, Map.of(), bytes(100))).isEqualTo("v1");
    }

    /** The SDK alone would send the first 100 bytes and report success. */
    @Test
    void single_put_of_a_longer_stream_is_a_data_error() {
        assertThatThrownBy(() -> io.writeOutputStream(URI, 100, Map.of(), bytes(101)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("declared length");
    }

    @Test
    void single_put_of_a_shorter_stream_is_a_data_error() {
        assertThatThrownBy(() -> io.writeOutputStream(URI, 100, Map.of(), bytes(99)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("ended after 99");
    }

    @Test
    void single_put_of_an_empty_declared_stream_checks_for_extra_bytes() {
        assertThat(io.writeOutputStream(URI, 0, Map.of(), bytes(0))).isEqualTo("v1");
    }

    @Test
    void multipart_of_a_longer_stream_is_a_data_error_and_aborts() {
        io.setMultipartThresholdBytes(10);
        io.setMultipartPartSizeBytes(8);

        assertThatThrownBy(() -> io.writeOutputStream(URI, 20, Map.of(), bytes(21)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("holds more");
        verify(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    }

    @Test
    void multipart_of_a_shorter_stream_is_a_data_error_and_aborts() {
        io.setMultipartThresholdBytes(10);
        io.setMultipartPartSizeBytes(8);

        assertThatThrownBy(() -> io.writeOutputStream(URI, 20, Map.of(), bytes(19)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("ended after 19");
        verify(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    }

    /** A failing source is still an infrastructure problem, not a length mismatch. */
    @Test
    void multipart_stream_failure_stays_retryable() {
        io.setMultipartThresholdBytes(10);
        io.setMultipartPartSizeBytes(8);

        assertThatThrownBy(() -> io.writeOutputStream(URI, 20, Map.of(), () -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("source went away");
            }
        })).isInstanceOf(InfrastructureException.class);
    }

    @Test
    void part_size_grows_to_stay_within_ten_thousand_parts() {
        long mib = 1024 * 1024;
        // Small uploads keep the configured size.
        assertThat(IoResolver.partSizeFor(34L * 1024 * mib, 64 * mib)).isEqualTo(64 * mib);
        // 625 GiB at 64 MiB is exactly 10,000 parts: still the configured size.
        assertThat(IoResolver.partSizeFor(625L * 1024 * mib, 64 * mib)).isEqualTo(64 * mib);
        // One byte more needs a bigger part, rounded up to a whole MiB.
        long bigger = IoResolver.partSizeFor(625L * 1024 * mib + 1, 64 * mib);
        assertThat(bigger).isEqualTo(65 * mib);
        assertThat((625L * 1024 * mib + 1 + bigger - 1) / bigger).isLessThanOrEqualTo(IoResolver.MAX_PARTS);
        // The 5 TiB object cap still fits an int-sized buffer.
        assertThat(IoResolver.partSizeFor(IoResolver.MAX_OBJECT_BYTES, 64 * mib)).isLessThan(Integer.MAX_VALUE);
    }

    /** At a 1 KiB configured part size this upload would need 25,600 parts. */
    @Test
    void multipart_with_a_tiny_configured_part_size_stays_within_the_part_limit() {
        io.setMultipartThresholdBytes(1);
        io.setMultipartPartSizeBytes(1024);
        AtomicInteger parts = new AtomicInteger();
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenAnswer(inv -> {
            parts.incrementAndGet();
            return UploadPartResponse.builder().eTag("e").build();
        });

        int length = 25 * 1024 * 1024;
        io.writeOutputStream(URI, length, Map.of(), bytes(length));

        assertThat(parts.get()).isEqualTo(25).isLessThanOrEqualTo(IoResolver.MAX_PARTS);
    }

    @Test
    void objects_over_the_s3_size_cap_are_rejected_before_uploading() {
        io.setMultipartThresholdBytes(1);
        assertThatThrownBy(() -> io.writeOutputStream(URI, IoResolver.MAX_OBJECT_BYTES + 1, Map.of(), bytes(0)))
                .isInstanceOf(DataException.class);
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    private static S3Exception s3Error(int status) {
        return (S3Exception) S3Exception.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorMessage("status " + status).build())
                .build();
    }

    @Test
    void delete_classifies_s3_rejections_like_writes() {
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(s3Error(403));
        assertThatThrownBy(() -> io.delete(URI)).isInstanceOf(DataException.class);

        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(s3Error(503));
        assertThatThrownBy(() -> io.delete(URI)).isInstanceOf(InfrastructureException.class);

        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(s3Error(429));
        assertThatThrownBy(() -> io.delete(URI)).isInstanceOf(InfrastructureException.class);
    }

    @Test
    void size_classifies_s3_rejections_like_writes() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(s3Error(403));
        assertThatThrownBy(() -> io.size(URI)).isInstanceOf(DataException.class);

        // A HEAD error has no body: the message must still say something useful.
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow((S3Exception) S3Exception.builder()
                .statusCode(403).message("Access Denied (Service: S3, Status Code: 403)")
                .awsErrorDetails(AwsErrorDetails.builder().build()).build());
        assertThatThrownBy(() -> io.size(URI)).isInstanceOf(DataException.class)
                .hasMessageContaining(": Access Denied");

        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(s3Error(500));
        assertThatThrownBy(() -> io.size(URI)).isInstanceOf(InfrastructureException.class);

        // A HEAD 404 can arrive without the NoSuchKey code: still "does not exist".
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(s3Error(404));
        assertThat(io.size(URI)).isEqualTo(-1);
    }

    @Test
    void length_checked_stream_passes_exact_content_through() throws IOException {
        byte[] data = {1, 2, 3, 4, 5};
        try (InputStream in = new IoResolver.LengthCheckedInputStream(new ByteArrayInputStream(data), 5, null)) {
            assertThat(in.readAllBytes()).isEqualTo(data);
            assertThat(in.read()).isEqualTo(-1);
        }
    }
}
