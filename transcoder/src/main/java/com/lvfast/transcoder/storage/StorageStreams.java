package com.lvfast.transcoder.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;

/** Shared streaming helper for object copies. */
final class StorageStreams {

    /** A large buffer keeps a slow or high-latency link busy; the default copy uses 8 KiB. */
    static final int BUFFER_BYTES = 1 << 20;

    private StorageStreams() {
    }

    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }
}
