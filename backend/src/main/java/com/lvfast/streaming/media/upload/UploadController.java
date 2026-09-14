package com.lvfast.streaming.media.upload;

import com.lvfast.streaming.media.storage.PartPage;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class UploadController {

    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/movies/{movieId}/uploads")
    ResponseEntity<UploadSession> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody UploadInput input) {
        UploadSession session = uploads.create(actorId(jwt), idempotencyKey, movieId, input);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @GetMapping("/uploads/{uploadId}")
    UploadSession get(@PathVariable UUID uploadId) {
        return uploads.get(uploadId);
    }

    @GetMapping("/uploads/{uploadId}/parts")
    PartPage listParts(@PathVariable UUID uploadId, @RequestParam(required = false) Integer marker) {
        return uploads.listParts(uploadId, marker);
    }

    @PostMapping("/uploads/{uploadId}/part-urls")
    SignedPartList signParts(@PathVariable UUID uploadId, @Valid @RequestBody PartSignRequest request) {
        return uploads.signParts(uploadId, request);
    }

    @PostMapping("/uploads/{uploadId}/complete")
    UploadSession complete(
            @PathVariable UUID uploadId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return uploads.complete(idempotencyKey, uploadId);
    }

    @PostMapping("/uploads/{uploadId}/abort")
    UploadSession abort(@PathVariable UUID uploadId) {
        return uploads.abort(uploadId);
    }

    private UUID actorId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
