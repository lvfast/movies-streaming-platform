package com.lvfast.streaming.identity;

public class AuthRateLimitUnavailableException extends RuntimeException {
    public AuthRateLimitUnavailableException(Throwable cause) {
        super("authentication rate limiting is unavailable", cause);
    }
}
