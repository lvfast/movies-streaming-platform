package com.lvfast.streaming.playback;

import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.common.MediaUrlResolver;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaybackService {
    private final PlaybackRepository repository;
    private final MediaSessionService sessions;
    private final MediaUrlResolver mediaUrls;

    PlaybackService(
            PlaybackRepository repository, MediaSessionService sessions, MediaUrlResolver mediaUrls) {
        this.repository = repository;
        this.sessions = sessions;
        this.mediaUrls = mediaUrls;
    }

    /**
     * Managed movies return a version-pinned {@link PlaybackGrant} with a short-lived media token.
     * Legacy fixture rows keep the original manifest/resume response shape until managed cutover.
     */
    public Playback playback(UUID userId, UUID movieId) {
        PlayableMovie movie = repository.playableMovie(movieId)
                .orElseThrow(() -> new MovieNotFoundException(movieId));
        if (movie.managed()) {
            return sessions.createViewerSession(userId, movieId);
        }
        int resumePosition = repository.progress(userId, movieId)
                .filter(progress -> !progress.completed())
                .map(ViewingProgress::positionSeconds)
                .orElse(0);
        return new FixturePlayback(movie.id(), mediaUrls.resolve(movie.manifestUrl()), resumePosition);
    }

    @Transactional
    public ViewingProgress updateProgress(
            UUID userId,
            UUID movieId,
            int positionSeconds,
            int durationSeconds,
            Instant clientUpdatedAt) {
        validate(positionSeconds, durationSeconds, clientUpdatedAt);
        repository.playableMovie(movieId)
                .orElseThrow(() -> new MovieNotFoundException(movieId));
        boolean completed = (long) positionSeconds * 10 >= (long) durationSeconds * 9;
        return repository.save(
                userId, movieId, positionSeconds, durationSeconds, clientUpdatedAt, completed);
    }

    /**
     * Version-pinned progress for a managed playback session. The session, user, movie and version
     * must all match, so a replacement activation cannot mix resume positions between versions.
     */
    @Transactional
    public VersionedProgress updateManagedProgress(
            UUID userId,
            UUID movieId,
            UUID sessionId,
            UUID mediaVersionId,
            int positionSeconds,
            int durationSeconds,
            Instant clientUpdatedAt) {
        validate(positionSeconds, durationSeconds, clientUpdatedAt);
        return sessions.updateViewerProgress(
                userId, movieId, sessionId, mediaVersionId, positionSeconds, durationSeconds,
                clientUpdatedAt);
    }

    private void validate(int positionSeconds, int durationSeconds, Instant clientUpdatedAt) {
        if (positionSeconds < 0) {
            throw new ProgressValidationException("Position must be non-negative");
        }
        if (durationSeconds < 1) {
            throw new ProgressValidationException("Duration must be at least one second");
        }
        if (positionSeconds > durationSeconds) {
            throw new ProgressValidationException("Position cannot exceed duration");
        }
        if (clientUpdatedAt == null) {
            throw new ProgressValidationException("Client update time is required");
        }
    }
}
