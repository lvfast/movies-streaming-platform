package com.lvfast.streaming.media.job;

import com.lvfast.streaming.media.upload.MediaValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conditional claim and lease heartbeat for the independent worker. A claim atomically moves a
 * QUEUED/RETRY_WAIT job to RUNNING, creates a unique attempt with a fresh output prefix and returns
 * the immutable source/output parameters. Human JWTs never reach these methods: they are exposed
 * only behind machine authentication.
 */
@Service
public class JobClaimService {

    private static final String VIDEO_PROFILE = "h264-aac-1080p30";

    private final JdbcJobStateRepository jobs;
    private final WorkerAccess worker;
    private final Clock clock;
    private final TransactionTemplate tx;

    public JobClaimService(
            JdbcJobStateRepository jobs,
            WorkerAccess worker,
            Clock clock,
            PlatformTransactionManager txManager) {
        this.jobs = jobs;
        this.worker = worker;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public ClaimResponse claim(UUID jobId, ClaimRequest request) {
        String workerId = request == null ? null : request.workerId();
        if (workerId == null || workerId.isBlank()) {
            throw new MediaValidationException("workerId is required");
        }
        JdbcJobStateRepository.JobRow job = jobs.findJob(jobId).orElse(null);
        if (job == null) {
            return ClaimResponse.skip("NOT_AVAILABLE");
        }
        return tx.execute(status -> {
            if (jobs.claim(jobId) == 0) {
                JdbcJobStateRepository.JobRow current = jobs.findJob(jobId).orElseThrow();
                return isTerminal(current.state())
                        ? ClaimResponse.skip("TERMINAL")
                        : ClaimResponse.skip("NOT_AVAILABLE");
            }
            int attemptNumber = jobs.attemptNumber(jobId);
            UUID attemptId = UUID.randomUUID();
            String outputPrefix = outputPrefix(job, attemptId);
            Instant leaseUntil = clock.instant().plus(worker.leaseTtl());
            jobs.insertAttempt(attemptId, jobId, attemptNumber, workerId, leaseUntil, outputPrefix);
            jobs.markTargetProcessing(job.mediaVersionId(), job.assetId());
            return ClaimResponse.claimed(
                    attemptId, leaseUntil, job.kind(), job.sourceKey(), outputPrefix,
                    job.movieId(), job.mediaVersionId(), job.assetId(), profile(job));
        });
    }

    public HeartbeatResponse heartbeat(UUID jobId, HeartbeatRequest request) {
        if (request == null || request.workerId() == null || request.workerId().isBlank()) {
            throw new MediaValidationException("workerId is required");
        }
        UUID attemptId = parseUuid(request.attemptId(), "attemptId");
        Instant leaseUntil = clock.instant().plus(worker.leaseTtl());
        int updated = jobs.heartbeat(attemptId, jobId, request.workerId(), leaseUntil);
        if (updated == 0) {
            throw new LeaseLostException(jobId, attemptId);
        }
        return new HeartbeatResponse(leaseUntil.toString());
    }

    private String outputPrefix(JdbcJobStateRepository.JobRow job, UUID attemptId) {
        return switch (job.kind()) {
            case "TRANSCODE" -> "hls/" + job.movieId() + "/" + job.mediaVersionId() + "/" + attemptId + "/";
            case "ARTWORK" -> "artwork/" + job.movieId() + "/" + job.assetId() + "/" + attemptId + "/";
            default -> throw new IllegalStateException("Unknown job kind '" + job.kind() + "'");
        };
    }

    private String profile(JdbcJobStateRepository.JobRow job) {
        return switch (job.kind()) {
            case "TRANSCODE" -> VIDEO_PROFILE;
            case "ARTWORK" -> switch (job.assetKind()) {
                case "POSTER" -> "poster-600x900";
                case "BACKDROP" -> "backdrop-1600x900";
                default -> throw new IllegalStateException("Unknown artwork kind '" + job.assetKind() + "'");
            };
            default -> throw new IllegalStateException("Unknown job kind '" + job.kind() + "'");
        };
    }

    private boolean isTerminal(String state) {
        return "SUCCEEDED".equals(state) || "FAILED".equals(state);
    }

    private UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MediaValidationException(field + " is required");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            throw new MediaValidationException(field + " must be a UUID");
        }
    }

    public record ClaimRequest(String workerId) {
    }

    public record HeartbeatRequest(String workerId, String attemptId) {
    }

    public record ClaimResponse(
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

        static ClaimResponse claimed(
                UUID attemptId, Instant leaseUntil, String kind, String source, String outputPrefix,
                UUID movieId, UUID mediaVersionId, UUID assetId, String profile) {
            return new ClaimResponse(
                    "CLAIMED", null,
                    attemptId.toString(), leaseUntil.toString(), kind, source, outputPrefix,
                    movieId.toString(), str(mediaVersionId), str(assetId), profile);
        }

        static ClaimResponse skip(String reason) {
            return new ClaimResponse("SKIP", reason, null, null, null, null, null,
                    null, null, null, null);
        }

        private static String str(UUID value) {
            return value == null ? null : value.toString();
        }
    }

    public record HeartbeatResponse(String leaseUntil) {
    }
}
