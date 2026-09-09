package com.lvfast.streaming.identity;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException() {
        super("too many authentication attempts");
    }
}
