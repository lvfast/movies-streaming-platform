package com.lvfast.streaming.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisAuthRateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisAuthRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        values = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        limiter = new RedisAuthRateLimiter(redis, 10, Duration.ofMinutes(1));
    }

    @Test
    void rejectsRequestsAboveTheConfiguredWindowLimit() {
        when(values.increment("rate:auth:login:client-1")).thenReturn(11L);

        assertThatThrownBy(() -> limiter.check("login", "client-1"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        when(values.increment("rate:auth:login:client-1"))
                .thenThrow(new RedisConnectionFailureException("offline"));

        assertThatThrownBy(() -> limiter.check("login", "client-1"))
                .isInstanceOf(AuthRateLimitUnavailableException.class);
    }
}
