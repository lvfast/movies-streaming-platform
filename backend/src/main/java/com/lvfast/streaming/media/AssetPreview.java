package com.lvfast.streaming.media;

/** A short-lived ADMIN-only read URL for a validated artwork asset. */
public record AssetPreview(String url, String expiresAt) {
}
