package com.lvfast.transcoder.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits one source object into contiguous ranges fetched in parallel. Objects smaller than one
 * part use a single range. A failed range is retried a bounded number of times before the whole
 * download fails, so one slow or reset connection cannot stall a large transfer.
 */
public class ParallelDownload {

    private static final Logger log = LoggerFactory.getLogger(ParallelDownload.class);
    private static final int ATTEMPTS = 3;

    @FunctionalInterface
    public interface RangeReader {
        void read(long offset, long length, Path target) throws IOException;
    }

    private final RangeReader reader;
    private final long partSize;
    private final int concurrency;

    public ParallelDownload(RangeReader reader, long partSize, int concurrency) {
        this.reader = reader;
        this.partSize = partSize;
        this.concurrency = concurrency;
    }

    /** Downloads {@code sizeBytes} into {@code target}, fetching the ranges concurrently. */
    public void download(long sizeBytes, Path target) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
            file.setLength(sizeBytes);
        }
        List<long[]> ranges = plan(sizeBytes);
        if (ranges.isEmpty()) {
            return;
        }
        if (ranges.size() == 1) {
            fetchRangeWithRetries(ranges.getFirst(), target);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, ranges.size()),
                runnable -> {
                    Thread thread = new Thread(runnable, "source-range-download");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long[] range : ranges) {
                futures.add(pool.submit(() -> {
                    fetchRangeWithRetries(range, target);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                await(future, futures);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void await(Future<?> future, List<Future<?>> futures) throws IOException {
        try {
            future.get();
        } catch (ExecutionException failure) {
            for (Future<?> other : futures) {
                other.cancel(true);
            }
            Throwable cause = failure.getCause();
            if (cause instanceof StorageUnavailableException storage) {
                throw storage;
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Source range download failed", cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Source download was interrupted", interrupted);
        }
    }

    private List<long[]> plan(long sizeBytes) {
        List<long[]> ranges = new ArrayList<>();
        for (long offset = 0; offset < sizeBytes; offset += partSize) {
            ranges.add(new long[] {offset, Math.min(partSize, sizeBytes - offset)});
        }
        return ranges;
    }

    private void fetchRangeWithRetries(long[] range, Path target) throws IOException {
        StorageUnavailableException storageFailure = null;
        IOException ioFailure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                reader.read(range[0], range[1], target);
                return;
            } catch (StorageUnavailableException failure) {
                storageFailure = failure;
                ioFailure = null;
                log.warn("Source range at {} ({} bytes) failed on attempt {}", range[0], range[1], attempt, failure);
            } catch (IOException failure) {
                ioFailure = failure;
                storageFailure = null;
                log.warn("Source range at {} ({} bytes) failed on attempt {}", range[0], range[1], attempt, failure);
            }
        }
        if (storageFailure != null) {
            throw storageFailure;
        }
        throw ioFailure;
    }
}
