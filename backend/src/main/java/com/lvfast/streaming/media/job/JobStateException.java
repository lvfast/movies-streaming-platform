package com.lvfast.streaming.media.job;

/** The requested job transition is invalid for the job's current state. */
public class JobStateException extends RuntimeException {

    public JobStateException(String message) {
        super(message);
    }
}
