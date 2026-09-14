package com.lvfast.streaming.media.job;

import com.lvfast.streaming.media.artifact.Artifact;
import com.lvfast.streaming.media.artifact.ArtifactVerificationException;
import com.lvfast.streaming.media.artifact.StoredArtifactVerifier;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies worker result events to durable job state. Events are deduplicated by {@code eventId} and
 * only the current RUNNING attempt may move a job to a terminal state. A completed result is
 * verified against storage before the job and its target become READY.
 */
@Service
public class MediaResultService {

    private static final String DELIVERY_ROLE = "delivery";

    private final JdbcJobStateRepository jobs;
    private final StoredArtifactVerifier verifier;
    private final JobRetryPolicy retryPolicy;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public MediaResultService(
            JdbcJobStateRepository jobs,
            StoredArtifactVerifier verifier,
            JobRetryPolicy retryPolicy,
            Clock clock,
            ObjectMapper json,
            PlatformTransactionManager txManager) {
        this.jobs = jobs;
        this.verifier = verifier;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    public void handle(String body) {
        JsonNode event = readTree(body);
        String type = event.get("type").asText();
        String eventId = event.get("eventId").asText();
        String jobId = event.get("jobId").asText();
        String attemptId = textOrNull(event, "attemptId");
        long sequence = event.get("sequence").asLong();
        JsonNode payload = event.get("payload");

        UUID eventUuid = UUID.fromString(eventId);
        UUID jobUuid = UUID.fromString(jobId);
        UUID attemptUuid = attemptId == null ? null : UUID.fromString(attemptId);
        if (!jobs.insertInbox(eventUuid, type, jobUuid, attemptUuid, sequence, payload.toString())) {
            return; // Already recorded: a duplicate or late delivery.
        }

        switch (type) {
            case "media.progress.v1" -> progress(jobUuid, attemptUuid, sequence, payload);
            case "media.completed.v1" -> completed(jobUuid, attemptUuid, payload);
            case "media.failed.v1" -> failed(jobUuid, attemptUuid, payload);
            default -> { /* Unknown type: recorded and dropped to avoid a poison-message loop. */ }
        }
        jobs.markInboxProcessed(eventUuid);
    }

    private void progress(UUID jobId, UUID attemptId, long sequence, JsonNode payload) {
        if (!isCurrentAttempt(jobId, attemptId)) {
            return;
        }
        String stage = payload.get("stage").asText();
        int percent = payload.get("percent").asInt();
        jobs.updateProgress(jobId, stage, percent, sequence);
    }

    private void completed(UUID jobId, UUID attemptId, JsonNode payload) {
        if (attemptId == null || !isCurrentAttempt(jobId, attemptId)) {
            return;
        }
        JdbcJobStateRepository.JobRow job = jobs.findJob(jobId).orElseThrow();
        JdbcJobStateRepository.AttemptRow attempt = jobs.findAttempt(attemptId).orElseThrow();
        String artifactKey = payload.get("artifactKey").asText();
        var identity = new StoredArtifactVerifier.Identity(
                jobId.toString(), attemptId.toString(), job.movieId().toString(), job.kind());
        Artifact artifact;
        try {
            artifact = verifier.verify(DELIVERY_ROLE, attempt.outputPrefix(), artifactKey, identity);
        } catch (ArtifactVerificationException invalid) {
            tx.execute(status -> {
                jobs.markFailed(job.id(), attempt.id(), "OUTPUT_INVALID", invalid.getMessage(),
                        job.mediaVersionId(), job.assetId());
                return null;
            });
            return;
        }
        tx.execute(status -> {
            jobs.markSucceeded(job.id(), attempt.id(), job.mediaVersionId(), job.assetId());
            if ("TRANSCODE".equals(job.kind()) && artifact.durationSeconds() != null) {
                jobs.recordVerifiedDuration(job.mediaVersionId(), artifact.durationSeconds().intValue());
            }
            return null;
        });
    }

    private void failed(UUID jobId, UUID attemptId, JsonNode payload) {
        if (attemptId == null || !isCurrentAttempt(jobId, attemptId)) {
            return;
        }
        JdbcJobStateRepository.JobRow job = jobs.findJob(jobId).orElseThrow();
        String code = payload.get("code").asText();
        String summary = payload.get("summary").asText();
        Optional<Duration> delay = retryPolicy.delayFor(job.attemptNumber(), code);
        tx.execute(status -> {
            if (delay.isPresent()) {
                jobs.markRetryWait(job.id(), attemptId, code, summary, clock.instant().plus(delay.get()));
            } else {
                jobs.markFailed(job.id(), attemptId, code, summary, job.mediaVersionId(), job.assetId());
            }
            return null;
        });
    }

    private boolean isCurrentAttempt(UUID jobId, UUID attemptId) {
        if (attemptId == null) {
            return false;
        }
        JdbcJobStateRepository.JobRow job = jobs.findJob(jobId).orElse(null);
        if (job == null || !"RUNNING".equals(job.state())) {
            return false;
        }
        JdbcJobStateRepository.AttemptRow attempt = jobs.findAttempt(attemptId).orElse(null);
        return attempt != null
                && "RUNNING".equals(attempt.state())
                && attempt.jobId().equals(jobId)
                && attempt.attemptNumber() == job.attemptNumber();
    }

    private JsonNode readTree(String body) {
        try {
            return json.readTree(body);
        } catch (Exception unreadable) {
            throw new IllegalArgumentException("Result event is not valid JSON", unreadable);
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
