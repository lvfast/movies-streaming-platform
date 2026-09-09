package com.lvfast.streaming;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvfast.streaming.catalog.MoviePage;
import com.lvfast.streaming.catalog.MovieSummary;
import com.lvfast.streaming.common.ApiExceptionHandler;
import com.lvfast.streaming.common.RequestIdFilter;
import com.lvfast.streaming.identity.AuthRateLimiter;
import com.lvfast.streaming.identity.SecurityConfiguration;
import com.lvfast.streaming.library.LibraryController;
import com.lvfast.streaming.library.LibraryService;
import com.lvfast.streaming.playback.Playback;
import com.lvfast.streaming.playback.PlaybackController;
import com.lvfast.streaming.playback.PlaybackService;
import com.lvfast.streaming.playback.ProgressValidationException;
import com.lvfast.streaming.playback.ViewingProgress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig
@ContextConfiguration(classes = Task4SecurityHttpTest.TestConfig.class)
class Task4SecurityHttpTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID MOVIE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CLIENT_TIME = Instant.parse("2026-09-07T00:00:00Z");

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired LibraryService library;
    @Autowired PlaybackService playback;
    MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void allTaskFourRoutesRequireABearerToken() throws Exception {
        assertAuthenticationRequired(get("/api/v1/me/watchlist"));
        assertAuthenticationRequired(put("/api/v1/me/watchlist/{movieId}", MOVIE_ID));
        assertAuthenticationRequired(delete("/api/v1/me/watchlist/{movieId}", MOVIE_ID));
        assertAuthenticationRequired(get("/api/v1/movies/{movieId}/playback", MOVIE_ID));
        assertAuthenticationRequired(put("/api/v1/me/progress/{movieId}", MOVIE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"positionSeconds":1,"durationSeconds":2,"clientUpdatedAt":"2026-09-07T00:00:00Z"}
                        """));
    }

    @Test
    void authenticatedWatchlistRoutesMatchTheOpenApiContract() throws Exception {
        MovieSummary movie = new MovieSummary(
                MOVIE_ID, "starlight-archive", "Starlight Archive", 2026, 2, "PG",
                "http://localhost/poster.svg", "http://localhost/backdrop.svg", List.of("Adventure"));
        when(library.watchlist(USER_ID, 0, 20))
                .thenReturn(new MoviePage(List.of(movie), 0, 20, 1));

        mvc.perform(get("/api/v1/me/watchlist").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(MOVIE_ID.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(put("/api/v1/me/watchlist/{movieId}", MOVIE_ID).with(userJwt()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(delete("/api/v1/me/watchlist/{movieId}", MOVIE_ID).with(userJwt()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void authenticatedPlaybackAndProgressRoutesMatchTheOpenApiContract() throws Exception {
        when(playback.playback(USER_ID, MOVIE_ID))
                .thenReturn(new Playback(MOVIE_ID, "/media/movie/index.m3u8", 17));
        when(playback.updateProgress(USER_ID, MOVIE_ID, 90, 100, CLIENT_TIME))
                .thenReturn(new ViewingProgress(MOVIE_ID, 90, 100, CLIENT_TIME, true));

        mvc.perform(get("/api/v1/movies/{movieId}/playback", MOVIE_ID).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieId").value(MOVIE_ID.toString()))
                .andExpect(jsonPath("$.manifestUrl").value("/media/movie/index.m3u8"))
                .andExpect(jsonPath("$.resumePositionSeconds").value(17));

        mvc.perform(put("/api/v1/me/progress/{movieId}", MOVIE_ID)
                        .with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"positionSeconds":90,"durationSeconds":100,
                                 "clientUpdatedAt":"2026-09-07T00:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieId").value(MOVIE_ID.toString()))
                .andExpect(jsonPath("$.positionSeconds").value(90))
                .andExpect(jsonPath("$.durationSeconds").value(100))
                .andExpect(jsonPath("$.clientUpdatedAt").value("2026-09-07T00:00:00Z"))
                .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void invalidProgressAndMalformedMovieIdUseStableProblemDetails() throws Exception {
        when(playback.updateProgress(USER_ID, MOVIE_ID, 101, 100, CLIENT_TIME))
                .thenThrow(new ProgressValidationException("Position cannot exceed duration"));

        mvc.perform(put("/api/v1/me/progress/{movieId}", MOVIE_ID)
                        .with(userJwt())
                        .header("X-Request-Id", "bad-progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"positionSeconds":101,"durationSeconds":100,
                                 "clientUpdatedAt":"2026-09-07T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("bad-progress"));

        mvc.perform(get("/api/v1/movies/not-a-uuid/playback")
                        .with(userJwt())
                        .header("X-Request-Id", "bad-movie-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("bad-movie-id"));
    }

    @Test
    void malformedProgressJsonUsesStableProblemDetails() throws Exception {
        mvc.perform(put("/api/v1/me/progress/{movieId}", MOVIE_ID)
                        .with(userJwt())
                        .header("X-Request-Id", "bad-progress-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"positionSeconds":1,"durationSeconds":2,
                                 "clientUpdatedAt":"not-a-date"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("bad-progress-json"));
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
            userJwt() {
        return jwt().jwt(token -> token.subject(USER_ID.toString()).claim("username", "task4_user"));
    }

    private void assertAuthenticationRequired(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.header("X-Request-Id", "task4-auth-required"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            LibraryController.class,
            PlaybackController.class,
            SecurityConfiguration.class,
            ApiExceptionHandler.class
    })
    static class TestConfig {
        @Bean LibraryService libraryService() {
            return mock(LibraryService.class);
        }

        @Bean PlaybackService playbackService() {
            return mock(PlaybackService.class);
        }

        @Bean AuthRateLimiter authRateLimiter() {
            return mock(AuthRateLimiter.class);
        }

        @Bean JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean RequestIdFilter requestIdFilter() {
            return new RequestIdFilter();
        }
    }
}
