package com.lvfast.streaming.media;

/** Server-owned artwork asset projection. Raw source keys and storage credentials are never exposed. */
public record MediaAssetView(
        String id,
        String movieId,
        String kind,
        String state,
        String createdAt,
        String updatedAt) {
}
