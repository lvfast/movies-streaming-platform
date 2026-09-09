package com.lvfast.streaming.playback;

import java.util.UUID;

public record Playback(UUID movieId, String manifestUrl, int resumePositionSeconds) {}
