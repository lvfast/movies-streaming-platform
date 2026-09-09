package com.lvfast.streaming.catalog;

import java.util.UUID;

public class MovieNotFoundException extends RuntimeException {
    public MovieNotFoundException(String slug) {
        super("No published movie exists with slug '" + slug + "'");
    }

    public MovieNotFoundException(UUID movieId) {
        super("No published movie exists with id '" + movieId + "'");
    }
}
