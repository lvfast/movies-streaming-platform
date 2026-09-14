package com.lvfast.streaming.media.upload;

import tools.jackson.databind.ObjectMapper;
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
 * Upload session, media version and media asset persistence. Reads are plain JDBC; multi-statement
 * writes are invoked inside a caller-managed transaction so storage network calls stay outside any
 * database transaction.
 */
@Repository
public class JdbcUploadRepository {

    private static final String SELECT_VIEW = """
            select id, movie_id, media_version_id, asset_id, kind, state, part_size_bytes,
                   total_parts, declared_bytes, expires_at, job_id
            from upload_session
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcUploadRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<UploadSession> findView(UUID uploadId) {
        List<UploadSession> rows = jdbc.query(SELECT_VIEW + " where id=?", this::view, uploadId);
        return rows.stream().findFirst();
    }

    Optional<UploadRow> findRow(UUID uploadId) {
        List<UploadRow> rows = jdbc.query("""
                select id, movie_id, media_version_id, asset_id, kind, state, object_key,
                       storage_upload_id, content_type, resume_fingerprint, part_size_bytes,
                       total_parts, declared_bytes, expires_at, job_id
                from upload_session where id=?
                """, this::row, uploadId);
        return rows.stream().findFirst();
    }

    Optional<MovieState> findMovieState(UUID movieId) {
        List<MovieState> rows = jdbc.query(
                "select management_mode, lifecycle from movie where id=?",
                (rs, i) -> new MovieState(rs.getString("management_mode"), rs.getString("lifecycle")),
                movieId);
        return rows.stream().findFirst();
    }

    List<UploadRow> findExpiredOpenSessions(Instant now, int limit) {
        return jdbc.query("""
                select id, movie_id, media_version_id, asset_id, kind, state, object_key,
                       storage_upload_id, content_type, resume_fingerprint, part_size_bytes,
                       total_parts, declared_bytes, expires_at, job_id
                from upload_session where state='OPEN' and expires_at < ?
                order by expires_at limit ?
                """, this::row, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC), limit);
    }

    void insertTarget(UUID targetId, UUID movieId, String kind, String objectKey) {
        if ("VIDEO".equals(kind)) {
            jdbc.update("""
                    insert into media_version(id, movie_id, state, source_key)
                    values (?, ?, 'UPLOADING', ?)
                    """, targetId, movieId, objectKey);
        } else {
            jdbc.update("""
                    insert into media_asset(id, movie_id, kind, state, source_key)
                    values (?, ?, ?, 'UPLOADING', ?)
                    """, targetId, movieId, kind, objectKey);
        }
    }

    void insertSession(UploadRow row) {
        jdbc.update("""
                insert into upload_session(id, movie_id, media_version_id, asset_id, kind, state,
                                           object_key, storage_upload_id, content_type, part_size_bytes,
                                           total_parts, declared_bytes, resume_fingerprint, expires_at)
                values (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id(),
                row.movieId(),
                row.mediaVersionId(),
                row.assetId(),
                row.kind(),
                row.objectKey(),
                row.storageUploadId(),
                row.contentType(),
                row.partSizeBytes(),
                row.totalParts(),
                row.declaredBytes(),
                row.resumeFingerprint(),
                OffsetDateTime.ofInstant(row.expiresAt(), java.time.ZoneOffset.UTC));
    }

    int markCompleting(UUID uploadId) {
        return jdbc.update("""
                update upload_session set state='COMPLETING', updated_at=now()
                where id=? and state='OPEN'
                """, uploadId);
    }

    int markCompleted(UUID uploadId) {
        return jdbc.update("""
                update upload_session set state='COMPLETED', completed_at=now(), updated_at=now()
                where id=? and state='COMPLETING'
                """, uploadId);
    }

    void setJobId(UUID uploadId, UUID jobId) {
        jdbc.update("""
                update upload_session set job_id=?, updated_at=now() where id=?
                """, jobId, uploadId);
    }

    void markTargetState(UUID mediaVersionId, UUID assetId, String targetState) {
        if (mediaVersionId != null) {
            jdbc.update("""
                    update media_version set state=?, updated_at=now()
                    where id=? and state='UPLOADING'
                    """, targetState, mediaVersionId);
        }
        if (assetId != null) {
            jdbc.update("""
                    update media_asset set state=?, updated_at=now()
                    where id=? and state='UPLOADING'
                    """, targetState, assetId);
        }
    }

    int markAborted(UUID uploadId) {
        int rows = jdbc.update("""
                update upload_session set state='ABORTED', updated_at=now()
                where id=? and state='OPEN'
                """, uploadId);
        if (rows == 0) {
            return 0;
        }
        jdbc.update("""
                update media_version set state='ABORTED', updated_at=now()
                where id=(select media_version_id from upload_session where id=?) and state='UPLOADING'
                """, uploadId);
        jdbc.update("""
                update media_asset set state='ABORTED', updated_at=now()
                where id=(select asset_id from upload_session where id=?) and state='UPLOADING'
                """, uploadId);
        return rows;
    }

    void markFailed(UUID uploadId) {
        int rows = jdbc.update("""
                update upload_session set state='FAILED', updated_at=now()
                where id=? and state='COMPLETING'
                """, uploadId);
        if (rows == 0) {
            return;
        }
        jdbc.update("""
                update media_version set state='FAILED', updated_at=now()
                where id=(select media_version_id from upload_session where id=?) and state='UPLOADING'
                """, uploadId);
        jdbc.update("""
                update media_asset set state='FAILED', updated_at=now()
                where id=(select asset_id from upload_session where id=?) and state='UPLOADING'
                """, uploadId);
    }

    int markExpired(UUID uploadId) {
        int rows = jdbc.update("""
                update upload_session set state='EXPIRED', updated_at=now()
                where id=? and state='OPEN'
                """, uploadId);
        if (rows == 0) {
            return 0;
        }
        jdbc.update("""
                update media_version set state='ABORTED', updated_at=now()
                where id=(select media_version_id from upload_session where id=?) and state='UPLOADING'
                """, uploadId);
        jdbc.update("""
                update media_asset set state='ABORTED', updated_at=now()
                where id=(select asset_id from upload_session where id=?) and state='UPLOADING'
                """, uploadId);
        return rows;
    }

    Optional<UploadSession> findReplay(UUID actorId, String operation, String idempotencyKey) {
        List<String> bodies = jdbc.queryForList("""
                select response_body::text from operation_request
                where actor_id=? and operation=? and idempotency_key=?
                """, String.class, actorId, operation, idempotencyKey);
        if (bodies.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(bodies.getFirst(), UploadSession.class));
        } catch (Exception unreadable) {
            throw new IllegalStateException("Stored idempotent response cannot be replayed", unreadable);
        }
    }

    void storeReplay(UUID actorId, String operation, String idempotencyKey, int status, UploadSession view) {
        try {
            jdbc.update("""
                    insert into operation_request(actor_id, operation, idempotency_key, status_code, response_body)
                    values (?, ?, ?, ?, ?::jsonb)
                    """, actorId, operation, idempotencyKey, status, json.writeValueAsString(view));
        } catch (Exception serialization) {
            throw new IllegalStateException("Unable to store idempotent response", serialization);
        }
    }

    private UploadRow row(ResultSet rs, int i) throws SQLException {
        return new UploadRow(
                rs.getObject("id", UUID.class),
                rs.getObject("movie_id", UUID.class),
                nullable(rs, "media_version_id"),
                nullable(rs, "asset_id"),
                rs.getString("kind"),
                rs.getString("state"),
                rs.getString("object_key"),
                rs.getString("storage_upload_id"),
                rs.getString("content_type"),
                rs.getString("resume_fingerprint"),
                rs.getLong("part_size_bytes"),
                rs.getInt("total_parts"),
                rs.getLong("declared_bytes"),
                instant(rs, "expires_at"),
                nullable(rs, "job_id"));
    }

    private UploadSession view(ResultSet rs, int i) throws SQLException {
        return new UploadSession(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("movie_id", UUID.class).toString(),
                nullableString(rs, "media_version_id"),
                nullableString(rs, "asset_id"),
                rs.getString("kind"),
                rs.getString("state"),
                rs.getLong("part_size_bytes"),
                rs.getInt("total_parts"),
                rs.getLong("declared_bytes"),
                instant(rs, "expires_at").toString(),
                nullableString(rs, "job_id"));
    }

    private UUID nullable(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : (UUID) value;
    }

    private String nullableString(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : value.toString();
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record UploadRow(
            UUID id,
            UUID movieId,
            UUID mediaVersionId,
            UUID assetId,
            String kind,
            String state,
            String objectKey,
            String storageUploadId,
            String contentType,
            String resumeFingerprint,
            long partSizeBytes,
            int totalParts,
            long declaredBytes,
            Instant expiresAt,
            UUID jobId) {
    }

    record MovieState(String managementMode, String lifecycle) {
    }
}
