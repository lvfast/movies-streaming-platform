package com.lvfast.streaming.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lvfast.streaming.MediaStreamingApplication;
import com.lvfast.streaming.catalog.MovieNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = MediaStreamingApplication.class, properties = "app.auth.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class PlaybackTestcontainersTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID PLAYABLE_MOVIE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID UNPLAYABLE_MOVIE = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant EARLIER = Instant.parse("2026-09-07T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-07T00:01:00Z");

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
    @Autowired PlaybackService playback;

    @BeforeEach
    void createUser() {
        jdbc.update("""
                insert into app_user(id, username, password_hash)
                values (?, 'playback_user', 'test-hash')
                on conflict (id) do nothing
                """, USER_ID);
        jdbc.update("delete from viewing_progress where user_id=?", USER_ID);
    }

    @Test
    void playbackExposesOnlyPublishedPlayableMovies() {
        Playback metadata = playback.playback(USER_ID, PLAYABLE_MOVIE);

        assertThat(metadata.manifestUrl()).isEqualTo("/media/fixtures/starlight-archive/index.m3u8");
        assertThat(metadata.resumePositionSeconds()).isZero();
        assertThatThrownBy(() -> playback.playback(USER_ID, UNPLAYABLE_MOVIE))
                .isInstanceOf(MovieNotFoundException.class);
    }

    @Test
    @Transactional
    void unpublishedPlayableMovieIsNotExposed() {
        jdbc.update("update movie set published=false where id=?", PLAYABLE_MOVIE);

        assertThatThrownBy(() -> playback.playback(USER_ID, PLAYABLE_MOVIE))
                .isInstanceOf(MovieNotFoundException.class);
    }

    @Test
    void progressPersistsAndCompletionBeginsAtNinetyPercent() {
        ViewingProgress incomplete = playback.updateProgress(
                USER_ID, PLAYABLE_MOVIE, 89, 100, EARLIER);

        assertThat(incomplete.completed()).isFalse();
        assertThat(playback.playback(USER_ID, PLAYABLE_MOVIE).resumePositionSeconds()).isEqualTo(89);

        ViewingProgress completed = playback.updateProgress(
                USER_ID, PLAYABLE_MOVIE, 90, 100, LATER);

        assertThat(completed.completed()).isTrue();
        assertThat(playback.playback(USER_ID, PLAYABLE_MOVIE).resumePositionSeconds()).isZero();
    }

    @Test
    void olderClientUpdatesCannotOverwriteNewerProgress() {
        ViewingProgress newer = playback.updateProgress(
                USER_ID, PLAYABLE_MOVIE, 80, 100, LATER);
        ViewingProgress resultOfStaleWrite = playback.updateProgress(
                USER_ID, PLAYABLE_MOVIE, 20, 100, EARLIER);

        assertThat(newer.positionSeconds()).isEqualTo(80);
        assertThat(resultOfStaleWrite.positionSeconds()).isEqualTo(80);
        assertThat(resultOfStaleWrite.clientUpdatedAt()).isEqualTo(LATER);
        assertThat(playback.playback(USER_ID, PLAYABLE_MOVIE).resumePositionSeconds()).isEqualTo(80);
    }
}
