package com.lvfast.streaming.library;

import com.lvfast.streaming.catalog.MoviePage;
import com.lvfast.streaming.catalog.MovieSummary;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLibraryRepository implements LibraryRepository {
    private static final String SELECT_SUMMARY = """
            select m.id, m.slug, m.title, m.release_year, m.runtime_seconds,
                   m.maturity_rating, m.poster_url, m.backdrop_url,
                   coalesce(array_agg(g.name order by g.name) filter (where g.id is not null), '{}') genres
            from watchlist w
            join movie m on m.id=w.movie_id
            left join movie_genre mg on mg.movie_id=m.id
            left join genre g on g.id=mg.genre_id
            where w.user_id=? and m.published
            group by m.id, w.added_at
            order by w.added_at desc, m.title
            limit ? offset ?
            """;

    private final JdbcTemplate jdbc;

    JdbcLibraryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MoviePage list(UUID userId, int page, int size) {
        Long total = jdbc.queryForObject("""
                select count(*)
                from watchlist w
                join movie m on m.id=w.movie_id
                where w.user_id=? and m.published
                """, Long.class, userId);
        List<MovieSummary> items = jdbc.query(
                SELECT_SUMMARY, this::summary, userId, size, (long) page * size);
        return new MoviePage(items, page, size, total == null ? 0 : total);
    }

    @Override
    public boolean add(UUID userId, UUID movieId) {
        Integer rows = jdbc.queryForObject("""
                with candidate as (
                    select id from movie where id=? and published
                ), upserted as (
                    insert into watchlist(user_id, movie_id)
                    select ?, id from candidate
                    on conflict (user_id, movie_id) do update set added_at=watchlist.added_at
                    returning movie_id
                )
                select count(*) from upserted
                """, Integer.class, movieId, userId);
        return rows != null && rows == 1;
    }

    @Override
    public void remove(UUID userId, UUID movieId) {
        jdbc.update("delete from watchlist where user_id=? and movie_id=?", userId, movieId);
    }

    private MovieSummary summary(ResultSet rs, int row) throws SQLException {
        return new MovieSummary(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("title"),
                rs.getInt("release_year"),
                rs.getInt("runtime_seconds"),
                rs.getString("maturity_rating"),
                rs.getString("poster_url"),
                rs.getString("backdrop_url"),
                genres(rs.getArray("genres")));
    }

    private List<String> genres(Array sqlArray) throws SQLException {
        if (sqlArray == null) return List.of();
        Object value = sqlArray.getArray();
        if (value instanceof String[] names) return List.copyOf(Arrays.asList(names));
        return Arrays.stream((Object[]) value).map(Object::toString).toList();
    }
}
