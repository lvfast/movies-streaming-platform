package com.lvfast.streaming.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@ConfigurationProperties("app.auth")
public record AuthProperties(
        boolean enabled,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Resource privateKeyLocation,
        Resource publicKeyLocation,
        String refreshCookieName,
        boolean secureCookie) {
}
