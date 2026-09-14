package com.lvfast.streaming.media.upload;

import java.util.UUID;

/** No upload session exists for the requested identifier (404). */
public class UploadNotFoundException extends RuntimeException {
    public UploadNotFoundException(UUID uploadId) {
        super("No upload session exists with id '" + uploadId + "'");
    }
}
