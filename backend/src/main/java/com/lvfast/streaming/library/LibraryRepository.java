package com.lvfast.streaming.library;

import com.lvfast.streaming.catalog.MoviePage;
import java.util.UUID;

interface LibraryRepository {
    MoviePage list(UUID userId, int page, int size);

    boolean add(UUID userId, UUID movieId);

    void remove(UUID userId, UUID movieId);
}
