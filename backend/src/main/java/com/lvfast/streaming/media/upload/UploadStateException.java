package com.lvfast.streaming.media.upload;

/** Invalid upload state transition or conflict (409). */
public class UploadStateException extends RuntimeException {
    public UploadStateException(String message) {
        super(message);
    }
}
