package com.lvfast.transcoder.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A large source download is split into contiguous ranges fetched in parallel; a failed range is
 * retried a bounded number of times before the whole download fails.
 */
class ParallelDownloadTest {

    @Test
    void downloadsEveryRangeAndRetriesOneFailedRange(@TempDir Path dir) throws IOException {
        byte[] source = new byte[10];
        for (int i = 0; i < source.length; i++) {
            source[i] = (byte) i;
        }
        Map<Long, AtomicInteger> calls = new ConcurrentHashMap<>();
        ParallelDownload.RangeReader reader = (offset, length, target) -> {
            int attempt = calls.computeIfAbsent(offset, key -> new AtomicInteger()).incrementAndGet();
            if (offset == 4 && attempt == 1) {
                throw new IOException("transient range failure");
            }
            writeRange(source, offset, length, target);
        };

        Path target = dir.resolve("source");
        new ParallelDownload(reader, 4, 2).download(source.length, target);

        assertThat(Files.readAllBytes(target)).isEqualTo(source);
        assertThat(calls.get(4L).get()).isEqualTo(2);
    }

    @Test
    void failsAfterBoundedRetriesWhenARangeKeepsFailing(@TempDir Path dir) {
        byte[] source = new byte[10];
        Map<Long, AtomicInteger> calls = new ConcurrentHashMap<>();
        ParallelDownload.RangeReader reader = (offset, length, target) -> {
            calls.computeIfAbsent(offset, key -> new AtomicInteger()).incrementAndGet();
            if (offset == 8) {
                throw new IOException("permanent range failure");
            }
            writeRange(source, offset, length, target);
        };

        Path target = dir.resolve("source");
        assertThatThrownBy(() -> new ParallelDownload(reader, 4, 2).download(source.length, target))
                .isInstanceOf(IOException.class);
        assertThat(calls.get(8L).get()).isEqualTo(3);
    }

    @Test
    void downloadsAnObjectSmallerThanOnePartInASingleRange(@TempDir Path dir) throws IOException {
        byte[] source = {1, 2, 3};
        AtomicInteger calls = new AtomicInteger();
        ParallelDownload.RangeReader reader = (offset, length, target) -> {
            calls.incrementAndGet();
            writeRange(source, offset, length, target);
        };

        Path target = dir.resolve("source");
        new ParallelDownload(reader, 16, 4).download(source.length, target);

        assertThat(Files.readAllBytes(target)).isEqualTo(source);
        assertThat(calls).hasValue(1);
    }

    private static void writeRange(byte[] source, long offset, long length, Path target) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
            file.seek(offset);
            file.write(source, (int) offset, (int) length);
        }
    }
}
