package com.lvfast.transcoder.jobs;

import com.lvfast.transcoder.config.TranscoderProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a claim lease alive with periodic heartbeats and lets the executor verify the lease is still
 * active between long-running steps. A lost lease is sticky: once the backend rejects a heartbeat the
 * worker stops working on the attempt.
 */
public class LeaseGuard implements AutoCloseable {

    private final BackendJobClient client;
    private final UUID jobId;
    private final String attemptId;
    private final Duration heartbeatInterval;
    private final Clock clock;
    private volatile Instant leaseUntil;
    private volatile boolean lost;
    private ScheduledExecutorService scheduler;

    public LeaseGuard(
            BackendJobClient client,
            TranscoderProperties properties,
            Clock clock,
            ClaimResult claim) {
        this.client = client;
        this.jobId = UUID.fromString(claim.jobId());
        this.attemptId = claim.attemptId();
        this.heartbeatInterval = properties.leaseHeartbeatInterval();
        this.clock = clock;
        this.leaseUntil = Instant.parse(claim.leaseUntil());
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::heartbeat, heartbeatInterval.toMillis(),
                heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void assertActive() {
        if (lost || clock.instant().isAfter(leaseUntil)) {
            throw new LeaseLostException("Lease expired or lost for job " + jobId);
        }
    }

    private void heartbeat() {
        try {
            String refreshed = client.heartbeat(jobId, attemptId);
            if (refreshed != null) {
                leaseUntil = Instant.parse(refreshed);
            }
        } catch (LeaseLostException leaseLost) {
            lost = true;
        } catch (Exception transientFailure) {
            // Keep the current lease; a transient failure does not immediately forfeit the attempt.
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
