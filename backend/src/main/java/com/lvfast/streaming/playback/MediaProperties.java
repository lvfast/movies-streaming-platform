package com.lvfast.streaming.playback;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Media signing and playback-session settings. The media signing key is deliberately separate from
 * the login-token key: a media token authorizes one immutable HLS prefix for a few minutes and must
 * never be accepted as a login token, or the other way round.
 */
@ConfigurationProperties("app.media.signing")
public record MediaProperties(
        String issuer,
        String audience,
        Duration tokenTtl,
        Duration playbackSessionTtl,
        String keyId,
        Resource privateKeyLocation,
        Resource publicKeyLocation) {

    /** The contract fixes the media audience and caps the media token lifetime at five minutes. */
    public MediaProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "lvfast-media-backend";
        }
        if (audience == null || audience.isBlank()) {
            audience = "lvfast-media";
        }
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            tokenTtl = Duration.ofMinutes(5);
        }
        if (tokenTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            tokenTtl = Duration.ofMinutes(5);
        }
        if (playbackSessionTtl == null || playbackSessionTtl.isZero() || playbackSessionTtl.isNegative()) {
            playbackSessionTtl = Duration.ofHours(12);
        }
    }
}
