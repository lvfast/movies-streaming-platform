package com.lvfast.streaming.catalog;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private final CatalogRepository repository;
    private final CatalogCache cache;

    CatalogService(CatalogRepository repository, CatalogCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public CatalogHome home() {
        return cache.getHome().orElseGet(() -> {
            List<CatalogRail> rails = new ArrayList<>();
            addRail(rails, "featured", "Featured", repository.featured(12));
            addRail(rails, "new-releases", "New Releases", repository.newest(12));
            addRail(rails, "adventure", "Adventure", repository.byGenre("adventure", 12));
            addRail(rails, "drama", "Drama", repository.byGenre("drama", 12));
            CatalogHome home = new CatalogHome(List.copyOf(rails));
            cache.putHome(home);
            return home;
        });
    }

    public MovieDetails movie(String slug) {
        return cache.getMovie(slug).orElseGet(() -> {
            MovieDetails movie = repository.bySlug(slug).orElseThrow(() -> new MovieNotFoundException(slug));
            cache.putMovie(movie);
            return movie;
        });
    }

    public MoviePage search(String query, int page, int size) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new CatalogValidationException("Search query must contain between 1 and 100 characters");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new CatalogValidationException("Page must be non-negative and size must be between 1 and 100");
        }
        return repository.search(normalized, page, size);
    }

    private void addRail(List<CatalogRail> rails, String key, String title, List<MovieSummary> items) {
        if (!items.isEmpty()) rails.add(new CatalogRail(key, title, items));
    }
}
