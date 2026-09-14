package com.lvfast.streaming.media.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.media.job.JobClaimService.ClaimRequest;
import com.lvfast.streaming.media.job.JobClaimService.ClaimResponse;
import com.lvfast.streaming.ops.AdminRoleCommand;
import com.lvfast.streaming.support.ApiTestSupport;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P4 focused recovery tests. Job rows are inserted directly so the tests exercise the job state
 * machine and result fencing without repeating the multipart upload journey (covered by P2/P3).
 * RabbitMQ is not needed here: the recovery paths under test write durable rows and are invoked
 * directly, so the AMQP listener is disabled to keep the context free of broker dependencies.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProcessingReliabilityTest extends ApiTestSupport {

    @DynamicPropertySource
    static void mediaRecovery(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("app.media.outbox.poll-interval-ms", () -> "600000");
        registry.add("app.media.recovery.poll-interval-ms", () -> "600000");
        registry.add("app.media.recovery.initial-delay-ms", () -> "600000");
    }

    @Autowired JobRetryPolicy policy;
    @Autowired JobClaimService claims;
    @Autowired MediaResultService results;
    @Autowired JobRecoveryScheduler recovery;
    @Autowired AdminRoleCommand roleCommand;

    @BeforeEach
    void cleanMedia() {
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from inbox_event");
        jdbc.update("delete from media_job_attempt");
        jdbc.update("delete from upload_session");
        jdbc.update("delete from media_job");
        jdbc.update("delete from media_asset");
        jdbc.update("delete from media_version");
        jdbc.update("delete from movie where management_mode='MANAGED'");
    }

    @Test
    void retryPolicyIsBoundedAndTransientOnly() {
        assertThat(policy.delayFor(1, "STORAGE_UNAVAILABLE")).contains(Duration.ofSeconds(60));
        assertThat(policy.delayFor(2, "STORAGE_UNAVAILABLE")).contains(Duration.ofSeconds(300));
        assertThat(policy.delayFor(3, "STORAGE_UNAVAILABLE")).isEmpty();
        assertThat(policy.delayFor(1, "SOURCE_INVALID")).isEmpty();
        assertThat(policy.delayFor(2, "PROCESS_TIMEOUT")).contains(Duration.ofSeconds(300));
    }

    @Test
    void expiredLeaseMovesTransientJobToRetryWait() {
        UUID jobId = createQueuedJob();
        ClaimResponse claim = claims.claim(jobId, new ClaimRequest("worker-1"));
        assertThat(claim.disposition()).isEqualTo("CLAIMED");
        UUID attemptId = UUID.fromString(claim.attemptId());
        jdbc.update("update media_job_attempt set lease_until = now() - interval '1 hour' where id=?",
                attemptId);

        assertThat(recovery.expireLeases()).isEqualTo(1);

        assertThat(jobState(jobId)).isEqualTo("RETRY_WAIT");
        assertThat(attemptState(attemptId)).isEqualTo("FAILED");
        assertThat(versionState(jobId)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject(
                "select retry_at from media_job where id=?", OffsetDateTime.class, jobId))
                .isAfter(OffsetDateTime.now());
    }

    @Test
    void expiredLeaseAfterAttemptBudgetIsTerminal() {
        UUID jobId = createQueuedJob();
        ClaimResponse claim = claims.claim(jobId, new ClaimRequest("worker-1"));
        UUID attemptId = UUID.fromString(claim.attemptId());
        jdbc.update("update media_job set attempt_number=3 where id=?", jobId);
        jdbc.update("update media_job_attempt set lease_until = now() - interval '1 hour' where id=?",
                attemptId);

        assertThat(recovery.expireLeases()).isEqualTo(1);

        assertThat(jobState(jobId)).isEqualTo("FAILED");
        assertThat(attemptState(attemptId)).isEqualTo("FAILED");
        assertThat(versionState(jobId)).isEqualTo("FAILED");
    }

    @Test
    void dueRetryBecomesQueuedWithOneOutboxCommand() {
        UUID jobId = createQueuedJob();
        ClaimResponse claim = claims.claim(jobId, new ClaimRequest("worker-1"));
        UUID attemptId = UUID.fromString(claim.attemptId());
        jdbc.update("update media_job_attempt set lease_until = now() - interval '1 hour' where id=?",
                attemptId);
        recovery.expireLeases();
        assertThat(jobState(jobId)).isEqualTo("RETRY_WAIT");
        jdbc.update("update media_job set retry_at = now() - interval '1 hour' where id=?", jobId);

        assertThat(recovery.enqueueDueRetries()).isEqualTo(1);

        assertThat(jobState(jobId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where job_id=?",
                Integer.class, jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select event_type from outbox_event where job_id=?", String.class, jobId))
                .isEqualTo("transcode.requested.v1");
    }

    @Test
    void duplicateResultEventIdIsAppliedOnce() {
        UUID jobId = createQueuedJob();
        ClaimResponse claim = claims.claim(jobId, new ClaimRequest("worker-1"));
        String attemptId = claim.attemptId();
        String eventId = UUID.randomUUID().toString();
        String event = resultEvent("media.failed.v1", jobId, attemptId, 1, eventId,
                "{\"code\":\"SOURCE_INVALID\",\"summary\":\"bad source\"}");

        results.handle(event);
        results.handle(event);

        assertThat(jobState(jobId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from inbox_event where event_id=?::uuid",
                Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void lowerSequenceProgressDoesNotRegressRunningState() {
        UUID jobId = createQueuedJob();
        ClaimResponse claim = claims.claim(jobId, new ClaimRequest("worker-1"));
        String attemptId = claim.attemptId();

        results.handle(resultEvent("media.progress.v1", jobId, attemptId, 2, UUID.randomUUID().toString(),
                "{\"stage\":\"ENCODING\",\"percent\":40}"));
        results.handle(resultEvent("media.progress.v1", jobId, attemptId, 1, UUID.randomUUID().toString(),
                "{\"stage\":\"DOWNLOADING\",\"percent\":5}"));

        assertThat(jobState(jobId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select progress_percent from media_job where id=?",
                Integer.class, jobId)).isEqualTo(40);
        assertThat(jdbc.queryForObject("select stage from media_job where id=?",
                String.class, jobId)).isEqualTo("ENCODING");
    }

    @Test
    void oldAttemptEventCannotRegressCurrentAttempt() {
        UUID jobId = createQueuedJob();
        ClaimResponse first = claims.claim(jobId, new ClaimRequest("worker-1"));
        UUID firstAttempt = UUID.fromString(first.attemptId());
        jdbc.update("update media_job_attempt set lease_until = now() - interval '1 hour' where id=?",
                firstAttempt);
        recovery.expireLeases();
        jdbc.update("update media_job set retry_at = now() - interval '1 hour' where id=?", jobId);
        recovery.enqueueDueRetries();

        ClaimResponse second = claims.claim(jobId, new ClaimRequest("worker-2"));
        assertThat(second.disposition()).isEqualTo("CLAIMED");
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());

        results.handle(resultEvent("media.failed.v1", jobId, first.attemptId(), 2,
                UUID.randomUUID().toString(),
                "{\"code\":\"STORAGE_UNAVAILABLE\",\"summary\":\"late old-attempt failure\"}"));

        assertThat(jobState(jobId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select attempt_number from media_job where id=?",
                Integer.class, jobId)).isEqualTo(2);
    }

    @Test
    void adminRetriesTerminalFailedJobOncePerIdempotencyKey() {
        String token = registerAdmin("p4r" + UUID.randomUUID().toString().substring(0, 8));
        UUID jobId = createFailedJob();
        String key = "retry-" + UUID.randomUUID();

        Result first = postNoBody("/api/v1/admin/jobs/" + jobId + "/retry", token,
                java.util.Map.of("Idempotency-Key", key));
        assertThat(first.status()).isEqualTo(201);
        UUID newJobId = idOf(bodyOf(first));
        assertThat(newJobId).isNotEqualTo(jobId);
        assertThat(jobState(newJobId)).isEqualTo("QUEUED");
        assertThat(versionState(newJobId)).isEqualTo("QUEUED");

        Result replay = postNoBody("/api/v1/admin/jobs/" + jobId + "/retry", token,
                java.util.Map.of("Idempotency-Key", key));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(idOf(bodyOf(replay))).isEqualTo(newJobId);

        Result conflict = postNoBody("/api/v1/admin/jobs/" + jobId + "/retry", token,
                java.util.Map.of("Idempotency-Key", "retry-other-" + UUID.randomUUID()));
        assertThat(conflict.status()).isEqualTo(409);
    }

    private UUID createQueuedJob() {
        UUID movieId = createMovie();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                insert into media_version(id, movie_id, state, source_key)
                values (?, ?, 'QUEUED', ?)
                """, versionId, movieId, "source/" + movieId + "/" + versionId + "/original");
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into media_job(id, movie_id, media_version_id, kind, state)
                values (?, ?, ?, 'TRANSCODE', 'QUEUED')
                """, jobId, movieId, versionId);
        return jobId;
    }

    private UUID createFailedJob() {
        UUID movieId = createMovie();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                insert into media_version(id, movie_id, state, source_key)
                values (?, ?, 'FAILED', ?)
                """, versionId, movieId, "source/" + movieId + "/" + versionId + "/original");
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into media_job(id, movie_id, media_version_id, kind, state, error_code)
                values (?, ?, ?, 'TRANSCODE', 'FAILED', 'SOURCE_INVALID')
                """, jobId, movieId, versionId);
        return jobId;
    }

    private UUID createMovie() {
        UUID movieId = UUID.randomUUID();
        jdbc.update("""
                insert into movie(id, slug, title, synopsis, release_year, maturity_rating,
                                  management_mode, lifecycle)
                values (?, ?, 'Reliability', 'Reliability', 2026, 'PG', 'MANAGED', 'DRAFT')
                """, movieId, "p4-" + movieId.toString().substring(0, 8));
        return movieId;
    }

    private String registerAdmin(String username) {
        String token = registerAndToken(username);
        roleCommand.grantAdmin(username);
        return token;
    }

    private String jobState(UUID jobId) {
        return jdbc.queryForObject("select state from media_job where id=?", String.class, jobId);
    }

    private String attemptState(UUID attemptId) {
        return jdbc.queryForObject(
                "select state from media_job_attempt where id=?", String.class, attemptId);
    }

    private String versionState(UUID jobId) {
        return jdbc.queryForObject(
                "select state from media_version where id=(select media_version_id from media_job where id=?)",
                String.class, jobId);
    }

    private String resultEvent(String type, UUID jobId, String attemptId, long sequence,
            String eventId, String payload) {
        return "{\"schemaVersion\":1,\"eventId\":\"" + eventId + "\",\"type\":\"" + type
                + "\",\"jobId\":\"" + jobId + "\",\"attemptId\":\"" + attemptId
                + "\",\"sequence\":" + sequence + ",\"occurredAt\":\"2026-09-12T10:00:00Z\","
                + "\"payload\":" + payload + "}";
    }
}
