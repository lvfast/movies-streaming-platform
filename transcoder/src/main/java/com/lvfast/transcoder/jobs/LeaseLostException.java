package com.lvfast.transcoder.jobs;

/** The backend no longer considers this worker's attempt active. */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(String message) {
        super(message);
    }
}
