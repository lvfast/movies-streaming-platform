package com.lvfast.streaming.identity;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisAuthRateLimiter implements AuthRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisAuthRateLimiter.class);

    private final StringRedisTemplate redis;
    private final int limit;
    private final Duration window;

    public RedisAuthRateLimiter(StringRedisTemplate redis, int limit, Duration window) {
        this.redis = redis;
        this.limit = limit;
        this.window = window;
    }

    @Override
    public void check(String action, String subject) {
        String key = "rate:auth:" + action + ":" + subject;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, window);
            }
            if (count != null && count > limit) {
                throw new RateLimitExceededException();
            }
        } catch (DataAccessException unavailable) {
            LOGGER.warn("Redis unavailable; authentication rate limiting is failing closed");
            throw new AuthRateLimitUnavailableException(unavailable);
        }
    }
}
