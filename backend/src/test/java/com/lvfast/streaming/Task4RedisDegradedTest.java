package com.lvfast.streaming;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvfast.streaming.common.RequestIdFilter;
import com.lvfast.streaming.identity.AccessTokenIssuer;
import com.lvfast.streaming.identity.UserAccount;
import com.lvfast.streaming.identity.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = MediaStreamingApplication.class, properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=100ms"
})
@Testcontainers(disabledWithoutDocker = true)
class Task4RedisDegradedTest {

    private static final String MOVIE_ID = "00000000-0000-0000-0000-000000000001";
    private static final KeyFiles KEYS = createKeyFiles();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.auth.private-key-location", () -> KEYS.privateKey().toUri().toString());
        registry.add("app.auth.public-key-location", () -> KEYS.publicKey().toUri().toString());
    }

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired UserAccountRepository users;
    @Autowired AccessTokenIssuer accessTokens;
    MockMvc mvc;
    String accessToken;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter)
                .apply(springSecurity())
                .build();
        Instant now = Instant.now();
        UserAccount user = users.save(UserAccount.register("redis_down_user", "test-hash", now));
        accessToken = accessTokens.issue(user, java.util.Set.of("USER"), now.minusSeconds(1), Duration.ofMinutes(15));
    }

    @Test
    void existingBearerTokenCanUseDatabaseBackedEndpointsWithoutRedis() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .header("X-Request-Id", "redis-down-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new_user","password":"LongEnough9X"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").value("redis-down-register"));
        mvc.perform(post("/api/v1/auth/login")
                        .header("X-Request-Id", "redis-down-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"redis_down_user","password":"LongEnough9X"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").value("redis-down-login"));
        mvc.perform(post("/api/v1/auth/refresh")
                        .header("X-Request-Id", "redis-down-refresh"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").value("redis-down-refresh"));

        mvc.perform(put("/api/v1/me/watchlist/{movieId}", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/watchlist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(put("/api/v1/me/progress/{movieId}", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"positionSeconds":1,"durationSeconds":2,
                                 "clientUpdatedAt":"2026-09-07T00:00:00Z"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/movies/{movieId}/playback", MOVIE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumePositionSeconds").value(1));
    }

    private static KeyFiles createKeyFiles() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Path privateKey = Files.createTempFile("task4-redis-down-jwt-private-", ".txt");
            Path publicKey = Files.createTempFile("task4-redis-down-jwt-public-", ".txt");
            Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
            Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
            privateKey.toFile().deleteOnExit();
            publicKey.toFile().deleteOnExit();
            return new KeyFiles(privateKey, publicKey);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create Redis-degraded JWT test keys", exception);
        }
    }

    private static String pem(String type, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private record KeyFiles(Path privateKey, Path publicKey) {}
}
