package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3VersionedObjectSourceTest {

    private final S3Client s3 = mock(S3Client.class);
    private final S3VersionedObjectSource source = new S3VersionedObjectSource(s3);

    @Test
    void the_current_version_is_read_with_a_head_request() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().versionId("abc123").build());
        assertThat(source.currentVersionId("s3://bucket/data/m.csv")).contains("abc123");
        ArgumentCaptor<HeadObjectRequest> req = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3).headObject(req.capture());
        assertThat(req.getValue().bucket()).isEqualTo("bucket");
        assertThat(req.getValue().key()).isEqualTo("data/m.csv");
    }

    @Test
    void an_absent_object_has_no_version_and_an_unversioned_one_is_a_config_error() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThat(source.currentVersionId("s3://bucket/k")).isEmpty();
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        assertThatThrownBy(() -> source.currentVersionId("s3://bucket/k")).isInstanceOf(ConfigException.class)
                .hasMessageContaining("not versioned");
    }

    @Test
    void other_head_failures_are_infrastructure_errors() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(403).message("denied").build());
        assertThatThrownBy(() -> source.currentVersionId("s3://bucket/k")).isInstanceOf(InfrastructureException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_read_asks_for_exactly_the_version() throws IOException {
        ResponseInputStream<GetObjectResponse> body = new ResponseInputStream<>(GetObjectResponse.builder().build(),
                new ByteArrayInputStream("x".getBytes()));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(body);
        try (InputStream in = source.open("s3://bucket/data/f.csv", "v-42")) {
            assertThat(in.read()).isEqualTo('x');
        }
        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(req.capture());
        assertThat(req.getValue().versionId()).isEqualTo("v-42");
        assertThat(req.getValue().key()).isEqualTo("data/f.csv");
    }

    @Test
    void reading_without_a_version_id_or_a_missing_version_is_a_config_error() {
        assertThatThrownBy(() -> source.open("s3://bucket/k", "")).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> source.open("s3://bucket/k", "null")).isInstanceOf(ConfigException.class);
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).message("NoSuchVersion").build());
        assertThatThrownBy(() -> source.open("s3://bucket/k", "gone")).isInstanceOf(ConfigException.class);
    }

    @Test
    void a_local_path_is_read_as_is_with_a_size_and_time_fingerprint(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("m.csv");
        Files.writeString(f, "abc");
        assertThat(source.currentVersionId(f.toString())).hasValueSatisfying(v -> assertThat(v).startsWith("local-3-"));
        try (InputStream in = source.open(f.toString(), "ignored")) {
            assertThat(new String(in.readAllBytes())).isEqualTo("abc");
        }
        assertThat(source.currentVersionId(dir.resolve("none.csv").toString())).isEmpty();
        assertThatThrownBy(() -> source.open(dir.resolve("none.csv").toString(), "")).isInstanceOf(ConfigException.class);
    }
}
