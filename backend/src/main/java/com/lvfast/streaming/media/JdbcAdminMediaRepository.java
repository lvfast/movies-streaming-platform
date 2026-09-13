package com.lvfast.streaming.media;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only admin projections for media versions and artwork assets. */
@Repository
public class JdbcAdminMediaRepository {

    private final JdbcTemplate jdbc;

    public JdbcAdminMediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean movieExists(UUID movieId) {
        Integer count = jdbc.queryForObject("select count(*) from movie where id=?", Integer.class, movieId);
        return count != null && count > 0;
    }

    List<MediaVersionView> listVersions(UUID movieId, int page, int size) {
        return jdbc.query("""
                select id, movie_id, state, created_at, updated_at
                from media_version where movie_id=?
                order by created_at desc limit ? offset ?
                """, this::version, movieId, size, page * size);
    }

    long countVersions(UUID movieId) {
        return jdbc.queryForObject("select count(*) from media_version where movie_id=?", Long.class, movieId);
    }

    List<MediaAssetView> listAssets(UUID movieId, int page, int size) {
        return jdbc.query("""
                select id, movie_id, kind, state, created_at, updated_at
                from media_asset where movie_id=?
                order by created_at desc limit ? offset ?
                """, this::asset, movieId, size, page * size);
    }

    long countAssets(UUID movieId) {
        return jdbc.queryForObject("select count(*) from media_asset where movie_id=?", Long.class, movieId);
    }

    private MediaVersionView version(ResultSet rs, int i) throws SQLException {
        return new MediaVersionView(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("movie_id", UUID.class).toString(),
                rs.getString("state"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private MediaAssetView asset(ResultSet rs, int i) throws SQLException {
        return new MediaAssetView(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("movie_id", UUID.class).toString(),
                rs.getString("kind"),
                rs.getString("state"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private String instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant().toString();
    }
}
