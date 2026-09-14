package com.lvfast.transcoder.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lvfast.transcoder.config.TranscoderProperties;
import com.rabbitmq.client.Channel;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

class CommandConsumerTest {

    private static final UUID JOB_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void claimsExecutesThenAcknowledgesInOrder() throws Exception {
        BackendJobClient client = mock(BackendJobClient.class);
        JobExecutor executor = mock(JobExecutor.class);
        ClaimResult claim = claimed("attempt-1");
        when(client.claim(JOB_ID)).thenReturn(claim);
        Channel channel = mock(Channel.class);
        CommandConsumer consumer = new CommandConsumer(client, executor, properties(), Clock.systemUTC());

        consumer.onCommand(message(7L), channel);

        InOrder order = inOrder(client, executor, channel);
        order.verify(client).claim(JOB_ID);
        order.verify(executor).execute(any(), any(LeaseGuard.class));
        order.verify(channel).basicAck(7L, false);
    }

    @Test
    void acknowledgesTerminalSkipWithoutExecuting() throws Exception {
        BackendJobClient client = mock(BackendJobClient.class);
        JobExecutor executor = mock(JobExecutor.class);
        when(client.claim(JOB_ID)).thenReturn(new ClaimResult(
                JOB_ID.toString(), "SKIP", "TERMINAL", null, null, null, null, null, null, null, null, null));
        Channel channel = mock(Channel.class);
        CommandConsumer consumer = new CommandConsumer(client, executor, properties(), Clock.systemUTC());

        consumer.onCommand(message(9L), channel);

        verifyNoInteractions(executor);
        verify(channel).basicAck(9L, false);
    }

    @Test
    void requeuesWhenTheClaimIsNotAvailable() throws Exception {
        BackendJobClient client = mock(BackendJobClient.class);
        JobExecutor executor = mock(JobExecutor.class);
        when(client.claim(JOB_ID)).thenReturn(new ClaimResult(
                JOB_ID.toString(), "SKIP", "NOT_AVAILABLE", null, null, null, null, null, null, null, null, null));
        Channel channel = mock(Channel.class);
        CommandConsumer consumer = new CommandConsumer(client, executor, properties(), Clock.systemUTC());

        consumer.onCommand(message(11L), channel);

        verifyNoInteractions(executor);
        verify(channel).basicNack(11L, false, true);
    }

    @Test
    void consumesWithMaximumConcurrencyOne() throws Exception {
        Method method = CommandConsumer.class.getMethod("onCommand", Message.class, Channel.class);
        RabbitListener listener = method.getAnnotation(RabbitListener.class);
        assertThat(listener.concurrency()).isEqualTo("1");
        assertThat(listener.queues()).containsExactly("media.commands");
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuresPrefetchOneAndManualAck() throws Exception {
        java.util.Map<String, Object> document = new org.yaml.snakeyaml.Yaml()
                .load(Files.readString(Path.of("src", "main", "resources", "application.yml")));
        var spring = (java.util.Map<String, Object>) document.get("spring");
        var rabbitmq = (java.util.Map<String, Object>) spring.get("rabbitmq");
        var listener = (java.util.Map<String, Object>) rabbitmq.get("listener");
        var simple = (java.util.Map<String, Object>) listener.get("simple");
        assertThat(simple.get("prefetch")).isEqualTo(1);
        assertThat(simple.get("concurrency")).isEqualTo(1);
        assertThat(simple.get("acknowledge-mode")).isEqualTo("manual");
    }

    private ClaimResult claimed(String attemptId) {
        return new ClaimResult(
                JOB_ID.toString(), "CLAIMED", null, attemptId,
                Instant.now().plus(Duration.ofMinutes(2)).toString(),
                "TRANSCODE", "source/" + JOB_ID + "/original", "hls/" + JOB_ID + "/" + attemptId + "/",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, "h264-aac-1080p30");
    }

    private Message message(long deliveryTag) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(deliveryTag);
        String body = "{\"schemaVersion\":1,\"eventId\":\"" + UUID.randomUUID()
                + "\",\"type\":\"transcode.requested.v1\",\"jobId\":\"" + JOB_ID
                + "\",\"occurredAt\":\"2026-09-12T10:00:00Z\"}";
        return new Message(body.getBytes(StandardCharsets.UTF_8), messageProperties);
    }

    private TranscoderProperties properties() {
        var source = new TranscoderProperties.Role("bucket", null, "auto", "a", "s");
        var delivery = new TranscoderProperties.Role("bucket", null, "auto", "a", "s");
        return new TranscoderProperties(
                "http://localhost:8080", "worker-1", "cred", "ffmpeg", "ffprobe",
                Path.of("."), Duration.ofSeconds(30), Duration.ofMinutes(15),
                new TranscoderProperties.Storage(source, delivery));
    }
}
