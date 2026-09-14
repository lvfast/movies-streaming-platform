package com.lvfast.streaming.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Generates throw-away RSA key pairs for tests that need a real RS256 signing key on disk. Nothing
 * here is a secret: every file is created in the system temporary directory and deleted on exit.
 */
public final class TestKeyFiles {

    private TestKeyFiles() {
    }

    public static Pair generate(String prefix) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Path privateKey = Files.createTempFile(prefix + "-private-", ".pem");
            Path publicKey = Files.createTempFile(prefix + "-public-", ".pem");
            Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                    StandardCharsets.US_ASCII);
            Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                    StandardCharsets.US_ASCII);
            privateKey.toFile().deleteOnExit();
            publicKey.toFile().deleteOnExit();
            return new Pair(privateKey.toUri().toString(), publicKey.toUri().toString());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create test RSA key material", failure);
        }
    }

    private static String pem(String type, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    public record Pair(String privateKeyLocation, String publicKeyLocation) {
    }
}
