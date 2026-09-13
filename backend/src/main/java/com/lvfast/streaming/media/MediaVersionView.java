package com.lvfast.streaming.media;

/** Server-owned media version projection. Raw source keys and storage credentials are never exposed. */
public record MediaVersionView(
        String id,
        String movieId,
        String state,
        String createdAt,
        String updatedAt) {
}
