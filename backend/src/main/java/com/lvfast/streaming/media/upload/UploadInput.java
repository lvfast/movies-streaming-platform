package com.lvfast.streaming.media.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Administrator-submitted upload declaration. The browser hashes the file fingerprint locally. */
public record UploadInput(
        @NotBlank String kind,
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Size(max = 255) String contentType,
        @Positive long sizeBytes,
        @NotBlank String resumeFingerprint) {
}
