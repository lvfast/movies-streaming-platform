package com.lvfast.streaming.identity;

public interface AuthRateLimiter {
    void check(String action, String subject);
}
