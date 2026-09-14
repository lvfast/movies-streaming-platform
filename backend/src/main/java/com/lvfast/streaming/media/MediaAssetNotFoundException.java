package com.lvfast.streaming.media;

import java.util.UUID;

/** The requested artwork asset does not exist or is not a validated READY asset. */
public class MediaAssetNotFoundException extends RuntimeException {

    public MediaAssetNotFoundException(UUID assetId) {
        super("Artwork asset " + assetId + " is not READY");
    }
}
