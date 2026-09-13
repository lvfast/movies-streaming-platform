package com.lvfast.streaming.playback;

import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.media.catalog.MediaCatalog;
import com.lvfast.streaming.media.catalog.ReadyMediaVersion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and re-issues version-pinned playback authorizations. A viewer session is created against
 * the movie's currently active READY version and keeps that version for its whole lifetime, so
 * activating a replacement cannot disturb it. Unpublishing or archiving a movie denies new sessions
 * and token refresh; an already issued short-lived media token stays valid until it expires.
 */
@Service
public class MediaSessionService {

    static final String VIEWER = "VIEWER";
    static final String PREVIEW = "PREVIEW";

    private final MediaSessionRepository repository;
    private final MediaCatalog catalog;
    private final MediaTokenIssuer tokens;
    private final Clock clock;
    private final Duration sessionTtl;

    public MediaSessionService(
            MediaSessionRepository repository,
            MediaCatalog catalog,
            MediaTokenIssuer tokens,
            Clock clock,
            MediaProperties properties) {
        this.repository = repository;
        this.catalog = catalog;
        this.tokens = tokens;
        this.clock = clock;
        this.sessionTtl = properties.playbackSessionTtl();
    }

    @Transactional
    public PlaybackGrant createViewerSession(UUID userId, UUID movieId) {
        UUID versionId = repository.activeVersionId(movieId)
                .orElseThrow(() -> new MovieNotFoundException(movieId));
        ReadyMediaVersion version = catalog.readyVersion(movieId, versionId)
                .orElseThrow(() -> new MovieNotFoundException(movieId));
        return grantFor(userId, movieId, version);
    }

    /** ADMIN preview of a READY version: short-lived, and never published or activated. */
    @Transactional
    public PlaybackGrant preview(UUID adminId, UUID movieId, ReadyMediaVersion version, int resumePosition) {
        String manifestPath = version.manifestPath();
        UUID sessionId = repository.createSession(
                adminId, movieId, version.versionId(), PREVIEW, clock.instant().plus(sessionTtl));
        MediaTokenIssuer.IssuedToken token = tokens.issue(
                adminId, sessionId, movieId, version.versionId(), version.prefix(), PREVIEW);
        return new PlaybackGrant(
                movieId, manifestPath, resumePosition, sessionId, version.versionId(),
                token.token(), token.expiresAt().toString());
    }

    @Transactional
    public MediaTokenIssuer.IssuedToken refreshViewerToken(UUID userId, UUID sessionId) {
        return refresh(userId, sessionId, VIEWER, false);
    }

    @Transactional
    public MediaTokenIssuer.IssuedToken refreshPreviewToken(UUID adminId, UUID sessionId) {
        return refresh(adminId, sessionId, PREVIEW, false);
    }

    @Transactional
    public VersionedProgress updateViewerProgress(
            UUID userId,
            UUID movieId,
            UUID sessionId,
            UUID mediaVersionId,
            int positionSeconds,
            int durationSeconds,
            Instant clientUpdatedAt) {
        requireActiveSession(sessionId, userId, VIEWER);
        StoredSession session = repository.findSession(sessionId).orElseThrow();
        if (!session.movieId().equals(movieId) || !session.mediaVersionId().equals(mediaVersionId)) {
            throw new PlaybackSessionException(
                    "Progress must reference the movie and media version of the playback session");
        }
        if (repository.activeVersionId(movieId).isEmpty()) {
            throw new PlaybackSessionException("This movie is not currently published");
        }
        boolean completed = (long) positionSeconds * 10 >= (long) durationSeconds * 9;
        return repository.saveViewerProgress(
                userId, movieId, sessionId, mediaVersionId, positionSeconds, durationSeconds,
                clientUpdatedAt, completed);
    }

    private MediaTokenIssuer.IssuedToken refresh(
            UUID userId, UUID sessionId, String purpose, boolean revokeOnDenial) {
        StoredSession session = requireActiveSession(sessionId, userId, purpose);
        if (VIEWER.equals(purpose)
                && repository.activeVersionId(session.movieId()).isEmpty()) {
            if (revokeOnDenial) {
                repository.revokeSession(sessionId);
            }
            throw new PlaybackSessionException("This movie is no longer published");
        }
        ReadyMediaVersion version = catalog.readyVersion(session.movieId(), session.mediaVersionId())
                .orElseThrow(() -> PlaybackSessionException.notFound(sessionId));
        return tokens.issue(
                userId, sessionId, session.movieId(), session.mediaVersionId(), version.prefix(), purpose);
    }

    private PlaybackGrant grantFor(UUID userId, UUID movieId, ReadyMediaVersion version) {
        UUID sessionId = repository.createSession(
                userId, movieId, version.versionId(), VIEWER, clock.instant().plus(sessionTtl));
        MediaTokenIssuer.IssuedToken token = tokens.issue(
                userId, sessionId, movieId, version.versionId(), version.prefix(), VIEWER);
        int resumePosition = repository.resumePosition(
                userId, movieId, sessionId, version.versionId());
        return new PlaybackGrant(
                movieId, version.manifestPath(), resumePosition, sessionId, version.versionId(),
                token.token(), token.expiresAt().toString());
    }

    private StoredSession requireActiveSession(UUID sessionId, UUID userId, String purpose) {
        StoredSession session = repository.findOwnedSession(sessionId, userId, purpose)
                .orElseThrow(() -> PlaybackSessionException.notFound(sessionId));
        if (!session.activeAt(clock.instant())) {
            throw PlaybackSessionException.notFound(sessionId);
        }
        return session;
    }
}
