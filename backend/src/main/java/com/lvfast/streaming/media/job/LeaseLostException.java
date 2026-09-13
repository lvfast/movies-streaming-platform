package com.lvfast.streaming.media.job;

import java.util.UUID;

/** The worker's attempt is no longer the current RUNNING attempt and cannot extend its lease. */
public class LeaseLostException extends RuntimeException {

    private final UUID jobId;
    private final UUID attemptId;

    public LeaseLostException(UUID jobId, UUID attemptId) {
        super("Lease lost for job " + jobId + " attempt " + attemptId);
        this.jobId = jobId;
        this.attemptId = attemptId;
    }

    public UUID jobId() {
        return jobId;
    }

    public UUID attemptId() {
        return attemptId;
    }
}
