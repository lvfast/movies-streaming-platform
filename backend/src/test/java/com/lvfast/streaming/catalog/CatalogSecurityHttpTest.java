package com.lvfast.streaming.catalog;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvfast.streaming.common.ApiExceptionHandler;
import com.lvfast.streaming.common.RequestIdFilter;
import com.lvfast.streaming.identity.AuthRateLimiter;
import com.lvfast.streaming.identity.SecurityConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig
@ContextConfiguration(classes = CatalogSecurityHttpTest.TestConfig.class)
class CatalogSecurityHttpTest {

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired CatalogService catalog;
    MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void productionSecurityChainPermitsEveryPublicCatalogRouteAndProtectsOtherApiRoutes() throws Exception {
        MovieDetails details = new MovieDetails(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "starlight-archive", "Starlight Archive", 2026, 2, "PG",
                "http://localhost:8081/media/artwork/poster-placeholder.svg",
                "http://localhost:8081/media/artwork/backdrop-placeholder.svg",
                List.of("Adventure"), "An archivist follows a signal.", true);
        when(catalog.home()).thenReturn(new CatalogHome(List.of()));
        when(catalog.movie("starlight-archive")).thenReturn(details);
        when(catalog.search("archive", 0, 1)).thenReturn(new MoviePage(List.of(), 0, 1, 0));

        mvc.perform(get("/api/v1/catalog/home")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/movies/starlight-archive")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/search").param("q", "archive").param("size", "1"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/watchlist").header("X-Request-Id", "secured-route"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value("secured-route"));
    }

    @Test
    void malformedPaginationUsesSharedProblemDetailsContract() throws Exception {
        assertInvalidParameter("page", "abc", "bad-page-text");
        assertInvalidParameter("size", "1.5", "bad-size-decimal");
        assertInvalidParameter("page", "999999999999999999999999", "bad-page-overflow");
    }

    private void assertInvalidParameter(String name, String value, String requestId) throws Exception {
        mvc.perform(get("/api/v1/search")
                        .param("q", "archive")
                        .param(name, value)
                        .header("X-Request-Id", requestId))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({CatalogController.class, SecurityConfiguration.class, ApiExceptionHandler.class})
    static class TestConfig {
        @Bean CatalogService catalogService() {
            return mock(CatalogService.class);
        }

        @Bean AuthRateLimiter authRateLimiter() {
            return mock(AuthRateLimiter.class);
        }

        @Bean JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean RequestIdFilter requestIdFilter() {
            return new RequestIdFilter();
        }
    }
}
