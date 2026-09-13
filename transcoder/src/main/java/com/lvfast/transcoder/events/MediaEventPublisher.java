package com.lvfast.transcoder.events;

import com.lvfast.transcoder.jobs.ClaimResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Publishes worker result events to the durable media results exchange with publisher confirms. */
@Component
public class MediaEventPublisher {

    public static final String RESULT_EXCHANGE = "media.results";

    private final RabbitTemplate rabbit;
    private final ObjectMapper json = new ObjectMapper();

    public MediaEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public void progress(ClaimResult claim, long sequence, String stage, int percent) {
        publish(claim, sequence, "media.progress.v1", Map.of("stage", stage, "percent", percent));
    }

    public void completed(ClaimResult claim, long sequence, String artifactKey) {
        publish(claim, sequence, "media.completed.v1", Map.of("artifactKey", artifactKey));
    }

    public void failed(ClaimResult claim, long sequence, String code, String summary) {
        publish(claim, sequence, "media.failed.v1", Map.of("code", code, "summary", summary));
    }

    private void publish(ClaimResult claim, long sequence, String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", 1);
        event.put("eventId", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("jobId", claim.jobId());
        event.put("attemptId", claim.attemptId());
        event.put("sequence", sequence);
        event.put("occurredAt", Instant.now().toString());
        event.put("payload", payload);
        String body;
        try {
            body = json.writeValueAsString(event);
        } catch (RuntimeException serialization) {
            throw new IllegalStateException("Unable to serialize result event", serialization);
        }
        CorrelationData correlation = new CorrelationData(claim.jobId());
        rabbit.convertAndSend(RESULT_EXCHANGE, type, body, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException("Result event was not confirmed by the broker");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while confirming result event", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to confirm result event", failure);
        }
    }
}
