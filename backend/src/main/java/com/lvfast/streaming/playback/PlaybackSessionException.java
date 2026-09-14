package com.lvfast.streaming.playback;

import java.util.UUID;

/** Raised when a playback session, its movie or its pinned version no longer authorizes playback. */
public class PlaybackSessionException extends RuntimeException {
    public PlaybackSessionException(String message) {
        super(message);
    }

    public static PlaybackSessionException notFound(UUID sessionId) {
        return new PlaybackSessionException("Playback session " + sessionId + " is not available");
    }
}
