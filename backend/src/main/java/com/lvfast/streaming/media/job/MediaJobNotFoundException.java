package com.lvfast.streaming.media.job;

import java.util.UUID;

/** The requested media job does not exist. */
public class MediaJobNotFoundException extends RuntimeException {

    private final UUID jobId;

    public MediaJobNotFoundException(UUID jobId) {
        super("Media job " + jobId + " was not found");
        this.jobId = jobId;
    }

    public UUID jobId() {
        return jobId;
    }
}
