package com.lvfast.streaming.media.upload;

/** Declared upload size exceeds the configured limit for the file kind (413). */
public class UploadSizeExceededException extends RuntimeException {
    public UploadSizeExceededException(String kind, long declaredBytes, long maxBytes) {
        super(kind + " upload of " + declaredBytes + " bytes exceeds the " + maxBytes + " byte limit");
    }
}
