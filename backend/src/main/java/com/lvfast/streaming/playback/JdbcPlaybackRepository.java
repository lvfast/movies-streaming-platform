package com.lvfast.streaming.playback;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlaybackRepository implements PlaybackRepository {
    private final JdbcTemplate jdbc;

    JdbcPlaybackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlayableMovie> playableMovie(UUID movieId) {
        List<PlayableMovie> rows = jdbc.query("""
                select m.id, m.hls_manifest_url, m.management_mode, m.active_media_version_id
                from movie m
                where m.id=? and m.published and m.hls_manifest_url is not null
                """, this::playableMovieRow, movieId);
        return rows.stream().findFirst();
    }

    private PlayableMovie playableMovieRow(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject(1, UUID.class);
        String manifestUrl = rs.getString(2);
        String managementMode = rs.getString(3);
        UUID activeMediaVersionId = rs.getObject(4, UUID.class);
        return new PlayableMovie(id, manifestUrl, managementMode, activeMediaVersionId);
    }

    @Override
    public Optional<ViewingProgress> progress(UUID userId, UUID movieId) {
        List<ViewingProgress> rows = jdbc.query("""
                select movie_id, position_seconds, duration_seconds, client_updated_at, completed
                from viewing_progress
                where user_id=? and movie_id=?
                """, this::progress, userId, movieId);
        return rows.stream().findFirst();
    }

    @Override
    public ViewingProgress save(
            UUID userId,
            UUID movieId,
            int positionSeconds,
            int durationSeconds,
            Instant clientUpdatedAt,
            boolean completed) {
        jdbc.update("""
                insert into viewing_progress(
                    user_id, movie_id, position_seconds, duration_seconds, completed, client_updated_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (user_id, movie_id) do update set
                    position_seconds=excluded.position_seconds,
                    duration_seconds=excluded.duration_seconds,
                    completed=excluded.completed,
                    client_updated_at=excluded.client_updated_at,
                    updated_at=now()
                where excluded.client_updated_at >= viewing_progress.client_updated_at
                """, userId, movieId, positionSeconds, durationSeconds, completed,
                Timestamp.from(clientUpdatedAt));
        return progress(userId, movieId)
                .orElseThrow(() -> new IllegalStateException("Progress write did not produce a stored row"));
    }

    private ViewingProgress progress(ResultSet rs, int row) throws SQLException {
        return new ViewingProgress(
                rs.getObject("movie_id", UUID.class),
                rs.getInt("position_seconds"),
                rs.getInt("duration_seconds"),
                rs.getTimestamp("client_updated_at").toInstant(),
                rs.getBoolean("completed"));
    }
}
