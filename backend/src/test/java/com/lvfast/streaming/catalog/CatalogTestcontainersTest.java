package com.lvfast.streaming.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.MediaStreamingApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = MediaStreamingApplication.class, properties = "app.auth.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class CatalogTestcontainersTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired CatalogService catalog;

    @Test
    void postgresSearchManifestAndRedisCacheIntegrate() {
        redis.delete("catalog:home:v1");

        assertThat(jdbc.queryForObject("select count(*) from movie", Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject(
                "select count(*) from catalog_import where manifest_version='catalog-v3'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_extension where extname in ('pgcrypto', 'pg_trgm')", Integer.class))
                .isEqualTo(2);
        assertThat(catalog.search("starligt", 0, 5).items())
                .extracting(MovieSummary::slug)
                .containsExactly("starlight-archive");
        assertThat(catalog.search("archivist", 0, 5).items())
                .extracting(MovieSummary::slug)
                .containsExactly("starlight-archive");
        MoviePage firstPage = catalog.search("old", 0, 1);
        MoviePage secondPage = catalog.search("old", 1, 1);
        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(MovieSummary::slug).containsExactly("lanterns-at-noon");
        assertThat(secondPage.total()).isEqualTo(3);
        assertThat(secondPage.items()).extracting(MovieSummary::slug).containsExactly("copper-and-rain");
        MoviePage wildcard = catalog.search("%", 0, 20);
        assertThat(wildcard.items()).isEmpty();
        assertThat(wildcard.total()).isZero();
        MovieDetails starlight = catalog.movie("starlight-archive");
        assertThat(starlight.runtimeSeconds()).isEqualTo(2);
        assertThat(starlight.posterUrl()).isEqualTo("/media/artwork/starlight-archive-poster.svg");
        assertThat(starlight.backdropUrl()).isEqualTo("/media/artwork/starlight-archive-backdrop.svg");
        assertThat(catalog.home().rails()).isNotEmpty();
        assertThat(redis.hasKey("catalog:home:v1")).isTrue();
    }
}
