package com.lvfast.transcoder.support;

import com.lvfast.transcoder.config.TranscoderProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Shared helpers for worker tests that shell out to the FFmpeg binaries on PATH. */
public final class TranscoderTestSupport {

    private TranscoderTestSupport() {
    }

    public static TranscoderProperties properties() {
        var source = new TranscoderProperties.Role("bucket", null, "auto", "a", "s");
        var delivery = new TranscoderProperties.Role("bucket", null, "auto", "a", "s");
        return new TranscoderProperties(
                "http://localhost:8080", "worker-1", "cred", ffmpeg(), ffprobe(),
                Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(30), Duration.ofMinutes(15),
                new TranscoderProperties.Storage(source, delivery));
    }

    public static String ffmpeg() {
        return System.getProperty("ffmpeg.path", "ffmpeg");
    }

    public static String ffprobe() {
        return System.getProperty("ffprobe.path", "ffprobe");
    }

    public static boolean ffmpegAvailable() {
        return isAvailable(ffmpeg()) && isAvailable(ffprobe());
    }

    public static Path tempDir() {
        try {
            return Files.createTempDirectory("transcoder-test-");
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to create a test temp directory", failure);
        }
    }

    public static void run(List<String> command, Path dir) {
        capture(command, dir);
    }

    public static String capture(List<String> command, Path dir) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("Process failed (" + exit + "): " + String.join(" ", command)
                        + "\n" + output);
            }
            return output;
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to run " + command.getFirst(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command.getFirst(), interrupted);
        }
    }

    private static boolean isAvailable(String binary) {
        try {
            ProcessBuilder builder = new ProcessBuilder(binary, "-version");
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return builder.start().waitFor() == 0;
        } catch (IOException | InterruptedException unavailable) {
            return false;
        }
    }
}
