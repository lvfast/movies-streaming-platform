package com.lvfast.streaming.playback;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

/**
 * Issues short-lived RS256 media tokens. A token is bound to the authenticated subject, the playback
 * session, the movie, the pinned media version, the purpose and exactly one slash-terminated
 * immutable HLS prefix, and is signed with the dedicated media key.
 *
 * <p>The signing key is resolved per issued token rather than at construction, so an application
 * context that never issues a media token never needs the key material to be present.
 */
public class MediaTokenIssuer {

    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final String configuredKeyId;
    private final java.time.Duration ttl;
    private final Clock clock;
    private final MediaSigningConfiguration.MediaSigningKey signingKey;

    public MediaTokenIssuer(
            JwtEncoder encoder,
            MediaProperties properties,
            MediaSigningConfiguration.MediaSigningKey signingKey,
            Clock clock) {
        this.encoder = encoder;
        this.issuer = properties.issuer();
        this.audience = properties.audience();
        this.configuredKeyId = properties.keyId();
        this.ttl = properties.tokenTtl();
        this.clock = clock;
        this.signingKey = signingKey;
    }

    public IssuedToken issue(
            UUID subject,
            UUID sessionId,
            UUID movieId,
            UUID mediaVersionId,
            String prefix,
            String purpose) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId()).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(subject.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .claim("sid", sessionId.toString())
                .claim("movieId", movieId.toString())
                .claim("versionId", mediaVersionId.toString())
                .claim("prefix", prefix)
                .claim("purpose", purpose)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public String keyId() {
        RSAKey key = signingKey.jwk();
        return configuredKeyId == null || configuredKeyId.isBlank() ? key.getKeyID() : configuredKeyId;
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
