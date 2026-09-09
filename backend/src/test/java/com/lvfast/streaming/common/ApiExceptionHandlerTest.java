package com.lvfast.streaming.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.identity.IdentityValidationException;
import com.lvfast.streaming.identity.RefreshTokenReuseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void validationProblemContainsStableCodeAndRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req-123");

        ProblemDetail problem = handler.identityValidation(
                new IdentityValidationException("username is invalid"), request);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties())
                .containsEntry("code", "VALIDATION_FAILED")
                .containsEntry("requestId", "req-123");
    }

    @Test
    void reuseProblemDoesNotExposeTokenDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ProblemDetail problem = handler.refreshReuse(new RefreshTokenReuseException(), request);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(problem.getProperties()).containsEntry("code", "REFRESH_TOKEN_REUSE");
        assertThat(problem.getDetail()).doesNotContain("token value");
    }
}
