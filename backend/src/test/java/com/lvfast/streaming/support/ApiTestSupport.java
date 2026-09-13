package com.lvfast.streaming.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.MediaStreamingApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared real-HTTP, random-port test harness backed by Testcontainers Postgres and Redis. The
 * application runs with authentication enabled so admin authorization is exercised end to end.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MediaStreamingApplication.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class ApiTestSupport {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static final TestKeyFiles.Pair KEYS = TestKeyFiles.generate("admin-foundation-jwt");
    private static final TestKeyFiles.Pair MEDIA_KEYS = TestKeyFiles.generate("admin-media-jwt");

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.auth.private-key-location", KEYS::privateKeyLocation);
        registry.add("app.auth.public-key-location", KEYS::publicKeyLocation);
        registry.add("app.media.signing.private-key-location", MEDIA_KEYS::privateKeyLocation);
        registry.add("app.media.signing.public-key-location", MEDIA_KEYS::publicKeyLocation);
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;

    @Value("${local.server.port}")
    protected int port;

    private HttpClient client;

    @BeforeEach
    void clearResidualData() {
        jdbc.update("delete from operation_request");
        jdbc.update("delete from audit_event");
        jdbc.update("delete from user_role");
        client = HttpClient.newHttpClient();
    }

    protected Result postJson(String url, String body) {
        return send("POST", url, null, Map.of(), body);
    }

    protected Result get(String url, String token) {
        return send("GET", url, token, Map.of(), null);
    }

    protected Result getNoAuth(String url) {
        return send("GET", url, null, Map.of(), null);
    }

    protected Result putJson(String url, String token, String ifMatch, String body) {
        Map<String, String> headers = ifMatch == null ? Map.of() : Map.of("If-Match", ifMatch);
        return send("PUT", url, token, headers, body);
    }

    protected Result postAdminJson(String url, String token, String idempotencyKey, String body) {
        Map<String, String> headers =
                idempotencyKey == null ? Map.of() : Map.of("Idempotency-Key", idempotencyKey);
        return send("POST", url, token, headers, body);
    }

    /**
     * POST with both a required {@code Idempotency-Key} and a required {@code If-Match} revision,
     * used by the publication and activation commands.
     */
    protected Result postAdminCommand(
            String url, String token, String idempotencyKey, String ifMatch, String body) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (idempotencyKey != null) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        if (ifMatch != null) {
            headers.put("If-Match", ifMatch);
        }
        return send("POST", url, token, headers, body);
    }

    protected Result postNoBody(String url, String token, Map<String, String> headers) {
        return send("POST", url, token, headers, null);
    }

    protected String registerAndToken(String username) {
        Result response = postJson("/api/v1/auth/register",
                "{\"username\":\"" + username + "\",\"password\":\"LongEnough9X\"}");
        assertThat(response.status()).isEqualTo(201);
        return tokenFrom(response);
    }

    protected String tokenFrom(Result response) {
        try {
            return json.readTree(response.body()).get("accessToken").asText();
        } catch (Exception unreadable) {
            throw new IllegalStateException("Auth response has no accessToken", unreadable);
        }
    }

    protected JsonNode bodyOf(Result response) {
        try {
            return json.readTree(response.body());
        } catch (Exception unreadable) {
            throw new IllegalStateException("Response body is not JSON", unreadable);
        }
    }

    protected UUID idOf(JsonNode body) {
        return UUID.fromString(body.get("id").asText());
    }

    private Result send(String method, String url, String token, Map<String, String> headers, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + url));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(response.statusCode(), response.body(), response.headers().firstValue("ETag").orElse(null));
        } catch (Exception failure) {
            throw new IllegalStateException("HTTP request failed: " + method + " " + url, failure);
        }
    }

    protected record Result(int status, String body, String etag) {
    }
}
