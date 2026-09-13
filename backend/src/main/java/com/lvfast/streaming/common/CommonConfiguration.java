package com.lvfast.streaming.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared infrastructure beans. The UTC {@link Clock} is available whether or not authentication is
 * enabled, because media signing and playback sessions must work in every supported configuration.
 */
@Configuration(proxyBeanMethods = false)
public class CommonConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
