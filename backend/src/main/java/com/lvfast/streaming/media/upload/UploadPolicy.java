package com.lvfast.streaming.media.upload;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configured limits and lifetimes for private multipart uploads. */
@ConfigurationProperties(prefix = "app.media.upload")
public record UploadPolicy(
        @DefaultValue("21474836480") long videoMaxBytes,
        @DefaultValue("10485760") long artworkMaxBytes,
        @DefaultValue("8388608") long partSizeBytes,
        @DefaultValue("32") int maxPartsPerRequest,
        @DefaultValue("PT24H") Duration sessionTtl,
        @DefaultValue("PT15M") Duration signTtl,
        @DefaultValue("100") int sweepBatchSize) {

    public UploadPolicy {
        if (partSizeBytes <= 0) {
            throw new IllegalStateException("app.media.upload.part-size-bytes must be positive");
        }
        if (maxPartsPerRequest < 1 || maxPartsPerRequest > 32) {
            throw new IllegalStateException("app.media.upload.max-parts-per-request must be between 1 and 32");
        }
    }

    public long maxBytes(String kind) {
        return switch (kind) {
            case "VIDEO" -> videoMaxBytes;
            case "POSTER", "BACKDROP" -> artworkMaxBytes;
            default -> throw new IllegalArgumentException("Unknown upload kind '" + kind + "'");
        };
    }

    public int totalParts(long declaredBytes) {
        return (int) ((declaredBytes + partSizeBytes - 1) / partSizeBytes);
    }
}
