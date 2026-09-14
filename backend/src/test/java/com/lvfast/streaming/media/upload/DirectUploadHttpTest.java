package com.lvfast.streaming.media.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.ops.AdminRoleCommand;
import com.lvfast.streaming.support.ApiTestSupport;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Testcontainers(disabledWithoutDocker = true)
class DirectUploadHttpTest extends ApiTestSupport {

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String BUCKET = "media-source";
    private static final int PART_SIZE = 5 * 1024 * 1024;
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final UUID LEGACY_MOVIE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("quay.io/minio/minio:latest")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server /data")
            .withExposedPorts(9000);

    @DynamicPropertySource
    static void media(DynamicPropertyRegistry registry) {
        registry.add("app.media.storage.roles.source.bucket", () -> BUCKET);
        registry.add("app.media.storage.roles.source.endpoint", DirectUploadHttpTest::minioEndpoint);
        registry.add("app.media.storage.roles.source.region", () -> "us-east-1");
        registry.add("app.media.storage.roles.source.access-key", () -> ACCESS_KEY);
        registry.add("app.media.storage.roles.source.secret-key", () -> SECRET_KEY);
        registry.add("app.media.storage.roles.source-browser.bucket", () -> BUCKET);
        registry.add("app.media.storage.roles.source-browser.endpoint", DirectUploadHttpTest::minioEndpoint);
        registry.add("app.media.storage.roles.source-browser.region", () -> "us-east-1");
        registry.add("app.media.storage.roles.source-browser.access-key", () -> ACCESS_KEY);
        registry.add("app.media.storage.roles.source-browser.secret-key", () -> SECRET_KEY);
        registry.add("app.media.upload.part-size-bytes", () -> PART_SIZE);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client client = S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .endpointOverride(URI.create(minioEndpoint()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @Autowired UploadService uploads;
    @Autowired AdminRoleCommand roleCommand;

    private final HttpClient client = HttpClient.newHttpClient();

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
    void createsOpenSessionAndReplaysByIdempotencyKey() {
        String token = registerAdmin("upload_admin");
        UUID movieId = createManagedMovie(token, "upload-movie");

        Result created = postAdminJson(
                "/api/v1/admin/movies/" + movieId + "/uploads", token, "upload-1",
                uploadBody("VIDEO", "movie.mp4", "video/mp4", 1024, FINGERPRINT));
        assertThat(created.status()).isEqualTo(201);
        JsonNode session = bodyOf(created);
        assertThat(session.get("state").asText()).isEqualTo("OPEN");
        assertThat(session.get("kind").asText()).isEqualTo("VIDEO");
        assertThat(session.get("mediaVersionId").isNull()).isFalse();
        assertThat(session.get("assetId").isNull()).isTrue();
        assertThat(session.get("jobId").isNull()).isTrue();
        assertThat(session.get("partSizeBytes").asLong()).isEqualTo(PART_SIZE);
        assertThat(session.get("totalParts").asInt()).isEqualTo(1);
        assertThat(session.get("declaredBytes").asLong()).isEqualTo(1024);
        assertThat(Instant.parse(session.get("expiresAt").asText())).isAfter(Instant.now());
        UUID uploadId = idOf(session);

        assertThat(versionState(uploadId)).isEqualTo("UPLOADING");

        Result repeated = postAdminJson(
                "/api/v1/admin/movies/" + movieId + "/uploads", token, "upload-1",
                uploadBody("VIDEO", "movie.mp4", "video/mp4", 1024, FINGERPRINT));
        assertThat(repeated.status()).isEqualTo(201);
        assertThat(idOf(bodyOf(repeated))).isEqualTo(uploadId);

        Result fetched = get("/api/v1/admin/uploads/" + uploadId, token);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(bodyOf(fetched).get("state").asText()).isEqualTo("OPEN");
    }

    @Test
    void validatesAdminMovieKindSizeAndFingerprint() {
        String token = registerAdmin("upload_validator");
        UUID movieId = createManagedMovie(token, "validator-movie");

        Result forbidden = postAdminJson(
                "/api/v1/admin/movies/" + movieId + "/uploads",
                registerAndToken("upload_plain_user"), "v-1",
                uploadBody("VIDEO", "movie.mp4", "video/mp4", 1024, FINGERPRINT));
        assertThat(forbidden.status()).isEqualTo(403);

        assertThat(postAdminJson("/api/v1/admin/movies/" + movieId + "/uploads", token, "v-2",
                uploadBody("AUDIO", "a.mp3", "audio/mpeg", 1024, FINGERPRINT)).status()).isEqualTo(400);
        assertThat(postAdminJson("/api/v1/admin/movies/" + movieId + "/uploads", token, "v-3",
                uploadBody("VIDEO", "movie.mp4", "video/mp4", 21L * 1024 * 1024 * 1024, FINGERPRINT)).status())
                .isEqualTo(413);
        assertThat(postAdminJson("/api/v1/admin/movies/" + movieId + "/uploads", token, "v-4",
                uploadBody("VIDEO", "movie.mp4", "video/mp4", 1024, "sha256:nothex")).status()).isEqualTo(400);
        assertThat(postAdminJson("/api/v1/admin/movies/" + movieId + "/uploads", token, null,
                uploadBody("VIDEO", "movie.mp4", "video/mp4", 1024, FINGERPRINT)).status()).isEqualTo(400);

        assertThat(postAdminJson("/api/v1/admin/movies/" + LEGACY_MOVIE + "/uploads", token, "v-5",
                uploadBody("VIDEO", "movie.mp4", "video/mp4", 1024, FINGERPRINT)).status()).isEqualTo(409);
        assertThat(postAdminJson(
                "/api/v1/admin/movies/" + UUID.randomUUID() + "/uploads", token, "v-6",
                uploadBody("VIDEO", "movie.mp4", "video/mp4", 1024, FINGERPRINT)).status()).isEqualTo(404);
    }

    @Test
    void signsAndListsAuthoritativeParts() throws Exception {
        String token = registerAdmin("upload_signer");
        UUID movieId = createManagedMovie(token, "signer-movie");
        long declared = PART_SIZE + 100L;
        UUID uploadId = createUpload(token, movieId, "VIDEO", declared);

        List<Integer> numbers = List.of(1, 2);
        Result signed = postAdminJson("/api/v1/admin/uploads/" + uploadId + "/part-urls", token, null,
                "{\"partNumbers\":[1,2]}");
        assertThat(signed.status()).isEqualTo(200);
        List<String> urls = urlsOf(bodyOf(signed));
        assertThat(urls).hasSize(2);

        uploadPart(urls.get(0), "A".repeat(PART_SIZE).getBytes(StandardCharsets.UTF_8));
        uploadPart(urls.get(1), "B".repeat(100).getBytes(StandardCharsets.UTF_8));

        Result listed = get("/api/v1/admin/uploads/" + uploadId + "/parts", token);
        assertThat(listed.status()).isEqualTo(200);
        JsonNode items = bodyOf(listed).get("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).get("partNumber").asInt()).isEqualTo(1);
        assertThat(items.get(0).get("sizeBytes").asLong()).isEqualTo(PART_SIZE);
        assertThat(items.get(1).get("partNumber").asInt()).isEqualTo(2);
        assertThat(items.get(1).get("sizeBytes").asLong()).isEqualTo(100);

        assertThat(postAdminJson("/api/v1/admin/uploads/" + uploadId + "/part-urls", token, null,
                "{\"partNumbers\":[3]}").status()).isEqualTo(400);
        assertThat(postAdminJson("/api/v1/admin/uploads/" + uploadId + "/part-urls", token, null,
                "{\"partNumbers\":[1,1]}").status()).isEqualTo(400);
    }

    @Test
    void completionCreatesExactlyOneJobAndOneOutboxEventAndReplays() throws Exception {
        String token = registerAdmin("upload_completer");
        UUID movieId = createManagedMovie(token, "completer-movie");
        long declared = PART_SIZE + 100L;
        UUID uploadId = createUpload(token, movieId, "VIDEO", declared);
        uploadAllParts(token, uploadId, List.of(
                "A".repeat(PART_SIZE).getBytes(StandardCharsets.UTF_8),
                "B".repeat(100).getBytes(StandardCharsets.UTF_8)));

        Result completed = postNoBody(
                "/api/v1/admin/uploads/" + uploadId + "/complete", token, Map.of("Idempotency-Key", "complete-1"));
        assertThat(completed.status()).isEqualTo(200);
        JsonNode session = bodyOf(completed);
        assertThat(session.get("state").asText()).isEqualTo("COMPLETED");
        String jobId = session.get("jobId").asText();
        assertThat(jobId).isNotBlank();

        UUID versionId = UUID.fromString(session.get("mediaVersionId").asText());
        assertThat(versionState(uploadId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "select count(*) from media_job where media_version_id=?", Integer.class, versionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select state from media_job where media_version_id=?", String.class, versionId)).isEqualTo("QUEUED");
        UUID jobUuid = UUID.fromString(jobId);
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox_event where job_id=?", Integer.class, jobUuid)).isEqualTo(1);

        Result repeated = postNoBody(
                "/api/v1/admin/uploads/" + uploadId + "/complete", token, Map.of("Idempotency-Key", "complete-2"));
        assertThat(repeated.status()).isEqualTo(200);
        assertThat(bodyOf(repeated).get("jobId").asText()).isEqualTo(jobId);
        assertThat(jdbc.queryForObject(
                "select count(*) from media_job where media_version_id=?", Integer.class, versionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox_event where job_id=?", Integer.class, jobUuid)).isEqualTo(1);
    }

    @Test
    void abortIsIdempotentAndRejectsCompletedSessions() throws Exception {
        String token = registerAdmin("upload_aborter");
        UUID movieId = createManagedMovie(token, "aborter-movie");

        UUID aborted = createUpload(token, movieId, "VIDEO", 1024);
        Result first = postNoBody("/api/v1/admin/uploads/" + aborted + "/abort", token, Map.of());
        assertThat(first.status()).isEqualTo(200);
        assertThat(bodyOf(first).get("state").asText()).isEqualTo("ABORTED");
        assertThat(versionState(aborted)).isEqualTo("ABORTED");

        Result again = postNoBody("/api/v1/admin/uploads/" + aborted + "/abort", token, Map.of());
        assertThat(again.status()).isEqualTo(200);
        assertThat(bodyOf(again).get("state").asText()).isEqualTo("ABORTED");

        UUID completed = createUpload(token, movieId, "VIDEO", 1024);
        uploadAllParts(token, completed, List.of("X".repeat(1024).getBytes(StandardCharsets.UTF_8)));
        assertThat(postNoBody("/api/v1/admin/uploads/" + completed + "/complete", token,
                Map.of("Idempotency-Key", "c-1")).status()).isEqualTo(200);
        assertThat(postNoBody("/api/v1/admin/uploads/" + completed + "/abort", token, Map.of()).status())
                .isEqualTo(409);
    }

    @Test
    void sweepExpiresStaleOpenSessions() {
        String token = registerAdmin("upload_sweeper");
        UUID movieId = createManagedMovie(token, "sweeper-movie");
        UUID uploadId = createUpload(token, movieId, "VIDEO", 1024);

        jdbc.update("update upload_session set expires_at=now() - interval '1 hour' where id=?", uploadId);

        assertThat(uploads.sweepExpired()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select state from upload_session where id=?", String.class, uploadId)).isEqualTo("EXPIRED");
        assertThat(versionState(uploadId)).isEqualTo("ABORTED");
    }

    private String registerAdmin(String username) {
        String token = registerAndToken(username);
        roleCommand.grantAdmin(username);
        return token;
    }

    private UUID createManagedMovie(String token, String slug) {
        Result created = postAdminJson("/api/v1/admin/movies", token, "movie-" + slug,
                "{\"title\":\"" + slug + "\",\"slug\":\"" + slug
                        + "\",\"synopsis\":\"Managed draft.\",\"releaseYear\":2026,\"maturityRating\":\"PG\","
                        + "\"genreIds\":[],\"featured\":false}");
        assertThat(created.status()).isEqualTo(201);
        return idOf(bodyOf(created));
    }

    private UUID createUpload(String token, UUID movieId, String kind, long size) {
        Result created = postAdminJson(
                "/api/v1/admin/movies/" + movieId + "/uploads", token, "upload-" + UUID.randomUUID(),
                uploadBody(kind, "file.bin", kind.equals("VIDEO") ? "video/mp4" : "image/jpeg", size, FINGERPRINT));
        assertThat(created.status()).isEqualTo(201);
        return idOf(bodyOf(created));
    }

    private void uploadAllParts(String token, UUID uploadId, List<byte[]> parts) throws Exception {
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i <= parts.size(); i++) {
            numbers.add(i);
        }
        Result signed = postAdminJson("/api/v1/admin/uploads/" + uploadId + "/part-urls", token, null,
                "{\"partNumbers\":[" + numbers.stream().map(String::valueOf)
                        .collect(Collectors.joining(",")) + "]}");
        assertThat(signed.status()).isEqualTo(200);
        List<String> urls = urlsOf(bodyOf(signed));
        for (int i = 0; i < parts.size(); i++) {
            uploadPart(urls.get(i), parts.get(i));
        }
    }

    private void uploadPart(String url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(200, 204);
    }

    private List<String> urlsOf(JsonNode response) {
        List<String> urls = new ArrayList<>();
        response.get("items").forEach(node -> urls.add(node.get("url").asText()));
        return urls;
    }

    private String versionState(UUID uploadId) {
        return jdbc.queryForObject("""
                select v.state from upload_session s join media_version v on v.id = s.media_version_id
                where s.id=?
                """, String.class, uploadId);
    }

    private String uploadBody(String kind, String fileName, String contentType, long size, String fingerprint) {
        return "{\"kind\":\"" + kind + "\",\"fileName\":\"" + fileName + "\",\"contentType\":\"" + contentType
                + "\",\"sizeBytes\":" + size + ",\"resumeFingerprint\":\"" + fingerprint + "\"}";
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
