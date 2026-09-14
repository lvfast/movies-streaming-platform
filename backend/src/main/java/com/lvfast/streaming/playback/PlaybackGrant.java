package com.lvfast.streaming.playback;

import java.util.UUID;

/**
 * Version-pinned playback authorization returned to a browser and by the ADMIN preview endpoint.
 * The manifest path is always the gateway path for the exact attempt that produced the pinned
 * version, and the media token authorizes only that immutable prefix.
 */
public record PlaybackGrant(
        UUID movieId,
        String manifestUrl,
        int resumePositionSeconds,
        UUID sessionId,
        UUID mediaVersionId,
        String mediaToken,
        String mediaTokenExpiresAt) implements Playback {
}
