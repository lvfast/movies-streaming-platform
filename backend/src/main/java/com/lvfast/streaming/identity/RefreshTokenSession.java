package com.lvfast.streaming.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "refresh_session")
public class RefreshTokenSession {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected RefreshTokenSession() {
    }

    private RefreshTokenSession(
            UUID id,
            UUID userId,
            UUID familyId,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public static RefreshTokenSession issue(
            UUID id,
            UUID userId,
            UUID familyId,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt) {
        return new RefreshTokenSession(id, userId, familyId, tokenHash, expiresAt, createdAt);
    }

    public RefreshTokenSession rotate(UUID childId, String childHash, Instant childExpiry, Instant now) {
        if (isRevoked()) {
            throw new RefreshTokenReuseException();
        }
        if (!expiresAt.isAfter(now)) {
            revokedAt = now;
            throw new InvalidRefreshTokenException("refresh token expired");
        }
        revokedAt = now;
        lastUsedAt = now;
        replacedBy = childId;
        return issue(childId, userId, familyId, childHash, childExpiry, now);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID familyId() {
        return familyId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public Optional<UUID> replacedBy() {
        return Optional.ofNullable(replacedBy);
    }
}
