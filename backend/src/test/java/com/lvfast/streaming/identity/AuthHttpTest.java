package com.lvfast.streaming.identity;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvfast.streaming.common.ApiExceptionHandler;
import com.lvfast.streaming.common.RequestIdFilter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthHttpTest {

    private AuthService auth;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        auth = org.mockito.Mockito.mock(AuthService.class);
        RoleService roles = org.mockito.Mockito.mock(RoleService.class);
        org.mockito.Mockito.when(roles.roles(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of("USER"));
        AuthController controller = new AuthController(
                auth, roles, "__Host-refresh_token", true, Duration.ofDays(7));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void registrationMatchesTheOpenApiResponseShapeAndCookiePolicy() throws Exception {
        UserAccount user = UserAccount.register("demo", "hash", Instant.EPOCH);
        when(auth.register("demo", "LongEnough9X"))
                .thenReturn(new AuthSession("access", "refresh", 900, user));

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"demo","password":"LongEnough9X"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"))))
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.username").value("demo"))
                .andExpect(jsonPath("$.user.roles[0]").value("USER"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void invalidBodyUsesProblemDetailsExtensions() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-http-1")
                        .content("""
                                {"username":"demo","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("req-http-1"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }
}
