package com.lvfast.transcoder.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store's portable download fallback must copy every byte, including sources larger than the
 * small buffer used by {@code Files.copy}.
 */
class WorkerObjectStoreDownloadTest {

    @Test
    void defaultDownloadCopiesEveryByte(@TempDir Path dir) throws Exception {
        byte[] source = new byte[200_000];
        for (int i = 0; i < source.length; i++) {
            source[i] = (byte) (i * 31);
        }

        WorkerObjectStore store = new WorkerObjectStore() {
            @Override
            public InputStream read(String role, String key) {
                return new ByteArrayInputStream(source);
            }

            @Override
            public void put(String role, String key, Path file, String contentType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Head head(String role, String key) {
                return new Head(source.length, "video/mp4");
            }
        };

        Path target = dir.resolve("source");
        store.download("source", "original", target);

        assertThat(Files.readAllBytes(target)).isEqualTo(source);
    }
}
