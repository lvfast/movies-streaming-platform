package com.lvfast.streaming.catalog;

import com.lvfast.streaming.common.MediaUrlResolver;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private final CatalogRepository repository;
    private final CatalogCache cache;
    private final MediaUrlResolver mediaUrls;

    CatalogService(CatalogRepository repository, CatalogCache cache, MediaUrlResolver mediaUrls) {
        this.repository = repository;
        this.cache = cache;
        this.mediaUrls = mediaUrls;
    }

    public CatalogHome home() {
        CatalogHome home = cache.getHome().orElseGet(() -> {
            List<CatalogRail> rails = new ArrayList<>();
            addRail(rails, "featured", "Featured", repository.featured(12));
            addRail(rails, "new-releases", "New Releases", repository.newest(12));
            addRail(rails, "adventure", "Adventure", repository.byGenre("adventure", 12));
            addRail(rails, "drama", "Drama", repository.byGenre("drama", 12));
            CatalogHome loaded = new CatalogHome(List.copyOf(rails));
            cache.putHome(loaded);
            return loaded;
        });
        return new CatalogHome(home.rails().stream()
                .map(rail -> new CatalogRail(rail.key(), rail.title(), resolve(rail.items())))
                .toList());
    }

    public MovieDetails movie(String slug) {
        MovieDetails movie = cache.getMovie(slug).orElseGet(() -> {
            MovieDetails loaded = repository.bySlug(slug).orElseThrow(() -> new MovieNotFoundException(slug));
            cache.putMovie(loaded);
            return loaded;
        });
        return resolve(movie);
    }

    public MoviePage search(String query, int page, int size) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new CatalogValidationException("Search query must contain between 1 and 100 characters");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new CatalogValidationException("Page must be non-negative and size must be between 1 and 100");
        }
        MoviePage result = repository.search(normalized, page, size);
        return new MoviePage(resolve(result.items()), result.page(), result.size(), result.total());
    }

    private void addRail(List<CatalogRail> rails, String key, String title, List<MovieSummary> items) {
        if (!items.isEmpty()) rails.add(new CatalogRail(key, title, items));
    }

    private List<MovieSummary> resolve(List<MovieSummary> movies) {
        return movies.stream().map(this::resolve).toList();
    }

    private MovieSummary resolve(MovieSummary movie) {
        return new MovieSummary(movie.id(), movie.slug(), movie.title(), movie.releaseYear(), movie.runtimeSeconds(),
                movie.maturityRating(), mediaUrls.resolve(movie.posterUrl()), mediaUrls.resolve(movie.backdropUrl()),
                movie.genres());
    }

    private MovieDetails resolve(MovieDetails movie) {
        return new MovieDetails(movie.id(), movie.slug(), movie.title(), movie.releaseYear(), movie.runtimeSeconds(),
                movie.maturityRating(), mediaUrls.resolve(movie.posterUrl()), mediaUrls.resolve(movie.backdropUrl()),
                movie.genres(), movie.synopsis(), movie.playable());
    }
}
