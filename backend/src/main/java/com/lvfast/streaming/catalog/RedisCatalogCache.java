package com.lvfast.streaming.catalog;

import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Revision-namespaced catalog cache. Keys embed the current PostgreSQL catalog revision so admin
 * and import mutations simply bump the revision; stale entries expire by TTL and no wildcard
 * {@code KEYS} scan is ever performed.
 */
@Component
class RedisCatalogCache implements CatalogCache {
    private static final Logger log = LoggerFactory.getLogger(RedisCatalogCache.class);
    private static final String HOME_KEY_PREFIX = "catalog:home:v";
    private static final String MOVIE_KEY_PREFIX = "catalog:movie:v";
    private static final Duration TTL = Duration.ofMinutes(10);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final CatalogRevisionRepository revisions;

    RedisCatalogCache(StringRedisTemplate redis, ObjectMapper json, CatalogRevisionRepository revisions) {
        this.redis = redis;
        this.json = json;
        this.revisions = revisions;
    }

    @Override
    public Optional<CatalogHome> getHome() {
        return read(homeKey(), CatalogHome.class);
    }

    @Override
    public void putHome(CatalogHome home) {
        write(homeKey(), home);
    }

    @Override
    public Optional<MovieDetails> getMovie(String slug) {
        return read(movieKey(slug), MovieDetails.class);
    }

    @Override
    public void putMovie(MovieDetails movie) {
        write(movieKey(movie.slug()), movie);
    }

    private String homeKey() {
        return HOME_KEY_PREFIX + revisions.current();
    }

    private String movieKey(String slug) {
        return MOVIE_KEY_PREFIX + revisions.current() + ":" + slug;
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
