package com.lvfast.streaming.administration;

import com.lvfast.streaming.common.RequestIdFilter;
import com.lvfast.streaming.playback.PlaybackGrant;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN publication and lifecycle commands. Publish and activation are explicit and idempotent;
 * every command requires {@code If-Match} and answers the updated movie with a fresh ETag.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminLifecycleController {

    private final MoviePublicationService publication;

    public AdminLifecycleController(MoviePublicationService publication) {
        this.publication = publication;
    }

    @PostMapping("/movies/{movieId}/publish")
    ResponseEntity<AdminMovieView> publish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PublishRequest request,
            HttpServletRequest httpRequest) {
        AdminMovieView view = publication.publish(
                actorId(jwt), movieId, ifMatch, idempotencyKey, requestId(httpRequest),
                request == null ? PublishRequest.of(null) : request);
        return ResponseEntity.ok().eTag(etag(view)).body(view);
    }

    @PostMapping("/movies/{movieId}/activate")
    ResponseEntity<AdminMovieView> activate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PublishRequest request,
            HttpServletRequest httpRequest) {
        AdminMovieView view = publication.activate(
                actorId(jwt), movieId, ifMatch, idempotencyKey, requestId(httpRequest),
                request == null ? PublishRequest.of(null) : request);
        return ResponseEntity.ok().eTag(etag(view)).body(view);
    }

    @PostMapping("/movies/{movieId}/unpublish")
    ResponseEntity<AdminMovieView> unpublish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest request) {
        return respond(publication.unpublish(actorId(jwt), movieId, ifMatch, requestId(request)));
    }

    @PostMapping("/movies/{movieId}/archive")
    ResponseEntity<AdminMovieView> archive(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest request) {
        return respond(publication.archive(actorId(jwt), movieId, ifMatch, requestId(request)));
    }

    @PostMapping("/movies/{movieId}/restore")
    ResponseEntity<AdminMovieView> restore(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest request) {
        return respond(publication.restore(actorId(jwt), movieId, ifMatch, requestId(request)));
    }

    @PostMapping("/movies/{movieId}/artwork")
    ResponseEntity<AdminMovieView> attachArtwork(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) ArtworkRequest request,
            HttpServletRequest httpRequest) {
        AdminMovieView view = publication.attachArtwork(
                actorId(jwt), movieId, ifMatch, requestId(httpRequest),
                request == null ? new ArtworkRequest(null, null) : request);
        return ResponseEntity.ok().eTag(etag(view)).body(view);
    }

    @PostMapping("/movies/{movieId}/preview")
    PlaybackGrant preview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId,
            @RequestBody(required = false) PublishRequest body,
            HttpServletRequest request) {
        return publication.preview(actorId(jwt), movieId, requestId(request),
                body == null ? null : body.mediaVersionId());
    }

    private ResponseEntity<AdminMovieView> respond(AdminMovieView view) {
        return ResponseEntity.ok().eTag(etag(view)).body(view);
    }

    private UUID actorId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? null : value.toString();
    }

    private String etag(AdminMovieView view) {
        return "\"" + view.revision() + "\"";
    }
}
