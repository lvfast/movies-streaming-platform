package com.lvfast.streaming.playback;

import java.util.UUID;

/**
 * Playback metadata shared by both response shapes: legacy fixture playback and the managed
 * version-pinned {@link PlaybackGrant}. A managed grant implements this interface and adds the
 * session, token and pinned-version fields the browser needs.
 */
public interface Playback {
    UUID movieId();

    String manifestUrl();

    int resumePositionSeconds();
}
