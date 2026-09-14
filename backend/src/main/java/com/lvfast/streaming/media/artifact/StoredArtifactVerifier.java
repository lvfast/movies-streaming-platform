package com.lvfast.streaming.media.artifact;

import com.lvfast.streaming.media.storage.MediaObjectStore;
import com.lvfast.streaming.media.storage.ObjectHead;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Verifies an immutable artifact manifest before a job is allowed to become READY. It checks the
 * manifest identity against the claimed attempt, that every object key stays under the attempt
 * prefix, that the playlist is present and covers every referenced segment, and that declared object
 * sizes match storage.
 */
@Service
public class StoredArtifactVerifier {

    private final MediaObjectStore store;
    private final ObjectMapper json;

    public StoredArtifactVerifier(MediaObjectStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    public Artifact verify(String bucketRole, String prefix, String artifactKey, Identity expected) {
        String manifestKey = prefix + artifactKey;
        Artifact artifact = readManifest(bucketRole, manifestKey);
        verifyIdentity(artifact, expected);
        verifyShape(artifact);
        verifyKeys(artifact);
        verifyPlaylist(bucketRole, prefix, artifact);
        verifySizes(bucketRole, prefix, artifact);
        return artifact;
    }

    private Artifact readManifest(String bucketRole, String key) {
        ObjectHead head = store.head(bucketRole, key);
        if (head == null) {
            throw new ArtifactVerificationException("Artifact manifest '" + key + "' is missing");
        }
        try (InputStream input = store.read(bucketRole, key)) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Artifact artifact = json.readValue(content, Artifact.class);
            if (artifact.schemaVersion() != 1) {
                throw new ArtifactVerificationException("Unsupported artifact schemaVersion " + artifact.schemaVersion());
            }
            return artifact;
        } catch (ArtifactVerificationException expected) {
            throw expected;
        } catch (Exception unreadable) {
            throw new ArtifactVerificationException("Artifact manifest '" + key + "' is unreadable");
        }
    }

    private void verifyIdentity(Artifact artifact, Identity expected) {
        if (!expected.jobId().equals(artifact.jobId())) {
            throw new ArtifactVerificationException("Artifact jobId does not match the claimed job");
        }
        if (!expected.attemptId().equals(artifact.attemptId())) {
            throw new ArtifactVerificationException("Artifact attemptId does not match the claimed attempt");
        }
        if (!expected.movieId().equals(artifact.movieId())) {
            throw new ArtifactVerificationException("Artifact movieId does not match the claimed movie");
        }
        if (!expected.kind().equals(artifact.kind())) {
            throw new ArtifactVerificationException("Artifact kind does not match the claimed job");
        }
    }

    private void verifyShape(Artifact artifact) {
        if (artifact.width() < 1 || artifact.height() < 1) {
            throw new ArtifactVerificationException("Artifact dimensions must be positive");
        }
        if ("TRANSCODE".equals(artifact.kind())) {
            if (artifact.mediaVersionId() == null || artifact.assetId() != null) {
                throw new ArtifactVerificationException("Transcode artifact must target a media version only");
            }
            if (!"h264".equals(artifact.videoCodec()) || !"aac".equals(artifact.audioCodec())) {
                throw new ArtifactVerificationException("Transcode artifact must declare h264 video and aac audio");
            }
            if (artifact.durationSeconds() == null || artifact.durationSeconds() <= 0) {
                throw new ArtifactVerificationException("Transcode artifact must declare a positive duration");
            }
            if (!"index.m3u8".equals(artifact.playlist())) {
                throw new ArtifactVerificationException("Transcode playlist must be index.m3u8");
            }
        } else if ("ARTWORK".equals(artifact.kind())) {
            if (artifact.assetId() == null || artifact.mediaVersionId() != null) {
                throw new ArtifactVerificationException("Artwork artifact must target an asset only");
            }
            if (artifact.objects().size() != 1 || !"image.jpg".equals(artifact.objects().getFirst().key())) {
                throw new ArtifactVerificationException("Artwork artifact must contain exactly one image.jpg object");
            }
        } else {
            throw new ArtifactVerificationException("Unknown artifact kind '" + artifact.kind() + "'");
        }
    }

    private void verifyKeys(Artifact artifact) {
        for (Artifact.ArtifactObject object : artifact.objects()) {
            String key = object.key();
            if (key == null || key.isBlank()) {
                throw new ArtifactVerificationException("Artifact object key must not be blank");
            }
            if (key.startsWith("/") || key.contains("..") || key.contains("\\")) {
                throw new ArtifactVerificationException("Artifact object key '" + key + "' escapes the attempt prefix");
            }
            if (object.sizeBytes() < 1) {
                throw new ArtifactVerificationException("Artifact object '" + key + "' has an invalid size");
            }
        }
    }

    private void verifyPlaylist(String bucketRole, String prefix, Artifact artifact) {
        if (!"TRANSCODE".equals(artifact.kind())) {
            return;
        }
        Set<String> declared = new HashSet<>();
        for (Artifact.ArtifactObject object : artifact.objects()) {
            declared.add(object.key());
        }
        if (!declared.contains("index.m3u8")) {
            throw new ArtifactVerificationException("Artifact objects do not include the index.m3u8 playlist");
        }
        String playlist = readText(bucketRole, prefix + "index.m3u8");
        Set<String> segments = referencedSegments(playlist);
        if (segments.isEmpty()) {
            throw new ArtifactVerificationException("Playlist references no segments");
        }
        for (String segment : segments) {
            if (!declared.contains(segment)) {
                throw new ArtifactVerificationException("Playlist references missing segment '" + segment + "'");
            }
        }
        for (String key : declared) {
            if (key.endsWith(".ts") && !segments.contains(key)) {
                throw new ArtifactVerificationException("Object '" + key + "' is not referenced by the playlist");
            }
        }
    }

    private void verifySizes(String bucketRole, String prefix, Artifact artifact) {
        for (Artifact.ArtifactObject object : artifact.objects()) {
            ObjectHead head = store.head(bucketRole, prefix + object.key());
            if (head == null) {
                throw new ArtifactVerificationException("Artifact object '" + object.key() + "' is missing");
            }
            if (head.sizeBytes() != object.sizeBytes()) {
                throw new ArtifactVerificationException(
                        "Artifact object '" + object.key() + "' size " + head.sizeBytes()
                                + " does not match declared " + object.sizeBytes());
            }
        }
    }

    private String readText(String bucketRole, String key) {
        try (InputStream input = store.read(bucketRole, key)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            throw new ArtifactVerificationException("Playlist '" + key + "' is unreadable");
        }
    }

    static Set<String> referencedSegments(String playlist) {
        Set<String> segments = new HashSet<>();
        for (String line : playlist.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            segments.add(trimmed);
        }
        return segments;
    }

    public record Identity(String jobId, String attemptId, String movieId, String kind) {
    }
}
