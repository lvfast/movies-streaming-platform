package com.lvfast.streaming.media.upload;

/** Malformed upload input (400). */
public class MediaValidationException extends RuntimeException {
    public MediaValidationException(String message) {
        super(message);
    }
}
