package com.lvfast.streaming.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenSessionTest {

    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    @Test
    void rotationRevokesTheParentAndKeepsTheFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshTokenSession parent = RefreshTokenSession.issue(
                UUID.randomUUID(), UUID.randomUUID(), familyId, "old-hash", NOW.plusSeconds(60), NOW);

        RefreshTokenSession child = parent.rotate(
                UUID.randomUUID(), "new-hash", NOW.plusSeconds(120), NOW.plusSeconds(1));

        assertThat(parent.isRevoked()).isTrue();
        assertThat(parent.replacedBy()).contains(child.id());
        assertThat(child.familyId()).isEqualTo(familyId);
        assertThat(child.userId()).isEqualTo(parent.userId());
    }

    @Test
    void usingARotatedTokenSignalsFamilyReuse() {
        RefreshTokenSession parent = RefreshTokenSession.issue(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "old-hash", NOW.plusSeconds(60), NOW);
        parent.rotate(UUID.randomUUID(), "new-hash", NOW.plusSeconds(120), NOW.plusSeconds(1));

        assertThatThrownBy(() -> parent.rotate(
                UUID.randomUUID(), "another-hash", NOW.plusSeconds(180), NOW.plusSeconds(2)))
                .isInstanceOf(RefreshTokenReuseException.class);
    }

    @Test
    void expiredTokenCannotRotate() {
        RefreshTokenSession expired = RefreshTokenSession.issue(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hash", NOW.minusSeconds(1), NOW.minusSeconds(60));

        assertThatThrownBy(() -> expired.rotate(
                UUID.randomUUID(), "new-hash", NOW.plusSeconds(60), NOW))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }
}
