package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import java.io.InputStream;
import java.util.Optional;

/**
 * Reads objects by version. {@code IoResolver.openInput} cannot ask for a version id, and the adapter
 * must read exactly the versions its input manifest names, so it reads through this interface instead
 * ({@link S3VersionedObjectSource} in production, an in-memory fake in tests). Writes still go through
 * {@code IoResolver}.
 */
public interface VersionedObjectSource {

    /**
     * The id of the current version of {@code uri}; empty when the object does not exist (or its
     * current version is a delete marker). Never "null" or blank for an object that exists: an object
     * with no version id (an unversioned bucket) is a {@code ConfigException}, since nothing could
     * then be pinned.
     */
    Optional<String> currentVersionId(String uri);

    /**
     * Opens exactly {@code versionId} of {@code uri}. The caller closes the stream.
     *
     * @throws edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException         the object or version does not exist
     * @throws edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException the read failed
     */
    InputStream open(String uri, String versionId);
}
