package com.lvfast.streaming.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.MediaStreamingApplication;
import com.lvfast.streaming.support.TestKeyFiles;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression for the documented operator command. The runbook starts the application with
 * {@code --spring.main.web-application-type=none --app.ops.roles=grant:<username>}; that context has
 * no {@code HttpSecurity} bean, so the web security chains must not be created for a non-web
 * application or the command cannot start at all.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = MediaStreamingApplication.class,
        properties = {
            "app.ops.roles=grant:ops_cli_user",
            "app.catalog.import-enabled=false",
        })
@Import(OperatorGrantContextTest.OperatorAccountSeed.class)
class OperatorGrantContextTest {

    private static final TestKeyFiles.Pair KEYS = TestKeyFiles.generate("ops-cli-jwt");
    private static final TestKeyFiles.Pair MEDIA_KEYS = TestKeyFiles.generate("ops-cli-media-jwt");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.auth.private-key-location", KEYS::privateKeyLocation);
        registry.add("app.auth.public-key-location", KEYS::publicKeyLocation);
        registry.add("app.media.signing.private-key-location", MEDIA_KEYS::privateKeyLocation);
        registry.add("app.media.signing.public-key-location", MEDIA_KEYS::publicKeyLocation);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void grantsAdminFromTheOperatorCommandWithoutAWebServer() {
        assertThat(jdbc.queryForObject("""
                select count(*) from user_role
                where role='ADMIN' and user_id=(select id from app_user where username='ops_cli_user')
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_event where action='ROLE_GRANTED' and actor_type='OPERATOR'",
                Integer.class)).isGreaterThanOrEqualTo(1);
    }

    /**
     * Seeds the account the operator command grants. `SmartInitializingSingleton` runs at the end of
     * the context refresh, so it is always complete before any `ApplicationRunner`, including the
     * `AdminRoleCommand` under test.
     */
    @TestConfiguration
    static class OperatorAccountSeed {
        @Bean
        SmartInitializingSingleton createOperatorAccount(JdbcTemplate jdbc) {
            return () -> jdbc.update(
                    "insert into app_user(id, username, password_hash) values (?, ?, ?)",
                    UUID.randomUUID(), "ops_cli_user", "unused-test-hash");
        }
    }
}
