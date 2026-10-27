package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Versioned reads from S3, with the versioned GET/HEAD pattern of {@code AllConceptsFolders}
 * ({@code GetObjectRequest.versionId}, {@code HeadObjectRequest}).
 *
 * <p>A location that is not an {@code s3://} URI is a local file, for the laptop proof of concept on the
 * committed test vectors only: its "version id" is a fingerprint of size and modification time, and
 * {@link #open} ignores the id. Real data never runs that way.
 */
@Component
public class S3VersionedObjectSource implements VersionedObjectSource {

    private static final Logger log = LoggerFactory.getLogger(S3VersionedObjectSource.class);
    private static final String S3_PREFIX = "s3://";

    private final S3Client s3;

    public S3VersionedObjectSource(S3Client s3) {
        this.s3 = s3;
    }

    @Override
    public Optional<String> currentVersionId(String uri) {
        if (!uri.startsWith(S3_PREFIX)) {
            return localFingerprint(Path.of(stripFile(uri)));
        }
        String[] loc = split(uri);
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(loc[0]).key(loc[1]).build());
            String id = head.versionId();
            if (id == null || id.isBlank() || "null".equals(id)) {
                throw new ConfigException("s3://" + loc[0] + "/" + loc[1] + " has no version id: the bucket is not versioned, "
                        + "so its content cannot be pinned");
            }
            return Optional.of(id);
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new InfrastructureException("Failed to read the version of " + uri, e);
        } catch (RuntimeException e) {
            if (e instanceof ConfigException c) {
                throw c;
            }
            throw new InfrastructureException("Failed to read the version of " + uri, e);
        }
    }

    @Override
    public InputStream open(String uri, String versionId) {
        if (!uri.startsWith(S3_PREFIX)) {
            Path path = Path.of(stripFile(uri));
            if (!Files.isRegularFile(path)) {
                throw new ConfigException("Input file not found: " + path.toAbsolutePath());
            }
            try {
                return new BufferedInputStream(Files.newInputStream(path), 1 << 16);
            } catch (IOException e) {
                throw new InfrastructureException("Failed to open local input: " + path, e);
            }
        }
        String[] loc = split(uri);
        if (versionId == null || versionId.isBlank() || "null".equals(versionId)) {
            throw new ConfigException("A version id is required to read " + uri);
        }
        try {
            InputStream in = s3.getObject(GetObjectRequest.builder()
                    .bucket(loc[0]).key(loc[1]).versionId(versionId).build());
            log.info("Opened {} version {}", uri, versionId);
            return in;
        } catch (NoSuchKeyException e) {
            throw versionGone(uri, versionId, e);
        } catch (S3Exception e) {
            // a version id that names no version answers 404 (NoSuchVersion), which has no exception class of its own
            if (e.statusCode() == 404) {
                throw versionGone(uri, versionId, e);
            }
            throw new InfrastructureException("Failed to read " + uri + " (version " + versionId + ")", e);
        } catch (RuntimeException e) {
            throw new InfrastructureException("Failed to read " + uri + " (version " + versionId + ")", e);
        }
    }

    /** The manifest pins a version that is no longer there (deleted or expired): the pin failed, the input is not what it names. */
    private static DataException versionGone(String uri, String versionId, Exception cause) {
        return new DataException("VERSION_NOT_FOUND: version " + versionId + " of " + uri + " named by the manifest does not exist (any more)",
                cause);
    }

    private static Optional<String> localFingerprint(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of("local-" + Files.size(path) + "-" + Files.getLastModifiedTime(path).toMillis());
        } catch (IOException e) {
            throw new InfrastructureException("Failed to stat " + path, e);
        }
    }

    private static String stripFile(String uri) {
        return uri.startsWith("file://") ? uri.substring("file://".length()) : uri;
    }

    /** {@code s3://bucket/key} to {bucket, key}. */
    static String[] split(String uri) {
        String rest = uri.substring(S3_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 1 || slash == rest.length() - 1) {
            throw new ConfigException("Malformed S3 URI (expected s3://bucket/key): " + uri);
        }
        return new String[]{rest.substring(0, slash), rest.substring(slash + 1)};
    }
}
