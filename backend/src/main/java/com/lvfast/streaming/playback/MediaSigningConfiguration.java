package com.lvfast.streaming.playback;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/**
 * Isolated media-signing configuration. The media key pair is loaded from its own locations, so a
 * leaked or rotated media key never affects login tokens. The key identifier is derived from the
 * public key when it is not configured, keeping the gateway verification key stable across restarts.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
public class MediaSigningConfiguration {

    @Bean
    @Lazy
    MediaSigningKey mediaSigningKey(
            MediaProperties properties,
            @Value("${app.auth.private-key-location:}") Resource loginPrivateKey,
            @Value("${app.auth.public-key-location:}") Resource loginPublicKey) {
        Resource privateKeyLocation = keyLocation(properties.privateKeyLocation(), loginPrivateKey);
        Resource publicKeyLocation = keyLocation(properties.publicKeyLocation(), loginPublicKey);
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(pemBytes(privateKeyLocation, "PRIVATE KEY")));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(pemBytes(publicKeyLocation, "PUBLIC KEY")));
            String keyId = properties.keyId() == null || properties.keyId().isBlank()
                    ? thumbprint(publicKey)
                    : properties.keyId();
            RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
            return new MediaSigningKey(jwk);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException invalidKey) {
            throw new IllegalStateException("Unable to load the configured media signing key pair", invalidKey);
        }
    }

    /**
     * Media signing prefers its own key pair and falls back to the login key pair when a dedicated
     * location is not configured. Every deployment must configure the media pair: the fallback exists
     * for local development and tests so a media token can never verify as a login token in production.
     */
    private static Resource keyLocation(Resource mediaKey, Resource loginKey) {
        return mediaKey != null && mediaKey.exists() ? mediaKey : loginKey;
    }

    @Bean
    @Lazy
    JwtEncoder mediaJwtEncoder(MediaSigningKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key.jwk())));
    }

    @Bean
    @Lazy
    MediaTokenIssuer mediaTokenIssuer(
            @Qualifier("mediaJwtEncoder") JwtEncoder mediaJwtEncoder,
            MediaProperties properties,
            MediaSigningKey key,
            Clock clock) {
        return new MediaTokenIssuer(mediaJwtEncoder, properties, key, clock);
    }

    /**
     * Verifier for the media key only. It is deliberately not the application's primary decoder: a
     * media token must never authenticate an API request, and a login token must never verify as a
     * media token.
     */
    @Bean
    @Lazy
    JwtDecoder mediaJwtDecoder(MediaSigningKey key, MediaProperties properties) {
        NimbusJwtDecoder decoder;
        try {
            decoder = NimbusJwtDecoder.withPublicKey(key.jwk().toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        } catch (com.nimbusds.jose.JOSEException unusableKey) {
            throw new IllegalStateException("The configured media signing key is not an RSA public key",
                    unusableKey);
        }
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                audiences -> audiences != null && audiences.contains(properties.audience()));        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new JwtIssuerValidator(properties.issuer()), audience));
        return decoder;
    }

    private static String thumbprint(RSAPublicKey publicKey) throws NoSuchAlgorithmException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(publicKey.getPublicExponent().toByteArray());
        byte[] digest = sha256.digest(publicKey.getModulus().toByteArray());
        return HexFormat.of().formatHex(digest).substring(0, 32);
    }

    private static byte[] pemBytes(Resource resource, String type) throws IOException {
        if (resource == null || !resource.exists()) {
            throw new IOException("Media signing key material is not configured");
        }
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

    public record MediaSigningKey(RSAKey jwk) {
    }
}
