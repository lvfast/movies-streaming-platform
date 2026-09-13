package com.lvfast.streaming.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvfast.streaming.MediaStreamingApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = MediaStreamingApplication.class)
@org.springframework.test.context.ActiveProfiles("test")
@EnabledIfSystemProperty(named = "catalog.external-it", matches = "true")
@TestPropertySource(properties = {
        "spring.datasource.url=${catalog.jdbc-url:jdbc:postgresql://host.docker.internal:5432/media_streaming}",
        "spring.datasource.username=${catalog.jdbc-username:media_streaming}",
        "spring.datasource.password=${catalog.jdbc-password:local-only-change-me}",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=100ms",
        "app.auth.enabled=false"
})
class CatalogRedisFallbackExternalIntegrationTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void redisLossFallsBackToPostgresForCatalogReads() throws Exception {
        mvc.perform(get("/api/v1/catalog/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rails[0].items[0].slug").isString());

        mvc.perform(get("/api/v1/movies/starlight-archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Starlight Archive"));
    }
}
