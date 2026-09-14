package com.lvfast.streaming.administration;

import com.lvfast.streaming.audit.AuditService;
import com.lvfast.streaming.catalog.CatalogRevisionRepository;
import com.lvfast.streaming.catalog.MovieNotFoundException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminMovieService {

    private static final Set<String> MATURITY_RATINGS =
            Set.of("G", "PG", "PG-13", "R", "NC-17", "NR");
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final String MOVIE_CREATE = "MOVIE_CREATE";

    private final JdbcAdminMovieRepository movies;
    private final AuditService audit;
    private final CatalogRevisionRepository catalogRevisions;

    public AdminMovieService(
            JdbcAdminMovieRepository movies,
            AuditService audit,
            CatalogRevisionRepository catalogRevisions) {
        this.movies = movies;
        this.audit = audit;
        this.catalogRevisions = catalogRevisions;
    }

    @Transactional
    public AdminMovieView create(UUID actorId, String idempotencyKey, String requestId, AdminMovieInput input) {
        AdminMovieInput normalized = validate(input);
        requireIdempotencyKey(idempotencyKey);
        Optional<AdminMovieView> replay = movies.findReplay(actorId, MOVIE_CREATE, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        UUID movieId = UUID.randomUUID();
        try {
            movies.insertMovie(movieId, normalized);
        } catch (DataIntegrityViolationException duplicateSlug) {
            throw new SlugUnavailableException(normalized.slug());
        }
        movies.replaceGenres(movieId, normalized.genreIds());
        AdminMovieView view = movies.findView(movieId).orElseThrow();
        movies.storeReplay(actorId, MOVIE_CREATE, idempotencyKey, 201, view);
        audit.record(actorId, "USER", "MOVIE_CREATED", "MOVIE", movieId, requestId, Map.of(), after(view));
        catalogRevisions.bump();
        return view;
    }

    @Transactional
    public AdminMovieView update(
            UUID actorId, UUID movieId, String ifMatch, String requestId, AdminMovieInput input) {
        AdminMovieInput normalized = validate(input);
        var current = movies.findRow(movieId).orElseThrow(() -> new MovieNotFoundException(movieId));
        long expected = parseIfMatch(ifMatch);
        if (expected != current.revision()) {
            throw new StaleRevisionException(expected, current.revision());
        }
        int rows;
        try {
            rows = movies.updateCas(movieId, expected, normalized);
        } catch (DataIntegrityViolationException duplicateSlug) {
            throw new SlugUnavailableException(normalized.slug());
        }
        if (rows == 0) {
            throw new StaleRevisionException(expected, current.revision());
        }
        movies.replaceGenres(movieId, normalized.genreIds());
        AdminMovieView view = movies.findView(movieId).orElseThrow();
        audit.record(
                actorId,
                "USER",
                "MOVIE_UPDATED",
                "MOVIE",
                movieId,
                requestId,
                before(current),
                after(view));
        catalogRevisions.bump();
        return view;
    }

    public AdminMovieView get(UUID movieId) {
        return movies.findView(movieId).orElseThrow(() -> new MovieNotFoundException(movieId));
    }

    public AdminMoviePage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new AdminValidationException("Page must be non-negative and size must be between 1 and 100");
        }
        return movies.list(page, size);
    }

    public GenreList genres() {
        return new GenreList(movies.listGenres());
    }

    private AdminMovieInput validate(AdminMovieInput input) {
        String title = input.title() == null ? "" : input.title().trim();
        String slug = input.slug() == null ? "" : input.slug().trim();
        String synopsis = input.synopsis() == null ? "" : input.synopsis().trim();
        String maturity = input.maturityRating() == null ? "" : input.maturityRating().trim();
        if (title.length() < 1 || title.length() > 200) {
            throw new AdminValidationException("title must contain 1-200 characters");
        }
        if (slug.length() < 1 || slug.length() > 120 || !SLUG.matcher(slug).matches()) {
            throw new AdminValidationException("slug must be 1-120 lowercase letters/digits separated by single hyphens");
        }
        if (synopsis.length() > 10000) {
            throw new AdminValidationException("synopsis must not exceed 10000 characters");
        }
        if (!MATURITY_RATINGS.contains(maturity)) {
            throw new AdminValidationException("maturityRating must be one of G, PG, PG-13, R, NC-17, NR");
        }
        return new AdminMovieInput(title, slug, synopsis, input.releaseYear(), maturity,
                normalizeGenres(input.genreIds()), input.featured());
    }

    private List<Integer> normalizeGenres(List<Integer> genreIds) {
        List<Integer> ids = genreIds == null ? List.of() : genreIds;
        Set<Integer> unique = new LinkedHashSet<>(ids);
        if (unique.size() != ids.size()) {
            throw new AdminValidationException("genreIds must not contain duplicates");
        }
        if (unique.size() > 10) {
            throw new AdminValidationException("at most ten genres are allowed");
        }
        Set<Integer> existing = movies.existingGenreIds();
        for (Integer id : unique) {
            if (id == null || id <= 0 || !existing.contains(id)) {
                throw new AdminValidationException("genreIds contains an unknown genre id");
            }
        }
        return List.copyOf(unique);
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AdminValidationException("Idempotency-Key header is required");
        }
    }

    private long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IfMatchRequiredException();
        }
        String value = ifMatch.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            throw new AdminValidationException("If-Match must be a quoted numeric revision");
        }
    }

    private Map<String, Object> before(JdbcAdminMovieRepository.AdminMovieRow row) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("title", row.title());
        before.put("slug", row.slug());
        before.put("synopsis", row.synopsis());
        before.put("releaseYear", row.releaseYear());
        before.put("maturityRating", row.maturityRating());
        before.put("featured", row.featured());
        return before;
    }

    private Map<String, Object> after(AdminMovieView view) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("title", view.title());
        after.put("slug", view.slug());
        after.put("synopsis", view.synopsis());
        after.put("releaseYear", view.releaseYear());
        after.put("maturityRating", view.maturityRating());
        after.put("featured", view.featured());
        after.put("genreIds", view.genreIds());
        return after;
    }
}
