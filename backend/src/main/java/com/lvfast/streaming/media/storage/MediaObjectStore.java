package com.lvfast.streaming.media.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Backend-owned facade over private S3-compatible object storage. {@code bucketRole} selects the
 * bucket, endpoint and credential set used by the operation so browser signing can use a separate
 * signing-only identity from internal server access. Raw source objects are never made public.
 */
public interface MediaObjectStore {

    String initiate(String bucketRole, String key, String contentType, Map<String, String> metadata);

    SignedPart signPart(String bucketRole, String key, String uploadId, int partNumber, Duration ttl);

    /** Short-lived private read URL for a stored object, used for admin-only asset preview. */
    PresignedGet presignGet(String bucketRole, String key, Duration ttl);

    PartPage listParts(String bucketRole, String key, String uploadId, Integer marker);

    void complete(String bucketRole, String key, String uploadId, List<CompletedPart> parts);

    /** Returns {@code null} when the key does not exist. */
    ObjectHead head(String bucketRole, String key);

    void abort(String bucketRole, String key, String uploadId);

    InputStream read(String bucketRole, String key);

    void put(String bucketRole, String key, Path file, String contentType);

    void copy(String bucketRole, String fromKey, String toKey);
}
