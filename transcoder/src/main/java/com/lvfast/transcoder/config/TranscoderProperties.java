package com.lvfast.transcoder.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Worker configuration. The worker authenticates to the backend with a shared machine credential,
 * runs FFmpeg binaries by configured path and stores its working files under {@code workDir}.
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app.transcoder")
public record TranscoderProperties(
        String backendBaseUrl,
        String workerId,
        String credential,
        String ffmpegPath,
        String ffprobePath,
        Path workDir,
        Duration leaseHeartbeatInterval,
        Duration processTimeout,
        Storage storage) {

    public record Storage(Role source, Role delivery) {
    }

    public record Role(String bucket, String endpoint, String region, String accessKey, String secretKey) {
        public boolean hasEndpoint() {
            return endpoint != null && !endpoint.isBlank();
        }
    }
}
