package com.lvfast.transcoder;

import com.lvfast.transcoder.config.TranscoderProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Independent RabbitMQ worker. It has no JDBC/JPA or database driver dependency. */
@SpringBootApplication
@EnableConfigurationProperties(TranscoderProperties.class)
public class TranscoderApplication {

    public static void main(String[] args) {
        SpringApplication.run(TranscoderApplication.class, args);
    }
}
