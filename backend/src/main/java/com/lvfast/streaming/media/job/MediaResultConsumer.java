package com.lvfast.streaming.media.job;

import com.lvfast.streaming.messaging.RabbitTopology;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes worker result events from the durable media results queue. The message is acknowledged
 * only after the backend durably records and applies it; an unprocessable message is rejected
 * without requeue so it cannot loop forever (bounded retry arrives in the reliability packet).
 */
@Component
public class MediaResultConsumer {

    private final MediaResultService results;

    public MediaResultConsumer(MediaResultService results) {
        this.results = results;
    }

    @RabbitListener(queues = RabbitTopology.RESULT_QUEUE, concurrency = "1")
    public void onResult(Message message, Channel channel) throws IOException {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            results.handle(body);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception unprocessable) {
            channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
        }
    }
}
