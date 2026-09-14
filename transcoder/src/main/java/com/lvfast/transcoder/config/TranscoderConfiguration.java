package com.lvfast.transcoder.config;

import com.lvfast.transcoder.storage.S3WorkerObjectStore;
import com.lvfast.transcoder.storage.WorkerObjectStore;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TranscoderConfiguration {

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    WorkerObjectStore workerObjectStore(TranscoderProperties properties) {
        return new S3WorkerObjectStore(properties.storage());
    }
}
