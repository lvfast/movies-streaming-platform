package com.lvfast.streaming.administration;

import com.lvfast.streaming.audit.AuditService;
import com.lvfast.streaming.catalog.CatalogRevisionRepository;
import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.media.catalog.MediaCatalog;
import com.lvfast.streaming.media.catalog.ReadyMediaAsset;
import com.lvfast.streaming.media.catalog.ReadyMediaVersion;
import com.lvfast.streaming.playback.MediaSessionService;
import com.lvfast.streaming.playback.PlaybackGrant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publication and lifecycle commands for managed movies. Publication refuses anything that is not
 * validated READY media or artwork and never disturbs the previous active version when it refuses.
 * A successful command changes lifecycle, active version, runtime, the public compatibility
 * projection, the catalog revision and the audit record inside one transaction. Replacement
 * activation changes only the active pointer, so existing playback sessions stay pinned to the
 * version they were created with.
 */
@Service
public class MoviePublicationService {

    static final String PUBLISH_OPERATION = "MOVIE_PUBLISH";
    static final String ACTIVATE_OPERATION = "MOVIE_ACTIVATE";

    private final JdbcAdminMovieRepository movies;
    private final MediaCatalog catalog;
    private final ArtworkPromotionService artwork;
    private final AuditService audit;
    private final CatalogRevisionRepository catalogRevisions;
    private final MediaSessionService sessions;

    public MoviePublicationService(
            JdbcAdminMovieRepository movies,
            MediaCatalog catalog,
            ArtworkPromotionService artwork,
            AuditService audit,
            CatalogRevisionRepository catalogRevisions,
            MediaSessionService sessions) {
        this.movies = movies;
        this.catalog = catalog;
        this.artwork = artwork;
        this.audit = audit;
        this.catalogRevisions = catalogRevisions;
        this.sessions = sessions;
    }

    @Transactional
    public AdminMovieView publish(
            UUID actorId, UUID movieId, String ifMatch, String idempotencyKey, String requestId,
            PublishRequest request) {
        requireIdempotencyKey(idempotencyKey);
        Optional<AdminMovieView> replay =
                movies.findReceipt(actorId, PUBLISH_OPERATION, idempotencyKey, AdminMovieView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        AdminMovieView current = requireMovie(movieId);
        long expected = parseIfMatch(ifMatch);
        if (expected != current.revision()) {
            throw new StaleRevisionException(expected, current.revision());
        }
        requirePublishableMetadata(current);
        UUID versionId = requireUuid(request == null ? null : request.mediaVersionId(), "mediaVersionId");
        ReadyMediaVersion version = catalog.readyVersion(movieId, versionId)
                .orElseThrow(() -> new PublicationRejectedException(
                        "mediaVersionId " + versionId + " is not a READY version of this movie"));
        UUID posterAssetId = resolveArtwork(movieId, request.posterAssetId(), current.posterAssetId(), "POSTER");
        UUID backdropAssetId =
                resolveArtwork(movieId, request.backdropAssetId(), current.backdropAssetId(), "BACKDROP");
        int runtimeSeconds = runtimeSeconds(version);
        String posterUrl = posterAssetId == null ? null : ReadyMediaAsset.publicKeyFor(posterAssetId);
        String backdropUrl = backdropAssetId == null ? null : ReadyMediaAsset.publicKeyFor(backdropAssetId);
        int updated = movies.publishCas(
                movieId, expected, version.manifestPath(), posterUrl, backdropUrl,
                version.versionId(), posterAssetId, backdropAssetId, runtimeSeconds);
        if (updated == 0) {
            throw new StaleRevisionException(expected, movies.revisionOf(movieId));
        }
        AdminMovieView view = requireMovie(movieId);
        movies.storeReceipt(actorId, PUBLISH_OPERATION, idempotencyKey, 200, view);
        audit.record(actorId, "USER", "MOVIE_PUBLISHED", "MOVIE", movieId, requestId,
                publicationState(current), publicationState(view));
        catalogRevisions.bump();
        return view;
    }

    @Transactional
    public AdminMovieView activate(
            UUID actorId, UUID movieId, String ifMatch, String idempotencyKey, String requestId,
            PublishRequest request) {
        requireIdempotencyKey(idempotencyKey);
        Optional<AdminMovieView> replay =
                movies.findReceipt(actorId, ACTIVATE_OPERATION, idempotencyKey, AdminMovieView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        AdminMovieView current = requireMovie(movieId);
        long expected = parseIfMatch(ifMatch);
        if (expected != current.revision()) {
            throw new StaleRevisionException(expected, current.revision());
        }
        if (!"PUBLISHED".equals(current.lifecycle())) {
            throw new PublicationRejectedException(
                    "Only a PUBLISHED movie can activate a replacement version");
        }
        UUID versionId = requireUuid(request == null ? null : request.mediaVersionId(), "mediaVersionId");
        ReadyMediaVersion version = catalog.readyVersion(movieId, versionId)
                .orElseThrow(() -> new PublicationRejectedException(
                        "mediaVersionId " + versionId + " is not a READY version of this movie"));
        int updated = movies.activateCas(
                movieId, expected, version.manifestPath(), version.versionId(), runtimeSeconds(version));
        if (updated == 0) {
            throw new StaleRevisionException(expected, movies.revisionOf(movieId));
        }
        AdminMovieView view = requireMovie(movieId);
        movies.storeReceipt(actorId, ACTIVATE_OPERATION, idempotencyKey, 200, view);
        audit.record(actorId, "USER", "MOVIE_VERSION_ACTIVATED", "MOVIE", movieId, requestId,
                Map.of("activeMediaVersionId", String.valueOf(current.activeMediaVersionId())),
                Map.of("activeMediaVersionId", String.valueOf(view.activeMediaVersionId())));
        catalogRevisions.bump();
        return view;
    }

    @Transactional
    public AdminMovieView unpublish(UUID actorId, UUID movieId, String ifMatch, String requestId) {
        return transition(actorId, movieId, ifMatch, requestId, "MOVIE_UNPUBLISHED",
                from -> {
                    if (!"PUBLISHED".equals(from)) {
                        throw new PublicationRejectedException(
                                "Only a PUBLISHED movie can be unpublished");
                    }
                },
                "UNPUBLISHED", false);
    }

    @Transactional
    public AdminMovieView archive(UUID actorId, UUID movieId, String ifMatch, String requestId) {
        return transition(actorId, movieId, ifMatch, requestId, "MOVIE_ARCHIVED",
                from -> {
                    if ("ARCHIVED".equals(from)) {
                        throw new PublicationRejectedException("Movie is already ARCHIVED");
                    }
                },
                "ARCHIVED", false);
    }

    @Transactional
    public AdminMovieView restore(UUID actorId, UUID movieId, String ifMatch, String requestId) {
        return transition(actorId, movieId, ifMatch, requestId, "MOVIE_RESTORED",
                from -> {
                    if (!"ARCHIVED".equals(from)) {
                        throw new PublicationRejectedException(
                                "Only an ARCHIVED movie can be restored");
                    }
                },
                "UNPUBLISHED", false);
    }

    /**
     * Selects a validated READY poster/backdrop for a movie. Selection is a revision-checked edit
     * and never rewrites the other artwork kind. For an already published movie the artwork is
     * promoted immediately so the public projection keeps pointing at a readable object.
     */
    @Transactional
    public AdminMovieView attachArtwork(
            UUID actorId, UUID movieId, String ifMatch, String requestId, ArtworkRequest request) {
        AdminMovieView current = requireMovie(movieId);
        long expected = parseIfMatch(ifMatch);
        if (expected != current.revision()) {
            throw new StaleRevisionException(expected, current.revision());
        }
        String kind = requireArtworkKind(request == null ? null : request.kind());
        UUID assetId = requireUuid(request == null ? null : request.assetId(), "assetId");
        ReadyMediaAsset asset = catalog.readyAsset(movieId, assetId)
                .orElseThrow(() -> new PublicationRejectedException(
                        "Artwork asset " + assetId + " is not a READY asset of this movie"));
        if (!asset.kind().equals(kind)) {
            throw new PublicationRejectedException(
                    "Artwork asset " + assetId + " is a " + asset.kind() + ", not a " + kind);
        }
        if ("PUBLISHED".equals(current.lifecycle())) {
            artwork.promote(movieId, assetId, kind);
        }
        int updated = movies.attachArtworkCas(
                movieId, expected, kind, assetId, ReadyMediaAsset.publicKeyFor(assetId));
        if (updated == 0) {
            throw new StaleRevisionException(expected, movies.revisionOf(movieId));
        }
        AdminMovieView view = requireMovie(movieId);
        audit.record(actorId, "USER", "MOVIE_ARTWORK_ATTACHED", "MOVIE", movieId, requestId,
                artworkState(current, kind), artworkState(view, kind));
        catalogRevisions.bump();
        return view;
    }

    /**
     * Creates a short-lived ADMIN preview session. A requested READY version is previewed as-is so
     * an administrator can inspect a candidate replacement; without a request the active version (or
     * the latest READY version) is used.
     */
    @Transactional
    public PlaybackGrant preview(UUID actorId, UUID movieId, String requestId, String requestedVersionId) {
        AdminMovieView movie = requireMovie(movieId);
        UUID versionId;
        if (requestedVersionId != null && !requestedVersionId.isBlank()) {
            versionId = requireUuid(requestedVersionId, "mediaVersionId");
        } else {
            versionId = movie.activeMediaVersionId() == null
                    ? catalog.latestReadyVersionId(movieId).orElse(null)
                    : UUID.fromString(movie.activeMediaVersionId());
        }
        if (versionId == null) {
            throw new PublicationRejectedException("Movie has no READY media version to preview");
        }
        ReadyMediaVersion version = catalog.readyVersion(movieId, versionId)
                .orElseThrow(() -> new PublicationRejectedException(
                        "Movie has no READY media version to preview"));
        PlaybackGrant grant = sessions.preview(actorId, movieId, version, 0);
        audit.record(actorId, "USER", "MOVIE_PREVIEWED", "MOVIE", movieId, requestId,
                Map.of(), Map.of("mediaVersionId", version.versionId().toString()));
        return grant;
    }

    private AdminMovieView transition(
            UUID actorId,
            UUID movieId,
            String ifMatch,
            String requestId,
            String action,
            java.util.function.Consumer<String> guard,
            String lifecycle,
            boolean published) {
        AdminMovieView current = requireMovie(movieId);
        long expected = parseIfMatch(ifMatch);
        if (expected != current.revision()) {
            throw new StaleRevisionException(expected, current.revision());
        }
        guard.accept(current.lifecycle());
        int updated = movies.updateLifecycleCas(movieId, expected, lifecycle, published);
        if (updated == 0) {
            throw new StaleRevisionException(expected, movies.revisionOf(movieId));
        }
        AdminMovieView view = requireMovie(movieId);
        audit.record(actorId, "USER", action, "MOVIE", movieId, requestId,
                Map.of("lifecycle", current.lifecycle()), Map.of("lifecycle", view.lifecycle()));
        catalogRevisions.bump();
        return view;
    }

    private UUID resolveArtwork(UUID movieId, String requested, String existing, String kind) {
        if (requested != null) {
            UUID assetId = requireUuid(requested, "artwork asset id");
            artwork.promote(movieId, assetId, kind);
            return assetId;
        }
        if (existing == null) {
            return null;
        }
        UUID assetId = UUID.fromString(existing);
        if (catalog.readyAsset(movieId, assetId).isEmpty()) {
            throw new PublicationRejectedException(
                    "Existing " + kind.toLowerCase() + " artwork is no longer a READY asset of this movie");
        }
        artwork.promote(movieId, assetId, kind);
        return assetId;
    }

    private int runtimeSeconds(ReadyMediaVersion version) {
        return catalog.runtimeSeconds(version.versionId());
    }

    private void requirePublishableMetadata(AdminMovieView movie) {
        if (movie.synopsis() == null || movie.synopsis().isBlank()) {
            throw new AdminValidationException("synopsis must contain at least one character to publish");
        }
        if (!movies.hasGenres(UUID.fromString(movie.id()))) {
            throw new AdminValidationException("At least one genre is required to publish");
        }
    }

    private AdminMovieView requireMovie(UUID movieId) {
        return movies.findView(movieId).orElseThrow(() -> new MovieNotFoundException(movieId));
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

    private UUID requireUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AdminValidationException(field + " is required");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            throw new AdminValidationException(field + " must be a UUID");
        }
    }

    private String requireArtworkKind(String kind) {
        if (kind == null || (!"POSTER".equals(kind) && !"BACKDROP".equals(kind))) {
            throw new AdminValidationException("kind must be POSTER or BACKDROP");
        }
        return kind;
    }

    private Map<String, Object> artworkState(AdminMovieView view, String kind) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("posterAssetId", String.valueOf(view.posterAssetId()));
        state.put("backdropAssetId", String.valueOf(view.backdropAssetId()));
        state.put("selectedKind", kind);
        return state;
    }

    private Map<String, Object> publicationState(AdminMovieView view) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("lifecycle", view.lifecycle());
        state.put("activeMediaVersionId", String.valueOf(view.activeMediaVersionId()));
        state.put("posterAssetId", String.valueOf(view.posterAssetId()));
        state.put("backdropAssetId", String.valueOf(view.backdropAssetId()));
        state.put("runtimeSeconds", view.runtimeSeconds());
        return state;
    }
}
