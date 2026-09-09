package com.lvfast.streaming.catalog;

import java.util.List;
import java.util.UUID;

public record MovieSummary(
        UUID id,
        String slug,
        String title,
        int releaseYear,
        int runtimeSeconds,
        String maturityRating,
        String posterUrl,
        String backdropUrl,
        List<String> genres) {}
