package com.lvfast.streaming.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

public class AuthService {

    private static final String USER_ROLE = "USER";

    private final UserAccountRepository users;
    private final RefreshTokenSessionRepository sessions;
    private final CredentialsPolicy credentialsPolicy;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenCodec refreshTokens;
    private final AccessTokenIssuer accessTokens;
    private final RoleService roles;
    private final Clock clock;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    public AuthService(
            UserAccountRepository users,
            RefreshTokenSessionRepository sessions,
            CredentialsPolicy credentialsPolicy,
            PasswordEncoder passwordEncoder,
            RefreshTokenCodec refreshTokens,
            AccessTokenIssuer accessTokens,
            RoleService roles,
            Clock clock,
            Duration accessTokenTtl,
            Duration refreshTokenTtl) {
        this.users = users;
        this.sessions = sessions;
        this.credentialsPolicy = credentialsPolicy;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.roles = roles;
        this.clock = clock;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    @Transactional
    public AuthSession register(String username, String password) {
        String normalized = credentialsPolicy.validateAndNormalizeUsername(username);
        credentialsPolicy.validatePassword(password);
        if (users.existsByUsername(normalized)) {
            throw new UsernameUnavailableException();
        }
        Instant now = clock.instant();
        UserAccount user;
        try {
            user = users.saveAndFlush(UserAccount.register(
                    normalized, passwordEncoder.encode(password), now));
        } catch (DataIntegrityViolationException duplicateUsername) {
            throw new UsernameUnavailableException();
        }
        roles.grant(user.id(), USER_ROLE);
        return issueSession(user, UUID.randomUUID(), now);
    }

    @Transactional
    public AuthSession login(String username, String password) {
        String normalized = credentialsPolicy.validateAndNormalizeUsername(username);
        UserAccount user = users.findByUsername(normalized)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueSession(user, UUID.randomUUID(), clock.instant());
    }

    // Reuse is reported as an error, but its family revocation must still commit.
    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public AuthSession refresh(String rawToken) {
        Instant now = clock.instant();
        RefreshTokenSession parent = sessions.findByTokenHash(refreshTokens.hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("refresh token is invalid"));
        if (parent.isRevoked()) {
            sessions.revokeFamily(parent.familyId(), now);
            throw new RefreshTokenReuseException();
        }

        String childRawToken = refreshTokens.generate();
        RefreshTokenSession child;
        try {
            child = parent.rotate(
                    UUID.randomUUID(),
                    refreshTokens.hash(childRawToken),
                    now.plus(refreshTokenTtl),
                    now);
        } catch (RefreshTokenReuseException reused) {
            sessions.revokeFamily(parent.familyId(), now);
            throw reused;
        }
        sessions.save(parent);
        sessions.save(child);
        UserAccount user = users.findById(parent.userId())
                .orElseThrow(() -> new InvalidRefreshTokenException("refresh token user no longer exists"));
        return new AuthSession(
                accessTokens.issue(user, roles.roles(user.id()), now, accessTokenTtl),
                childRawToken,
                accessTokenTtl.toSeconds(),
                user);
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        sessions.findByTokenHash(refreshTokens.hash(rawToken)).ifPresent(session -> {
            session.revoke(clock.instant());
            sessions.save(session);
        });
    }

    private AuthSession issueSession(UserAccount user, UUID familyId, Instant now) {
        String rawRefreshToken = refreshTokens.generate();
        sessions.save(RefreshTokenSession.issue(
                UUID.randomUUID(),
                user.id(),
                familyId,
                refreshTokens.hash(rawRefreshToken),
                now.plus(refreshTokenTtl),
                now));
        return new AuthSession(
                accessTokens.issue(user, roles.roles(user.id()), now, accessTokenTtl),
                rawRefreshToken,
                accessTokenTtl.toSeconds(),
                user);
    }
}
