package com.lvfast.streaming.media.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Machine authentication and claim lease settings for the independent transcoder worker. The
 * credential is a shared secret distinct from human login JWTs; the lease is the window a worker has
 * before the backend may consider its attempt abandoned.
 */
@ConfigurationProperties(prefix = "app.media.worker")
public record WorkerAccess(
        @DefaultValue("") String credential,
        @DefaultValue("PT2M") Duration leaseTtl) {

    public WorkerAccess {
        if (leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalStateException("app.media.worker.lease-ttl must be positive");
        }
    }
}
