package com.lvfast.streaming.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvfast.streaming.MediaStreamingApplication;
import com.lvfast.streaming.common.RequestIdFilter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = MediaStreamingApplication.class)
@EnabledIfSystemProperty(named = "catalog.external-it", matches = "true")
@TestPropertySource(properties = {
        "spring.datasource.url=${catalog.jdbc-url:jdbc:postgresql://host.docker.internal:5432/media_streaming}",
        "spring.datasource.username=${catalog.jdbc-username:media_streaming}",
        "spring.datasource.password=${catalog.jdbc-password:local-only-change-me}",
        "spring.data.redis.host=${catalog.redis-host:host.docker.internal}",
        "spring.data.redis.port=${catalog.redis-port:6379}",
        "app.auth.enabled=false"
})
class CatalogExternalIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogManifestImporter importer;
    @Autowired CatalogService catalog;
    @Autowired StringRedisTemplate redis;
    @Autowired RequestIdFilter requestIdFilter;
    @MockitoSpyBean CatalogCache cache;
    MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        jdbc.update("delete from catalog_import where manifest_version like 'catalog-test-%'");
    }

    @Test
    void sameManifestHashIsANoOpAndSeedsExactlyTwentyMovies() {
        Timestamp movieUpdatedAt = jdbc.queryForObject(
                "select updated_at from movie where slug='starlight-archive'", Timestamp.class);
        Timestamp importedAt = jdbc.queryForObject(
                "select imported_at from catalog_import where manifest_version='catalog-v3'", Timestamp.class);

        assertThat(importer.importConfiguredManifest()).isFalse();

        assertThat(jdbc.queryForObject("select count(*) from movie", Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject(
                "select count(*) from catalog_import where manifest_version = 'catalog-v3'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select updated_at from movie where slug='starlight-archive'", Timestamp.class))
                .isEqualTo(movieUpdatedAt);
        assertThat(jdbc.queryForObject(
                "select imported_at from catalog_import where manifest_version='catalog-v3'", Timestamp.class))
                .isEqualTo(importedAt);
    }

    @Test
    void changedContentForAnImportedVersionIsRejected() {
        byte[] changed = manifestText()
                .replace("Starlight Archive", "Changed Without A New Version")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> importer.importManifest(changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version content changed");
    }

    @Test
    void aNewManifestVersionIsImportedExactlyOnce() {
        byte[] nextVersion = manifestText()
                .replace("\"catalog-v3\"", "\"catalog-test-next-version\"")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(importer.importManifest(nextVersion)).isTrue();
        assertThat(importer.importManifest(nextVersion)).isFalse();
        assertThat(jdbc.queryForObject(
                "select count(*) from catalog_import where manifest_version='catalog-test-next-version'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from movie", Integer.class)).isEqualTo(20);
    }

    @Test
    void failedManifestImportRollsBackAllMovieAndVersionWrites() {
        String originalTitle = jdbc.queryForObject(
                "select title from movie where slug='starlight-archive'", String.class);
        byte[] invalid = manifestText()
                .replace("\"catalog-v3\"", "\"catalog-test-rollback\"")
                .replace("Starlight Archive", "Must Roll Back")
                .replace("\"runtimeSeconds\":5400", "\"runtimeSeconds\":0")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> importer.importManifest(invalid)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "select title from movie where slug='starlight-archive'", String.class)).isEqualTo(originalTitle);
        assertThat(jdbc.queryForObject(
                "select count(*) from catalog_import where manifest_version='catalog-test-rollback'", Integer.class))
                .isZero();
    }

    @Test
    void concurrentFirstImportsSerializeByManifestVersion() throws Exception {
        byte[] concurrent = manifestText()
                .replace("\"catalog-v3\"", "\"catalog-test-concurrent\"")
                .getBytes(StandardCharsets.UTF_8);
        CyclicBarrier start = new CyclicBarrier(4);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> results = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(ignored -> executor.submit(() -> {
                        start.await();
                        return importer.importManifest(concurrent);
                    }))
                    .toList();

            assertThat(results).extracting(future -> future.get()).containsExactlyInAnyOrder(true, false, false, false);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from catalog_import where manifest_version='catalog-test-concurrent'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void successfulImportInvalidatesOnlyAfterTheTransactionCommits() throws Exception {
        String movieKey = "catalog:movie:v1:starlight-archive";
        redis.delete(movieKey);
        assertThat(catalog.movie("starlight-archive").title()).isEqualTo("Starlight Archive");
        assertThat(redis.hasKey(movieKey)).isTrue();

        CountDownLatch invalidated = new CountDownLatch(1);
        CountDownLatch releaseInvalidation = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            invalidated.countDown();
            if (!releaseInvalidation.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release cache invalidation");
            }
            return result;
        }).when(cache).invalidateAll();
        byte[] changed = manifestText()
                .replace("\"catalog-v3\"", "\"catalog-test-after-commit\"")
                .replace("Starlight Archive", "Starlight Archive Updated")
                .getBytes(StandardCharsets.UTF_8);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> importResult = executor.submit(() -> importer.importManifest(changed));
            try {
                assertThat(invalidated.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(catalog.movie("starlight-archive").title()).isEqualTo("Starlight Archive Updated");
            } finally {
                releaseInvalidation.countDown();
            }
            assertThat(importResult.get()).isTrue();
            assertThat(catalog.movie("starlight-archive").title()).isEqualTo("Starlight Archive Updated");
        } finally {
            releaseInvalidation.countDown();
            jdbc.update("update movie set title='Starlight Archive' where slug='starlight-archive'");
            jdbc.update("delete from catalog_import where manifest_version='catalog-test-after-commit'");
            redis.delete(movieKey);
        }
    }

    @Test
    void rolledBackImportLeavesExistingCacheEntryIntact() {
        String movieKey = "catalog:movie:v1:starlight-archive";
        redis.delete(movieKey);
        assertThat(catalog.movie("starlight-archive").title()).isEqualTo("Starlight Archive");
        byte[] invalid = manifestText()
                .replace("\"catalog-v3\"", "\"catalog-test-cache-rollback\"")
                .replace("\"runtimeSeconds\":5400", "\"runtimeSeconds\":0")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> importer.importManifest(invalid)).isInstanceOf(RuntimeException.class);
        assertThat(redis.hasKey(movieKey)).isTrue();
    }

    @Test
    void publicCatalogAndDetailsMatchTheOpenApiShape() throws Exception {
        redis.delete("catalog:home:v1");
        mvc.perform(get("/api/v1/catalog/home"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.rails[0].key").isString())
                .andExpect(jsonPath("$.rails[0].items[0].id").isString())
                .andExpect(jsonPath("$.rails[0].items[0].genres").isArray());
        assertThat(redis.hasKey("catalog:home:v1")).isTrue();

        mvc.perform(get("/api/v1/movies/starlight-archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Starlight Archive"))
                .andExpect(jsonPath("$.synopsis").isString())
                .andExpect(jsonPath("$.playable").value(true));
    }

    @Test
    void fullTextSearchFindsSynopsisOnlyTerms() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "archivist").param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].slug").value("starlight-archive"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @Transactional
    void trigramTypoSearchRanksTheClosestTitleFirst() throws Exception {
        jdbc.update("""
                insert into movie(id, slug, title, synopsis, release_year, runtime_seconds, maturity_rating,
                                  poster_url, backdrop_url, featured, published)
                values ('00000000-0000-0000-0000-000000000099', 'starlight-annex', 'Starlight Annex',
                        'A similarly named catalog entry for ranking verification.', 2020, 60, 'G',
                        'http://localhost/poster.svg', 'http://localhost/backdrop.svg', false, true)
                """);

        mvc.perform(get("/api/v1/search").param("q", "starligt archive").param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].slug").value("starlight-archive"))
                .andExpect(jsonPath("$.items[1].slug").value("starlight-annex"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void paginationReturnsRealSlicesAndExactTotals() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "old").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].slug").value("lanterns-at-noon"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/v1/search").param("q", "old").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].slug").value("copper-and-rain"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/v1/search").param("q", "old").param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].slug").value("windmill-notebook"))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/v1/search").param("q", "old").param("page", "3").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    @Transactional
    void wildcardInputIsLiteralAndSearchCandidatesUseBothGinIndexes() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));

        jdbc.execute("set local enable_seqscan=off");
        jdbc.execute("set local enable_indexscan=off");
        String plan = String.join("\n", jdbc.queryForList(
                "explain (costs off) " + JdbcCatalogRepository.SEARCH_CANDIDATES,
                String.class, "archive", "archive", "%archive%"));
        assertThat(plan)
                .contains("ix_movie_search_vector")
                .contains("ix_movie_title_trgm")
                .doesNotContain("Seq Scan on movie");
    }

    @Test
    void missingMovieAndInvalidSearchUseProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/movies/not-a-movie").header("X-Request-Id", "catalog-404"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value("catalog-404"));

        mvc.perform(get("/api/v1/search").param("q", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(get("/api/v1/search").header("X-Request-Id", "search-missing-q"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("search-missing-q"));
    }

    private String manifestText() {
        try (var input = getClass().getResourceAsStream("/catalog/catalog-v3.json")) {
            if (input == null) throw new IllegalStateException("Catalog manifest test resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
