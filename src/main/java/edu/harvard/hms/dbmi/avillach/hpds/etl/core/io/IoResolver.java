package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.EtlException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * A single door for job I/O that hides whether a location is on S3 or the local
 * filesystem. Jobs take a URI parameter and never branch on the scheme themselves:
 * <ul>
 *   <li>{@code s3://bucket/key} &rarr; S3</li>
 *   <li>{@code file:///abs/path} or a plain path &rarr; local filesystem</li>
 * </ul>
 * This keeps jobs identical between local/CI runs (local files, LocalStack) and
 * production Jenkins runs (real S3).
 */
@Component
public class IoResolver {

    private static final Logger log = LoggerFactory.getLogger(IoResolver.class);
    private static final String S3_PREFIX = "s3://";

    private final S3Client s3;

    public IoResolver(S3Client s3) {
        this.s3 = s3;
    }

    public boolean isS3(String uri) {
        return uri != null && uri.startsWith(S3_PREFIX);
    }

    /**
     * Fails unless the bucket of an {@code s3://} location has versioning {@code Enabled}, for
     * jobs that overwrite or delete in place and rely on versioning to keep the previous version
     * recoverable. S3 only; callers skip it for local outputs. Same contract as merge-allconcepts'
     * {@code AllConceptsFolders.requireVersioning} (added later in the merge work), which could
     * delegate here.
     */
    public void requireVersioning(String uri) {
        if (!isS3(uri)) {
            throw new ConfigException("Versioning check needs an s3:// location, got: " + uri);
        }
        String bucket = S3Uri.parsePrefix(uri).bucket();
        BucketVersioningStatus status;
        try {
            status = s3.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).status();
        } catch (RuntimeException e) {
            throw new InfrastructureException("Could not read the versioning status of bucket " + bucket, e);
        }
        if (status != BucketVersioningStatus.ENABLED) {
            throw new ConfigException("Bucket " + bucket + " must have versioning Enabled (is "
                    + (status == null ? "never enabled" : status) + ")");
        }
    }

    /** Opens an input stream for reading. Caller must close it. */
    public InputStream openInput(String uri) {
        if (isS3(uri)) {
            S3Uri s3Uri = S3Uri.parse(uri);
            try {
                ResponseInputStream<?> in = s3.getObject(GetObjectRequest.builder()
                        .bucket(s3Uri.bucket()).key(s3Uri.key()).build());
                log.info("Opened S3 input {}", uri);
                return in;
            } catch (NoSuchKeyException e) {
                throw new ConfigException("S3 object not found: " + uri, e);
            } catch (RuntimeException e) {
                throw new InfrastructureException("Failed to read from S3: " + uri, e);
            }
        }
        Path path = toLocalPath(uri);
        if (!Files.exists(path)) {
            throw new ConfigException("Input file not found: " + path.toAbsolutePath());
        }
        try {
            log.info("Opened local input {}", path.toAbsolutePath());
            return new BufferedInputStream(Files.newInputStream(path));
        } catch (IOException e) {
            throw new InfrastructureException("Failed to open local input: " + path, e);
        }
    }

    /** Checks whether a location exists, without reading its content. */
    public boolean exists(String uri) {
        if (isS3(uri)) {
            S3Uri s3Uri = S3Uri.parse(uri);
            try {
                s3.headObject(HeadObjectRequest.builder().bucket(s3Uri.bucket()).key(s3Uri.key()).build());
                return true;
            } catch (NoSuchKeyException e) {
                return false;
            } catch (RuntimeException e) {
                throw new InfrastructureException("Failed to check existence in S3: " + uri, e);
            }
        }
        return Files.exists(toLocalPath(uri));
    }

    /** A file's size in bytes, or -1 if it does not exist. */
    public long size(String uri) {
        if (isS3(uri)) {
            S3Uri s3Uri = S3Uri.parse(uri);
            try {
                return s3.headObject(HeadObjectRequest.builder().bucket(s3Uri.bucket()).key(s3Uri.key()).build())
                        .contentLength();
            } catch (NoSuchKeyException e) {
                return -1;
            } catch (S3Exception e) {
                // A HEAD has no body to carry an error code, so a missing key may surface as a
                // bare 404 rather than NoSuchKeyException.
                if (e.statusCode() == 404) {
                    return -1;
                }
                throw classifyS3Failure("size lookup", "Failed to read the size of S3 object: " + uri, uri, e);
            } catch (RuntimeException e) {
                throw classifyS3Failure("size lookup", "Failed to read the size of S3 object: " + uri, uri, e);
            }
        }
        Path path = toLocalPath(uri);
        if (!Files.isRegularFile(path)) {
            return -1;
        }
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new InfrastructureException("Failed to read the size of local file: " + path, e);
        }
    }

    /**
     * Deletes a single file. Deleting a location that does not exist is not an error. On a
     * versioned S3 bucket this adds a delete marker, so the previous version stays recoverable.
     */
    public void delete(String uri) {
        if (isS3(uri)) {
            S3Uri s3Uri = S3Uri.parse(uri);
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(s3Uri.bucket()).key(s3Uri.key()).build());
                log.info("Deleted S3 object {}", uri);
            } catch (RuntimeException e) {
                throw classifyS3Failure("delete", "Failed to delete from S3: " + uri, uri, e);
            }
            return;
        }
        Path path = toLocalPath(uri);
        try {
            if (Files.deleteIfExists(path)) {
                log.info("Deleted {}", path.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new InfrastructureException("Failed to delete local file: " + path, e);
        }
    }

    /**
     * Writes bytes to the target location, creating parent dirs for local paths.
     * Small payloads only: the content is held in memory and sent as one PutObject
     * (5 GiB S3 cap). For anything sizeable use {@link #writeOutputFile} or
     * {@link #writeOutput(String, IoWriter)}, which stream and go multipart.
     */
    public void writeOutput(String uri, byte[] content) {
        if (isS3(uri)) {
            S3Uri s3Uri = S3Uri.parse(uri);
            try {
                s3.putObject(PutObjectRequest.builder().bucket(s3Uri.bucket()).key(s3Uri.key()).build(),
                        RequestBody.fromBytes(content));
                log.info("Wrote {} bytes to S3 {}", content.length, uri);
            } catch (RuntimeException e) {
                throw classifyS3WriteFailure(uri, e);
            }
            return;
        }
        Path path = toLocalPath(uri);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, content);
            log.info("Wrote {} bytes to {}", content.length, path.toAbsolutePath());
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write local output: " + path, e);
        }
    }

    /**
     * Sends an existing local file to the target location without loading it into memory
     * and without a second spool. The caller keeps ownership of the file.
     */
    public void writeOutputFile(String uri, Path file) {
        if (isS3(uri)) {
            S3Uri s3Uri = S3Uri.parse(uri);
            try {
                uploadFile(s3Uri.bucket(), s3Uri.key(), file);
                log.info("Uploaded {} bytes to S3 {}", Files.size(file), uri);
            } catch (IOException e) {
                throw new InfrastructureException("Failed to write to S3: " + uri, e);
            } catch (RuntimeException e) {
                throw classifyS3WriteFailure(uri, e);
            }
            return;
        }
        Path path = toLocalPath(uri);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.copy(file, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Copied {} bytes to {}", Files.size(path), path.toAbsolutePath());
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write local output: " + path, e);
        }
    }

    /**
     * Uploads a local file to S3 with user metadata, and returns the version id S3 assigned (null
     * on an unversioned bucket). S3 only accepts metadata when an object is created, so callers
     * that derive it from the content (a checksum) spool to a file first. S3 only.
     */
    public String writeOutputFile(String uri, Path file, Map<String, String> metadata) {
        if (!isS3(uri)) {
            throw new ConfigException("Object metadata needs an s3:// target, got: " + uri);
        }
        S3Uri s3Uri = S3Uri.parse(uri);
        try {
            String versionId = uploadFile(s3Uri.bucket(), s3Uri.key(), file, metadata);
            log.info("Uploaded {} bytes to S3 {} (version {})", Files.size(file), uri, versionId);
            return versionId;
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write to S3: " + uri, e);
        } catch (RuntimeException e) {
            throw classifyS3WriteFailure(uri, e);
        }
    }

    // A single PutObject caps at 5 GiB; above the threshold we go multipart. 4 GiB leaves
    // comfortable margin under the cap. Each upload in flight holds one part in memory, so
    // 64 MiB parts let several run at once on a small runner (merge-allconcepts uploads up to
    // --parallelism files concurrently) while keeping the count well inside S3's 10,000 parts
    // (a 34 GiB consent file is 544). Past 625 GiB the part size grows to stay under 10,000;
    // see partSizeFor.
    // Non-final so the LocalStack IT can lower them and exercise multipart with small files.
    static final long DEFAULT_MULTIPART_THRESHOLD_BYTES = 4L * 1024 * 1024 * 1024;
    static final int DEFAULT_MULTIPART_PART_SIZE_BYTES = 64 * 1024 * 1024;
    /** S3's limit on the number of parts in one multipart upload. */
    static final int MAX_PARTS = 10_000;
    /** S3's limit on one object's size. */
    static final long MAX_OBJECT_BYTES = 5L * 1024 * 1024 * 1024 * 1024;
    private static final long PART_SIZE_ROUNDING_BYTES = 1024 * 1024;

    private long multipartThresholdBytes = DEFAULT_MULTIPART_THRESHOLD_BYTES;
    private int multipartPartSizeBytes = DEFAULT_MULTIPART_PART_SIZE_BYTES;

    void setMultipartThresholdBytes(long bytes) {
        this.multipartThresholdBytes = bytes;
    }

    void setMultipartPartSizeBytes(int bytes) {
        this.multipartPartSizeBytes = bytes;
    }

    /** Restores the production threshold and part size after a test lowered them. */
    void resetMultipartDefaults() {
        this.multipartThresholdBytes = DEFAULT_MULTIPART_THRESHOLD_BYTES;
        this.multipartPartSizeBytes = DEFAULT_MULTIPART_PART_SIZE_BYTES;
    }

    /**
     * The part size for a multipart upload of {@code length} bytes: the configured size, or the
     * smallest whole MiB that keeps the upload within {@link #MAX_PARTS}, whichever is larger.
     */
    static long partSizeFor(long length, long configuredPartSize) {
        long minimum = (length + MAX_PARTS - 1) / MAX_PARTS;
        long rounded = (minimum + PART_SIZE_ROUNDING_BYTES - 1) / PART_SIZE_ROUNDING_BYTES * PART_SIZE_ROUNDING_BYTES;
        return Math.max(configuredPartSize, rounded);
    }

    /**
     * Uploads a local file to S3, transparently switching to a multipart upload when the
     * file exceeds the single-PutObject threshold. Parts are read sequentially through one
     * reused buffer, so memory stays at one part regardless of file size.
     */
    private void uploadFile(String bucket, String key, Path file) throws IOException {
        uploadFile(bucket, key, file, Map.of());
    }

    /** As {@link #uploadFile(String, String, Path)}, with user metadata; returns the new version id. */
    private String uploadFile(String bucket, String key, Path file, Map<String, String> metadata) throws IOException {
        long size = Files.size(file);
        if (size < multipartThresholdBytes) {
            return versionIdOf(s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).metadata(metadata).build(),
                    RequestBody.fromFile(file)));
        }
        return uploadMultipart(bucket, key, size, metadata, () -> Files.newInputStream(file));
    }

    /**
     * Uploads {@code length} bytes read from {@code content} to S3 with user metadata, without
     * spooling to local disk, and returns the version id S3 assigned (null on an unversioned
     * bucket). For content too large for the runner's disk: the length must be known up front
     * (S3 needs it for a single PutObject), and {@code content} may be opened more than once
     * (the SDK re-reads a single-put body to retry it), so each open must yield the same bytes.
     * An {@link IOException} from the stream aborts the upload, leaving no object behind. S3 only.
     */
    public String writeOutputStream(String uri, long length, Map<String, String> metadata, ContentSource content) {
        if (!isS3(uri)) {
            throw new ConfigException("Streamed output needs an s3:// target, got: " + uri);
        }
        S3Uri s3Uri = S3Uri.parse(uri);
        // The SDK reads a single-put body through its own exactly-length-bytes window, so an
        // over-long stream would be silently truncated and a short one surfaces wrapped in an SDK
        // exception. The checked stream records a mismatch here, whatever the SDK wraps it in.
        AtomicReference<LengthMismatchException> mismatch = new AtomicReference<>();
        try {
            String versionId;
            if (length < multipartThresholdBytes) {
                versionId = versionIdOf(s3.putObject(
                        PutObjectRequest.builder().bucket(s3Uri.bucket()).key(s3Uri.key()).metadata(metadata).build(),
                        RequestBody.fromContentProvider(() -> {
                            try {
                                return new LengthCheckedInputStream(content.open(), length, mismatch);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }, length, "application/octet-stream")));
            } else {
                versionId = uploadMultipart(s3Uri.bucket(), s3Uri.key(), length, metadata, content);
            }
            log.info("Uploaded {} bytes to S3 {} (version {})", length, uri, versionId);
            return versionId;
        } catch (IOException | RuntimeException e) {
            if (mismatch.get() != null) {
                throw lengthMismatch(uri, mismatch.get());
            }
            if (e instanceof IOException || e instanceof UncheckedIOException) {
                throw new InfrastructureException("Failed to write to S3: " + uri, e);
            }
            throw classifyS3WriteFailure(uri, (RuntimeException) e);
        }
    }

    /**
     * The content did not have the length the caller declared. The same source yields the same
     * bytes on every open, so this is a bug in the caller, not something a retry can fix.
     */
    private static DataException lengthMismatch(String uri, LengthMismatchException e) {
        return new DataException("Content for " + uri + " does not match its declared length: " + e.getMessage(), e);
    }

    /** Thrown by {@link LengthCheckedInputStream} when the stream is shorter or longer than declared. */
    static final class LengthMismatchException extends IOException {
        LengthMismatchException(String message) {
            super(message);
        }
    }

    /**
     * Passes through exactly {@code expected} bytes and fails if the underlying stream holds fewer
     * or more. Over-run is detected by probing for one more byte as soon as the last expected byte
     * is read, so it fails even for a reader (like the SDK's) that never asks past the length.
     * The first mismatch is also recorded in {@code seen}, if given.
     */
    static final class LengthCheckedInputStream extends InputStream {
        private final InputStream in;
        private final long expected;
        private final AtomicReference<LengthMismatchException> seen;
        private final byte[] one = new byte[1];
        private long count;
        private boolean endVerified;

        LengthCheckedInputStream(InputStream in, long expected, AtomicReference<LengthMismatchException> seen) {
            this.in = in;
            this.expected = expected;
            this.seen = seen;
        }

        @Override
        public int read() throws IOException {
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (count == expected) {
                verifyEnd();
                return -1;
            }
            if (len == 0) {
                return 0;
            }
            int n = in.read(b, off, (int) Math.min(len, expected - count));
            if (n < 0) {
                throw fail("expected " + expected + " bytes but the stream ended after " + count);
            }
            count += n;
            if (count == expected) {
                verifyEnd();
            }
            return n;
        }

        private void verifyEnd() throws IOException {
            if (endVerified) {
                return;
            }
            if (in.read() >= 0) {
                throw fail("expected " + expected + " bytes but the stream holds more");
            }
            endVerified = true;
        }

        private LengthMismatchException fail(String message) {
            LengthMismatchException e = new LengthMismatchException(message);
            if (seen != null) {
                seen.compareAndSet(null, e);
            }
            return e;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Opens the content of a streamed upload; see {@link #writeOutputStream}. */
    @FunctionalInterface
    public interface ContentSource {
        InputStream open() throws IOException;
    }

    /**
     * Multipart upload of {@code size} bytes read from {@code content}. Parts are read
     * sequentially through one reused buffer, so memory stays at one part regardless of size.
     */
    private String uploadMultipart(String bucket, String key, long size, Map<String, String> metadata,
                                   ContentSource content) throws IOException {
        if (size > MAX_OBJECT_BYTES) {
            throw new DataException("s3://" + bucket + "/" + key + " would be " + size
                    + " bytes, over S3's " + MAX_OBJECT_BYTES + " byte object limit");
        }
        // Fits an int: at the 5 TiB object cap this is 525 MiB.
        int partSize = (int) partSizeFor(size, multipartPartSizeBytes);
        String uploadId = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).metadata(metadata).build()).uploadId();
        log.info("Multipart upload {} started for s3://{}/{} ({} bytes, {} byte parts)",
                uploadId, bucket, key, size, partSize);
        try {
            List<CompletedPart> parts = new ArrayList<>();
            byte[] buffer = new byte[partSize];
            int partNumber = 1;
            try (InputStream in = new LengthCheckedInputStream(
                    new BufferedInputStream(content.open()), size, null)) {
                while (true) {
                    int filled = 0;
                    while (filled < buffer.length) {
                        int n = in.read(buffer, filled, buffer.length - filled);
                        if (n < 0) {
                            break;
                        }
                        filled += n;
                    }
                    if (filled == 0) {
                        break;
                    }
                    String etag = s3.uploadPart(
                            UploadPartRequest.builder()
                                    .bucket(bucket).key(key)
                                    .uploadId(uploadId).partNumber(partNumber)
                                    .build(),
                            RequestBody.fromInputStream(
                                    new java.io.ByteArrayInputStream(buffer, 0, filled), filled))
                            .eTag();
                    parts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
                    partNumber++;
                    if (filled < buffer.length) {
                        break;
                    }
                }
            }
            var completed = s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
            String versionId = completed == null ? null : completed.versionId();
            log.info("Multipart upload {} completed: {} part(s)", uploadId, parts.size());
            return versionId;
        } catch (IOException | RuntimeException e) {
            // Best effort: without cleanup the parts sit invisibly, billed, until a
            // lifecycle rule or manual abort removes them. Never mask the original failure.
            try {
                s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucket).key(key).uploadId(uploadId).build());
                log.warn("Multipart upload {} aborted after failure", uploadId);
            } catch (RuntimeException abortFailure) {
                log.warn("Could not abort multipart upload {} for s3://{}/{}: {}",
                        uploadId, bucket, key, abortFailure.getMessage());
            }
            if (e instanceof LengthMismatchException mismatch) {
                throw lengthMismatch("s3://" + bucket + "/" + key, mismatch);
            }
            throw e;
        }
    }

    private static String versionIdOf(software.amazon.awssdk.services.s3.model.PutObjectResponse response) {
        return response == null ? null : response.versionId();
    }

    /**
     * A 4xx from S3 (bar 429 throttling) is a deterministic rejection of this request —
     * retrying the run re-provisions an instance and re-streams the data only to be
     * rejected identically, so it must not map to the retryable INFRASTRUCTURE exit.
     */
    private RuntimeException classifyS3WriteFailure(String uri, RuntimeException e) {
        return classifyS3Failure("write", "Failed to write to S3: " + uri, uri, e);
    }

    /**
     * As {@link #classifyS3WriteFailure}, for any S3 call: a 4xx (bar 429) is a {@link DataException}
     * naming {@code operation}; anything else is an {@link InfrastructureException} with
     * {@code infraMessage}.
     */
    private RuntimeException classifyS3Failure(String operation, String infraMessage, String uri, RuntimeException e) {
        if (e instanceof EtlException classified) {
            return classified;
        }
        if (e instanceof S3Exception s3e) {
            int status = s3e.statusCode();
            if (status >= 400 && status < 500 && status != 429) {
                // A HEAD has no body, so its error details usually carry no message.
                String detail = s3e.awsErrorDetails() == null ? null : s3e.awsErrorDetails().errorMessage();
                if (detail == null || detail.isBlank()) {
                    detail = s3e.getMessage();
                }
                return new DataException("S3 rejected the " + operation + " (" + status + ") for " + uri
                        + ": " + detail, e);
            }
        }
        return new InfrastructureException(infraMessage, e);
    }

    /**
     * Streams output to the target location without materializing the full content in memory.
     * For S3, the content is spooled to a temp file first (S3 PutObject needs content length).
     */
    public void writeOutput(String uri, IoWriter writer) {
        if (isS3(uri)) {
            S3Uri s3Uri = S3Uri.parse(uri);
            try {
                Path tmp = Files.createTempFile("etl-upload-", ".tmp");
                try {
                    try (OutputStream out = Files.newOutputStream(tmp)) {
                        writer.writeTo(out);
                    }
                    uploadFile(s3Uri.bucket(), s3Uri.key(), tmp);
                    log.info("Streamed {} bytes to S3 {}", Files.size(tmp), uri);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                throw new InfrastructureException("Failed to write to S3: " + uri, e);
            } catch (RuntimeException e) {
                throw classifyS3WriteFailure(uri, e);
            }
            return;
        }
        Path path = toLocalPath(uri);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                writer.writeTo(out);
            }
            log.info("Streamed to {}", path.toAbsolutePath());
        } catch (IOException e) {
            throw new InfrastructureException("Failed to write local output: " + path, e);
        }
    }

    @FunctionalInterface
    public interface IoWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * Lists file names (not full paths) directly under the given directory URI.
     * For S3, lists object keys at the given prefix level (single-level, not recursive).
     * Returns an empty list if the directory does not exist.
     */
    public List<String> listFileNames(String directoryUri) {
        if (isS3(directoryUri)) {
            String prefix = directoryUri.endsWith("/") ? directoryUri : directoryUri + "/";
            S3Uri s3Uri = S3Uri.parse(prefix);
            try {
                var request = ListObjectsV2Request.builder()
                        .bucket(s3Uri.bucket()).prefix(s3Uri.key()).delimiter("/").build();
                List<String> names = new java.util.ArrayList<>();
                var paginator = s3.listObjectsV2Paginator(request);
                for (var page : paginator) {
                    page.contents().stream()
                            .map(S3Object::key)
                            .map(k -> k.substring(s3Uri.key().length()))
                            .filter(name -> !name.isEmpty())
                            .forEach(names::add);
                }
                return List.copyOf(names);
            } catch (RuntimeException e) {
                throw new InfrastructureException("Failed to list S3 prefix: " + directoryUri, e);
            }
        }
        Path dir = toLocalPath(directoryUri);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            throw new InfrastructureException("Failed to list directory: " + dir, e);
        }
    }

    /**
     * Lists the names of the immediate subdirectories of the given directory URI.
     * For S3 these are the common prefixes one level below the given prefix; for the
     * local filesystem, its child directories. Returns an empty list if the location
     * does not exist or has no subdirectories.
     */
    public List<String> listDirectoryNames(String directoryUri) {
        if (isS3(directoryUri)) {
            String prefix = directoryUri.endsWith("/") ? directoryUri : directoryUri + "/";
            S3Uri s3Uri = S3Uri.parsePrefix(prefix);
            try {
                var request = ListObjectsV2Request.builder()
                        .bucket(s3Uri.bucket()).prefix(s3Uri.key()).delimiter("/").build();
                List<String> names = new ArrayList<>();
                for (var page : s3.listObjectsV2Paginator(request)) {
                    page.commonPrefixes().stream()
                            .map(p -> p.prefix().substring(s3Uri.key().length()))
                            .map(name -> name.endsWith("/") ? name.substring(0, name.length() - 1) : name)
                            .filter(name -> !name.isEmpty())
                            .forEach(names::add);
                }
                return List.copyOf(names);
            } catch (RuntimeException e) {
                throw new InfrastructureException("Failed to list S3 prefix: " + directoryUri, e);
            }
        }
        Path dir = toLocalPath(directoryUri);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new InfrastructureException("Failed to list directory: " + dir, e);
        }
    }

    /**
     * Lists the relative paths (with {@code /} separators) of every file anywhere below
     * the given directory URI. Returns an empty list if the location does not exist.
     */
    public List<String> listFilesRecursive(String directoryUri) {
        if (isS3(directoryUri)) {
            String prefix = directoryUri.endsWith("/") ? directoryUri : directoryUri + "/";
            S3Uri s3Uri = S3Uri.parsePrefix(prefix);
            try {
                var request = ListObjectsV2Request.builder()
                        .bucket(s3Uri.bucket()).prefix(s3Uri.key()).build();
                List<String> keys = new ArrayList<>();
                for (var page : s3.listObjectsV2Paginator(request)) {
                    page.contents().stream()
                            .map(S3Object::key)
                            .map(k -> k.substring(s3Uri.key().length()))
                            .filter(name -> !name.isEmpty())
                            .forEach(keys::add);
                }
                return List.copyOf(keys);
            } catch (RuntimeException e) {
                throw new InfrastructureException("Failed to list S3 prefix: " + directoryUri, e);
            }
        }
        Path dir = toLocalPath(directoryUri);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new InfrastructureException("Failed to walk directory: " + dir, e);
        }
    }

    /** Downloads a remote (or local) file to a local path, creating parent directories as needed. */
    public void copyToLocal(String sourceUri, Path target) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (InputStream in = openInput(sourceUri)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Staged {} -> {}", sourceUri, target);
        } catch (IOException e) {
            throw new InfrastructureException("Failed to stage " + sourceUri + " to " + target, e);
        }
    }

    private Path toLocalPath(String uri) {
        String p = uri.startsWith("file://") ? uri.substring("file://".length()) : uri;
        return Path.of(p);
    }

    /** Parsed {@code s3://bucket/key} location. */
    public record S3Uri(String bucket, String key) {
        static S3Uri parse(String uri) {
            String rest = uri.substring(S3_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash < 1 || slash == rest.length() - 1) {
                throw new ConfigException("Malformed S3 URI (expected s3://bucket/key): " + uri);
            }
            return new S3Uri(rest.substring(0, slash), rest.substring(slash + 1));
        }

        /**
         * Like {@link #parse} but for prefix listings, where the bucket root is a legal
         * location: {@code s3://bucket} and {@code s3://bucket/} yield an empty key.
         */
        static S3Uri parsePrefix(String uri) {
            String rest = uri.substring(S3_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                slash = rest.length();
            }
            if (slash == 0) {
                throw new ConfigException("Malformed S3 URI (expected s3://bucket[/prefix]): " + uri);
            }
            String key = slash == rest.length() ? "" : rest.substring(slash + 1);
            return new S3Uri(rest.substring(0, slash), key);
        }
    }
}
