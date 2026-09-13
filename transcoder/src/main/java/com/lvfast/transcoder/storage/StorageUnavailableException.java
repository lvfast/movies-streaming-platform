package com.lvfast.transcoder.storage;

/** Object storage could not be reached or the operation failed. */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String operation, Throwable cause) {
        super("Storage operation '" + operation + "' failed", cause);
    }
}
