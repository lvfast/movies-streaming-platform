package com.lvfast.streaming.media.catalog;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only lookups for validated READY media and artwork that other packages (administration,
 * playback) may consume without touching media persistence directly. Implementations never expose
 * raw storage credentials, source keys or output prefixes to callers outside the backend.
 */
public interface MediaCatalog {

    Optional<ReadyMediaVersion> readyVersion(UUID movieId, UUID versionId);

    Optional<ReadyMediaVersion> readyTranscode(UUID versionId);

    Optional<ReadyMediaAsset> readyAsset(UUID movieId, UUID assetId);

    /** A validated READY asset addressed by id alone, used by the admin-only asset preview. */
    Optional<ReadyMediaAsset> readyAssetById(UUID assetId);

    /** The most recently created READY version of a movie, used to pick a default preview target. */
    Optional<UUID> latestReadyVersionId(UUID movieId);

    /** Recorded duration of a READY version, or zero when the version has no duration yet. */
    int runtimeSeconds(UUID versionId);
}
