package com.lvfast.streaming.catalog;

import java.util.List;
import java.util.Optional;

interface CatalogRepository {
    List<MovieSummary> featured(int limit);
    List<MovieSummary> newest(int limit);
    List<MovieSummary> byGenre(String genreSlug, int limit);
    Optional<MovieDetails> bySlug(String slug);
    MoviePage search(String query, int page, int size);
}
