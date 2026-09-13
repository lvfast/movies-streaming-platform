package com.lvfast.transcoder.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the durable media command queue and result exchange the worker needs. Names match the
 * backend topology so both processes share the same durable resources (declarations are idempotent).
 */
@Configuration(proxyBeanMethods = false)
public class RabbitConfiguration {

    public static final String COMMAND_EXCHANGE = "media.commands";
    public static final String COMMAND_QUEUE = "media.commands";
    public static final String RESULT_EXCHANGE = "media.results";

    @Bean
    DirectExchange commandExchange() {
        return new DirectExchange(COMMAND_EXCHANGE, true, false);
    }

    @Bean
    Queue commandQueue() {
        return QueueBuilder.durable(COMMAND_QUEUE).build();
    }

    @Bean
    Binding transcodeCommandBinding(Queue commandQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(commandQueue).to(commandExchange).with("transcode.requested.v1");
    }

    @Bean
    Binding artworkCommandBinding(Queue commandQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(commandQueue).to(commandExchange).with("artwork.requested.v1");
    }

    @Bean
    DirectExchange resultExchange() {
        return new DirectExchange(RESULT_EXCHANGE, true, false);
    }
}
