package com.lvfast.streaming.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lvfast.streaming.identity.RoleService;
import com.lvfast.streaming.ops.AdminRoleCommand;
import com.lvfast.streaming.ops.LastAdminRoleException;
import com.lvfast.streaming.support.ApiTestSupport;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminFoundationHttpTest extends ApiTestSupport {

    @Autowired RoleService roles;
    @Autowired AdminRoleCommand roleCommand;

    @Test
    void adminRoutesWithoutTokenReturn401() {
        assertThat(getNoAuth("/api/v1/admin/movies").status()).isEqualTo(401);
    }

    @Test
    void enforcesCurrentDatabaseAdminRole() {
        String token = registerAndToken("admin_operator");

        assertThat(get("/api/v1/admin/movies", token).status()).isEqualTo(403);

        roleCommand.grantAdmin("admin_operator");
        assertThat(get("/api/v1/admin/movies", token).status()).isEqualTo(200);

        roles.revoke(userIdOf("admin_operator"), "ADMIN");
        assertThat(get("/api/v1/admin/movies", token).status()).isEqualTo(403);
    }

    @Test
    void registrationGrantsUserRoleAndRejectsSuppliedRoles() {
        Result response = postJson(
                "/api/v1/auth/register", "{\"username\":\"fresh_user\",\"password\":\"LongEnough9X\"}");
        assertThat(response.status()).isEqualTo(201);
        JsonNode user = bodyOf(response).get("user");
        assertThat(stringList(user.get("roles"))).containsExactly("USER");

        Result rejected = postJson(
                "/api/v1/auth/register",
                "{\"username\":\"fresh_user_two\",\"password\":\"LongEnough9X\",\"roles\":[\"ADMIN\"]}");
        assertThat(rejected.status()).isEqualTo(400);
    }

    @Test
    void operatorCommandGrantsRevokesAndProtectsLastAdmin() {
        registerAndToken("first_admin");
        registerAndToken("second_admin");

        roleCommand.grantAdmin("first_admin");
        roleCommand.grantAdmin("second_admin");
        assertThat(roles.isAdmin(userIdOf("first_admin"))).isTrue();
        assertThat(roles.isAdmin(userIdOf("second_admin"))).isTrue();
        assertThat(auditCount("ROLE_GRANTED")).isEqualTo(2);

        roleCommand.revokeAdmin("second_admin");
        assertThat(roles.isAdmin(userIdOf("second_admin"))).isFalse();
        assertThat(auditCount("ROLE_REVOKED")).isEqualTo(1);

        assertThatThrownBy(() -> roleCommand.revokeAdmin("first_admin"))
                .isInstanceOf(LastAdminRoleException.class);
        assertThat(roles.isAdmin(userIdOf("first_admin"))).isTrue();
    }

    @Test
    void exposesSportGenreToEditors() {
        String token = registerAndToken("admin_sport");
        roleCommand.grantAdmin("admin_sport");

        Result response = get("/api/v1/admin/genres", token);
        assertThat(response.status()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        bodyOf(response).get("items").forEach(node -> names.add(node.get("name").asText()));

        assertThat(names).contains("Sport");
    }

    @Test
    void adminCreatesReadsListsUpdatesDraft() {
        String token = registerAndToken("admin_editor");
        roleCommand.grantAdmin("admin_editor");
        List<Integer> genreIds = genreIds(token);
        assertThat(genreIds).isNotEmpty();

        Result created = postAdminJson(
                "/api/v1/admin/movies", token, "create-draft-1",
                movieBody("draft-movie", "Draft Movie", 2026, "PG", List.of(genreIds.get(0)), false));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.etag()).isEqualTo("\"0\"");
        JsonNode draft = bodyOf(created);
        assertThat(draft.get("lifecycle").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("managementMode").asText()).isEqualTo("MANAGED");
        assertThat(draft.get("runtimeSeconds").isNull()).isTrue();
        assertThat(draft.get("revision").asLong()).isZero();
        UUID movieId = idOf(draft);

        Result fetched = get("/api/v1/admin/movies/" + movieId, token);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.etag()).isEqualTo("\"0\"");

        Result updated = putJson(
                "/api/v1/admin/movies/" + movieId, token, "\"0\"",
                movieBody("draft-movie", "Draft Movie Edited", 2027, "PG-13", List.of(genreIds.get(0)), true));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.etag()).isEqualTo("\"1\"");
        assertThat(bodyOf(updated).get("title").asText()).isEqualTo("Draft Movie Edited");

        Result list = get("/api/v1/admin/movies", token);
        assertThat(list.status()).isEqualTo(200);
        assertThat(slugsIn(list)).contains("draft-movie");

        Result rejected = postAdminJson(
                "/api/v1/admin/movies", token, "create-draft-2",
                movieBody("draft-movie-two", "Draft Movie Two", 2026, "PG", List.of(), false)
                        .replace("}", ",\"runtimeSeconds\":120}"));
        assertThat(rejected.status()).isEqualTo(400);
    }

    @Test
    void concurrentUpdatesWithSameEtagYieldOneSuccessAndOnePrecondition() throws Exception {
        String token = registerAndToken("admin_racer");
        roleCommand.grantAdmin("admin_racer");
        List<Integer> genreIds = genreIds(token);

        Result created = postAdminJson(
                "/api/v1/admin/movies", token, "create-race-movie",
                movieBody("race-movie", "Race Movie", 2026, "PG", List.of(), false));
        assertThat(created.status()).isEqualTo(201);
        UUID movieId = idOf(bodyOf(created));

        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                start.await();
                return putJson("/api/v1/admin/movies/" + movieId, token, "\"0\"",
                        movieBody("race-movie", "Race One", 2026, "PG", genreIds, false)).status();
            });
            Future<Integer> second = executor.submit(() -> {
                start.await();
                return putJson("/api/v1/admin/movies/" + movieId, token, "\"0\"",
                        movieBody("race-movie", "Race Two", 2026, "PG", genreIds, false)).status();
            });
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 412);
        }
    }

    @Test
    void repeatedCreateReturnsTheSameMovie() {
        String token = registerAndToken("admin_idem");
        roleCommand.grantAdmin("admin_idem");

        String body = movieBody("idem-movie", "Idem Movie", 2026, "PG", List.of(), false);
        Result first = postAdminJson("/api/v1/admin/movies", token, "same-key", body);
        Result second = postAdminJson("/api/v1/admin/movies", token, "same-key", body);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(idOf(bodyOf(first))).isEqualTo(idOf(bodyOf(second)));
    }

    private List<Integer> genreIds(String token) {
        Result response = get("/api/v1/admin/genres", token);
        assertThat(response.status()).isEqualTo(200);
        JsonNode items = bodyOf(response).get("items");
        List<Integer> ids = new ArrayList<>();
        items.forEach(node -> ids.add(node.get("id").asInt()));
        return ids;
    }

    private String movieBody(String slug, String title, int year, String rating, List<Integer> genreIds, boolean featured) {
        String genres = genreIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return "{\"title\":\"" + title + "\",\"slug\":\"" + slug
                + "\",\"synopsis\":\"A managed draft synopsis.\",\"releaseYear\":" + year
                + ",\"maturityRating\":\"" + rating + "\",\"genreIds\":[" + genres + "],\"featured\":" + featured + "}";
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private List<String> slugsIn(Result response) {
        JsonNode items = bodyOf(response).get("items");
        List<String> slugs = new ArrayList<>();
        items.forEach(node -> slugs.add(node.get("slug").asText()));
        return slugs;
    }

    private UUID userIdOf(String username) {
        return jdbc.queryForObject("select id from app_user where username=?", UUID.class, username);
    }

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject(
                "select count(*) from audit_event where action=?", Integer.class, action);
        return count == null ? 0 : count;
    }
}
