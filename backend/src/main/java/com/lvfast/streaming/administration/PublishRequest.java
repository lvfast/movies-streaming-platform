package com.lvfast.streaming.administration;

/**
 * Idempotent publication command. Artwork pointers are optional: when omitted the movie keeps its
 * current pointers, and when present the asset must be READY and belong to the same movie.
 */
public record PublishRequest(String mediaVersionId, String posterAssetId, String backdropAssetId) {

    public static PublishRequest of(String mediaVersionId) {
        return new PublishRequest(mediaVersionId, null, null);
    }
}
