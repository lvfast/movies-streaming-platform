package com.lvfast.streaming.media.storage;

/** A part submitted to storage when completing a multipart upload. */
public record CompletedPart(int partNumber, String etag) {
}
