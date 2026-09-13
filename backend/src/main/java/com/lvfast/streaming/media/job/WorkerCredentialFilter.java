package com.lvfast.streaming.media.job;

import com.lvfast.streaming.common.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Machine authentication for the internal worker endpoints. The worker presents a configured shared
 * secret in the Authorization header; a human login JWT is never accepted here. Requests with a
 * missing or mismatched credential are rejected with 401 before reaching a controller.
 */
public class WorkerCredentialFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public WorkerCredentialFilter(String credential) {
        this.expected = credential == null ? new byte[0] : credential.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim()
                : "";
        if (expected.length == 0 || !constantTimeEquals(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            ProblemResponseWriter.write(response, request, 401, "WORKER_AUTHENTICATION_REQUIRED",
                    "A valid machine credential is required for internal worker endpoints");
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(
                "worker", null, AuthorityUtils.createAuthorityList("ROLE_WORKER"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        try {
            return MessageDigest.isEqual(left, right);
        } catch (RuntimeException unavailable) {
            return java.util.Arrays.equals(left, right);
        }
    }
}
