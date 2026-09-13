package com.lvfast.streaming.media.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain-JDBC persistence for worker job claim, heartbeat and result handling. Multi-statement writes
 * are invoked inside a caller-managed transaction so storage network calls stay outside any
 * database transaction.
 */
@Repository
public class JdbcJobStateRepository {

    private final JdbcTemplate jdbc;

    public JdbcJobStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<JobRow> findJob(UUID jobId) {
        List<JobRow> rows = jdbc.query("""
                select j.id, j.movie_id, j.media_version_id, j.asset_id, j.kind, j.state,
                       j.attempt_number, j.stage, j.progress_percent, j.error_code, j.error_summary,
                       coalesce(v.source_key, a.source_key) as source_key, a.kind as asset_kind
                from media_job j
                left join media_version v on v.id = j.media_version_id
                left join media_asset a on a.id = j.asset_id
                where j.id = ?
                """, this::job, jobId);
        return rows.stream().findFirst();
    }

    /** Conditionally moves a QUEUED/RETRY_WAIT job to RUNNING and bumps its attempt number. */
    int claim(UUID jobId) {
        return jdbc.update("""
                update media_job
                set state='RUNNING', attempt_number=attempt_number+1, stage='DOWNLOADING',
                    progress_percent=0, error_code=null, error_summary=null, retry_at=null,
                    last_sequence=0, updated_at=now()
                where id=? and state in ('QUEUED','RETRY_WAIT')
                """, jobId);
    }

    int attemptNumber(UUID jobId) {
        return jdbc.queryForObject("select attempt_number from media_job where id=?", Integer.class, jobId);
    }

    void insertAttempt(UUID attemptId, UUID jobId, int attemptNumber, String workerId,
            Instant leaseUntil, String outputPrefix) {
        jdbc.update("""
                insert into media_job_attempt(id, job_id, attempt_number, worker_id, state, lease_until, output_prefix)
                values (?, ?, ?, ?, 'RUNNING', ?, ?)
                """, attemptId, jobId, attemptNumber, workerId,
                OffsetDateTime.ofInstant(leaseUntil, java.time.ZoneOffset.UTC), outputPrefix);
    }

    void markTargetProcessing(UUID mediaVersionId, UUID assetId) {
        if (mediaVersionId != null) {
            jdbc.update("""
                    update media_version set state='PROCESSING', updated_at=now()
                    where id=? and state in ('QUEUED','UPLOADING')
                    """, mediaVersionId);
        }
        if (assetId != null) {
            jdbc.update("""
                    update media_asset set state='PROCESSING', updated_at=now()
                    where id=? and state in ('STORED','UPLOADING')
                    """, assetId);
        }
    }

    Optional<AttemptRow> findAttempt(UUID attemptId) {
        List<AttemptRow> rows = jdbc.query("""
                select id, job_id, attempt_number, worker_id, state, lease_until, output_prefix, started_at, finished_at
                from media_job_attempt where id=?
                """, this::attempt, attemptId);
        return rows.stream().findFirst();
    }

    int heartbeat(UUID attemptId, UUID jobId, String workerId, Instant leaseUntil) {
        return jdbc.update("""
                update media_job_attempt set lease_until=?
                where id=? and job_id=? and worker_id=? and state='RUNNING'
                """, OffsetDateTime.ofInstant(leaseUntil, java.time.ZoneOffset.UTC),
                attemptId, jobId, workerId);
    }

    /** Returns true when this event was not already recorded. */
    boolean insertInbox(UUID eventId, String eventType, UUID jobId, UUID attemptId, long sequence, String payload) {
        return jdbc.update("""
                insert into inbox_event(event_id, event_type, job_id, attempt_id, sequence, payload)
                values (?, ?, ?, ?, ?, ?::jsonb)
                on conflict (event_id) do nothing
                """, eventId, eventType, jobId, attemptId, sequence, payload) == 1;
    }

    void markInboxProcessed(UUID eventId) {
        jdbc.update("update inbox_event set processed_at=now() where event_id=?", eventId);
    }

    /** Applies a progress event only when its sequence is higher than anything already applied. */
    int updateProgress(UUID jobId, String stage, int percent, long sequence) {
        return jdbc.update("""
                update media_job set stage=?, progress_percent=?, last_sequence=?, updated_at=now()
                where id=? and state='RUNNING' and last_sequence < ?
                """, stage, percent, sequence, jobId, sequence);
    }

    void markSucceeded(UUID jobId, UUID attemptId, UUID mediaVersionId, UUID assetId) {
        jdbc.update("""
                update media_job set state='SUCCEEDED', stage='VALIDATING', progress_percent=100, updated_at=now()
                where id=? and state='RUNNING'
                """, jobId);
        jdbc.update("""
                update media_job_attempt set state='SUCCEEDED', finished_at=now()
                where id=? and job_id=? and state='RUNNING'
                """, attemptId, jobId);
        markTargetReady(mediaVersionId, assetId);
    }

    void markFailed(UUID jobId, UUID attemptId, String errorCode, String errorSummary,
            UUID mediaVersionId, UUID assetId) {
        jdbc.update("""
                update media_job set state='FAILED', error_code=?, error_summary=?, updated_at=now()
                where id=? and state='RUNNING'
                """, errorCode, errorSummary, jobId);
        jdbc.update("""
                update media_job_attempt set state='FAILED', finished_at=now()
                where id=? and job_id=? and state='RUNNING'
                """, attemptId, jobId);
        markTargetFailed(mediaVersionId, assetId);
    }

    /**
     * Moves a RUNNING job to RETRY_WAIT after a transient failure and closes its attempt. The target
     * stays PROCESSING because the source/output are retained and another attempt is already
     * scheduled; only a terminal failure moves the target to FAILED.
     */
    void markRetryWait(UUID jobId, UUID attemptId, String errorCode, String errorSummary,
            Instant retryAt) {
        jdbc.update("""
                update media_job set state='RETRY_WAIT', error_code=?, error_summary=?, retry_at=?,
                    updated_at=now()
                where id=? and state='RUNNING'
                """, errorCode, errorSummary,
                OffsetDateTime.ofInstant(retryAt, java.time.ZoneOffset.UTC), jobId);
        jdbc.update("""
                update media_job_attempt set state='FAILED', finished_at=now()
                where id=? and job_id=? and state='RUNNING'
                """, attemptId, jobId);
    }

    List<ExpiredLeaseRow> findExpiredLeases(Instant now, int limit) {
        return jdbc.query("""
                select a.id as attempt_id, a.job_id, j.attempt_number,
                       j.media_version_id, j.asset_id
                from media_job_attempt a
                join media_job j on j.id = a.job_id
                where a.state='RUNNING' and j.state='RUNNING' and a.lease_until < ?
                order by a.lease_until limit ?
                """, this::expiredLease,
                OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC), limit);
    }

    List<DueRetryRow> findDueRetries(Instant now, int limit) {
        return jdbc.query("""
                select id, kind from media_job
                where state='RETRY_WAIT' and retry_at <= ?
                order by retry_at limit ?
                """, this::dueRetry,
                OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC), limit);
    }

    /** Conditionally moves a due RETRY_WAIT job back to QUEUED so a later claim can win it. */
    int markQueued(UUID jobId) {
        return jdbc.update("""
                update media_job set state='QUEUED', retry_at=null, stage=null,
                    error_code=null, error_summary=null, updated_at=now()
                where id=? and state='RETRY_WAIT'
                """, jobId);
    }

    /** Resets a terminal target so a manually retried job can be claimed and completed again. */
    void resetTargetForRetry(UUID mediaVersionId, UUID assetId) {
        if (mediaVersionId != null) {
            jdbc.update("""
                    update media_version set state='QUEUED', updated_at=now()
                    where id=? and state='FAILED'
                    """, mediaVersionId);
        }
        if (assetId != null) {
            jdbc.update("""
                    update media_asset set state='STORED', updated_at=now()
                    where id=? and state='FAILED'
                    """, assetId);
        }
    }

    /**
     * Records the verified duration of a READY transcode output so publication can project a runtime
     * without re-reading the artifact manifest.
     */
    void recordVerifiedDuration(UUID mediaVersionId, int durationSeconds) {
        if (mediaVersionId == null || durationSeconds <= 0) {
            return;
        }
        jdbc.update("""
                update media_version set duration_seconds=?, updated_at=now()
                where id=? and state='READY' and duration_seconds is null
                """, durationSeconds, mediaVersionId);
    }

    private void markTargetReady(UUID mediaVersionId, UUID assetId) {
        if (mediaVersionId != null) {
            jdbc.update("""
                    update media_version set state='READY', updated_at=now()
                    where id=? and state='PROCESSING'
                    """, mediaVersionId);
        }
        if (assetId != null) {
            jdbc.update("""
                    update media_asset set state='READY', updated_at=now()
                    where id=? and state='PROCESSING'
                    """, assetId);
        }
    }

    private void markTargetFailed(UUID mediaVersionId, UUID assetId) {
        if (mediaVersionId != null) {
            jdbc.update("""
                    update media_version set state='FAILED', updated_at=now()
                    where id=? and state in ('PROCESSING','QUEUED')
                    """, mediaVersionId);
        }
        if (assetId != null) {
            jdbc.update("""
                    update media_asset set state='FAILED', updated_at=now()
                    where id=? and state in ('PROCESSING','STORED')
                    """, assetId);
        }
    }

    private JobRow job(ResultSet rs, int i) throws SQLException {
        return new JobRow(
                rs.getObject("id", UUID.class),
                rs.getObject("movie_id", UUID.class),
                nullable(rs, "media_version_id"),
                nullable(rs, "asset_id"),
                rs.getString("kind"),
                rs.getString("state"),
                rs.getInt("attempt_number"),
                rs.getString("stage"),
                rs.getInt("progress_percent"),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                rs.getString("source_key"),
                rs.getString("asset_kind"));
    }

    private AttemptRow attempt(ResultSet rs, int i) throws SQLException {
        return new AttemptRow(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getInt("attempt_number"),
                rs.getString("worker_id"),
                rs.getString("state"),
                rs.getObject("lease_until", OffsetDateTime.class).toInstant(),
                rs.getString("output_prefix"));
    }

    private ExpiredLeaseRow expiredLease(ResultSet rs, int i) throws SQLException {
        return new ExpiredLeaseRow(
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getInt("attempt_number"),
                nullable(rs, "media_version_id"),
                nullable(rs, "asset_id"));
    }

    private DueRetryRow dueRetry(ResultSet rs, int i) throws SQLException {
        return new DueRetryRow(
                rs.getObject("id", UUID.class),
                rs.getString("kind"));
    }

    private UUID nullable(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : (UUID) value;
    }

    record JobRow(
            UUID id,
            UUID movieId,
            UUID mediaVersionId,
            UUID assetId,
            String kind,
            String state,
            int attemptNumber,
            String stage,
            int progressPercent,
            String errorCode,
            String errorSummary,
            String sourceKey,
            String assetKind) {
    }

    record AttemptRow(
            UUID id,
            UUID jobId,
            int attemptNumber,
            String workerId,
            String state,
            Instant leaseUntil,
            String outputPrefix) {
    }

    record ExpiredLeaseRow(
            UUID attemptId,
            UUID jobId,
            int attemptNumber,
            UUID mediaVersionId,
            UUID assetId) {
    }

    record DueRetryRow(
            UUID jobId,
            String kind) {
    }
}
