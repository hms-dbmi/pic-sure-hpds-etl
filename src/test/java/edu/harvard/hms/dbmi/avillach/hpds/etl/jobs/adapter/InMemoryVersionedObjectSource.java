package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/** A {@link VersionedObjectSource} over a map: every {@link #put} adds a version "v1", "v2", ... of its key. */
class InMemoryVersionedObjectSource implements VersionedObjectSource {

    private final Map<String, List<byte[]>> objects = new TreeMap<>();
    /** Called with {@code uri#versionId} just before a stream is handed out. */
    Consumer<String> beforeOpen = s -> { };
    /** Every {@code uri#versionId} opened, in order. */
    final List<String> opened = new ArrayList<>();

    String put(String uri, String content) {
        List<byte[]> versions = objects.computeIfAbsent(uri, k -> new ArrayList<>());
        versions.add(content.getBytes(StandardCharsets.UTF_8));
        return "v" + versions.size();
    }

    @Override
    public Optional<String> currentVersionId(String uri) {
        List<byte[]> versions = objects.get(uri);
        return versions == null ? Optional.empty() : Optional.of("v" + versions.size());
    }

    @Override
    public InputStream open(String uri, String versionId) {
        List<byte[]> versions = objects.get(uri);
        if (versions == null || versionId == null || !versionId.matches("v[0-9]+")
                || Integer.parseInt(versionId.substring(1)) > versions.size()) {
            throw new DataException("VERSION_NOT_FOUND: no version " + versionId + " of " + uri);
        }
        opened.add(uri + "#" + versionId);
        beforeOpen.accept(uri + "#" + versionId);
        return new ByteArrayInputStream(versions.get(Integer.parseInt(versionId.substring(1)) - 1));
    }

    static String md5(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
