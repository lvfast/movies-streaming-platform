package com.lvfast.streaming.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.messaging.OutboxPublisher;
import com.lvfast.streaming.messaging.RabbitTopology;
import com.lvfast.streaming.media.job.MediaResultService;
import com.lvfast.streaming.ops.AdminRoleCommand;
import com.lvfast.streaming.support.ApiTestSupport;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Testcontainers(disabledWithoutDocker = true)
class ProcessingHappyPathTest extends ApiTestSupport {

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String SOURCE_BUCKET = "media-source";
    private static final String DELIVERY_BUCKET = "media-delivery";
    private static final String WORKER_CREDENTIAL = "worker-test-secret";
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final int PART_SIZE = 5 * 1024 * 1024;

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", "media")
            .withEnv("RABBITMQ_DEFAULT_PASS", "media-local")
            .withExposedPorts(5672);

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("quay.io/minio/minio:latest")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server /data")
            .withExposedPorts(9000);

    @DynamicPropertySource
    static void media(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "media");
        registry.add("spring.rabbitmq.password", () -> "media-local");
        registry.add("app.media.worker.credential", () -> WORKER_CREDENTIAL);
        registry.add("app.media.worker.lease-ttl", () -> "PT5M");
        registry.add("app.media.outbox.poll-interval-ms", () -> "600000");
        registry.add("app.media.storage.roles.source.bucket", () -> SOURCE_BUCKET);
        registry.add("app.media.storage.roles.source.endpoint", ProcessingHappyPathTest::minioEndpoint);
        registry.add("app.media.storage.roles.source.region", () -> "us-east-1");
        registry.add("app.media.storage.roles.source.access-key", () -> ACCESS_KEY);
        registry.add("app.media.storage.roles.source.secret-key", () -> SECRET_KEY);
        registry.add("app.media.storage.roles.source-browser.bucket", () -> SOURCE_BUCKET);
        registry.add("app.media.storage.roles.source-browser.endpoint", ProcessingHappyPathTest::minioEndpoint);
        registry.add("app.media.storage.roles.source-browser.region", () -> "us-east-1");
        registry.add("app.media.storage.roles.source-browser.access-key", () -> ACCESS_KEY);
        registry.add("app.media.storage.roles.source-browser.secret-key", () -> SECRET_KEY);
        registry.add("app.media.storage.roles.delivery.bucket", () -> DELIVERY_BUCKET);
        registry.add("app.media.storage.roles.delivery.endpoint", ProcessingHappyPathTest::minioEndpoint);
        registry.add("app.media.storage.roles.delivery.region", () -> "us-east-1");
        registry.add("app.media.storage.roles.delivery.access-key", () -> ACCESS_KEY);
        registry.add("app.media.storage.roles.delivery.secret-key", () -> SECRET_KEY);
        registry.add("app.media.upload.part-size-bytes", () -> PART_SIZE);
    }

    @BeforeAll
    static void createBuckets() {
        try (S3Client client = s3Client()) {
            client.createBucket(CreateBucketRequest.builder().bucket(SOURCE_BUCKET).build());
            client.createBucket(CreateBucketRequest.builder().bucket(DELIVERY_BUCKET).build());
        }
    }

    @Autowired OutboxPublisher outbox;
    @Autowired RabbitTemplate rabbit;
    @Autowired MediaResultService results;
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
    void publishesOnlyAfterBrokerConfirmation() throws Exception {
        String token = registerAdmin("p3_publisher");
        UUID jobId = completeVideoUpload(token);

        assertThat(publishedAt(jobId)).isNull();
        assertThat(outbox.publishPending()).isEqualTo(1);
        assertThat(publishedAt(jobId)).isNotNull();

        Message message = rabbit.receive(RabbitTopology.COMMAND_QUEUE, 3000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains(jobId.toString());
    }

    @Test
    void outboxStaysPendingWhenBrokerUnavailable() throws Exception {
        String token = registerAdmin("p3_broker_down");
        UUID jobId = completeVideoUpload(token);

        CachingConnectionFactory unreachable = new CachingConnectionFactory("127.0.0.1", 1);
        OutboxPublisher broken = new OutboxPublisher(jdbc, new RabbitTemplate(unreachable), 100, 1000);
        assertThat(broken.publishPending()).isZero();
        assertThat(publishedAt(jobId)).isNull();
    }

    @Test
    void claimsWithMachineIdentity() throws Exception {
        String token = registerAdmin("p3_claim");
        UUID jobId = completeVideoUpload(token);

        Resp denied = postInternal("/internal/v1/jobs/" + jobId + "/claim", "wrong", "{\"workerId\":\"w\"}");
        assertThat(denied.status()).isEqualTo(401);

        Resp claimed = postInternal("/internal/v1/jobs/" + jobId + "/claim", WORKER_CREDENTIAL,
                "{\"workerId\":\"worker-1\"}");
        assertThat(claimed.status()).isEqualTo(200);
        JsonNode body = json.readTree(claimed.body());
        assertThat(body.get("disposition").asText()).isEqualTo("CLAIMED");
        assertThat(body.get("kind").asText()).isEqualTo("TRANSCODE");
        assertThat(body.get("profile").asText()).isEqualTo("h264-aac-1080p30");
        assertThat(body.get("source").asText()).endsWith("/original");
        assertThat(body.get("outputPrefix").asText()).startsWith("hls/");
        assertThat(body.get("attemptId").asText()).isNotBlank();

        Resp repeated = postInternal("/internal/v1/jobs/" + jobId + "/claim", WORKER_CREDENTIAL,
                "{\"workerId\":\"worker-2\"}");
        assertThat(json.readTree(repeated.body()).get("disposition").asText()).isEqualTo("SKIP");
        assertThat(json.readTree(repeated.body()).get("reason").asText()).isEqualTo("NOT_AVAILABLE");
    }

    @Test
    void rejectsHumanJwtOnInternalEndpoints() throws Exception {
        String human = registerAndToken("p3_human_jwt");
        Resp denied = postInternal("/internal/v1/jobs/" + UUID.randomUUID() + "/claim",
                human, "{\"workerId\":\"worker-1\"}");
        assertThat(denied.status()).isEqualTo(401);
    }

    @Test
    void heartbeatsExtendTheLeaseAndRejectUnknownAttempts() throws Exception {
        String token = registerAdmin("p3_heartbeat");
        UUID jobId = completeVideoUpload(token);

        Resp claimed = postInternal("/internal/v1/jobs/" + jobId + "/claim", WORKER_CREDENTIAL,
                "{\"workerId\":\"worker-1\"}");
        JsonNode claim = json.readTree(claimed.body());
        String attemptId = claim.get("attemptId").asText();

        Instant oldLease = jdbc.queryForObject(
                "select lease_until from media_job_attempt where id=?", OffsetDateTime.class,
                UUID.fromString(attemptId)).toInstant();
        assertThat(oldLease).isAfter(Instant.now());

        Thread.sleep(300);

        Resp heartbeat = postInternal("/internal/v1/jobs/" + jobId + "/heartbeat", WORKER_CREDENTIAL,
                "{\"workerId\":\"worker-1\",\"attemptId\":\"" + attemptId + "\"}");
        assertThat(heartbeat.status()).isEqualTo(200);
        Instant newLease = Instant.parse(json.readTree(heartbeat.body()).get("leaseUntil").asText());
        assertThat(newLease).as("heartbeat must extend the lease").isAfter(oldLease);
        Instant dbLease = jdbc.queryForObject(
                "select lease_until from media_job_attempt where id=?", OffsetDateTime.class,
                UUID.fromString(attemptId)).toInstant();
        assertThat(dbLease.toEpochMilli()).as("database lease must match the response")
                .isEqualTo(newLease.toEpochMilli());

        Resp wrong = postInternal("/internal/v1/jobs/" + jobId + "/heartbeat", WORKER_CREDENTIAL,
                "{\"workerId\":\"worker-1\",\"attemptId\":\"" + UUID.randomUUID() + "\"}");
        assertThat(wrong.status()).isEqualTo(409);

        assertThat(jdbc.queryForObject(
                "select count(*) from media_job_attempt where job_id=?", Integer.class, jobId)).isEqualTo(1);
    }

    @Test
    void acceptsCurrentAttemptResult() throws Exception {
        String token = registerAdmin("p3_result");
        UUID jobId = completeVideoUpload(token);

        Resp claimed = postInternal("/internal/v1/jobs/" + jobId + "/claim", WORKER_CREDENTIAL,
                "{\"workerId\":\"worker-1\"}");
        JsonNode claim = json.readTree(claimed.body());
        String attemptId = claim.get("attemptId").asText();
        String prefix = claim.get("outputPrefix").asText();

        String stale = resultEvent("media.completed.v1", jobId, UUID.randomUUID().toString(), 1,
                "{\"artifactKey\":\"artifact.json\"}");
        results.handle(stale);
        assertThat(jobState(jobId)).isEqualTo("RUNNING");

        String playlist = "#EXTM3U\n#EXTINF:6.0,\nsegment_00000.ts\n#EXT-X-ENDLIST\n";
        writeDelivery(prefix + "index.m3u8", playlist);
        writeDelivery(prefix + "segment_00000.ts", "x".repeat(1024));
        writeDelivery(prefix + "artifact.json", artifact(jobId, attemptId, List.of(
                object("index.m3u8", playlist.length()),
                object("segment_00000.ts", 1024))));

        String completed = resultEvent("media.completed.v1", jobId, attemptId, 2,
                "{\"artifactKey\":\"artifact.json\"}");
        results.handle(completed);

        assertThat(jobState(jobId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "select state from media_version where id=(select media_version_id from media_job where id=?)",
                String.class, jobId)).isEqualTo("READY");
    }

    private UUID completeVideoUpload(String token) throws Exception {
        UUID movieId = createManagedMovie(token);
        Result created = postAdminJson("/api/v1/admin/movies/" + movieId + "/uploads", token,
                "upload-" + UUID.randomUUID(),
                uploadBody("VIDEO", "movie.mp4", "video/mp4", PART_SIZE + 100L, FINGERPRINT));
        assertThat(created.status()).isEqualTo(201);
        UUID uploadId = idOf(bodyOf(created));

        Result signed = postAdminJson("/api/v1/admin/uploads/" + uploadId + "/part-urls", token, null,
                "{\"partNumbers\":[1,2]}");
        List<String> urls = new java.util.ArrayList<>();
        bodyOf(signed).get("items").forEach(node -> urls.add(node.get("url").asText()));
        uploadPart(urls.get(0), "A".repeat(PART_SIZE).getBytes(StandardCharsets.UTF_8));
        uploadPart(urls.get(1), "B".repeat(100).getBytes(StandardCharsets.UTF_8));

        Result completed = postNoBody("/api/v1/admin/uploads/" + uploadId + "/complete", token,
                Map.of("Idempotency-Key", "complete-" + UUID.randomUUID()));
        assertThat(completed.status()).isEqualTo(200);
        return UUID.fromString(bodyOf(completed).get("jobId").asText());
    }

    private UUID createManagedMovie(String token) {
        String slug = "p3-" + UUID.randomUUID().toString().substring(0, 8);
        Result created = postAdminJson("/api/v1/admin/movies", token, "movie-" + slug,
                "{\"title\":\"P3\",\"slug\":\"" + slug
                        + "\",\"synopsis\":\"Processing.\",\"releaseYear\":2026,\"maturityRating\":\"PG\","
                        + "\"genreIds\":[],\"featured\":false}");
        assertThat(created.status()).isEqualTo(201);
        return idOf(bodyOf(created));
    }

    private String registerAdmin(String username) {
        String token = registerAndToken(username);
        roleCommand.grantAdmin(username);
        return token;
    }

    private String publishedAt(UUID jobId) {
        return jdbc.queryForObject(
                "select published_at::text from outbox_event where job_id=?", String.class, jobId);
    }

    private String jobState(UUID jobId) {
        return jdbc.queryForObject("select state from media_job where id=?", String.class, jobId);
    }

    private String resultEvent(String type, UUID jobId, String attemptId, long sequence, String payload) {
        return "{\"schemaVersion\":1,\"eventId\":\"" + UUID.randomUUID()
                + "\",\"type\":\"" + type + "\",\"jobId\":\"" + jobId
                + "\",\"attemptId\":\"" + attemptId + "\",\"sequence\":" + sequence
                + ",\"occurredAt\":\"2026-09-12T10:00:00Z\",\"payload\":" + payload + "}";
    }

    private String artifact(UUID jobId, String attemptId, List<Map<String, Object>> objects) {
        StringBuilder objs = new StringBuilder();
        for (Map<String, Object> object : objects) {
            if (!objs.isEmpty()) {
                objs.append(",");
            }
            objs.append("{\"key\":\"").append(object.get("key"))
                    .append("\",\"sizeBytes\":").append(object.get("sizeBytes"))
                    .append(",\"sha256\":\"").append("c".repeat(64)).append("\"}");
        }
        return "{\"schemaVersion\":1,\"jobId\":\"" + jobId + "\",\"attemptId\":\"" + attemptId
                + "\",\"kind\":\"TRANSCODE\",\"profile\":\"h264-aac-1080p30\",\"movieId\":\""
                + movieIdOf(jobId) + "\",\"mediaVersionId\":\"" + versionIdOf(jobId)
                + "\",\"assetId\":null,\"width\":640,\"height\":360,\"durationSeconds\":6.0,"
                + "\"videoCodec\":\"h264\",\"audioCodec\":\"aac\",\"playlist\":\"index.m3u8\","
                + "\"objects\":[" + objs + "]}";
    }

    private Map<String, Object> object(String key, long sizeBytes) {
        return Map.of("key", key, "sizeBytes", sizeBytes);
    }

    private String movieIdOf(UUID jobId) {
        return jdbc.queryForObject("select movie_id::text from media_job where id=?", String.class, jobId);
    }

    private String versionIdOf(UUID jobId) {
        return jdbc.queryForObject("select media_version_id::text from media_job where id=?", String.class, jobId);
    }

    private void writeDelivery(String key, String content) {
        try (S3Client s3 = s3Client()) {
            s3.putObject(PutObjectRequest.builder().bucket(DELIVERY_BUCKET).key(key).build(),
                    RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void uploadPart(String url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(200, 204);
    }

    private Resp postInternal(String path, String credential, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (credential != null) {
            builder.header("Authorization", "Bearer " + credential);
        }
        if (body == null) {
            builder.method("POST", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method("POST", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body());
    }

    private record Resp(int status, String body) {
    }

    private String uploadBody(String kind, String fileName, String contentType, long size, String fingerprint) {
        return "{\"kind\":\"" + kind + "\",\"fileName\":\"" + fileName + "\",\"contentType\":\"" + contentType
                + "\",\"sizeBytes\":" + size + ",\"resumeFingerprint\":\"" + fingerprint + "\"}";
    }

    private static S3Client s3Client() {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .endpointOverride(URI.create(minioEndpoint()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
