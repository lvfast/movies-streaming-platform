package com.lvfast.streaming.playback;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Media-token refresh for an existing playback session. The refresh is bounded by the session owner,
 * the session lifetime and, for viewer sessions, the movie's current publication state, so a media
 * token can never be refreshed for an unpublished or archived movie.
 */
@RestController
@RequestMapping("/api/v1")
public class MediaTokenController {

    private final MediaSessionService sessions;

    MediaTokenController(MediaSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/me/playback-sessions/{sessionId}/token")
    MediaTokenResponse refresh(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId) {
        return MediaTokenResponse.of(sessions.refreshViewerToken(userId(jwt), sessionId));
    }

    @PostMapping("/admin/playback-sessions/{sessionId}/token")
    MediaTokenResponse refreshPreview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId) {
        return MediaTokenResponse.of(sessions.refreshPreviewToken(userId(jwt), sessionId));
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record MediaTokenResponse(String mediaToken, String mediaTokenExpiresAt) {
        static MediaTokenResponse of(MediaTokenIssuer.IssuedToken token) {
            return new MediaTokenResponse(token.token(), token.expiresAt().toString());
        }
    }
}
