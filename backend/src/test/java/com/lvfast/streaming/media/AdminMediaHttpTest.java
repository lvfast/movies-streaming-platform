package com.lvfast.streaming.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.ops.AdminRoleCommand;
import com.lvfast.streaming.support.ApiTestSupport;
import tools.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * HTTP behavior for the P3 admin media read endpoints: versions/assets/jobs are listable and
 * individually readable by an ADMIN, a plain USER is rejected, and no projection leaks a raw source
 * key, storage credential or signed URL.
 */
class AdminMediaHttpTest extends ApiTestSupport {

    @Autowired AdminRoleCommand roleCommand;

    /**
     * Presigning is an offline operation, so a placeholder S3-compatible endpoint and credential
     * pair are enough to prove the preview endpoint returns a private signed URL without a bucket.
     */
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.media.storage.roles.delivery.bucket", () -> "media-delivery");
        registry.add("app.media.storage.roles.delivery.endpoint", () -> "http://localhost:9000");
        registry.add("app.media.storage.roles.delivery.region", () -> "us-east-1");
        registry.add("app.media.storage.roles.delivery.access-key", () -> "test-access");
        registry.add("app.media.storage.roles.delivery.secret-key", () -> "test-secret");
    }

    @BeforeEach
    void cleanMedia() {
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from inbox_event");
        jdbc.update("delete from media_job_attempt");
        jdbc.update("delete from upload_session");
        jdbc.update("delete from media_job");
        jdbc.update("delete from media_asset");
        jdbc.update("delete from media_version");
        jdbc.update("delete from movie where management_mode='MANAGED'");
    }

    @Test
    void listsVersionsAssetsAndJobsAndReadsAJobWithoutSensitiveFields() {
        String token = registerAdmin("p3_admin_media");
        UUID movieId = createManagedMovie(token, "admin-media");
        UUID versionId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update("insert into media_version(id, movie_id, state, source_key) values (?, ?, 'QUEUED', ?)",
                versionId, movieId, "source/" + movieId + "/" + versionId + "/original");
        jdbc.update("insert into media_asset(id, movie_id, kind, state, source_key) values (?, ?, 'POSTER', 'READY', ?)",
                assetId, movieId, "source/" + movieId + "/" + assetId + "/original");
        jdbc.update("insert into media_job(id, movie_id, media_version_id, kind, state) values (?, ?, ?, 'TRANSCODE', 'QUEUED')",
                jobId, movieId, versionId);

        Result versions = get("/api/v1/admin/movies/" + movieId + "/versions", token);
        assertThat(versions.status()).isEqualTo(200);
        JsonNode versionPage = bodyOf(versions);
        assertThat(versionPage.get("total").asLong()).isEqualTo(1);
        assertThat(versionPage.get("items").get(0).get("id").asText()).isEqualTo(versionId.toString());
        assertThat(versionPage.get("items").get(0).get("state").asText()).isEqualTo("QUEUED");

        Result assets = get("/api/v1/admin/movies/" + movieId + "/assets", token);
        assertThat(assets.status()).isEqualTo(200);
        JsonNode assetPage = bodyOf(assets);
        assertThat(assetPage.get("total").asLong()).isEqualTo(1);
        assertThat(assetPage.get("items").get(0).get("id").asText()).isEqualTo(assetId.toString());
        assertThat(assetPage.get("items").get(0).get("kind").asText()).isEqualTo("POSTER");

        Result jobs = get("/api/v1/admin/jobs", token);
        assertThat(jobs.status()).isEqualTo(200);
        assertThat(bodyOf(jobs).get("total").asLong()).isGreaterThanOrEqualTo(1);

        Result job = get("/api/v1/admin/jobs/" + jobId, token);
        assertThat(job.status()).isEqualTo(200);
        assertThat(bodyOf(job).get("state").asText()).isEqualTo("QUEUED");
        assertThat(bodyOf(job).get("kind").asText()).isEqualTo("TRANSCODE");

        for (Result response : java.util.List.of(versions, assets, jobs, job)) {
            assertNoSensitiveFields(response.body());
        }
    }

    @Test
    void rejectsNonAdminUsers() {
        String user = registerAndToken("p3_media_plain");
        Result versions = get("/api/v1/admin/movies/" + UUID.randomUUID() + "/versions", user);
        Result jobs = get("/api/v1/admin/jobs", user);
        Result job = get("/api/v1/admin/jobs/" + UUID.randomUUID(), user);
        assertThat(versions.status()).isEqualTo(403);
        assertThat(jobs.status()).isEqualTo(403);
        assertThat(job.status()).isEqualTo(403);
    }

    @Test
    void returns404ForAMissingJob() {
        String token = registerAdmin("p3_media_404");
        Result job = get("/api/v1/admin/jobs/" + UUID.randomUUID(), token);
        assertThat(job.status()).isEqualTo(404);
    }

    @Test
    void previewsValidatedArtworkWithAShortLivedSignedUrl() {
        String token = registerAdmin("p7_media_preview");
        UUID movieId = createManagedMovie(token, "p7-media-preview");
        UUID assetId = readyArtwork(movieId, "POSTER");

        Result preview = postNoBody("/api/v1/admin/assets/" + assetId + "/preview", token, Map.of());
        assertThat(preview.status()).isEqualTo(200);
        JsonNode body = bodyOf(preview);
        assertThat(body.get("url").asText()).contains("X-Amz-Expires=60", assetId.toString());
        assertThat(body.get("expiresAt").asText()).isNotBlank();

        String viewer = registerAndToken("p7_media_preview_viewer");
        Result denied = postNoBody("/api/v1/admin/assets/" + assetId + "/preview", viewer, Map.of());
        assertThat(denied.status()).isEqualTo(403);

        Result missing = postNoBody(
                "/api/v1/admin/assets/" + UUID.randomUUID() + "/preview", token, Map.of());
        assertThat(missing.status()).isEqualTo(404);
    }

    private UUID readyArtwork(UUID movieId, String kind) {
        UUID assetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                insert into media_asset(id, movie_id, kind, state, source_key)
                values (?, ?, ?, 'READY', ?)
                """, assetId, movieId, kind, "source/" + movieId + "/" + assetId + "/original");
        jdbc.update("""
                insert into media_job(id, movie_id, asset_id, kind, state, attempt_number)
                values (?, ?, ?, 'ARTWORK', 'SUCCEEDED', 1)
                """, jobId, movieId, assetId);
        jdbc.update("""
                insert into media_job_attempt(
                    id, job_id, attempt_number, worker_id, state, lease_until, output_prefix)
                values (?, ?, 1, 'worker-test', 'SUCCEEDED', now() + interval '1 hour', ?)
                """, attemptId, jobId, "artwork/" + movieId + "/" + assetId + "/" + attemptId + "/");
        return assetId;
    }

    private void assertNoSensitiveFields(String body) {
        assertThat(body)
                .doesNotContain("sourceKey", "source_key", "objectKey", "object_key", "signedUrl",
                        "signed", "presign", "credential", "accessKey", "access_key", "secretKey",
                        "secret_key", "storageUploadId");
    }

    private String registerAdmin(String username) {
        String token = registerAndToken(username);
        roleCommand.grantAdmin(username);
        return token;
    }

    private UUID createManagedMovie(String token, String slug) {
        Result created = postAdminJson("/api/v1/admin/movies", token, "movie-" + slug,
                "{\"title\":\"" + slug + "\",\"slug\":\"" + slug
                        + "\",\"synopsis\":\"Admin media.\",\"releaseYear\":2026,\"maturityRating\":\"PG\","
                        + "\"genreIds\":[],\"featured\":false}");
        assertThat(created.status()).isEqualTo(201);
        return idOf(bodyOf(created));
    }
}
