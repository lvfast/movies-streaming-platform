package com.lvfast.streaming.playback;

import java.time.Instant;
import java.util.UUID;

public record ViewingProgress(
        UUID movieId,
        int positionSeconds,
        int durationSeconds,
        Instant clientUpdatedAt,
        boolean completed) {}
