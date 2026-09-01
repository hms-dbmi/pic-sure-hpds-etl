package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    /** Writes bytes to the target location, creating parent dirs for local paths. */
    public void writeOutput(String uri, byte[] content) {
        if (isS3(uri)) {
            S3Uri s3Uri = S3Uri.parse(uri);
            try {
                s3.putObject(PutObjectRequest.builder().bucket(s3Uri.bucket()).key(s3Uri.key()).build(),
                        RequestBody.fromBytes(content));
                log.info("Wrote {} bytes to S3 {}", content.length, uri);
            } catch (RuntimeException e) {
                throw new InfrastructureException("Failed to write to S3: " + uri, e);
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
                s3.putObject(PutObjectRequest.builder().bucket(s3Uri.bucket()).key(s3Uri.key()).build(),
                        RequestBody.fromFile(file));
                log.info("Uploaded {} bytes to S3 {}", Files.size(file), uri);
            } catch (IOException | RuntimeException e) {
                throw new InfrastructureException("Failed to write to S3: " + uri, e);
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
                    s3.putObject(PutObjectRequest.builder().bucket(s3Uri.bucket()).key(s3Uri.key()).build(),
                            RequestBody.fromFile(tmp));
                    log.info("Streamed {} bytes to S3 {}", Files.size(tmp), uri);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                throw new InfrastructureException("Failed to write to S3: " + uri, e);
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
    }
}
