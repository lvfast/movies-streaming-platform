package com.lvfast.transcoder.output;

/** One uploaded output object referenced by the manifest. */
public record ArtifactObject(String key, long sizeBytes, String sha256) {
}
