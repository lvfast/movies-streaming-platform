package com.lvfast.streaming.media.job;

import com.lvfast.streaming.messaging.JdbcOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Periodic recovery for the processing path. It closes RUNNING attempts whose lease expired (a
 * worker that died or was partitioned away) and re-queues due RETRY_WAIT jobs with a fresh outbox
 * command. Every decision is a guarded single-row update, so concurrent API instances and the
 * scheduler cannot double-apply a transition.
 */
@Service
public class JobRecoveryScheduler {

    private static final String LEASE_LOST = "LEASE_LOST";

    private final JdbcJobStateRepository jobs;
    private final JobRetryPolicy retryPolicy;
    private final JdbcOutboxRepository outbox;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final int batchSize;

    public JobRecoveryScheduler(
            JdbcJobStateRepository jobs,
            JobRetryPolicy retryPolicy,
            JdbcOutboxRepository outbox,
            Clock clock,
            PlatformTransactionManager txManager,
            @Value("${app.media.recovery.batch-size:100}") int batchSize) {
        this.jobs = jobs;
        this.retryPolicy = retryPolicy;
        this.outbox = outbox;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.media.recovery.poll-interval-ms:1000}",
            initialDelayString = "${app.media.recovery.initial-delay-ms:1000}")
    public int recover() {
        return expireLeases() + enqueueDueRetries();
    }

    /** P4-A: an expired RUNNING lease closes the attempt and applies the retry policy. */
    public int expireLeases() {
        Instant now = clock.instant();
        int recovered = 0;
        for (JdbcJobStateRepository.ExpiredLeaseRow row : jobs.findExpiredLeases(now, batchSize)) {
            Optional<Duration> delay = retryPolicy.delayFor(row.attemptNumber(), LEASE_LOST);
            tx.execute(status -> {
                if (delay.isPresent()) {
                    jobs.markRetryWait(row.jobId(), row.attemptId(), LEASE_LOST,
                            "Worker lease expired before completion", now.plus(delay.get()));
                } else {
                    jobs.markFailed(row.jobId(), row.attemptId(), LEASE_LOST,
                            "Worker lease expired before completion",
                            row.mediaVersionId(), row.assetId());
                }
                return null;
            });
            recovered++;
        }
        return recovered;
    }

    /** P4-B: a due RETRY_WAIT job becomes QUEUED with one new outbox command. */
    public int enqueueDueRetries() {
        Instant now = clock.instant();
        int enqueued = 0;
        for (JdbcJobStateRepository.DueRetryRow row : jobs.findDueRetries(now, batchSize)) {
            tx.execute(status -> {
                if (jobs.markQueued(row.jobId()) == 1) {
                    outbox.append(row.jobId(), eventType(row.kind()));
                }
                return null;
            });
            enqueued++;
        }
        return enqueued;
    }

    private String eventType(String kind) {
        return switch (kind) {
            case "TRANSCODE" -> "transcode.requested.v1";
            case "ARTWORK" -> "artwork.requested.v1";
            default -> throw new IllegalStateException("Unknown job kind '" + kind + "'");
        };
    }
}
