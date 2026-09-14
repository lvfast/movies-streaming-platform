package com.lvfast.streaming.administration;

/**
 * Raised when a publication, activation or lifecycle command is refused because required validated
 * media or a valid source state is missing. The previous active version stays untouched.
 */
public class PublicationRejectedException extends RuntimeException {
    public PublicationRejectedException(String message) {
        super(message);
    }
}
