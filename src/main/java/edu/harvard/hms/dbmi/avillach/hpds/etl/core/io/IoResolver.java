package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
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

    // A single PutObject caps at 5 GiB; above the threshold we go multipart. 4 GiB leaves
    // comfortable margin under the cap. 512 MiB parts keep the part count tiny (a 34 GiB
    // consent file is 68 parts; S3 allows 10,000) while each part stays a modest request.
    // Non-final so the LocalStack IT can lower them and exercise multipart with small files.
    private long multipartThresholdBytes = 4L * 1024 * 1024 * 1024;
    private int multipartPartSizeBytes = 512 * 1024 * 1024;

    void setMultipartThresholdBytes(long bytes) {
        this.multipartThresholdBytes = bytes;
    }

    void setMultipartPartSizeBytes(int bytes) {
        this.multipartPartSizeBytes = bytes;
    }

    /**
     * Uploads a local file to S3, transparently switching to a multipart upload when the
     * file exceeds the single-PutObject threshold. Parts are read sequentially through one
     * reused buffer, so memory stays at one part regardless of file size.
     */
    private void uploadFile(String bucket, String key, Path file) throws IOException {
        long size = Files.size(file);
        if (size < multipartThresholdBytes) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromFile(file));
            return;
        }

        String uploadId = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build()).uploadId();
        log.info("Multipart upload {} started for s3://{}/{} ({} bytes, {} byte parts)",
                uploadId, bucket, key, size, multipartPartSizeBytes);
        try {
            List<CompletedPart> parts = new ArrayList<>();
            byte[] buffer = new byte[multipartPartSizeBytes];
            int partNumber = 1;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
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
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
            log.info("Multipart upload {} completed: {} part(s)", uploadId, parts.size());
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
            throw e;
        }
    }

    /**
     * A 4xx from S3 (bar 429 throttling) is a deterministic rejection of this request —
     * retrying the run re-provisions an instance and re-streams the data only to be
     * rejected identically, so it must not map to the retryable INFRASTRUCTURE exit.
     */
    private RuntimeException classifyS3WriteFailure(String uri, RuntimeException e) {
        if (e instanceof S3Exception s3e) {
            int status = s3e.statusCode();
            if (status >= 400 && status < 500 && status != 429) {
                return new DataException("S3 rejected the write (" + status + ") for " + uri
                        + ": " + s3e.awsErrorDetails().errorMessage(), e);
            }
        }
        return new InfrastructureException("Failed to write to S3: " + uri, e);
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
