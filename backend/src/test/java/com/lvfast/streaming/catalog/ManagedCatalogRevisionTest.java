package com.lvfast.streaming.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.MediaStreamingApplication;
import com.lvfast.streaming.administration.AdminMovieInput;
import com.lvfast.streaming.administration.AdminMovieService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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
@org.springframework.test.context.ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ManagedCatalogRevisionTest {

    private static final UUID STARLIGHT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

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
    @Autowired AdminMovieService admin;
    @Autowired CatalogManifestImporter importer;
    @Autowired CatalogRevisionRepository revisions;

    @Test
    void adoptedMovieSurvivesRerunImportAndEditStaysVisible() throws Exception {
        assertThat(catalog.movie("starlight-archive").title()).isEqualTo("Starlight Archive");
        long cachedRevision = revisions.current();
        assertThat(redis.hasKey("catalog:movie:v" + cachedRevision + ":starlight-archive")).isTrue();

        Integer adventureId = jdbc.queryForObject(
                "select id from genre where name=?", (rs, i) -> rs.getInt(1), "Adventure");
        AdminMovieInput edit = new AdminMovieInput(
                "Starlight Archive (Adopted)", "starlight-archive", "An editor-owned synopsis.",
                2026, "PG", List.of(adventureId), true);
        admin.update(ACTOR, STARLIGHT, "\"0\"", "adopt-test", edit);

        assertThat(revisions.current()).isEqualTo(cachedRevision + 1);
        assertThat(catalog.movie("starlight-archive").title()).isEqualTo("Starlight Archive (Adopted)");

        byte[] nextManifest = manifestText()
                .replace("\"catalog-v3\"", "\"catalog-test-adopt\"")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(importer.importManifest(nextManifest)).isTrue();

        assertThat(jdbc.queryForObject(
                "select management_mode from movie where id=?", String.class, STARLIGHT)).isEqualTo("MANAGED");
        assertThat(catalog.movie("starlight-archive").title()).isEqualTo("Starlight Archive (Adopted)");
        assertThat(catalog.movie("starlight-archive").genres()).containsExactly("Adventure");
    }

    private String manifestText() throws Exception {
        try (var input = getClass().getResourceAsStream("/catalog/catalog-v3.json")) {
            if (input == null) {
                throw new IllegalStateException("Catalog manifest test resource is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
