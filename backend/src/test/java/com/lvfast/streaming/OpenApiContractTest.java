package com.lvfast.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void contractIsValidYamlAndContainsEveryLockedMvpPath() throws IOException {
        Path contract = Path.of("..", "docs", "api", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        assertThat(document.get("openapi")).isEqualTo("3.1.0");
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        assertThat(paths.keySet()).containsAll(Set.of(
                "/auth/register",
                "/auth/login",
                "/auth/refresh",
                "/auth/logout",
                "/auth/me",
                "/catalog/home",
                "/movies/{slug}",
                "/search",
                "/me/watchlist",
                "/movies/{movieId}/playback",
                "/me/playback-sessions/{sessionId}/token",
                "/me/progress/{movieId}",
                "/admin/movies",
                "/admin/movies/{movieId}",
                "/admin/genres",
                "/admin/movies/{movieId}/uploads",
                "/admin/uploads/{uploadId}",
                "/admin/uploads/{uploadId}/parts",
                "/admin/uploads/{uploadId}/part-urls",
                "/admin/uploads/{uploadId}/complete",
                "/admin/uploads/{uploadId}/abort",
                "/admin/movies/{movieId}/versions",
                "/admin/movies/{movieId}/assets",
                "/admin/jobs",
                "/admin/jobs/{jobId}",
                "/admin/jobs/{jobId}/retry",
                "/admin/assets/{assetId}/preview",
                "/admin/audit",
                "/admin/playback-sessions/{sessionId}/token",
                "/admin/movies/{movieId}/artwork",
                "/admin/movies/{movieId}/publish",
                "/admin/movies/{movieId}/activate",
                "/admin/movies/{movieId}/unpublish",
                "/admin/movies/{movieId}/archive",
                "/admin/movies/{movieId}/restore",
                "/admin/movies/{movieId}/preview"));

        Map<String, Object> components = (Map<String, Object>) document.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> movieSummary = (Map<String, Object>) schemas.get("MovieSummary");
        Map<String, Object> movieProperties = (Map<String, Object>) movieSummary.get("properties");
        assertThat((Map<String, Object>) movieProperties.get("posterUrl"))
                .containsEntry("format", "uri-reference");
        assertThat((Map<String, Object>) movieProperties.get("backdropUrl"))
                .containsEntry("format", "uri-reference");
        Map<String, Object> playback = (Map<String, Object>) schemas.get("Playback");
        Map<String, Object> playbackProperties = (Map<String, Object>) playback.get("properties");
        assertThat((Map<String, Object>) playbackProperties.get("manifestUrl"))
                .containsEntry("format", "uri-reference");

        Map<String, Object> user = (Map<String, Object>) schemas.get("User");
        assertThat((Map<String, Object>) user.get("properties")).containsKeys("id", "username", "roles");
        Map<String, Object> adminMovie = (Map<String, Object>) schemas.get("AdminMovie");
        assertThat((Map<String, Object>) adminMovie.get("properties"))
                .containsKeys("id", "lifecycle", "revision", "managementMode", "runtimeSeconds");

        Map<String, Object> uploadSession = (Map<String, Object>) schemas.get("UploadSession");
        assertThat((Map<String, Object>) uploadSession.get("properties"))
                .containsKeys("id", "movieId", "mediaVersionId", "assetId", "kind", "state",
                        "partSizeBytes", "totalParts", "declaredBytes", "expiresAt", "jobId");
        Map<String, Object> uploadInput = (Map<String, Object>) schemas.get("UploadInput");
        assertThat((Map<String, Object>) uploadInput.get("properties"))
                .containsKeys("kind", "fileName", "contentType", "sizeBytes", "resumeFingerprint");
        Map<String, Object> signedPart = (Map<String, Object>) schemas.get("SignedPart");
        assertThat((Map<String, Object>) signedPart.get("properties"))
                .containsKeys("partNumber", "url", "expiresAt", "headers");

        Map<String, Object> mediaVersion = (Map<String, Object>) schemas.get("MediaVersion");
        assertThat((Map<String, Object>) mediaVersion.get("properties"))
                .containsKeys("id", "movieId", "state", "createdAt", "updatedAt");
        Map<String, Object> mediaAsset = (Map<String, Object>) schemas.get("MediaAsset");
        assertThat((Map<String, Object>) mediaAsset.get("properties"))
                .containsKeys("id", "movieId", "kind", "state", "createdAt", "updatedAt");
        Map<String, Object> jobView = (Map<String, Object>) schemas.get("JobView");
        assertThat((Map<String, Object>) jobView.get("properties"))
                .containsKeys("id", "movieId", "kind", "state", "attemptNumber", "progressPercent");

        Map<String, Object> managedPlayback = (Map<String, Object>) schemas.get("ManagedPlayback");
        assertThat((Map<String, Object>) managedPlayback.get("properties"))
                .containsKeys("movieId", "manifestUrl", "resumePositionSeconds", "sessionId",
                        "mediaVersionId", "mediaToken", "mediaTokenExpiresAt");
        Map<String, Object> mediaToken = (Map<String, Object>) schemas.get("MediaToken");
        assertThat((Map<String, Object>) mediaToken.get("properties"))
                .containsKeys("mediaToken", "mediaTokenExpiresAt");
        Map<String, Object> publishRequest = (Map<String, Object>) schemas.get("PublishRequest");
        assertThat((Map<String, Object>) publishRequest.get("properties"))
                .containsKeys("mediaVersionId", "posterAssetId", "backdropAssetId");
        Map<String, Object> artworkRequest = (Map<String, Object>) schemas.get("ArtworkRequest");
        assertThat((Map<String, Object>) artworkRequest.get("properties")).containsKeys("kind", "assetId");
        Map<String, Object> assetPreview = (Map<String, Object>) schemas.get("AssetPreview");
        assertThat((Map<String, Object>) assetPreview.get("properties")).containsKeys("url", "expiresAt");
        Map<String, Object> auditEvent = (Map<String, Object>) schemas.get("AuditEvent");
        assertThat((Map<String, Object>) auditEvent.get("properties"))
                .containsKeys("id", "actorId", "action", "entityType", "before", "after", "createdAt");
        Map<String, Object> auditEventPage = (Map<String, Object>) schemas.get("AuditEventPage");
        assertThat((Map<String, Object>) auditEventPage.get("properties"))
                .containsKeys("items", "page", "size", "total");
        Map<String, Object> progressUpdate = (Map<String, Object>) schemas.get("ProgressUpdate");
        assertThat((Map<String, Object>) progressUpdate.get("properties"))
                .containsKeys("positionSeconds", "durationSeconds", "clientUpdatedAt", "sessionId",
                        "mediaVersionId");
    }
}
