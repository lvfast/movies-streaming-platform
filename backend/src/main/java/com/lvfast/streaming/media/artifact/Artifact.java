package com.lvfast.streaming.media.artifact;

import java.util.List;

/**
 * Immutable processing manifest written last under an attempt prefix. Object keys are relative to
 * that prefix. The shape matches {@code contracts/media/artifact-v1.schema.json}.
 */
public record Artifact(
        int schemaVersion,
        String jobId,
        String attemptId,
        String kind,
        String profile,
        String movieId,
        String mediaVersionId,
        String assetId,
        int width,
        int height,
        Double durationSeconds,
        String videoCodec,
        String audioCodec,
        String playlist,
        List<ArtifactObject> objects) {

    public record ArtifactObject(String key, long sizeBytes, String sha256) {
    }
}
