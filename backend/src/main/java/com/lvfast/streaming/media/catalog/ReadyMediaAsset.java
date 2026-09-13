package com.lvfast.streaming.media.catalog;

import java.util.UUID;

/**
 * A READY artwork asset. Only validated artwork is ever promoted into the anonymous public artwork
 * namespace, and the promotion copies this validated output object as its only source.
 */
public record ReadyMediaAsset(
        UUID movieId,
        UUID assetId,
        String kind,
        String sourceKey,
        String publicKey) {

    public static final String PUBLIC_ARTWORK_NAMESPACE = "public-artwork";
    public static final String ARTWORK_OBJECT = "image.jpg";

    public static String publicKeyFor(UUID assetId) {
        return PUBLIC_ARTWORK_NAMESPACE + "/" + assetId + "/" + ARTWORK_OBJECT;
    }
}
