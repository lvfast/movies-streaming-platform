package com.lvfast.streaming.media.upload;

/** Server-owned upload session projection returned to administrators. */
public record UploadSession(
        String id,
        String movieId,
        String mediaVersionId,
        String assetId,
        String kind,
        String state,
        long partSizeBytes,
        int totalParts,
        long declaredBytes,
        String expiresAt,
        String jobId) {
}
