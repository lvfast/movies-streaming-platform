package com.lvfast.streaming.ops;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lvfast.streaming.audit.AuditService;
import com.lvfast.streaming.identity.RoleService;
import com.lvfast.streaming.identity.UserAccount;
import com.lvfast.streaming.identity.UserAccountRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminRoleCommandTest {

    private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Mock private UserAccountRepository users;
    @Mock private RoleService roles;
    @Mock private AuditService audit;

    private AdminRoleCommand command;

    @BeforeEach
    void setUp() {
        command = new AdminRoleCommand("", users, roles, audit);
    }

    @Test
    void grantAdminAddsRoleAndRecordsAudit() {
        UserAccount user = UserAccount.register("alice", "hash", Instant.EPOCH);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));

        command.grantAdmin("alice");

        verify(roles).grant(user.id(), "ADMIN");
        verify(audit).record(
                eq(OPERATOR), eq("OPERATOR"), eq("ROLE_GRANTED"), eq("APP_USER"),
                eq(user.id()), isNull(), any(), any());
    }

    @Test
    void revokeAdminRefusesTheLastAdministrator() {
        UserAccount user = UserAccount.register("alice", "hash", Instant.EPOCH);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(roles.isAdmin(user.id())).thenReturn(true);
        when(roles.countAdmins()).thenReturn(1L);

        assertThatThrownBy(() -> command.revokeAdmin("alice"))
                .isInstanceOf(LastAdminRoleException.class);

        verify(roles, never()).revoke(any(), any());
    }

    @Test
    void revokeAdminRemovesRoleWhenAnotherAdminRemains() {
        UserAccount user = UserAccount.register("alice", "hash", Instant.EPOCH);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(roles.isAdmin(user.id())).thenReturn(true);
        when(roles.countAdmins()).thenReturn(2L);

        command.revokeAdmin("alice");

        verify(roles).revoke(user.id(), "ADMIN");
        verify(audit).record(
                eq(OPERATOR), eq("OPERATOR"), eq("ROLE_REVOKED"), eq("APP_USER"),
                eq(user.id()), isNull(), any(), any());
    }

    @Test
    void revokeAdminIgnoresAnAccountWithoutTheRole() {
        UserAccount user = UserAccount.register("alice", "hash", Instant.EPOCH);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(roles.isAdmin(user.id())).thenReturn(false);

        command.revokeAdmin("alice");

        verify(roles, never()).revoke(any(), any());
        verify(roles, never()).countAdmins();
    }
}
