package com.lvfast.streaming.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lvfast.streaming.media.artifact.Artifact;
import com.lvfast.streaming.media.artifact.ArtifactVerificationException;
import com.lvfast.streaming.media.artifact.StoredArtifactVerifier;
import com.lvfast.streaming.media.storage.CompletedPart;
import com.lvfast.streaming.media.storage.MediaObjectStore;
import com.lvfast.streaming.media.storage.ObjectHead;
import com.lvfast.streaming.media.storage.PartPage;
import com.lvfast.streaming.media.storage.PresignedGet;
import com.lvfast.streaming.media.storage.SignedPart;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ArtifactVerifierTest {

    private static final String PREFIX = "hls/movie/version/attempt/";
    private static final StoredArtifactVerifier.Identity IDENTITY = new StoredArtifactVerifier.Identity(
            "job-id", "attempt-id", "movie-id", "TRANSCODE");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsAValidTranscodeArtifact() throws Exception {
        String playlist = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:6.0,
                segment_00000.ts
                #EXTINF:4.0,
                segment_00001.ts
                #EXT-X-ENDLIST
                """;
        FakeStore store = new FakeStore();
        store.put(PREFIX + "index.m3u8", playlist);
        store.put(PREFIX + "segment_00000.ts", "a".repeat(100));
        store.put(PREFIX + "segment_00001.ts", "b".repeat(100));
        Artifact manifest = transcodeArtifact(List.of(
                object("index.m3u8", playlist.length()),
                object("segment_00000.ts", 100),
                object("segment_00001.ts", 100)));
        store.put(PREFIX + "artifact.json", json.writeValueAsString(manifest));

        StoredArtifactVerifier verifier = new StoredArtifactVerifier(store, json);
        Artifact verified = verifier.verify("delivery", PREFIX, "artifact.json", IDENTITY);
        assertThat(verified.playlist()).isEqualTo("index.m3u8");
        assertThat(verified.objects()).hasSize(3);
    }

    @Test
    void rejectsAPlaylistThatReferencesAMissingSegment() throws Exception {
        FakeStore store = new FakeStore();
        store.put(PREFIX + "index.m3u8", """
                #EXTM3U
                #EXTINF:6.0,
                segment_00000.ts
                #EXTINF:4.0,
                segment_00001.ts
                #EXT-X-ENDLIST
                """);
        store.put(PREFIX + "segment_00000.ts", "a".repeat(100));
        Artifact manifest = transcodeArtifact(List.of(
                object("index.m3u8", 100),
                object("segment_00000.ts", 100)));
        store.put(PREFIX + "artifact.json", json.writeValueAsString(manifest));

        StoredArtifactVerifier verifier = new StoredArtifactVerifier(store, json);
        assertThatThrownBy(() -> verifier.verify("delivery", PREFIX, "artifact.json", IDENTITY))
                .isInstanceOf(ArtifactVerificationException.class)
                .hasMessageContaining("segment_00001.ts");
    }

    @Test
    void rejectsAnObjectKeyThatEscapesTheAttemptPrefix() throws Exception {
        FakeStore store = new FakeStore();
        store.put(PREFIX + "index.m3u8", """
                #EXTM3U
                #EXTINF:6.0,
                segment_00000.ts
                #EXT-X-ENDLIST
                """);
        store.put(PREFIX + "segment_00000.ts", "a".repeat(100));
        Artifact manifest = transcodeArtifact(List.of(
                object("index.m3u8", 100),
                object("../segment_00000.ts", 100)));
        store.put(PREFIX + "artifact.json", json.writeValueAsString(manifest));

        StoredArtifactVerifier verifier = new StoredArtifactVerifier(store, json);
        assertThatThrownBy(() -> verifier.verify("delivery", PREFIX, "artifact.json", IDENTITY))
                .isInstanceOf(ArtifactVerificationException.class)
                .hasMessageContaining("escapes the attempt prefix");
    }

    @Test
    void rejectsAnArtifactWhoseIdentityDoesNotMatchTheClaim() throws Exception {
        FakeStore store = new FakeStore();
        store.put(PREFIX + "index.m3u8", "#EXTM3U\n#EXTINF:6.0,\nsegment_00000.ts\n#EXT-X-ENDLIST\n");
        store.put(PREFIX + "segment_00000.ts", "a".repeat(100));
        Artifact manifest = new Artifact(
                1, "other-job", "attempt-id", "TRANSCODE", "h264-aac-1080p30", "movie-id",
                "version-id", null, 640, 360, 6.0, "h264", "aac", "index.m3u8",
                List.of(object("index.m3u8", 100), object("segment_00000.ts", 100)));
        store.put(PREFIX + "artifact.json", json.writeValueAsString(manifest));

        StoredArtifactVerifier verifier = new StoredArtifactVerifier(store, json);
        assertThatThrownBy(() -> verifier.verify("delivery", PREFIX, "artifact.json", IDENTITY))
                .isInstanceOf(ArtifactVerificationException.class)
                .hasMessageContaining("jobId");
    }

    @Test
    void rejectsAnObjectWhoseDeclaredSizeDoesNotMatchStorage() throws Exception {
        String playlist = "#EXTM3U\n#EXTINF:6.0,\nsegment_00000.ts\n#EXT-X-ENDLIST\n";
        FakeStore store = new FakeStore();
        store.put(PREFIX + "index.m3u8", playlist);
        store.put(PREFIX + "segment_00000.ts", "a".repeat(100));
        Artifact manifest = transcodeArtifact(List.of(
                object("index.m3u8", playlist.length()),
                object("segment_00000.ts", 999)));
        store.put(PREFIX + "artifact.json", json.writeValueAsString(manifest));

        StoredArtifactVerifier verifier = new StoredArtifactVerifier(store, json);
        assertThatThrownBy(() -> verifier.verify("delivery", PREFIX, "artifact.json", IDENTITY))
                .isInstanceOf(ArtifactVerificationException.class)
                .hasMessageContaining("does not match declared");
    }

    @Test
    void acceptsAValidArtworkArtifact() throws Exception {
        FakeStore store = new FakeStore();
        store.put(PREFIX + "image.jpg", "jpeg");
        Artifact manifest = new Artifact(
                1, "job-id", "attempt-id", "ARTWORK", "poster-600x900", "movie-id",
                null, "asset-id", 600, 900, null, null, null, null,
                List.of(object("image.jpg", 4)));
        store.put(PREFIX + "artifact.json", json.writeValueAsString(manifest));

        StoredArtifactVerifier verifier = new StoredArtifactVerifier(store, json);
        StoredArtifactVerifier.Identity artwork = new StoredArtifactVerifier.Identity(
                "job-id", "attempt-id", "movie-id", "ARTWORK");
        Artifact verified = verifier.verify("delivery", PREFIX, "artifact.json", artwork);
        assertThat(verified.objects()).extracting(Artifact.ArtifactObject::key).containsExactly("image.jpg");
    }

    private Artifact transcodeArtifact(List<Artifact.ArtifactObject> objects) {
        return new Artifact(
                1, "job-id", "attempt-id", "TRANSCODE", "h264-aac-1080p30", "movie-id",
                "version-id", null, 640, 360, 6.0, "h264", "aac", "index.m3u8", objects);
    }

    private Artifact.ArtifactObject object(String key, long sizeBytes) {
        return new Artifact.ArtifactObject(key, sizeBytes, "a".repeat(64));
    }

    /** Minimal in-memory object store covering the read/head operations the verifier uses. */
    private static final class FakeStore implements MediaObjectStore {

        private final Map<String, byte[]> objects = new HashMap<>();

        void put(String key, String content) {
            objects.put(key, content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public ObjectHead head(String bucketRole, String key) {
            byte[] body = objects.get(key);
            return body == null ? null : new ObjectHead(body.length, "etag", "application/octet-stream", Map.of());
        }

        @Override
        public InputStream read(String bucketRole, String key) {
            byte[] body = objects.get(key);
            if (body == null) {
                throw new IllegalStateException("Missing object " + key);
            }
            return new ByteArrayInputStream(body);
        }

        @Override
        public String initiate(String bucketRole, String key, String contentType, Map<String, String> metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SignedPart signPart(String bucketRole, String key, String uploadId, int partNumber, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PresignedGet presignGet(String bucketRole, String key, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PartPage listParts(String bucketRole, String key, String uploadId, Integer marker) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete(String bucketRole, String key, String uploadId, List<CompletedPart> parts) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abort(String bucketRole, String key, String uploadId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void put(String bucketRole, String key, Path file, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copy(String bucketRole, String fromKey, String toKey) {
            throw new UnsupportedOperationException();
        }
    }
}
