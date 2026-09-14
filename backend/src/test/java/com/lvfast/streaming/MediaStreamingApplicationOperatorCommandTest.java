package com.lvfast.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The documented operator command runs as a bounded CLI. Without an explicit exit, the RabbitMQ
 * listener threads that P3 added would keep the non-web application alive forever after the grant.
 */
class MediaStreamingApplicationOperatorCommandTest {

    @Test
    void recognizesTheDocumentedOperatorCommandOption() {
        assertThat(MediaStreamingApplication.operatorCommandRequested(new String[] {
                "--spring.main.web-application-type=none",
                "--app.catalog.import-enabled=false",
                "--app.ops.roles=grant:alice",
        })).isTrue();
    }

    @Test
    void aNormalServerStartupDoesNotRequestTheOperatorExit() {
        assertThat(MediaStreamingApplication.operatorCommandRequested(new String[0])).isFalse();
        assertThat(MediaStreamingApplication.operatorCommandRequested(new String[] {
                "--server.port=8080",
                "--spring.profiles.active=local",
        })).isFalse();
    }
}
