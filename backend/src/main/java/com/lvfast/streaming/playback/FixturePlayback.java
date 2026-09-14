package com.lvfast.streaming.playback;

import java.util.UUID;

/**
 * Playback authorization for a legacy (non-managed) fixture row that still exposes a stored manifest
 * path directly.
 */
public record FixturePlayback(UUID movieId, String manifestUrl, int resumePositionSeconds)
        implements Playback {
}
