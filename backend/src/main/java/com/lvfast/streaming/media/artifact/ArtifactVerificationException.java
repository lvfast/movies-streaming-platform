package com.lvfast.streaming.media.artifact;

/** The stored artifact manifest or its referenced objects failed server-side validation. */
public class ArtifactVerificationException extends RuntimeException {

    public ArtifactVerificationException(String message) {
        super(message);
    }
}
