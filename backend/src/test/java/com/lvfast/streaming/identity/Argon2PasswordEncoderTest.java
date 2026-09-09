package com.lvfast.streaming.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class Argon2PasswordEncoderTest {

    @Test
    void configuredHasherProducesArgon2idAndVerifiesIt() {
        PasswordEncoder encoder = new IdentityConfiguration().passwordEncoder();

        String encoded = encoder.encode("LongEnough9X");

        assertThat(encoded).startsWith("$argon2id$");
        assertThat(encoder.matches("LongEnough9X", encoded)).isTrue();
        assertThat(encoder.matches("WrongPassword9X", encoded)).isFalse();
    }
}
