package com.lvfast.streaming.identity;

public class RefreshTokenReuseException extends RuntimeException {
    public RefreshTokenReuseException() {
        super("refresh token reuse detected");
    }
}
