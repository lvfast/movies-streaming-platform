package com.lvfast.streaming.playback;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain-JDBC persistence for version-pinned playback sessions and the viewing progress they own.
 * Progress rows written by a managed session record the session and media version, so a replacement
 * activation never mixes the resume positions of two different versions.
 */
@Repository
class JdbcMediaSessionRepository implements MediaSessionRepository {

    private final JdbcTemplate jdbc;

    JdbcMediaSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredSession> findSession(UUID sessionId) {
        return querySession("select * from media_playback_session where id=?", sessionId);
    }

    @Override
    public Optional<StoredSession> findOwnedSession(UUID sessionId, UUID userId, String purpose) {
        return querySession("""
                select * from media_playback_session
                where id=? and user_id=? and purpose=?
                """, sessionId, userId, purpose);
    }

    @Override
    public Optional<UUID> activeVersionId(UUID movieId) {
        List<UUID> rows = jdbc.query("""
                select active_media_version_id from movie
                where id=? and published and lifecycle='PUBLISHED' and active_media_version_id is not null
                """, (rs, row) -> rs.getObject("active_media_version_id", UUID.class), movieId);
        return rows.stream().findFirst();
    }

    @Override
    public UUID createSession(
            UUID userId, UUID movieId, UUID mediaVersionId, String purpose, Instant expiresAt) {
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                insert into media_playback_session(id, user_id, movie_id, media_version_id, purpose, expires_at)
                values (?, ?, ?, ?, ?, ?)
                """, sessionId, userId, movieId, mediaVersionId, purpose,
                OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC));
        return sessionId;
    }

    @Override
    public void revokeSession(UUID sessionId) {
        jdbc.update("""
                update media_playback_session set revoked_at=now(), updated_at=now()
                where id=? and revoked_at is null
                """, sessionId);
    }

    /**
     * Resume position of the viewer for the pinned version. Only the unique progress row
     * {@code (user_id, movie_id)} is stored, and it records the session that last wrote it, so the
     * lookup deliberately ignores the session argument: a session created moments ago - which is
     * exactly the one a new grant just inserted - can never own a progress row yet. The media version
     * is the discriminator that keeps a replacement activation from resuming into the wrong cut.
     */
    @Override
    public int resumePosition(UUID userId, UUID movieId, UUID sessionId, UUID mediaVersionId) {
        List<Integer> rows = jdbc.query("""
                select position_seconds from viewing_progress
                where user_id=? and movie_id=? and media_version_id=? and not completed
                """, (rs, row) -> rs.getInt("position_seconds"), userId, movieId, mediaVersionId);
        return rows.stream().findFirst().orElse(0);
    }

    @Override
    public VersionedProgress saveViewerProgress(
            UUID userId,
            UUID movieId,
            UUID sessionId,
            UUID mediaVersionId,
            int positionSeconds,
            int durationSeconds,
            Instant clientUpdatedAt,
            boolean completed) {
        jdbc.update("""
                insert into viewing_progress(
                    user_id, movie_id, position_seconds, duration_seconds, completed, client_updated_at,
                    session_id, media_version_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (user_id, movie_id) do update set
                    position_seconds=excluded.position_seconds,
                    duration_seconds=excluded.duration_seconds,
                    completed=excluded.completed,
                    client_updated_at=excluded.client_updated_at,
                    session_id=excluded.session_id,
                    media_version_id=excluded.media_version_id,
                    updated_at=now()
                where excluded.client_updated_at >= viewing_progress.client_updated_at
                """, userId, movieId, positionSeconds, durationSeconds, completed,
                Timestamp.from(clientUpdatedAt), sessionId, mediaVersionId);
        return new VersionedProgress(
                movieId, positionSeconds, durationSeconds, clientUpdatedAt, completed,
                sessionId, mediaVersionId);
    }

    private Optional<StoredSession> querySession(String sql, Object... arguments) {
        List<StoredSession> rows = jdbc.query(sql, this::mapSession, arguments);
        return rows.stream().findFirst();
    }

    private StoredSession mapSession(ResultSet rs, int row) throws SQLException {
        OffsetDateTime revokedAt = rs.getObject("revoked_at", OffsetDateTime.class);
        return new StoredSession(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("movie_id", UUID.class),
                rs.getObject("media_version_id", UUID.class),
                rs.getString("purpose"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }
}
