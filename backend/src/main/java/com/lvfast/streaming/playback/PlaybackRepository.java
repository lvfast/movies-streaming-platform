package com.lvfast.streaming.playback;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface PlaybackRepository {
    Optional<PlayableMovie> playableMovie(UUID movieId);

    Optional<ViewingProgress> progress(UUID userId, UUID movieId);

    ViewingProgress save(
            UUID userId,
            UUID movieId,
            int positionSeconds,
            int durationSeconds,
            Instant clientUpdatedAt,
            boolean completed);
}
