package com.lvfast.streaming.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AuthControllerTest {

    @Test
    void registerSetsTheRefreshTokenOnlyInAStrictHostCookie() {
        AuthService auth = org.mockito.Mockito.mock(AuthService.class);
        UserAccount user = UserAccount.register("demo", "hash", Instant.EPOCH);
        when(auth.register("demo", "LongEnough9X"))
                .thenReturn(new AuthSession("access", "refresh-secret", 900, user));
        AuthController controller = new AuthController(
                auth, "__Host-refresh_token", true, Duration.ofDays(7));

        ResponseEntity<AuthController.AuthResponse> response = controller.register(
                new AuthController.CredentialsRequest("demo", "LongEnough9X"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().accessToken()).isEqualTo("access");
        assertThat(response.getBody().toString()).doesNotContain("refresh-secret");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("__Host-refresh_token=refresh-secret")
                .contains("Path=/")
                .contains("Max-Age=604800")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }
}
