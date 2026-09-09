package com.lvfast.streaming.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lvfast.streaming.common.MediaUrlResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock private CatalogRepository repository;
    @Mock private CatalogCache cache;

    @Test
    void searchResolvesRelativeArtworkAgainstConfiguredMediaBase() {
        MovieSummary movie = new MovieSummary(
                UUID.randomUUID(), "movie", "Movie", 2026, 120, "PG",
                "/media/artwork/poster.svg", "/media/artwork/backdrop.svg", List.of("Drama"));
        when(repository.search("movie", 0, 20)).thenReturn(new MoviePage(List.of(movie), 0, 20, 1));
        CatalogService service = new CatalogService(
                repository, cache, new MediaUrlResolver("https://media.example.test/library/"));

        MovieSummary result = service.search("movie", 0, 20).items().getFirst();

        assertThat(result.posterUrl()).isEqualTo("https://media.example.test/library/artwork/poster.svg");
        assertThat(result.backdropUrl()).isEqualTo("https://media.example.test/library/artwork/backdrop.svg");
    }

    @Test
    void cachedMovieIsResolvedAtTheResponseBoundary() {
        MovieDetails movie = new MovieDetails(
                UUID.randomUUID(), "movie", "Movie", 2026, 120, "PG",
                "/media/artwork/poster.svg", "/media/artwork/backdrop.svg", List.of("Drama"), "Synopsis", true);
        when(cache.getMovie("movie")).thenReturn(Optional.of(movie));
        CatalogService service = new CatalogService(
                repository, cache, new MediaUrlResolver("https://media.example.test/library"));

        assertThat(service.movie("movie").posterUrl())
                .isEqualTo("https://media.example.test/library/artwork/poster.svg");
    }
}
