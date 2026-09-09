package com.lvfast.streaming.identity;

import com.lvfast.streaming.common.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_ACTIONS = Set.of("register", "login", "refresh");
    private final AuthRateLimiter limiter;

    public AuthRateLimitFilter(AuthRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String prefix = "/api/v1/auth/";
        if ("POST".equals(request.getMethod()) && request.getRequestURI().startsWith(prefix)) {
            String action = request.getRequestURI().substring(prefix.length());
            if (LIMITED_ACTIONS.contains(action)) {
                try {
                    limiter.check(action, request.getRemoteAddr());
                } catch (RateLimitExceededException limited) {
                    ProblemResponseWriter.write(
                            response,
                            request,
                            429,
                            "RATE_LIMITED",
                            "Too many authentication attempts");
                    return;
                } catch (AuthRateLimitUnavailableException unavailable) {
                    ProblemResponseWriter.write(
                            response,
                            request,
                            503,
                            "AUTH_RATE_LIMIT_UNAVAILABLE",
                            "Authentication is temporarily unavailable");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
