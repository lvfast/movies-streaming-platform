package com.lvfast.streaming.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final String issuer;

    public JwtAccessTokenIssuer(JwtEncoder encoder, String issuer) {
        this.encoder = encoder;
        this.issuer = issuer;
    }

    @Override
    public String issue(UserAccount user, Set<String> roles, Instant issuedAt, Duration ttl) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .subject(user.id().toString())
                .claim("username", user.username())
                .claim("roles", List.copyOf(roles))
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
