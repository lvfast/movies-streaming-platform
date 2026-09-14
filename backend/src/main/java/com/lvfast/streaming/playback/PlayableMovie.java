package com.lvfast.streaming.playback;

import java.util.UUID;

/**
 * A movie the public playback endpoint may expose. Managed movies carry the active READY version, so
 * playback can create a version-pinned session; legacy fixtures keep their stored manifest path.
 */
record PlayableMovie(UUID id, String manifestUrl, String managementMode, UUID activeMediaVersionId) {

    boolean managed() {
        return "MANAGED".equals(managementMode) && activeMediaVersionId != null;
    }
}
