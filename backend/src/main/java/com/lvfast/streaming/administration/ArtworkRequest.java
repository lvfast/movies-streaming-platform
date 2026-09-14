package com.lvfast.streaming.administration;

/**
 * Explicit artwork selection for a managed movie. The referenced asset must already be a READY,
 * server-validated asset of the same movie and kind.
 */
public record ArtworkRequest(String kind, String assetId) {
}
