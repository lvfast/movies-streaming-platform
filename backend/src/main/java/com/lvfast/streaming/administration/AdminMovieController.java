package com.lvfast.streaming.administration;

import com.lvfast.streaming.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminMovieController {

    private final AdminMovieService admin;

    public AdminMovieController(AdminMovieService admin) {
        this.admin = admin;
    }

    @GetMapping("/movies")
    AdminMoviePage list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return admin.list(page, size);
    }

    @PostMapping("/movies")
    ResponseEntity<AdminMovieView> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AdminMovieInput input,
            HttpServletRequest request) {
        AdminMovieView view = admin.create(actorId(jwt), idempotencyKey, requestId(request), input);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(view)).body(view);
    }

    @GetMapping("/movies/{movieId}")
    ResponseEntity<AdminMovieView> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId) {
        AdminMovieView view = admin.get(movieId);
        return ResponseEntity.ok().eTag(etag(view)).body(view);
    }

    @PutMapping("/movies/{movieId}")
    ResponseEntity<AdminMovieView> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody AdminMovieInput input,
            HttpServletRequest request) {
        AdminMovieView view = admin.update(actorId(jwt), movieId, ifMatch, requestId(request), input);
        return ResponseEntity.ok().eTag(etag(view)).body(view);
    }

    @GetMapping("/genres")
    GenreList genres(@AuthenticationPrincipal Jwt jwt) {
        return admin.genres();
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
