package com.lvfast.streaming.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    @Mock private UserAccountRepository users;
    @Mock private RefreshTokenSessionRepository sessions;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AccessTokenIssuer accessTokens;
    @Mock private RoleService roles;

    private RefreshTokenCodec refreshTokens;
    private AuthService service;

    @BeforeEach
    void setUp() {
        refreshTokens = new RefreshTokenCodec();
        service = new AuthService(
                users,
                sessions,
                new CredentialsPolicy(),
                passwordEncoder,
                refreshTokens,
                accessTokens,
                roles,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15),
                Duration.ofDays(7));
    }

    @Test
    void registrationHashesThePasswordAndIssuesBothTokenKinds() {
        when(users.existsByUsername("demo_user")).thenReturn(false);
        when(passwordEncoder.encode("LongEnough9X")).thenReturn("argon2-hash");
        when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(roles.roles(any())).thenReturn(java.util.Set.of("USER"));
        when(accessTokens.issue(any(), any(), eq(NOW), eq(Duration.ofMinutes(15))))
                .thenReturn("signed-access-token");

        AuthSession result = service.register("Demo_User", "LongEnough9X");

        ArgumentCaptor<UserAccount> user = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).saveAndFlush(user.capture());
        assertThat(user.getValue().username()).isEqualTo("demo_user");
        assertThat(user.getValue().passwordHash()).isEqualTo("argon2-hash");
        verify(roles).grant(eq(user.getValue().id()), eq("USER"));
        assertThat(result.accessToken()).isEqualTo("signed-access-token");
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.refreshToken()).doesNotContain("=");
    }

    @Test
    void duplicateUsernameIsRejectedBeforeHashing() {
        when(users.existsByUsername("demo_user")).thenReturn(true);

        assertThatThrownBy(() -> service.register("Demo_User", "LongEnough9X"))
                .isInstanceOf(UsernameUnavailableException.class);
    }

    @Test
    void databaseUniquenessRaceIsStillReportedAsUsernameUnavailable() {
        when(users.existsByUsername("demo_user")).thenReturn(false);
        when(passwordEncoder.encode("LongEnough9X")).thenReturn("argon2-hash");
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.register("Demo_User", "LongEnough9X"))
                .isInstanceOf(UsernameUnavailableException.class);
    }

    @Test
    void reuseOfARotatedRefreshTokenRevokesTheWholeFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshTokenSession parent = RefreshTokenSession.issue(
                UUID.randomUUID(), UUID.randomUUID(), familyId,
                refreshTokens.hash("stolen-token"), NOW.plusSeconds(60), NOW.minusSeconds(60));
        parent.rotate(UUID.randomUUID(), "child-hash", NOW.plusSeconds(120), NOW.minusSeconds(1));
        when(sessions.findByTokenHash(refreshTokens.hash("stolen-token")))
                .thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.refresh("stolen-token"))
                .isInstanceOf(RefreshTokenReuseException.class);
        verify(sessions).revokeFamily(eq(familyId), eq(NOW));
    }
}
