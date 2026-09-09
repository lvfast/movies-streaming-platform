package com.lvfast.streaming.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefreshTokenCodecTest {

    private final RefreshTokenCodec codec = new RefreshTokenCodec();

    @Test
    void persistsOnlyADeterministicSha256Hash() {
        String hash = codec.hash("raw-secret-token");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash).isEqualTo(codec.hash("raw-secret-token"));
        assertThat(hash).doesNotContain("raw-secret-token");
    }

    @Test
    void generatedTokensAreUrlSafeAndContainAtLeast256Bits() {
        String token = codec.generate();

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
    }
}
