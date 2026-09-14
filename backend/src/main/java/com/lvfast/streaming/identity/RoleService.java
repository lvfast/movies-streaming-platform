package com.lvfast.streaming.identity;

import java.util.Set;
import java.util.UUID;

/**
 * Current-database role source of truth. Roles are never trusted from a login token;
 * every authorization decision reads the authoritative {@code user_role} rows.
 */
public interface RoleService {

    Set<String> roles(UUID userId);

    boolean isAdmin(UUID userId);

    void grant(UUID userId, String role);

    void revoke(UUID userId, String role);

    long countAdmins();
}
