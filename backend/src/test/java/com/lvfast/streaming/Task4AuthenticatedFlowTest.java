package com.lvfast.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvfast.streaming.common.RequestIdFilter;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = MediaStreamingApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class Task4AuthenticatedFlowTest {

    private static final String MOVIE_ID = "00000000-0000-0000-0000-000000000001";
    private static final KeyFiles KEYS = createKeyFiles();

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
        registry.add("app.auth.private-key-location", () -> KEYS.privateKey().toUri().toString());
        registry.add("app.auth.public-key-location", () -> KEYS.publicKey().toUri().toString());
        registry.add("app.auth.secure-cookie", () -> "true");
    }

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired ObjectMapper json;
    MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void refreshReuseCommitsFamilyRevocationDespiteTheUnauthorizedResponse() throws Exception {
        MvcResult registration = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"task8_reuse","password":"LongEnough9X"}
                                """))
                .andExpect(status().isCreated()).andReturn();
        Cookie original = registration.getResponse().getCookie("__Host-refresh_token");
        MvcResult rotation = mvc.perform(post("/api/v1/auth/refresh").cookie(original))
                .andExpect(status().isOk()).andReturn();
        Cookie child = rotation.getResponse().getCookie("__Host-refresh_token");
        assertThat(child).isNotNull();
        assertThat(child.getValue()).isNotEqualTo(original.getValue());

        mvc.perform(post("/api/v1/auth/refresh").cookie(original))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE"));
        mvc.perform(post("/api/v1/auth/refresh").cookie(child))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationThroughLogoutCoversWatchlistPlaybackAndResume() throws Exception {
        MvcResult registration = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"task4_flow","password":"LongEnough9X"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = json.readTree(registration.getResponse().getContentAsString())
                .get("accessToken").asText();
        Cookie refreshCookie = registration.getResponse().getCookie("__Host-refresh_token");
        assertThat(refreshCookie).isNotNull();

        mvc.perform(put("/api/v1/me/watchlist/{movieId}", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/me/watchlist/{movieId}", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/watchlist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(MOVIE_ID))
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(delete("/api/v1/me/watchlist/{movieId}", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/me/watchlist/{movieId}", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/watchlist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/v1/movies/{movieId}/playback", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumePositionSeconds").value(0));
        mvc.perform(put("/api/v1/me/progress/{movieId}", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header("X-Request-Id", "extra-progress-field")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"positionSeconds":1,"durationSeconds":2,
                                 "clientUpdatedAt":"2026-09-07T00:01:00Z","unexpected":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("extra-progress-field"));
        mvc.perform(put("/api/v1/me/progress/{movieId}", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"positionSeconds":1,"durationSeconds":2,
                                 "clientUpdatedAt":"2026-09-07T00:01:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false));
        mvc.perform(get("/api/v1/movies/{movieId}/playback", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumePositionSeconds").value(1));

        mvc.perform(post("/api/v1/auth/logout").cookie(refreshCookie))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE"));
    }

    private static KeyFiles createKeyFiles() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Path privateKey = Files.createTempFile("task4-jwt-private-", ".txt");
            Path publicKey = Files.createTempFile("task4-jwt-public-", ".txt");
            Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
            Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
            privateKey.toFile().deleteOnExit();
            publicKey.toFile().deleteOnExit();
            return new KeyFiles(privateKey, publicKey);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create Task 4 JWT test keys", exception);
        }
    }

    private static String pem(String type, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private record KeyFiles(Path privateKey, Path publicKey) {}
}
