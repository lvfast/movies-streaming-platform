package com.lvfast.streaming.catalog;

import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisCatalogCache implements CatalogCache {
    private static final Logger log = LoggerFactory.getLogger(RedisCatalogCache.class);
    private static final String HOME_KEY = "catalog:home:v1";
    private static final Duration TTL = Duration.ofMinutes(10);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    RedisCatalogCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public Optional<CatalogHome> getHome() {
        return read(HOME_KEY, CatalogHome.class);
    }

    @Override
    public void putHome(CatalogHome home) {
        write(HOME_KEY, home);
    }

    @Override
    public Optional<MovieDetails> getMovie(String slug) {
        return read("catalog:movie:v1:" + slug, MovieDetails.class);
    }

    @Override
    public void putMovie(MovieDetails movie) {
        write("catalog:movie:v1:" + movie.slug(), movie);
    }

    @Override
    public void invalidateAll() {
        try {
            Set<String> keys = new HashSet<>();
            Set<String> movieKeys = redis.keys("catalog:movie:v1:*");
            if (movieKeys != null) keys.addAll(movieKeys);
            keys.add(HOME_KEY);
            redis.delete(keys);
        } catch (Exception unavailable) {
            log.debug("Catalog cache invalidation bypassed: {}", unavailable.getClass().getSimpleName());
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, type));
        } catch (Exception unavailableOrInvalid) {
            log.debug("Catalog cache read bypassed for {}: {}", key, unavailableOrInvalid.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void write(String key, Object value) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), TTL);
        } catch (Exception unavailable) {
            log.debug("Catalog cache write bypassed for {}: {}", key, unavailable.getClass().getSimpleName());
        }
    }
}
