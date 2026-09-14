package com.lvfast.streaming.playback;

import java.time.Instant;
import java.util.UUID;

/** Stored playback session row, without any token or storage material. */
record StoredSession(
        UUID id,
        UUID userId,
        UUID movieId,
        UUID mediaVersionId,
        String purpose,
        Instant expiresAt,
        Instant revokedAt) {

    boolean activeAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
