package com.lvfast.streaming.media.job;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal machine-authenticated endpoints consumed only by the transcoder worker. Public nginx
 * must not proxy {@code /internal/v1}. The claim response, not the RabbitMQ command, carries the
 * immutable source/output parameters.
 */
@RestController
@RequestMapping("/internal/v1")
public class WorkerJobController {

    private final JobClaimService claims;

    public WorkerJobController(JobClaimService claims) {
        this.claims = claims;
    }

    @PostMapping("/jobs/{jobId}/claim")
    JobClaimService.ClaimResponse claim(
            @PathVariable UUID jobId,
            @RequestBody JobClaimService.ClaimRequest request) {
        return claims.claim(jobId, request);
    }

    @PostMapping("/jobs/{jobId}/heartbeat")
    ResponseEntity<JobClaimService.HeartbeatResponse> heartbeat(
            @PathVariable UUID jobId,
            @RequestBody JobClaimService.HeartbeatRequest request) {
        return ResponseEntity.ok(claims.heartbeat(jobId, request));
    }
}
