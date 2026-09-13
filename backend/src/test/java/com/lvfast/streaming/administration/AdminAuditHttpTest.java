package com.lvfast.streaming.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.ops.AdminRoleCommand;
import com.lvfast.streaming.support.ApiTestSupport;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * HTTP behavior for the P7 audit history endpoint. Filters and stable pagination are exercised, and a
 * secret-like field written into a stored payload must never leave the API.
 */
class AdminAuditHttpTest extends ApiTestSupport {

    /**
     * Gives this class its own Spring context so it cannot reuse a cached context whose Testcontainers
     * Postgres port was remapped after the previous test class stopped the shared container.
     */
    @DynamicPropertySource
    static void contextKey(DynamicPropertyRegistry registry) {
        registry.add("app.test.context-key", () -> "p7-audit");
    }

    @Autowired AdminRoleCommand roleCommand;

    @Test
    void listsEditorialAuditEventsWithFiltersAndPagination() {
        String token = admin("p7_audit_admin");
        UUID movieId = movie(token, "p7-audit");

        Result all = get("/api/v1/admin/audit?size=50", token);
        assertThat(all.status()).isEqualTo(200);
        JsonNode page = bodyOf(all);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(50);
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(1);

        Result created = get("/api/v1/admin/audit?action=MOVIE_CREATED&entityType=MOVIE", token);
        assertThat(created.status()).isEqualTo(200);
        JsonNode createdPage = bodyOf(created);
        assertThat(createdPage.get("total").asLong()).isEqualTo(1);
        JsonNode event = createdPage.get("items").get(0);
        assertThat(event.get("action").asText()).isEqualTo("MOVIE_CREATED");
        assertThat(event.get("entityType").asText()).isEqualTo("MOVIE");
        assertThat(event.get("entityId").asText()).isEqualTo(movieId.toString());
        assertThat(event.get("actorType").asText()).isEqualTo("USER");
        assertThat(event.has("createdAt")).isTrue();

        Result scoped = get("/api/v1/admin/audit?entityId=" + movieId, token);
        assertThat(scoped.status()).isEqualTo(200);
        assertThat(bodyOf(scoped).get("total").asLong()).isEqualTo(1);

        Result empty = get("/api/v1/admin/audit?action=NO_SUCH_ACTION", token);
        assertThat(empty.status()).isEqualTo(200);
        assertThat(bodyOf(empty).get("total").asLong()).isZero();

        String viewer = registerAndToken("p7_audit_viewer");
        assertThat(get("/api/v1/admin/audit", viewer).status()).isEqualTo(403);
    }

    @Test
    void neverSerializesSecretLikeFieldsFromStoredPayloads() {
        String token = admin("p7_audit_redaction");
        UUID movieId = movie(token, "p7-audit-redaction");
        jdbc.update("""
                insert into audit_event(actor_id, actor_type, action, entity_type, entity_id,
                                        request_id, before, after)
                values (?, 'USER', 'MOVIE_UPDATED', 'MOVIE', ?, 'request-1',
                        '{"title":"old","password":"hunter2","mediaToken":"jwt-value"}'::jsonb,
                        '{"title":"new","secretKey":"storage-secret","authorization":"Bearer x"}'::jsonb)
                """, UUID.randomUUID(), movieId);

        Result response = get("/api/v1/admin/audit?action=MOVIE_UPDATED", token);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("old", "new")
                .doesNotContain("hunter2", "jwt-value", "storage-secret", "Bearer x",
                        "password", "mediaToken", "secretKey", "authorization");
        JsonNode event = bodyOf(response).get("items").get(0);
        assertThat(event.get("before").get("title").asText()).isEqualTo("old");
        assertThat(event.get("after").get("title").asText()).isEqualTo("new");
        assertThat(event.get("before").has("password")).isFalse();
        assertThat(event.get("after").has("secretKey")).isFalse();
    }

    @Test
    void redactsSecretLikeFieldsInsideNestedObjectsAndLists() {
        String token = admin("p7_audit_nested");
        UUID movieId = movie(token, "p7-audit-nested");
        jdbc.update("""
                insert into audit_event(actor_id, actor_type, action, entity_type, entity_id,
                                        request_id, before, after)
                values (?, 'USER', 'MOVIE_UPDATED', 'MOVIE', ?, 'request-1', '{}'::jsonb,
                        '{"nested":{"mediaToken":"nested-secret","title":"keep"},
                          "items":[{"password":"list-secret"},{"title":"listed"}]}'::jsonb)
                """, UUID.randomUUID(), movieId);

        Result response = get("/api/v1/admin/audit?action=MOVIE_UPDATED", token);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("keep", "listed")
                .doesNotContain("nested-secret", "list-secret", "mediaToken", "password");
        JsonNode after = bodyOf(response).get("items").get(0).get("after");
        assertThat(after.get("nested").get("title").asText()).isEqualTo("keep");
        assertThat(after.get("nested").has("mediaToken")).isFalse();
        assertThat(after.get("items").get(0).has("password")).isFalse();
        assertThat(after.get("items").get(1).get("title").asText()).isEqualTo("listed");
    }

    private UUID movie(String token, String slug) {
        Result created = postAdminJson("/api/v1/admin/movies", token, "movie-" + slug,
                "{\"title\":\"" + slug + "\",\"slug\":\"" + slug
                        + "\",\"synopsis\":\"Audit.\",\"releaseYear\":2026,"
                        + "\"maturityRating\":\"PG\",\"genreIds\":[],\"featured\":false}");
        assertThat(created.status()).isEqualTo(201);
        return idOf(bodyOf(created));
    }

    private String admin(String username) {
        String token = registerAndToken(username);
        roleCommand.grantAdmin(username);
        return token;
    }
}
