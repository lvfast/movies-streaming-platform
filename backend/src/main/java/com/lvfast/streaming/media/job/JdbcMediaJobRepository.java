package com.lvfast.streaming.media.job;

import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable media job rows. A committed upload creates exactly one QUEUED job. */
@Repository
public class JdbcMediaJobRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcMediaJobRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertQueued(UUID jobId, UUID movieId, UUID mediaVersionId, UUID assetId, String kind) {
        jdbc.update("""
                insert into media_job(id, movie_id, media_version_id, asset_id, kind, state)
                values (?, ?, ?, ?, ?, 'QUEUED')
                """, jobId, movieId, mediaVersionId, assetId, kind);
    }

    public Optional<JobView> findView(UUID jobId) {
        List<JobView> rows = jdbc.query("""
                select id, movie_id, media_version_id, asset_id, kind, state, attempt_number,
                       progress_percent, stage, error_code, error_summary, retry_at, updated_at
                from media_job where id=?
                """, this::view, jobId);
        return rows.stream().findFirst();
    }

    public List<JobView> listViews(int page, int size) {
        return jdbc.query("""
                select id, movie_id, media_version_id, asset_id, kind, state, attempt_number,
                       progress_percent, stage, error_code, error_summary, retry_at, updated_at
                from media_job order by created_at desc limit ? offset ?
                """, this::view, size, page * size);
    }

    public long count() {
        return jdbc.queryForObject("select count(*) from media_job", Long.class);
    }

    public Optional<JobView> findRetryReplay(UUID actorId, String operation, String idempotencyKey) {
        List<String> bodies = jdbc.queryForList("""
                select response_body::text from operation_request
                where actor_id=? and operation=? and idempotency_key=?
                """, String.class, actorId, operation, idempotencyKey);
        if (bodies.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(bodies.getFirst(), JobView.class));
        } catch (Exception unreadable) {
            throw new IllegalStateException("Stored idempotent response cannot be replayed", unreadable);
        }
    }

    public void storeRetryReplay(UUID actorId, String operation, String idempotencyKey, int status,
            JobView view) {
        try {
            jdbc.update("""
                    insert into operation_request(actor_id, operation, idempotency_key, status_code, response_body)
                    values (?, ?, ?, ?, ?::jsonb)
                    """, actorId, operation, idempotencyKey, status, json.writeValueAsString(view));
        } catch (Exception serialization) {
            throw new IllegalStateException("Unable to store idempotent response", serialization);
        }
    }

    private JobView view(ResultSet rs, int i) throws SQLException {
        return new JobView(
                nullable(rs, "id"),
                nullable(rs, "movie_id"),
                nullable(rs, "media_version_id"),
                nullable(rs, "asset_id"),
                rs.getString("kind"),
                rs.getString("state"),
                rs.getInt("attempt_number"),
                rs.getInt("progress_percent"),
                rs.getString("stage"),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                instant(rs, "retry_at"),
                instant(rs, "updated_at"));
    }

    private String nullable(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : value.toString();
    }

    private String instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant().toString();
    }
}
