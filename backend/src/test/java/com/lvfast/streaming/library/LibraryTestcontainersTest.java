package com.lvfast.streaming.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lvfast.streaming.MediaStreamingApplication;
import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.catalog.MovieSummary;
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
@org.springframework.test.context.ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LibraryTestcontainersTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PLAYABLE_MOVIE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_MOVIE = UUID.fromString("00000000-0000-0000-0000-000000000002");

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
    @Autowired LibraryService library;

    @BeforeEach
    void createUser() {
        jdbc.update("""
                insert into app_user(id, username, password_hash)
                values (?, 'library_user', 'test-hash')
                on conflict (id) do nothing
                """, USER_ID);
        jdbc.update("delete from watchlist where user_id=?", USER_ID);
    }

    @Test
    void repeatedAddsAndRemovesAreIdempotent() {
        library.add(USER_ID, PLAYABLE_MOVIE);
        library.add(USER_ID, PLAYABLE_MOVIE);

        assertThat(library.watchlist(USER_ID, 0, 20).total()).isEqualTo(1);

        library.remove(USER_ID, PLAYABLE_MOVIE);
        library.remove(USER_ID, PLAYABLE_MOVIE);

        assertThat(library.watchlist(USER_ID, 0, 20).total()).isZero();
    }

    @Test
    void watchlistUsesNewestFirstPaginationAndExactTotals() {
        library.add(USER_ID, PLAYABLE_MOVIE);
        library.add(USER_ID, SECOND_MOVIE);
        jdbc.update("update watchlist set added_at='2026-09-07T00:00:00Z' where user_id=? and movie_id=?",
                USER_ID, PLAYABLE_MOVIE);
        jdbc.update("update watchlist set added_at='2026-09-07T00:00:01Z' where user_id=? and movie_id=?",
                USER_ID, SECOND_MOVIE);

        assertThat(library.watchlist(USER_ID, 0, 1).items())
                .extracting(MovieSummary::id)
                .containsExactly(SECOND_MOVIE);
        assertThat(library.watchlist(USER_ID, 0, 1).total()).isEqualTo(2);
        assertThat(library.watchlist(USER_ID, 1, 1).items())
                .extracting(MovieSummary::id)
                .containsExactly(PLAYABLE_MOVIE);
    }

    @Test
    @Transactional
    void unpublishedAndUnknownMoviesCannotBeAdded() {
        jdbc.update("update movie set published=false where id=?", PLAYABLE_MOVIE);

        assertThatThrownBy(() -> library.add(USER_ID, PLAYABLE_MOVIE))
                .isInstanceOf(MovieNotFoundException.class);
        assertThatThrownBy(() -> library.add(
                USER_ID, UUID.fromString("00000000-0000-0000-0000-000000000099")))
                .isInstanceOf(MovieNotFoundException.class);
        assertThat(library.watchlist(USER_ID, 0, 20).items()).isEmpty();
    }
}
