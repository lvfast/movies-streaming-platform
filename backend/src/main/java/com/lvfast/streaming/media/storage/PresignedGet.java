package com.lvfast.streaming.media.storage;

import java.net.URI;
import java.time.Instant;

/** A short-lived presigned GET URL for one private object. */
public record PresignedGet(URI url, Instant expiresAt) {
}
