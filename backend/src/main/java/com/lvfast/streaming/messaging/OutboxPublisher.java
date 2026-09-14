package com.lvfast.streaming.messaging;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Delivers pending outbox commands to RabbitMQ. A row is marked published only after the broker
 * acknowledges the publisher confirm, so a committed upload is never reported as delivered while the
 * broker is unavailable. Nacked, failed and timed-out sends stay pending for the next sweep.
 */
@Service
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final int batchSize;
    private final Duration publishTimeout;

    public OutboxPublisher(
            JdbcTemplate jdbc,
            RabbitTemplate rabbit,
            @Value("${app.media.outbox.batch-size:100}") int batchSize,
            @Value("${app.media.outbox.publish-timeout-ms:5000}") long publishTimeoutMs) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.batchSize = batchSize;
        this.publishTimeout = Duration.ofMillis(publishTimeoutMs);
    }

    @Scheduled(fixedDelayString = "${app.media.outbox.poll-interval-ms:1000}",
            initialDelayString = "${app.media.outbox.poll-interval-ms:1000}")
    public int publishPending() {
        int published = 0;
        for (Pending row : loadPending(batchSize)) {
            if (publish(row)) {
                published++;
            }
        }
        return published;
    }

    boolean publish(Pending row) {
        CorrelationData correlation = new CorrelationData(row.eventId());
        try {
            rabbit.convertAndSend(
                    RabbitTopology.COMMAND_EXCHANGE,
                    row.eventType(),
                    row.payload(),
                    message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setContentType("application/json");
                        return message;
                    },
                    correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (confirm != null && confirm.isAck()) {
                markPublished(row.eventId());
                return true;
            }
            return false;
        } catch (Exception brokerUnavailable) {
            // Broker outage, nack or confirm timeout: keep the row pending for the next sweep.
            return false;
        }
    }

    private List<Pending> loadPending(int limit) {
        return jdbc.query("""
                select event_id::text as event_id, event_type, payload::text as payload
                from outbox_event where published_at is null
                order by created_at limit ?
                """, (rs, i) -> new Pending(
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getString("payload")),
                limit);
    }

    private void markPublished(String eventId) {
        jdbc.update("update outbox_event set published_at=now() where event_id=?::uuid and published_at is null",
                eventId);
    }

    record Pending(String eventId, String eventType, String payload) {
    }
}
