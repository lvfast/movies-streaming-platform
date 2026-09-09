package com.lvfast.streaming.catalog;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCatalogRepository implements CatalogRepository {
    static final String SEARCH_CANDIDATES = """
            select m.id
            from movie m
            where m.search_vector @@ websearch_to_tsquery('simple', ?)
            union
            select m.id
            from movie m
            where m.title % ?
            union
            select m.id
            from movie m
            where m.title ilike ? escape '\\'
            """;
    private static final String SELECT_SUMMARY = """
            select m.id, m.slug, m.title, m.release_year, m.runtime_seconds,
                   m.maturity_rating, m.poster_url, m.backdrop_url,
                   coalesce(array_agg(g.name order by g.name) filter (where g.id is not null), '{}') genres
            from movie m
            left join movie_genre mg on mg.movie_id = m.id
            left join genre g on g.id = mg.genre_id
            """;
    private static final String GROUP = " group by m.id ";

    private final JdbcTemplate jdbc;

    JdbcCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MovieSummary> featured(int limit) {
        return jdbc.query(SELECT_SUMMARY + " where m.published and m.featured " + GROUP
                + " order by m.release_year desc, m.title limit ?", this::summary, limit);
    }

    @Override
    public List<MovieSummary> newest(int limit) {
        return jdbc.query(SELECT_SUMMARY + " where m.published " + GROUP
                + " order by m.release_year desc, m.title limit ?", this::summary, limit);
    }

    @Override
    public List<MovieSummary> byGenre(String genreSlug, int limit) {
        return jdbc.query(SELECT_SUMMARY
                + " where m.published and exists (select 1 from movie_genre x join genre gx on gx.id=x.genre_id "
                + "where x.movie_id=m.id and gx.slug=?) " + GROUP
                + " order by m.release_year desc, m.title limit ?", this::summary, genreSlug, limit);
    }

    @Override
    public Optional<MovieDetails> bySlug(String slug) {
        List<MovieDetails> rows = jdbc.query("""
                select m.id, m.slug, m.title, m.release_year, m.runtime_seconds,
                       m.maturity_rating, m.poster_url, m.backdrop_url,
                       coalesce(array_agg(g.name order by g.name) filter (where g.id is not null), '{}') genres,
                       m.synopsis, (m.hls_manifest_url is not null) playable
                from movie m
                left join movie_genre mg on mg.movie_id = m.id
                left join genre g on g.id = mg.genre_id
                where m.published and m.slug=?
                group by m.id, m.synopsis, m.hls_manifest_url
                """,
                this::details, slug);
        return rows.stream().findFirst();
    }

    @Override
    public MoviePage search(String query, int page, int size) {
        String pattern = "%" + escapeLikePattern(query) + "%";
        Long total = jdbc.queryForObject("select count(*) from (" + SEARCH_CANDIDATES
                        + ") matches join movie m on m.id=matches.id where m.published",
                Long.class, query, query, pattern);
        String sql = "with matches as (" + SEARCH_CANDIDATES + ") "
                + SELECT_SUMMARY + " join matches on matches.id=m.id where m.published " + GROUP
                + " order by ts_rank(m.search_vector, websearch_to_tsquery('simple', ?)) desc, "
                + "similarity(m.title, ?) desc, m.release_year desc, m.title limit ? offset ?";
        List<MovieSummary> items = jdbc.query(sql, this::summary,
                query, query, pattern, query, query, size, (long) page * size);
        return new MoviePage(items, page, size, total == null ? 0 : total);
    }

    private String escapeLikePattern(String query) {
        return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private MovieSummary summary(ResultSet rs, int row) throws SQLException {
        return new MovieSummary(
                rs.getObject("id", java.util.UUID.class), rs.getString("slug"), rs.getString("title"),
                rs.getInt("release_year"), rs.getInt("runtime_seconds"), rs.getString("maturity_rating"),
                rs.getString("poster_url"), rs.getString("backdrop_url"), genres(rs.getArray("genres")));
    }

    private MovieDetails details(ResultSet rs, int row) throws SQLException {
        MovieSummary movie = summary(rs, row);
        return new MovieDetails(movie.id(), movie.slug(), movie.title(), movie.releaseYear(), movie.runtimeSeconds(),
                movie.maturityRating(), movie.posterUrl(), movie.backdropUrl(), movie.genres(),
                rs.getString("synopsis"), rs.getBoolean("playable"));
    }

    private List<String> genres(Array sqlArray) throws SQLException {
        if (sqlArray == null) return List.of();
        Object value = sqlArray.getArray();
        if (value instanceof String[] names) return List.copyOf(Arrays.asList(names));
        Object[] values = (Object[]) value;
        return Arrays.stream(values).map(Object::toString).toList();
    }
}
