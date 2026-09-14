package com.lvfast.streaming.media.storage;

import java.util.Map;

/** Head metadata for a stored object. */
public record ObjectHead(long sizeBytes, String etag, String contentType, Map<String, String> metadata) {
}
