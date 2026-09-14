package com.lvfast.streaming.administration;

public class SlugUnavailableException extends RuntimeException {
    public SlugUnavailableException(String slug) {
        super("A movie with slug '" + slug + "' already exists");
    }
}
