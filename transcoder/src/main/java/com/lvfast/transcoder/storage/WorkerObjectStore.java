package com.lvfast.transcoder.storage;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Worker-side object storage facade. The {@code source} role downloads private uploads and the
 * {@code delivery} role uploads immutable validated output. The worker defines its own records and
 * never imports backend code.
 */
public interface WorkerObjectStore {

    InputStream read(String role, String key);

    void put(String role, String key, Path file, String contentType);

    /** Returns {@code null} when the key does not exist. */
    Head head(String role, String key);

    record Head(long sizeBytes, String contentType) {
    }
}
