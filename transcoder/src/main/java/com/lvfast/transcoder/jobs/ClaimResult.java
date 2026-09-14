package com.lvfast.transcoder.jobs;

/**
 * Claim response held by the worker. The backend does not echo the job id on the wire (the command
 * carried it), so the client fills it from the id it requested. The worker never imports backend
 * code.
 */
public record ClaimResult(
        String jobId,
        String disposition,
        String reason,
        String attemptId,
        String leaseUntil,
        String kind,
        String source,
        String outputPrefix,
        String movieId,
        String mediaVersionId,
        String assetId,
        String profile) {

    public boolean claimed() {
        return "CLAIMED".equals(disposition);
    }

    public boolean terminalSkip() {
        return "SKIP".equals(disposition) && "TERMINAL".equals(reason);
    }
}
