package com.lvfast.streaming.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.catalog.MoviePage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LibraryServiceTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MOVIE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private LibraryRepository repository;
    private LibraryService service;

    @BeforeEach
    void setUp() {
        service = new LibraryService(repository);
    }

    @Test
    void listsTheRequestedWatchlistPageForTheAuthenticatedUser() {
        MoviePage expected = new MoviePage(List.of(), 1, 5, 0);
        when(repository.list(USER_ID, 1, 5)).thenReturn(expected);

        assertThat(service.watchlist(USER_ID, 1, 5)).isSameAs(expected);
    }

    @Test
    void rejectsPaginationOutsideTheContractBeforeQueryingTheDatabase() {
        assertThatThrownBy(() -> service.watchlist(USER_ID, -1, 20))
                .isInstanceOf(LibraryValidationException.class);
        assertThatThrownBy(() -> service.watchlist(USER_ID, 0, 101))
                .isInstanceOf(LibraryValidationException.class);
    }

    @Test
    void addingAnUnavailableMovieReturnsTheSharedMovieNotFoundFailure() {
        when(repository.add(USER_ID, MOVIE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.add(USER_ID, MOVIE_ID))
                .isInstanceOf(MovieNotFoundException.class);
    }

    @Test
    void removingAnAbsentMovieIsStillSuccessful() {
        service.remove(USER_ID, MOVIE_ID);

        verify(repository).remove(USER_ID, MOVIE_ID);
    }
}
