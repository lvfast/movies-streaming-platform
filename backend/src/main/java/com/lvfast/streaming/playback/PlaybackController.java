package com.lvfast.streaming.playback;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PlaybackController {
    private final PlaybackService playback;

    PlaybackController(PlaybackService playback) {
        this.playback = playback;
    }

    /**
     * Managed movies answer a version-pinned playback grant; legacy fixtures keep the original
     * manifest/resume shape.
     */
    @GetMapping("/movies/{movieId}/playback")
    Playback playback(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId) {
        return playback.playback(userId(jwt), movieId);
    }

    @PutMapping("/me/progress/{movieId}")
    Object updateProgress(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId,
            @Valid @RequestBody ProgressUpdateRequest request) {
        UUID userId = userId(jwt);
        boolean hasSession = request.sessionId() != null;
        boolean hasVersion = request.mediaVersionId() != null;
        if (hasSession != hasVersion) {
            throw new ProgressValidationException(
                    "sessionId and mediaVersionId must be supplied together");
        }
        if (hasSession) {
            return playback.updateManagedProgress(
                    userId,
                    movieId,
                    request.sessionId(),
                    request.mediaVersionId(),
                    request.positionSeconds(),
                    request.durationSeconds(),
                    request.clientUpdatedAt());
        }
        return playback.updateProgress(
                userId,
                movieId,
                request.positionSeconds(),
                request.durationSeconds(),
                request.clientUpdatedAt());
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record ProgressUpdateRequest(
            @NotNull @Min(0) Integer positionSeconds,
            @NotNull @Min(1) Integer durationSeconds,
            @NotNull Instant clientUpdatedAt,
            UUID sessionId,
            UUID mediaVersionId) {}
}
