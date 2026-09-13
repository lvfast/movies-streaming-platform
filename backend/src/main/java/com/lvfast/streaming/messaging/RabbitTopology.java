package com.lvfast.streaming.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the durable media command and result topology. Declarations are idempotent, so both the
 * backend and the transcoder can declare the same structure. Commands fan out by event type; results
 * return on a single durable queue consumed by the backend.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitTopology {

    public static final String COMMAND_EXCHANGE = "media.commands";
    public static final String COMMAND_QUEUE = "media.commands";
    public static final String RESULT_EXCHANGE = "media.results";
    public static final String RESULT_QUEUE = "media.results";

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

    @Bean
    Queue resultQueue() {
        return QueueBuilder.durable(RESULT_QUEUE).build();
    }

    @Bean
    Binding progressResultBinding(Queue resultQueue, DirectExchange resultExchange) {
        return BindingBuilder.bind(resultQueue).to(resultExchange).with("media.progress.v1");
    }

    @Bean
    Binding completedResultBinding(Queue resultQueue, DirectExchange resultExchange) {
        return BindingBuilder.bind(resultQueue).to(resultExchange).with("media.completed.v1");
    }

    @Bean
    Binding failedResultBinding(Queue resultQueue, DirectExchange resultExchange) {
        return BindingBuilder.bind(resultQueue).to(resultExchange).with("media.failed.v1");
    }
}
