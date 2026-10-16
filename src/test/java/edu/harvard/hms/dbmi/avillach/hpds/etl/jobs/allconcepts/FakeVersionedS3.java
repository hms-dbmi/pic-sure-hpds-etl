package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mocked {@link S3Client} backed by an in-memory, versioned bucket: every put and delete adds a
 * version (a delete adds a delete marker), version listings page through every key's history, and
 * reads honour a requested version id. Enough S3 for the merge and manifest jobs to run end to
 * end through the real {@link edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver}.
 * Thread-safe, since the merge job reads and writes folders concurrently.
 */
final class FakeVersionedS3 {

    final S3Client client = mock(S3Client.class);
    BucketVersioningStatus versioning = BucketVersioningStatus.ENABLED;
    /** Versions per page of {@code listObjectVersions}; small values exercise pagination. */
    int pageSize = 1000;
    /** When set, {@code putObject} returns no version id, as an unversioned bucket would. */
    boolean omitVersionIds;
    /**
     * When set, {@code putObject} reads exactly the body's declared content length and stops,
     * never reaching end of stream, as the SDK may when it sends no streaming checksum. Otherwise
     * it reads to end of stream, as the default aws-chunked encoding does.
     */
    boolean lengthCappedReads;
    /** Runs before each {@code headObject} is answered, e.g. to write a key concurrently. */
    java.util.function.Consumer<HeadObjectRequest> beforeHead = req -> { };
    /** Every {@code getObject} request, in order. */
    final List<GetObjectRequest> reads = java.util.Collections.synchronizedList(new ArrayList<>());

    private final Map<String, List<Version>> keys = new TreeMap<>();
    private long clock = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();
    private int nextVersion = 1;

    private record Version(String versionId, byte[] content, Map<String, String> metadata, Instant lastModified,
                           boolean deleteMarker) {
    }

    FakeVersionedS3() {
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class))).thenAnswer(inv ->
                GetBucketVersioningResponse.builder().status(versioning).build());
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(inv -> {
            PutObjectRequest req = inv.getArgument(0);
            RequestBody body = inv.getArgument(1);
            byte[] content;
            try (InputStream in = body.contentStreamProvider().newStream()) {
                content = lengthCappedReads
                        ? in.readNBytes(Math.toIntExact(body.optionalContentLength().orElseThrow()))
                        : in.readAllBytes();
            }
            Version v = add(req.key(), content, req.metadata(), false);
            return PutObjectResponse.builder().versionId(omitVersionIds ? null : v.versionId()).build();
        });
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(inv -> {
            DeleteObjectRequest req = inv.getArgument(0);
            add(req.key(), new byte[0], Map.of(), true);
            return DeleteObjectResponse.builder().deleteMarker(true).build();
        });
        when(client.headObject(any(HeadObjectRequest.class))).thenAnswer(inv -> {
            HeadObjectRequest req = inv.getArgument(0);
            beforeHead.accept(req);
            Version v = find(req.key(), req.versionId());
            return HeadObjectResponse.builder().versionId(v.versionId()).metadata(v.metadata())
                    .lastModified(v.lastModified()).contentLength((long) v.content().length).build();
        });
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> {
            GetObjectRequest req = inv.getArgument(0);
            reads.add(req);
            Version v = find(req.key(), req.versionId());
            return new ResponseInputStream<>(GetObjectResponse.builder().versionId(v.versionId()).build(),
                    AbortableInputStream.create(new ByteArrayInputStream(v.content())));
        });
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenAnswer(inv ->
                list(inv.getArgument(0)));
    }

    /** Writes a new version of {@code key} (relative to the bucket) and returns its version id. */
    String put(String key, String content) {
        return add(key, content.getBytes(StandardCharsets.UTF_8), Map.of(), false).versionId();
    }

    String put(String key, String content, Map<String, String> metadata) {
        return add(key, content.getBytes(StandardCharsets.UTF_8), metadata, false).versionId();
    }

    void delete(String key) {
        add(key, new byte[0], Map.of(), true);
    }

    /** Current content, or null if the key is absent or deleted. */
    String content(String key) {
        Version v = latest(key);
        return v == null || v.deleteMarker() ? null : new String(v.content(), StandardCharsets.UTF_8);
    }

    Map<String, String> metadata(String key) {
        return latest(key).metadata();
    }

    String versionId(String key) {
        return latest(key).versionId();
    }

    Instant lastModified(String key) {
        return latest(key).lastModified();
    }

    /** Number of versions (delete markers included) ever written to the key. */
    synchronized int versionCount(String key) {
        return keys.getOrDefault(key, List.of()).size();
    }

    boolean exists(String key) {
        Version v = latest(key);
        return v != null && !v.deleteMarker();
    }

    private synchronized Version add(String key, byte[] content, Map<String, String> metadata, boolean deleteMarker) {
        Version v = new Version("v" + nextVersion++, content, Map.copyOf(metadata), Instant.ofEpochSecond(clock += 60),
                deleteMarker);
        keys.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        return v;
    }

    private synchronized Version latest(String key) {
        List<Version> history = keys.get(key);
        return history == null || history.isEmpty() ? null : history.get(history.size() - 1);
    }

    private synchronized Version find(String key, String versionId) {
        List<Version> history = keys.getOrDefault(key, List.of());
        if (versionId == null) {
            Version v = latest(key);
            if (v == null || v.deleteMarker()) {
                throw NoSuchKeyException.builder().message("no such key " + key).build();
            }
            return v;
        }
        return history.stream().filter(v -> v.versionId().equals(versionId) && !v.deleteMarker()).findFirst()
                .orElseThrow(() -> NoSuchKeyException.builder().message("no version " + versionId + " of " + key).build());
    }

    /** Pages through every version under the prefix, newest first within a key, as S3 does. */
    private synchronized ListObjectVersionsResponse list(ListObjectVersionsRequest req) {
        record Entry(String key, Version version, boolean latest) {
        }
        List<Entry> all = new ArrayList<>();
        keys.forEach((key, history) -> {
            if (!key.startsWith(req.prefix() == null ? "" : req.prefix())) {
                return;
            }
            for (int i = history.size() - 1; i >= 0; i--) {
                all.add(new Entry(key, history.get(i), i == history.size() - 1));
            }
        });
        int start = 0;
        if (req.keyMarker() != null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).key().equals(req.keyMarker())
                        && all.get(i).version().versionId().equals(req.versionIdMarker())) {
                    start = i + 1;
                    break;
                }
            }
        }
        int end = Math.min(all.size(), start + pageSize);
        List<ObjectVersion> versions = new ArrayList<>();
        List<DeleteMarkerEntry> markers = new ArrayList<>();
        for (Entry e : all.subList(start, end)) {
            if (e.version().deleteMarker()) {
                markers.add(DeleteMarkerEntry.builder().key(e.key()).versionId(e.version().versionId())
                        .isLatest(e.latest()).lastModified(e.version().lastModified()).build());
            } else {
                versions.add(ObjectVersion.builder().key(e.key()).versionId(e.version().versionId())
                        .isLatest(e.latest()).lastModified(e.version().lastModified())
                        .size((long) e.version().content().length).build());
            }
        }
        ListObjectVersionsResponse.Builder resp = ListObjectVersionsResponse.builder()
                .versions(versions).deleteMarkers(markers).isTruncated(end < all.size());
        if (end < all.size()) {
            Entry last = all.get(end - 1);
            resp.nextKeyMarker(last.key()).nextVersionIdMarker(last.version().versionId());
        }
        return resp.build();
    }

    static String text(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
