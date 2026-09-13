package com.lvfast.transcoder.output;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Serializes the artifact manifest and computes object digests for integrity declarations. */
@Component
public class ArtifactWriter {

    private final ObjectMapper json = new ObjectMapper();

    public void write(Path file, Artifact artifact) {
        try {
            Files.writeString(file, json.writeValueAsString(artifact));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Unable to write artifact manifest", failure);
        }
    }

    public static String sha256Hex(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Unable to digest output object", failure);
        }
    }
}
