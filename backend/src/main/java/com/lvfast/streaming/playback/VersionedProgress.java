package com.lvfast.streaming.playback;

import java.time.Instant;
import java.util.UUID;

/** Viewing progress together with the session and media version it was recorded against. */
public record VersionedProgress(
        UUID movieId,
        int positionSeconds,
        int durationSeconds,
        Instant clientUpdatedAt,
        boolean completed,
        UUID sessionId,
        UUID mediaVersionId) {
}
