package com.lvfast.streaming.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
class S3MediaObjectStoreTest {

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String BUCKET = "media-source";
    private static final String SOURCE = "source";
    private static final String BROWSER = "source-browser";
    private static final int MIN_PART = 5 * 1024 * 1024;

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("quay.io/minio/minio:latest")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server /data")
            .withExposedPorts(9000);

    private static MediaObjectStore store;

    @BeforeAll
    static void createBucket() {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        StorageProperties properties = new StorageProperties(Map.of(
                SOURCE, new StorageProperties.Role(BUCKET, endpoint, "us-east-1", ACCESS_KEY, SECRET_KEY),
                BROWSER, new StorageProperties.Role(BUCKET, endpoint, "us-east-1", ACCESS_KEY, SECRET_KEY)));
        store = new S3MediaObjectStore(properties);
        try (S3Client admin = S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void completesARealLocalMultipartUploadAndListsHeadsReads() throws Exception {
        String key = "source/movie/upload/original";
        String uploadId = store.initiate(SOURCE, key, "video/mp4", Map.of("fingerprint", "sha256:abc"));

        SignedPart part1 = store.signPart(BROWSER, key, uploadId, 1, Duration.ofMinutes(15));
        SignedPart part2 = store.signPart(BROWSER, key, uploadId, 2, Duration.ofMinutes(15));
        assertThat(part1.partNumber()).isEqualTo(1);
        assertThat(part1.expiresAt()).isAfter(java.time.Instant.now());

        String etag1 = upload(part1.url(), "A".repeat(MIN_PART).getBytes(StandardCharsets.UTF_8));
        String etag2 = upload(part2.url(), "BBBBB".getBytes(StandardCharsets.UTF_8));

        PartPage listed = store.listParts(SOURCE, key, uploadId, null);
        assertThat(listed.items()).hasSize(2);
        assertThat(listed.items()).extracting(StoredPart::partNumber).containsExactly(1, 2);
        assertThat(listed.nextMarker()).isNull();

        store.complete(SOURCE, key, uploadId, List.of(
                new CompletedPart(1, etag1),
                new CompletedPart(2, etag2)));

        ObjectHead head = store.head(SOURCE, key);
        assertThat(head).isNotNull();
        assertThat(head.sizeBytes()).isEqualTo(MIN_PART + 5L);
        assertThat(head.contentType()).isEqualTo("video/mp4");
        assertThat(head.metadata()).containsEntry("fingerprint", "sha256:abc");

        try (var in = store.read(SOURCE, key)) {
            assertThat(in.readAllBytes()).hasSize(MIN_PART + 5);
        }

        assertThat(store.head(SOURCE, "source/missing")).isNull();
    }

    @Test
    void supportsPutCopyAndAbort() throws Exception {
        Path temp = Files.createTempFile("s3-store-", ".bin");
        Files.writeString(temp, "hello", StandardCharsets.UTF_8);
        store.put(SOURCE, "source/copy/from", temp, "text/plain");
        store.copy(SOURCE, "source/copy/from", "source/copy/to");
        try (var in = store.read(SOURCE, "source/copy/to")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }

        String key = "source/movie/abort/original";
        String uploadId = store.initiate(SOURCE, key, "video/mp4", Map.of());
        store.abort(SOURCE, key, uploadId);
        assertThatThrownBy(() -> store.complete(SOURCE, key, uploadId, List.of()))
                .isInstanceOf(StorageUnavailableException.class);
    }

    private String upload(java.net.URI url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(url)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(200, 204);
        return response.headers().firstValue("ETag").orElseThrow();
    }
}
