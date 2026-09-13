package com.lvfast.streaming.administration;

import tools.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdminMovieRepository {

    private static final String SELECT_VIEW = """
            select m.id, m.slug, m.title, m.synopsis, m.release_year, m.runtime_seconds,
                   m.maturity_rating, m.featured, m.lifecycle, m.revision, m.management_mode,
                   m.active_media_version_id, m.poster_asset_id, m.backdrop_asset_id,
                   m.first_published_at, m.created_at, m.updated_at,
                   coalesce(array_agg(mg.genre_id::int order by mg.genre_id)
                            filter (where mg.genre_id is not null), '{}') genre_ids
            from movie m
            left join movie_genre mg on mg.movie_id = m.id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcAdminMovieRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<AdminMovieView> findView(UUID movieId) {
        List<AdminMovieView> rows = jdbc.query(
                SELECT_VIEW + " where m.id=? group by m.id", this::view, movieId);
        return rows.stream().findFirst();
    }

    Optional<AdminMovieRow> findRow(UUID movieId) {
        List<AdminMovieRow> rows = jdbc.query("""
                select id, slug, title, synopsis, release_year, maturity_rating, featured,
                       revision, management_mode, lifecycle, published
                from movie where id=?
                """, this::row, movieId);
        return rows.stream().findFirst();
    }

    AdminMoviePage list(int page, int size) {
        Long total = jdbc.queryForObject("select count(*) from movie", Long.class);
        List<AdminMovieView> items = jdbc.query(
                SELECT_VIEW + " group by m.id order by m.updated_at desc, m.id limit ? offset ?",
                this::view, size, (long) page * size);
        return new AdminMoviePage(items, page, size, total == null ? 0 : total);
    }

    void insertMovie(UUID movieId, AdminMovieInput input) {
        jdbc.update("""
                insert into movie(id, slug, title, synopsis, release_year, runtime_seconds,
                                  maturity_rating, poster_url, backdrop_url, hls_manifest_url,
                                  featured, published, management_mode, lifecycle, revision)
                values (?, ?, ?, ?, ?, null, ?, null, null, null, ?, false, 'MANAGED', 'DRAFT', 0)
                """,
                movieId,
                input.slug(),
                input.title(),
                input.synopsis(),
                input.releaseYear(),
                input.maturityRating(),
                input.featured());
    }

    int updateCas(UUID movieId, long expectedRevision, AdminMovieInput input) {
        return jdbc.update("""
                update movie
                set slug=?, title=?, synopsis=?, release_year=?, maturity_rating=?, featured=?,
                    management_mode='MANAGED', revision=revision+1, updated_at=now()
                where id=? and revision=?
                """,
                input.slug(),
                input.title(),
                input.synopsis(),
                input.releaseYear(),
                input.maturityRating(),
                input.featured(),
                movieId,
                expectedRevision);
    }

    void replaceGenres(UUID movieId, List<Integer> genreIds) {
        jdbc.update("delete from movie_genre where movie_id=?", movieId);
        for (Integer genreId : genreIds) {
            jdbc.update("insert into movie_genre(movie_id, genre_id) values (?, ?)",
                    movieId, genreId.shortValue());
        }
    }

    List<GenreOption> listGenres() {
        return jdbc.query("select id, slug, name from genre order by name",
                (rs, i) -> new GenreOption(rs.getInt("id"), rs.getString("slug"), rs.getString("name")));
    }

    Set<Integer> existingGenreIds() {
        return jdbc.query("select id from genre", rs -> {
            Set<Integer> ids = new LinkedHashSet<>();
            while (rs.next()) {
                ids.add(rs.getInt("id"));
            }
            return ids;
        });
    }

    /**
     * Moves a movie projection to PUBLISHED and writes the server-owned projection in one statement.
     * The revision is compared inside the statement so a concurrent editor cannot be overwritten.
     * Only a READY active version whose movie/state still match is accepted by the caller.
     */
    int publishCas(
            UUID movieId,
            long expectedRevision,
            String hlsManifestUrl,
            String posterUrl,
            String backdropUrl,
            UUID activeVersionId,
            UUID posterAssetId,
            UUID backdropAssetId,
            int runtimeSeconds) {
        return jdbc.update("""
                update movie
                set lifecycle='PUBLISHED', published=true,
                    hls_manifest_url=?, poster_url=?, backdrop_url=?,
                    active_media_version_id=?, poster_asset_id=?, backdrop_asset_id=?,
                    runtime_seconds=?, first_published_at=coalesce(first_published_at, now()),
                    revision=revision+1, updated_at=now()
                where id=? and revision=? and management_mode='MANAGED'
                """,
                hlsManifestUrl, posterUrl, backdropUrl,
                activeVersionId, posterAssetId, backdropAssetId,
                runtimeSeconds, movieId, expectedRevision);
    }

    /** Atomically re-points an already published movie at a newly READY replacement version. */
    int activateCas(
            UUID movieId,
            long expectedRevision,
            String hlsManifestUrl,
            UUID activeVersionId,
            int runtimeSeconds) {
        return jdbc.update("""
                update movie
                set hls_manifest_url=?, active_media_version_id=?, runtime_seconds=?,
                    revision=revision+1, updated_at=now()
                where id=? and revision=? and lifecycle='PUBLISHED'
                """,
                hlsManifestUrl, activeVersionId, runtimeSeconds, movieId, expectedRevision);
    }

    /**
     * Revision-checked artwork selection. Only the pointer for the requested kind moves; the other
     * kind keeps its previous value.
     */
    int attachArtworkCas(
            UUID movieId, long expectedRevision, String kind, UUID assetId, String publicUrl) {
        boolean poster = "POSTER".equals(kind);
        boolean backdrop = !poster;
        return jdbc.update("""
                update movie
                set poster_asset_id = case when ? then ? else poster_asset_id end,
                    backdrop_asset_id = case when ? then ? else backdrop_asset_id end,
                    poster_url = case when ? then ? else poster_url end,
                    backdrop_url = case when ? then ? else backdrop_url end,
                    revision=revision+1, updated_at=now()
                where id=? and revision=?
                """,
                poster, assetId,
                backdrop, assetId,
                poster, publicUrl,
                backdrop, publicUrl,
                movieId, expectedRevision);
    }

    int updateLifecycleCas(UUID movieId, long expectedRevision, String lifecycle, boolean published) {
        return jdbc.update("""
                update movie
                set lifecycle=?, published=?, revision=revision+1, updated_at=now()
                where id=? and revision=?
                """,
                lifecycle, published, movieId, expectedRevision);
    }

    /** The READY media version currently acting as the movie's active replacement, if any. */
    Optional<UUID> activeVersionId(UUID movieId) {
        List<UUID> rows = jdbc.query("select active_media_version_id from movie where id=?",
                (rs, i) -> rs.getObject("active_media_version_id", UUID.class), movieId);
        return rows.stream().findFirst().filter(java.util.Objects::nonNull);
    }

    long revisionOf(UUID movieId) {
        Long revision = jdbc.queryForObject("select revision from movie where id=?", Long.class, movieId);
        return revision == null ? 0 : revision;
    }

    Optional<AdminMovieView> findReplay(UUID actorId, String operation, String idempotencyKey) {
        return findReceipt(actorId, operation, idempotencyKey, AdminMovieView.class);
    }

    <T> Optional<T> findReceipt(
            UUID actorId, String operation, String idempotencyKey, Class<T> responseType) {
        List<String> bodies = jdbc.queryForList("""
                select response_body::text from operation_request
                where actor_id=? and operation=? and idempotency_key=?
                """, String.class, actorId, operation, idempotencyKey);
        if (bodies.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(bodies.getFirst(), responseType));
        } catch (Exception unreadable) {
            throw new IllegalStateException("Stored idempotent response cannot be replayed", unreadable);
        }
    }

    <T> void storeReceipt(
            UUID actorId, String operation, String idempotencyKey, int status, T response) {
        try {
            jdbc.update("""
                    insert into operation_request(actor_id, operation, idempotency_key, status_code, response_body)
                    values (?, ?, ?, ?, ?::jsonb)
                    """,
                    actorId, operation, idempotencyKey, status, json.writeValueAsString(response));
        } catch (Exception serialization) {
            throw new IllegalStateException("Unable to store idempotent response", serialization);
        }
    }

    void storeReplay(UUID actorId, String operation, String idempotencyKey, int status, AdminMovieView view) {
        storeReceipt(actorId, operation, idempotencyKey, status, view);
    }

    boolean hasGenres(UUID movieId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from movie_genre where movie_id=?", Integer.class, movieId);
        return count != null && count > 0;
    }

    private AdminMovieRow row(ResultSet rs, int i) throws SQLException {
        return new AdminMovieRow(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("synopsis"),
                rs.getInt("release_year"),
                rs.getString("maturity_rating"),
                rs.getBoolean("featured"),
                rs.getLong("revision"),
                rs.getString("management_mode"),
                rs.getString("lifecycle"),
                rs.getBoolean("published"));
    }

    private AdminMovieView view(ResultSet rs, int i) throws SQLException {
        return new AdminMovieView(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("title"),
                rs.getString("slug"),
                rs.getString("synopsis"),
                rs.getInt("release_year"),
                rs.getString("maturity_rating"),
                genreIds(rs.getArray("genre_ids")),
                rs.getBoolean("featured"),
                rs.getString("lifecycle"),
                rs.getLong("revision"),
                rs.getString("management_mode"),
                nullableString(rs, "active_media_version_id"),
                nullableString(rs, "poster_asset_id"),
                nullableString(rs, "backdrop_asset_id"),
                rs.getObject("runtime_seconds", Integer.class),
                instant(rs, "first_published_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private List<Integer> genreIds(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object raw = sqlArray.getArray();
        if (raw instanceof int[] values) {
            return Arrays.stream(values).boxed().toList();
        }
        if (raw instanceof short[] values) {
            List<Integer> result = new ArrayList<>(values.length);
            for (short value : values) {
                result.add((int) value);
            }
            return List.copyOf(result);
        }
        Object[] values = (Object[]) raw;
        return Arrays.stream(values).map(v -> ((Number) v).intValue()).toList();
    }

    private String nullableString(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : value.toString();
    }

    private String instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant().toString();
    }

    record AdminMovieRow(
            UUID id,
            String slug,
            String title,
            String synopsis,
            int releaseYear,
            String maturityRating,
            boolean featured,
            long revision,
            String managementMode,
            String lifecycle,
            boolean published) {
    }
}
