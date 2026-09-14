package com.lvfast.streaming.playback;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable playback sessions and version-pinned viewing progress. */
interface MediaSessionRepository {

    Optional<StoredSession> findSession(UUID sessionId);

    Optional<StoredSession> findOwnedSession(UUID sessionId, UUID userId, String purpose);

    Optional<UUID> activeVersionId(UUID movieId);

    UUID createSession(
            UUID userId, UUID movieId, UUID mediaVersionId, String purpose, Instant expiresAt);

    void revokeSession(UUID sessionId);

    /**
     * Resume position for this user, movie and version; zero when the viewer has no unfinished
     * progress for that version. The session argument is context for implementations, not a filter:
     * progress belongs to the viewer and the pinned version, not to one playback session.
     */
    int resumePosition(UUID userId, UUID movieId, UUID sessionId, UUID mediaVersionId);

    VersionedProgress saveViewerProgress(
            UUID userId,
            UUID movieId,
            UUID sessionId,
            UUID mediaVersionId,
            int positionSeconds,
            int durationSeconds,
            Instant clientUpdatedAt,
            boolean completed);
}
