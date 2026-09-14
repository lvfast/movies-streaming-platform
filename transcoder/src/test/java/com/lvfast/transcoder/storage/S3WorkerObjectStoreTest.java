package com.lvfast.transcoder.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.transcoder.config.TranscoderProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The S3 store downloads small objects in one stream and large objects through parallel ranged
 * reads, so a slow single connection cannot stall a big source.
 */
class S3WorkerObjectStoreTest {

    private static final TranscoderProperties.Storage STORAGE = new TranscoderProperties.Storage(
            new TranscoderProperties.Role("bucket", "https://example.invalid", "auto", "a", "s"),
            new TranscoderProperties.Role("bucket", "https://example.invalid", "auto", "a", "s"));

    @Test
    void downloadsLargeObjectsThroughRangedReads(@TempDir Path dir) throws IOException {
        byte[] source = new byte[10];
        for (int i = 0; i < source.length; i++) {
            source[i] = (byte) i;
        }
        S3WorkerObjectStore store = new S3WorkerObjectStore(STORAGE, 4, 2) {
            @Override
            public Head head(String role, String key) {
                return new Head(source.length, "video/mp4");
            }

            @Override
            protected void readRange(String role, String key, long offset, long length, Path target)
                    throws IOException {
                try (RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
                    file.seek(offset);
                    file.write(source, (int) offset, (int) length);
                }
            }
        };

        Path target = dir.resolve("source");
        store.download("source", "original", target);

        assertThat(Files.readAllBytes(target)).isEqualTo(source);
    }

    @Test
    void downloadsSmallObjectsInOneStream(@TempDir Path dir) throws IOException {
        byte[] source = {9, 8, 7, 6, 5};
        S3WorkerObjectStore store = new S3WorkerObjectStore(STORAGE, 1024, 2) {
            @Override
            public Head head(String role, String key) {
                return new Head(source.length, "video/mp4");
            }

            @Override
            public InputStream read(String role, String key) {
                return new ByteArrayInputStream(source);
            }

            @Override
            protected void readRange(String role, String key, long offset, long length, Path target) {
                throw new AssertionError("Small objects must not use ranged reads");
            }
        };

        Path target = dir.resolve("source");
        store.download("source", "original", target);

        assertThat(Files.readAllBytes(target)).isEqualTo(source);
    }
}
