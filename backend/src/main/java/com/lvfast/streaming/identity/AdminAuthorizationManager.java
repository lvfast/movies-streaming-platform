package com.lvfast.streaming.identity;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Authorizes {@code /api/v1/admin/**} against the current database ADMIN role, never against a
 * role hint carried in a login token. A valid token whose ADMIN role was removed is denied (403);
 * a request without a valid token surfaces as 401.
 */
public class AdminAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final RoleService roles;

    public AdminAuthorizationManager(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new InsufficientAuthenticationException("A valid bearer access token is required");
        }
        return new AuthorizationDecision(roles.isAdmin(UUID.fromString(jwt.getSubject())));
    }
}
