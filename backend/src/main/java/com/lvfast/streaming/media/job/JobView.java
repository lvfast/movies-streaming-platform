package com.lvfast.streaming.media.job;

/** Server-owned processing job projection. */
public record JobView(
        String id,
        String movieId,
        String mediaVersionId,
        String assetId,
        String kind,
        String state,
        int attemptNumber,
        int progressPercent,
        String stage,
        String errorCode,
        String errorSummary,
        String retryAt,
        String updatedAt) {
}
