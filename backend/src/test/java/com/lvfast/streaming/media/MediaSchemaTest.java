package com.lvfast.streaming.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lvfast.streaming.MediaStreamingApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
class MediaSchemaTest {

    private static final String FINGERPRINT = "sha256:" + "0".repeat(64);

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

    @Test
    void createsTheMediaUploadAndJobTables() {
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema='public' and table_name in
                  ('media_version','media_asset','upload_session','media_job',
                   'media_job_attempt','outbox_event','inbox_event')
                """, Integer.class)).isEqualTo(7);
    }

    @Test
    void enforcesOneNonterminalVideoReplacementPerMovie() {
        UUID movieId = insertManagedMovie("schema-video");

        UUID first = insertVersion(movieId, "UPLOADING");
        assertThat(first).isNotNull();
        assertThatThrownBy(() -> insertVersion(movieId, "QUEUED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A terminal version does not block a later replacement.
        jdbc.update("update media_version set state='READY' where id=?", first);
        assertThat(insertVersion(movieId, "UPLOADING")).isNotNull();
    }

    @Test
    void rejectsInvalidStatesAndMismatchedSessionShape() {
        UUID movieId = insertManagedMovie("schema-shape");

        assertThatThrownBy(() -> insertVersion(movieId, "BOGUS"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID versionId = insertVersion(movieId, "UPLOADING");
        assertThatThrownBy(() -> jdbc.update("""
                insert into upload_session(id, movie_id, media_version_id, kind, state, object_key,
                                           content_type, part_size_bytes, total_parts, declared_bytes,
                                           resume_fingerprint, expires_at)
                values (gen_random_uuid(), ?, null, 'VIDEO', 'OPEN', 'source/k', 'video/mp4', 5242880, 1, 100,
                        ?, now())
                """, movieId, FINGERPRINT)).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                insert into upload_session(id, movie_id, media_version_id, kind, state, object_key,
                                           content_type, part_size_bytes, total_parts, declared_bytes,
                                           resume_fingerprint, expires_at)
                values (gen_random_uuid(), ?, ?, 'VIDEO', 'OPEN', 'source/k', 'video/mp4', 5242880, 1, 100,
                        'sha256:NOTHEX', now())
                """, movieId, versionId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesOneActiveJobPerVersionAndOnePerArtworkKind() {
        UUID movieId = insertManagedMovie("schema-jobs");
        UUID versionId = insertVersion(movieId, "QUEUED");
        UUID posterId = insertAsset(movieId, "POSTER");

        assertThat(insertJob(movieId, versionId, null)).isNotNull();
        assertThatThrownBy(() -> insertJob(movieId, versionId, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(insertJob(movieId, null, posterId)).isNotNull();
        assertThatThrownBy(() -> insertJob(movieId, null, posterId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertManagedMovie(String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into movie(id, slug, title, synopsis, release_year, maturity_rating,
                                  featured, published, management_mode, lifecycle, revision)
                values (?, ?, 'Schema Test', '', 2026, 'PG', false, false, 'MANAGED', 'DRAFT', 0)
                """, id, slug);
        return id;
    }

    private UUID insertVersion(UUID movieId, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into media_version(id, movie_id, state, source_key) values (?, ?, ?, ?)
                """, id, movieId, state, "source/" + movieId + "/" + id + "/original");
        return id;
    }

    private UUID insertAsset(UUID movieId, String kind) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into media_asset(id, movie_id, kind, state, source_key) values (?, ?, ?, 'UPLOADING', ?)
                """, id, movieId, kind, "source/" + movieId + "/" + id + "/original");
        return id;
    }

    private UUID insertJob(UUID movieId, UUID versionId, UUID assetId) {
        UUID id = UUID.randomUUID();
        String kind = versionId != null ? "TRANSCODE" : "ARTWORK";
        jdbc.update("""
                insert into media_job(id, movie_id, media_version_id, asset_id, kind, state)
                values (?, ?, ?, ?, ?, 'QUEUED')
                """, id, movieId, versionId, assetId, kind);
        return id;
    }
}
