package com.lvfast.streaming.media.job;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * MVP retry schedule. Transient storage, process-timeout and lost-lease failures retry once after
 * 60 seconds and once after 300 seconds; the third failure, or any permanent failure code, is
 * terminal. {@code LEASE_LOST} is a backend-detected recovery code (a worker died or abandoned its
 * claim), not a worker-reported result, so it is retried here to satisfy the worker-death recovery
 * scenario without widening the worker-reported failure contract.
 */
@Component
public class DefaultJobRetryPolicy implements JobRetryPolicy {

    private static final Set<String> TRANSIENT =
            Set.of("STORAGE_UNAVAILABLE", "PROCESS_TIMEOUT", "LEASE_LOST");

    @Override
    public Optional<Duration> delayFor(int failedAttemptNumber, String failureCode) {
        if (failureCode == null || !TRANSIENT.contains(failureCode)) {
            return Optional.empty();
        }
        return switch (failedAttemptNumber) {
            case 1 -> Optional.of(Duration.ofSeconds(60));
            case 2 -> Optional.of(Duration.ofSeconds(300));
            default -> Optional.empty();
        };
    }
}
