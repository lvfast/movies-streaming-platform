package com.lvfast.transcoder.process;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Runs FFmpeg/ffprobe via {@link ProcessBuilder} arguments (never shell interpolation). Process
 * output is captured with a bounded buffer and the whole invocation is capped by a timeout, after
 * which the process is destroyed.
 */
@Component
public class BoundedProcessRunner {

    private static final int MAX_CAPTURE_BYTES = 1_048_576;

    private final Duration timeout;

    public BoundedProcessRunner(com.lvfast.transcoder.config.TranscoderProperties properties) {
        this.timeout = properties.processTimeout();
    }

    public Result run(List<String> command, Path workingDir) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDir.toFile());
            Process process = builder.start();
            CompletableFuture<String> stdout = capture(process.getInputStream());
            CompletableFuture<String> stderr = capture(process.getErrorStream());
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ProcessTimeoutException("Process timed out after " + timeout);
            }
            return new Result(process.exitValue(), stdout.join(), stderr.join());
        } catch (ProcessTimeoutException expected) {
            throw expected;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to run process: " + String.join(" ", command), failure);
        }
    }

    private CompletableFuture<String> capture(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] buffer = new byte[8192];
                byte[] all = new byte[MAX_CAPTURE_BYTES];
                int total = 0;
                int read;
                while (total < MAX_CAPTURE_BYTES && (read = stream.read(buffer)) != -1) {
                    int toCopy = Math.min(read, MAX_CAPTURE_BYTES - total);
                    System.arraycopy(buffer, 0, all, total, toCopy);
                    total += toCopy;
                }
                return new String(all, 0, total, StandardCharsets.UTF_8);
            } catch (Exception failure) {
                return "";
            } finally {
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        });
    }

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean succeeded() {
            return exitCode == 0;
        }
    }
}
