package com.lvfast.streaming.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtAccessTokenIssuerTest {

    @Test
    void issuesAnRs256TokenWithRequiredClaimsAndFifteenMinuteExpiry() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
        RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        JwtAccessTokenIssuer issuer = new JwtAccessTokenIssuer(encoder, "https://issuer.example");
        UserAccount user = UserAccount.register("demo", "hash", Instant.parse("2026-09-06T00:00:00Z"));
        Instant issuedAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.SECONDS);

        String encoded = issuer.issue(user, issuedAt, Duration.ofMinutes(15));

        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        Jwt jwt = decoder.decode(encoded);
        assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://issuer.example");
        assertThat(jwt.getClaimAsString("username")).isEqualTo("demo");
        assertThat(jwt.getIssuedAt()).isEqualTo(issuedAt);
        assertThat(jwt.getExpiresAt()).isEqualTo(issuedAt.plus(Duration.ofMinutes(15)));
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
    }
}
