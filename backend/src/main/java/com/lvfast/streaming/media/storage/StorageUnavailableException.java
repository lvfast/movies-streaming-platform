package com.lvfast.streaming.media.storage;

/** Object storage could not complete the requested operation (503). */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String operation, Throwable cause) {
        super("Object storage is unavailable for " + operation, cause);
    }
}
