package com.lvfast.streaming.identity;

import com.lvfast.streaming.common.ProblemResponseWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain managementSecurity(HttpSecurity http,
            @Value("${management.server.port:-1}") int managementPort,
            @Value("${server.port:8080}") int applicationPort) throws Exception {
        return http
                .securityMatcher(request -> managementPort > 0 && managementPort != applicationPort
                        && request.getLocalPort() == managementPort)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true", matchIfMissing = true)
    SecurityFilterChain apiSecurity(HttpSecurity http, AuthRateLimiter rateLimiter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/catalog/**",
                                "/api/v1/movies/*",
                                "/api/v1/search",
                                "/actuator/health/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) ->
                                ProblemResponseWriter.write(
                                        response,
                                        request,
                                        401,
                                        "AUTHENTICATION_REQUIRED",
                                        "A valid bearer access token is required")))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(
                        (request, response, exception) -> ProblemResponseWriter.write(
                                response,
                                request,
                                403,
                                "ACCESS_DENIED",
                                "This account cannot access the requested resource")))
                .addFilterBefore(new AuthRateLimitFilter(rateLimiter), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.auth.enabled", havingValue = "false")
    SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
