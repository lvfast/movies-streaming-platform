package com.lvfast.transcoder.jobs;

import com.lvfast.transcoder.config.RabbitConfiguration;
import com.lvfast.transcoder.config.TranscoderProperties;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes media commands one at a time. A command is acknowledged only after the backend durably
 * returns CLAIMED (and the job executes) or a terminal SKIP. A NOT_AVAILABLE skip or a claim failure
 * is nacked for redelivery, so no command is dropped while the job is still workable.
 */
@Component
public class CommandConsumer {

    private final BackendJobClient client;
    private final JobExecutor executor;
    private final TranscoderProperties properties;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public CommandConsumer(
            BackendJobClient client,
            JobExecutor executor,
            TranscoderProperties properties,
            Clock clock) {
        this.client = client;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    @RabbitListener(queues = RabbitConfiguration.COMMAND_QUEUE, concurrency = "1")
    public void onCommand(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            UUID jobId = parseJobId(body);
            ClaimResult claim = client.claim(jobId);
            if (claim.claimed()) {
                LeaseGuard lease = new LeaseGuard(client, properties, clock, claim);
                try {
                    lease.start();
                    executor.execute(claim, lease);
                } finally {
                    lease.close();
                }
                channel.basicAck(deliveryTag, false);
            } else if (claim.terminalSkip()) {
                channel.basicAck(deliveryTag, false);
            } else {
                channel.basicNack(deliveryTag, false, true);
            }
        } catch (Exception failure) {
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private UUID parseJobId(String body) {
        try {
            JsonNode root = json.readTree(body);
            return UUID.fromString(root.get("jobId").asText());
        } catch (Exception unreadable) {
            throw new IllegalArgumentException("Command message has no valid jobId", unreadable);
        }
    }
}
