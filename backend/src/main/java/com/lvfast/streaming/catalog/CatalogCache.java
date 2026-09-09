package com.lvfast.streaming.catalog;

import java.util.Optional;

interface CatalogCache {
    Optional<CatalogHome> getHome();
    void putHome(CatalogHome home);
    Optional<MovieDetails> getMovie(String slug);
    void putMovie(MovieDetails movie);
    void invalidateAll();
}
