package com.lvfast.streaming.media.job;

import com.lvfast.streaming.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin write endpoint for manual job retry. Retry is guarded by the admin role (via the shared
 * {@code /api/v1/admin/**} authorization) and by an idempotency key, and returns a brand-new job id
 * targeting the retained source so a failed attempt never disturbs an active version.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminJobController {

    private final JobRetryService retry;

    public AdminJobController(JobRetryService retry) {
        this.retry = retry;
    }

    @PostMapping("/jobs/{jobId}/retry")
    ResponseEntity<JobView> retry(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        JobView view = retry.retry(actorId(jwt), idempotencyKey, requestId(request), jobId);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    private UUID actorId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
