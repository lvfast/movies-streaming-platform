package com.lvfast.streaming.media.catalog;

import java.util.UUID;

/**
 * A READY media version bound to the immutable attempt output that produced it. {@code prefix} is the
 * slash-terminated gateway prefix ({@code /hls/{movieId}/{versionId}/{attemptId}/}) and
 * {@code manifestPath} is the exact request path a client must use for the playlist.
 */
public record ReadyMediaVersion(
        UUID movieId,
        UUID versionId,
        UUID attemptId,
        UUID jobId,
        String prefix,
        String manifestPath) {
}
