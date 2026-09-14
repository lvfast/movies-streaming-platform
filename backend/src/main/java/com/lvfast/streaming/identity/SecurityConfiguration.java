package com.lvfast.streaming.identity;

import com.lvfast.streaming.common.ProblemResponseWriter;
import com.lvfast.streaming.media.job.WorkerAccess;
import com.lvfast.streaming.media.job.WorkerCredentialFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * HTTP security chains. This configuration only applies to a servlet web application: the operator
 * command runs the same application with {@code --spring.main.web-application-type=none}, where no
 * {@code HttpSecurity} bean exists and no filter chain is needed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    AdminAuthorizationManager adminAuthorizationManager(RoleService roles) {
        return new AdminAuthorizationManager(roles);
    }

    @Bean
    WorkerCredentialFilter workerCredentialFilter(WorkerAccess worker) {
        return new WorkerCredentialFilter(worker.credential());
    }

    @Bean
    FilterRegistrationBean<WorkerCredentialFilter> workerCredentialFilterRegistration(
            WorkerCredentialFilter filter) {
        FilterRegistrationBean<WorkerCredentialFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(1)
    SecurityFilterChain internalWorkerSecurity(HttpSecurity http, WorkerCredentialFilter workerFilter)
            throws Exception {
        return http
                .securityMatcher("/internal/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .addFilterBefore(workerFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

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
    SecurityFilterChain apiSecurity(HttpSecurity http, AuthRateLimiter rateLimiter,
            AdminAuthorizationManager adminAuthorizationManager) throws Exception {
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
                        .requestMatchers("/api/v1/admin/**").access(adminAuthorizationManager)
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
