package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Input pinning (head / pinned open / verifyUnchanged); S3 is mocked, so no Docker is needed. */
class IoResolverPinTest {

    @TempDir
    Path tempDir;

    private static HeadObjectResponse head(String versionId, String eTag) {
        return HeadObjectResponse.builder().versionId(versionId).eTag(eTag).contentLength(42L)
                .lastModified(Instant.ofEpochMilli(1_700_000_000_000L)).build();
    }

    @Test
    void s3_head_returns_version_etag_size_and_mtime() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head("v1", "\"abc\""));

        IoResolver.ObjectInfo info = new IoResolver(s3).head("s3://bucket-a/path/in.csv");

        assertThat(info).isEqualTo(new IoResolver.ObjectInfo("v1", "\"abc\"", 42, 1_700_000_000_000L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_pinned_s3_read_always_uses_if_match_even_on_a_versioned_object() {
        S3Client s3 = mock(S3Client.class);
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(mock(ResponseInputStream.class));

        new IoResolver(s3).openInput("s3://bucket-a/in.csv", new IoResolver.ObjectInfo("v1", "\"abc\"", 42, 1));

        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(req.capture());
        assertThat(req.getValue().versionId()).isNull(); // GetObjectVersion permission is not required
        assertThat(req.getValue().ifMatch()).isEqualTo("\"abc\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_pinned_s3_read_uses_if_match_on_an_unversioned_bucket() {
        S3Client s3 = mock(S3Client.class);
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(mock(ResponseInputStream.class));

        new IoResolver(s3).openInput("s3://bucket-a/in.csv", new IoResolver.ObjectInfo(null, "\"abc\"", 42, 1));

        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(req.capture());
        assertThat(req.getValue().versionId()).isNull();
        assertThat(req.getValue().ifMatch()).isEqualTo("\"abc\"");
    }

    @Test
    void a_precondition_failure_on_the_pinned_read_says_the_input_changed() {
        S3Client s3 = mock(S3Client.class);
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(412).message("Precondition Failed").build());

        assertThatThrownBy(() -> new IoResolver(s3).openInput("s3://bucket-a/in.csv",
                new IoResolver.ObjectInfo(null, "\"abc\"", 42, 1)))
                .isInstanceOf(InfrastructureException.class)
                .hasMessageContaining("Input changed");
    }

    @Test
    void verify_unchanged_compares_the_current_s3_identity() {
        S3Client s3 = mock(S3Client.class);
        IoResolver io = new IoResolver(s3);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head("v1", "\"abc\""));
        IoResolver.ObjectInfo pin = io.head("s3://bucket-a/in.csv");

        io.verifyUnchanged("s3://bucket-a/in.csv", pin); // same object: no throw

        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head("v2", "\"def\""));
        assertThatThrownBy(() -> io.verifyUnchanged("s3://bucket-a/in.csv", pin))
                .isInstanceOf(InfrastructureException.class).hasMessageContaining("Input changed");
    }

    @Test
    void a_local_file_is_identified_by_size_and_mtime_and_a_change_is_detected() throws Exception {
        Path f = tempDir.resolve("in.csv");
        Files.writeString(f, "abc");
        IoResolver io = new IoResolver(null);

        IoResolver.ObjectInfo pin = io.head(f.toString());

        assertThat(pin.versionId()).isNull();
        assertThat(pin.eTag()).isNull();
        assertThat(pin.size()).isEqualTo(3);
        io.verifyUnchanged(f.toString(), pin);
        try (var in = io.openInput(f.toString(), pin)) {
            assertThat(in.readAllBytes()).hasSize(3);
        }

        Files.writeString(f, "abcdef");
        assertThatThrownBy(() -> io.openInput(f.toString(), pin))
                .isInstanceOf(InfrastructureException.class).hasMessageContaining("Input changed");
    }
}
