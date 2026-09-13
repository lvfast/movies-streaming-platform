package com.lvfast.transcoder.output;

import java.util.List;

/**
 * Immutable processing manifest. The worker uploads output objects first and writes this manifest
 * last under the backend-issued attempt prefix. Object keys are relative to that prefix.
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
}
