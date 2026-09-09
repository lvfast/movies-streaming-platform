package com.lvfast.streaming.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CredentialsPolicyTest {

    private final CredentialsPolicy policy = new CredentialsPolicy();

    @Test
    void normalizesValidUsername() {
        assertThat(policy.validateAndNormalizeUsername("Demo_User"))
                .isEqualTo("demo_user");
    }

    @Test
    void rejectsUsernameOutsideThePublicContract() {
        assertThatThrownBy(() -> policy.validateAndNormalizeUsername("bad user"))
                .isInstanceOf(IdentityValidationException.class)
                .hasMessageContaining("username");
    }

    @Test
    void requiresTwelveCharacterPasswordWithUpperLowerAndDigit() {
        policy.validatePassword("LongEnough9X");

        assertThatThrownBy(() -> policy.validatePassword("alllowercase1"))
                .isInstanceOf(IdentityValidationException.class)
                .hasMessageContaining("password");
    }
}
