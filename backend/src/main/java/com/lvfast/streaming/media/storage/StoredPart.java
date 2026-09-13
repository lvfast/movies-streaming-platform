package com.lvfast.streaming.media.storage;

/** Authoritative uploaded part as reported by storage. */
public record StoredPart(int partNumber, String etag, long sizeBytes) {
}
