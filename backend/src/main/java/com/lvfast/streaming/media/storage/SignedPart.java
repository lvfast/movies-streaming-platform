package com.lvfast.streaming.media.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** Presigned multipart part upload. The browser PUTs the part body with credentials omitted. */
public record SignedPart(int partNumber, URI url, Instant expiresAt, Map<String, String> headers) {
}
