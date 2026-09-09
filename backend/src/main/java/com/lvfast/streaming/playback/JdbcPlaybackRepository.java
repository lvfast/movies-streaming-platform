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
                select id, hls_manifest_url
                from movie
                where id=? and published and hls_manifest_url is not null
                """, (rs, row) -> new PlayableMovie(
                rs.getObject("id", UUID.class), rs.getString("hls_manifest_url")), movieId);
        return rows.stream().findFirst();
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
