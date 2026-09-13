package com.lvfast.streaming.administration;

import java.util.List;

/** Server-owned admin movie projection; runtime and media/artwork URLs are server-owned. */
public record AdminMovieView(
        String id,
        String title,
        String slug,
        String synopsis,
        int releaseYear,
        String maturityRating,
        List<Integer> genreIds,
        boolean featured,
        String lifecycle,
        long revision,
        String managementMode,
        String activeMediaVersionId,
        String posterAssetId,
        String backdropAssetId,
        Integer runtimeSeconds,
        String firstPublishedAt,
        String createdAt,
        String updatedAt) {
}
