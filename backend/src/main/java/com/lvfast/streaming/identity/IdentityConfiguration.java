package com.lvfast.streaming.identity;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true", matchIfMissing = true)
public class IdentityConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    KeyMaterial jwtKeyMaterial(AuthProperties properties) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                    pemBytes(properties.privateKeyLocation(), "PRIVATE KEY")));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                    pemBytes(properties.publicKeyLocation(), "PUBLIC KEY")));
            return new KeyMaterial(publicKey, privateKey);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException invalidKey) {
            throw new IllegalStateException("Unable to load configured RS256 key pair", invalidKey);
        }
    }

    @Bean
    NimbusJwtEncoder jwtEncoder(KeyMaterial keys) {
        RSAKey jwk = new RSAKey.Builder(keys.publicKey()).privateKey(keys.privateKey()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    @Bean
    JwtDecoder jwtDecoder(KeyMaterial keys, AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new JwtIssuerValidator(properties.issuer())));
        return decoder;
    }

    @Bean
    AccessTokenIssuer accessTokenIssuer(NimbusJwtEncoder encoder, AuthProperties properties) {
        return new JwtAccessTokenIssuer(encoder, properties.issuer());
    }

    @Bean
    AuthService authService(
            UserAccountRepository users,
            RefreshTokenSessionRepository sessions,
            CredentialsPolicy credentialsPolicy,
            PasswordEncoder passwordEncoder,
            RefreshTokenCodec refreshTokens,
            AccessTokenIssuer accessTokens,
            Clock clock,
            AuthProperties properties) {
        return new AuthService(
                users,
                sessions,
                credentialsPolicy,
                passwordEncoder,
                refreshTokens,
                accessTokens,
                clock,
                properties.accessTokenTtl(),
                properties.refreshTokenTtl());
    }

    @Bean
    AuthRateLimiter authRateLimiter(StringRedisTemplate redis) {
        return new RedisAuthRateLimiter(redis, 10, Duration.ofMinutes(1));
    }

    private static byte[] pemBytes(org.springframework.core.io.Resource resource, String type)
            throws IOException {
        String pem;
        try (var input = resource.getInputStream()) {
            pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
        String base64 = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    public record KeyMaterial(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
    }
}
