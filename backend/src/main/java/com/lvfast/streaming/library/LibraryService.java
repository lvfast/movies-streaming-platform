package com.lvfast.streaming.library;

import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.catalog.MoviePage;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LibraryService {
    private final LibraryRepository repository;

    LibraryService(LibraryRepository repository) {
        this.repository = repository;
    }

    public MoviePage watchlist(UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new LibraryValidationException(
                    "Page must be non-negative and size must be between 1 and 100");
        }
        return repository.list(userId, page, size);
    }

    public void add(UUID userId, UUID movieId) {
        if (!repository.add(userId, movieId)) {
            throw new MovieNotFoundException(movieId);
        }
    }

    public void remove(UUID userId, UUID movieId) {
        repository.remove(userId, movieId);
    }
}
