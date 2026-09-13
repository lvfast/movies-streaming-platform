package com.lvfast.streaming.media.job;

import com.lvfast.streaming.audit.AuditService;
import com.lvfast.streaming.media.upload.MediaValidationException;
import com.lvfast.streaming.messaging.JdbcOutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual retry for a terminal FAILED job. Retry creates a new job id targeting the retained source
 * (the same media version or artwork asset), resets the target from FAILED and appends one outbox
 * command. The idempotency key replays the same new job, so a lost response cannot create a second
 * durable job.
 */
@Service
public class JobRetryService {

    private static final String JOB_RETRY = "JOB_RETRY";

    private final JdbcJobStateRepository state;
    private final JdbcMediaJobRepository jobs;
    private final JdbcOutboxRepository outbox;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public JobRetryService(
            JdbcJobStateRepository state,
            JdbcMediaJobRepository jobs,
            JdbcOutboxRepository outbox,
            AuditService audit,
            PlatformTransactionManager txManager) {
        this.state = state;
        this.jobs = jobs;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    public JobView retry(UUID actorId, String idempotencyKey, String requestId, UUID jobId) {
        requireIdempotencyKey(idempotencyKey);
        JobView replay = jobs.findRetryReplay(actorId, JOB_RETRY, idempotencyKey).orElse(null);
        if (replay != null) {
            return replay;
        }

        JdbcJobStateRepository.JobRow original =
                state.findJob(jobId).orElseThrow(() -> new MediaJobNotFoundException(jobId));
        if (!"FAILED".equals(original.state())) {
            throw new JobStateException("Only a FAILED job can be retried");
        }

        UUID newJobId = UUID.randomUUID();
        String eventType = "TRANSCODE".equals(original.kind())
                ? "transcode.requested.v1" : "artwork.requested.v1";
        try {
            tx.execute(status -> {
                state.resetTargetForRetry(original.mediaVersionId(), original.assetId());
                jobs.insertQueued(newJobId, original.movieId(), original.mediaVersionId(),
                        original.assetId(), original.kind());
                outbox.append(newJobId, eventType);
                JobView view = jobs.findView(newJobId).orElseThrow();
                jobs.storeRetryReplay(actorId, JOB_RETRY, idempotencyKey, 201, view);
                return null;
            });
        } catch (DataIntegrityViolationException conflict) {
            throw new JobStateException("Movie already has a media replacement in progress");
        }

        JobView view = jobs.findView(newJobId).orElseThrow();
        audit.record(actorId, "USER", "JOB_RETRIED", "MEDIA_JOB", newJobId, requestId,
                Map.of("originalJobId", jobId.toString()),
                Map.of("jobId", newJobId.toString()));
        return view;
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MediaValidationException("Idempotency-Key header is required");
        }
    }
}
