package com.lvfast.streaming.media.job;

import java.time.Duration;
import java.util.Optional;

/**
 * Decides whether a failed processing attempt should be retried and, when it should, how long the
 * job must wait before becoming claimable again. Only a bounded set of transient failure codes is
 * retried and at most three total attempts are allowed, matching the MVP's "simple configured
 * delays" rather than an exponential backoff curve.
 */
public interface JobRetryPolicy {

    /**
     * Returns the delay before the next attempt for the given 1-based failed attempt number, or
     * {@link Optional#empty()} when the failure is permanent or the attempt budget is exhausted.
     */
    Optional<Duration> delayFor(int failedAttemptNumber, String failureCode);
}
