package com.lvfast.streaming.identity;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(ObservabilitySecurityTest.Config.class)
@TestPropertySource(properties = {"management.server.port=9091", "server.port=8080"})
class ObservabilitySecurityTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void metricsAreAvailableOnlyOnManagementListener() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(r -> { r.setLocalPort(9091); return r; }))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus").with(r -> { r.setLocalPort(8080); return r; }))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("X-Forwarded-Port", "9091")
                .with(r -> { r.setLocalPort(8080); return r; })).andExpect(status().isUnauthorized());
    }

    @Test void managementPortDoesNotBypassApiAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me").with(r -> { r.setLocalPort(9091); return r; }))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env").with(r -> { r.setLocalPort(9091); return r; }))
                .andExpect(status().isForbidden());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, Endpoints.class})
    static class Config {
        @Bean AuthRateLimiter limiter() { return mock(AuthRateLimiter.class); }
        @Bean JwtDecoder decoder() { return mock(JwtDecoder.class); }
        @Bean RoleService roleService() { return mock(RoleService.class); }
        @Bean com.lvfast.streaming.media.job.WorkerAccess workerAccess() {
            return mock(com.lvfast.streaming.media.job.WorkerAccess.class);
        }
    }

    @RestController static class Endpoints {
        @GetMapping("/actuator/prometheus") String metrics() { return "up 1"; }
        @GetMapping("/api/v1/me") String user() { return "private"; }
        @GetMapping("/actuator/env") String env() { return "private"; }
    }
}
