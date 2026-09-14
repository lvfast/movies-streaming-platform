package com.lvfast.transcoder.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
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

    /**
     * Streams the object into {@code target}. Implementations that can read ranges override this
     * with a parallel download for large objects; the default streams once with a large buffer so a
     * slow link is not limited by the 8 KiB copy used elsewhere.
     */
    default void download(String role, String key, Path target) throws IOException {
        try (InputStream input = read(role, key);
                OutputStream output = Files.newOutputStream(target)) {
            StorageStreams.copy(input, output);
        }
    }

    record Head(long sizeBytes, String contentType) {
    }
}
