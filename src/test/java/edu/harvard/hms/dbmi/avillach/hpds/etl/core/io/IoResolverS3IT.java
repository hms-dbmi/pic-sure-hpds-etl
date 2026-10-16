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

    @Test
    void large_file_uploads_via_multipart_and_round_trips() throws Exception {
        // Lower the threshold so a ~12 MiB file exercises the multipart path with
        // 5 MiB parts (S3's minimum part size) instead of needing a 4 GiB fixture.
        io.setMultipartThresholdBytes(6L * 1024 * 1024);
        io.setMultipartPartSizeBytes(5 * 1024 * 1024);
        try {
            byte[] chunk = new byte[1024 * 1024];
            java.nio.file.Path big = java.nio.file.Files.createTempFile("multipart-", ".bin");
            try {
                try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(big)) {
                    for (int i = 0; i < 12; i++) {
                        java.util.Arrays.fill(chunk, (byte) ('a' + i));
                        out.write(chunk);
                    }
                }

                String uri = "s3://" + BUCKET + "/multipart/large.bin";
                io.writeOutputFile(uri, big);

                long total = 0;
                int firstByte = -1;
                int lastByte = -1;
                try (InputStream in = io.openInput(uri)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (firstByte < 0) {
                            firstByte = buf[0];
                        }
                        lastByte = buf[n - 1];
                        total += n;
                    }
                }
                assertThat(total).isEqualTo(12L * 1024 * 1024);
                assertThat(firstByte).isEqualTo('a');
                assertThat(lastByte).isEqualTo('a' + 11);
            } finally {
                java.nio.file.Files.deleteIfExists(big);
            }
        } finally {
            io.setMultipartThresholdBytes(4L * 1024 * 1024 * 1024);
            io.setMultipartPartSizeBytes(512 * 1024 * 1024);
        }
    }

    /** Metadata must survive both upload paths: it can only be set when an object is created. */
    @Test
    void writes_user_metadata_on_single_and_multipart_uploads() throws Exception {
        java.nio.file.Path small = java.nio.file.Files.createTempFile("meta-", ".txt");
        java.nio.file.Files.writeString(small, "small");
        io.writeOutputFile("s3://" + BUCKET + "/meta/small.txt", small, java.util.Map.of("content-md5", "abc"));
        assertThat(s3.headObject(b -> b.bucket(BUCKET).key("meta/small.txt")).metadata())
                .containsEntry("content-md5", "abc");

        io.setMultipartThresholdBytes(6L * 1024 * 1024);
        io.setMultipartPartSizeBytes(5 * 1024 * 1024);
        java.nio.file.Path big = java.nio.file.Files.createTempFile("meta-big-", ".bin");
        try {
            java.nio.file.Files.write(big, new byte[7 * 1024 * 1024]);
            io.writeOutputFile("s3://" + BUCKET + "/meta/big.bin", big, java.util.Map.of("content-md5", "def"));
            assertThat(s3.headObject(b -> b.bucket(BUCKET).key("meta/big.bin")).metadata())
                    .containsEntry("content-md5", "def");
        } finally {
            io.setMultipartThresholdBytes(4L * 1024 * 1024 * 1024);
            io.setMultipartPartSizeBytes(512 * 1024 * 1024);
            java.nio.file.Files.deleteIfExists(big);
            java.nio.file.Files.deleteIfExists(small);
        }
    }

    /** Streamed uploads never touch local disk; both paths carry metadata and the exact bytes. */
    @Test
    void streams_without_spooling_on_single_and_multipart_uploads() throws Exception {
        byte[] small = "streamed\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String smallUri = "s3://" + BUCKET + "/stream/small.txt";
        io.writeOutputStream(smallUri, small.length, java.util.Map.of("content-md5", "s"),
                () -> new java.io.ByteArrayInputStream(small));
        try (InputStream in = io.openInput(smallUri)) {
            assertThat(in.readAllBytes()).isEqualTo(small);
        }
        assertThat(s3.headObject(b -> b.bucket(BUCKET).key("stream/small.txt")).metadata())
                .containsEntry("content-md5", "s");

        io.setMultipartThresholdBytes(6L * 1024 * 1024);
        io.setMultipartPartSizeBytes(5 * 1024 * 1024);
        try {
            byte[] big = new byte[11 * 1024 * 1024 + 7];
            for (int i = 0; i < big.length; i++) {
                big[i] = (byte) (i % 251);
            }
            String bigUri = "s3://" + BUCKET + "/stream/big.bin";
            io.writeOutputStream(bigUri, big.length, java.util.Map.of("content-md5", "b"),
                    () -> new java.io.ByteArrayInputStream(big));
            try (InputStream in = io.openInput(bigUri)) {
                assertThat(in.readAllBytes()).isEqualTo(big);
            }
            assertThat(s3.headObject(b -> b.bucket(BUCKET).key("stream/big.bin")).metadata())
                    .containsEntry("content-md5", "b");

            // A stream that fails part-way aborts the upload rather than leaving a partial object.
            String failedUri = "s3://" + BUCKET + "/stream/failed.bin";
            assertThatThrownBy(() -> io.writeOutputStream(failedUri, big.length, java.util.Map.of(),
                    () -> new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(big, 0, 6 * 1024 * 1024),
                            new InputStream() {
                                @Override
                                public int read() throws java.io.IOException {
                                    throw new java.io.IOException("source went away");
                                }
                            })))
                    .isInstanceOf(edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException.class);
            assertThat(io.exists(failedUri)).isFalse();
        } finally {
            io.setMultipartThresholdBytes(4L * 1024 * 1024 * 1024);
            io.setMultipartPartSizeBytes(512 * 1024 * 1024);
        }
    }
}
