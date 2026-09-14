package com.lvfast.streaming.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true", matchIfMissing = true)
public class AuthController {

    private final AuthService auth;
    private final RoleService roles;
    private final String cookieName;
    private final boolean secureCookie;
    private final Duration refreshTokenTtl;

    @Autowired
    public AuthController(AuthService auth, RoleService roles, AuthProperties properties) {
        this(
                auth,
                roles,
                properties.refreshCookieName(),
                properties.secureCookie(),
                properties.refreshTokenTtl());
    }

    public AuthController(
            AuthService auth,
            RoleService roles,
            String cookieName,
            boolean secureCookie,
            Duration refreshTokenTtl) {
        this.auth = auth;
        this.roles = roles;
        this.cookieName = cookieName;
        this.secureCookie = secureCookie;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody CredentialsRequest request) {
        return authenticated(auth.register(request.username(), request.password()), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody CredentialsRequest request) {
        return authenticated(auth.login(request.username(), request.password()), HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        return authenticated(auth.refresh(readRefreshCookie(request)), HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(readRefreshCookie(request));
        ResponseCookie cleared = cookie("").maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return new UserResponse(userId, jwt.getClaimAsString("username"), List.copyOf(roles.roles(userId)));
    }

    private ResponseEntity<AuthResponse> authenticated(AuthSession session, HttpStatus status) {
        ResponseCookie refreshCookie = cookie(session.refreshToken())
                .maxAge(refreshTokenTtl)
                .build();
        UserResponse user = new UserResponse(
                session.user().id(),
                session.user().username(),
                List.copyOf(roles.roles(session.user().id())));
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(new AuthResponse(session.accessToken(), "Bearer", session.expiresIn(), user));
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String value) {
        return ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path("/");
    }

    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    public record CredentialsRequest(
            @NotBlank String username,
            @NotBlank @Size(min = 12, max = 128) String password) {
    }

    public record UserResponse(UUID id, String username, List<String> roles) {
    }

    public record AuthResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            UserResponse user) {
    }
}
