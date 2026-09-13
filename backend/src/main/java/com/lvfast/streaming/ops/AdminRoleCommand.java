package com.lvfast.streaming.ops;

import com.lvfast.streaming.audit.AuditService;
import com.lvfast.streaming.identity.RoleService;
import com.lvfast.streaming.identity.UserAccount;
import com.lvfast.streaming.identity.UserAccountRepository;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Operator command for granting or revoking ADMIN on an existing account. It runs only when
 * {@code app.ops.roles} is set, e.g. {@code grant:alice} or {@code revoke:alice}, and is intended
 * to be launched as a non-web runner. The underlying grant/revoke methods stay directly testable.
 */
@Component
public class AdminRoleCommand implements ApplicationRunner {

    private static final UUID OPERATOR_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String ADMIN = "ADMIN";

    private final String command;
    private final UserAccountRepository users;
    private final RoleService roles;
    private final AuditService audit;

    public AdminRoleCommand(
            @Value("${app.ops.roles:}") String command,
            UserAccountRepository users,
            RoleService roles,
            AuditService audit) {
        this.command = command;
        this.users = users;
        this.roles = roles;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (command == null || command.isBlank()) {
            return;
        }
        String[] parts = command.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("app.ops.roles must be grant:<username> or revoke:<username>");
        }
        String username = parts[1].trim();
        switch (parts[0].trim()) {
            case "grant" -> grantAdmin(username);
            case "revoke" -> revokeAdmin(username);
            default -> throw new IllegalStateException(
                    "app.ops.roles must be grant:<username> or revoke:<username>");
        }
    }

    public void grantAdmin(String username) {
        UserAccount user = findUser(username);
        roles.grant(user.id(), ADMIN);
        audit.record(
                OPERATOR_ACTOR_ID,
                "OPERATOR",
                "ROLE_GRANTED",
                "APP_USER",
                user.id(),
                null,
                Map.of(),
                Map.of("role", ADMIN));
    }

    public void revokeAdmin(String username) {
        UserAccount user = findUser(username);
        if (!roles.isAdmin(user.id())) {
            return;
        }
        if (roles.countAdmins() <= 1) {
            throw new LastAdminRoleException(username);
        }
        roles.revoke(user.id(), ADMIN);
        audit.record(
                OPERATOR_ACTOR_ID,
                "OPERATOR",
                "ROLE_REVOKED",
                "APP_USER",
                user.id(),
                null,
                Map.of("role", ADMIN),
                Map.of());
    }

    private UserAccount findUser(String username) {
        String normalized = username.toLowerCase(Locale.ROOT);
        return users.findByUsername(normalized)
                .orElseThrow(() -> new IllegalStateException(
                        "No account exists for username '" + username + "'"));
    }
}
