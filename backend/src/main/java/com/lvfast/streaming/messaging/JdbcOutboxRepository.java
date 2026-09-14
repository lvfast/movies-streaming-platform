package com.lvfast.streaming.messaging;

import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Transactional outbox for media command messages. The event row is appended in the same database
 * transaction that creates the durable job, so a committed upload never loses its processing
 * command during a broker outage. A publisher delivers pending rows later (later packet).
 */
@Repository
public class JdbcOutboxRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcOutboxRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(UUID jobId, String eventType) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("eventId", eventId.toString());
        payload.put("type", eventType);
        payload.put("jobId", jobId.toString());
        payload.put("occurredAt", Instant.now().toString());
        jdbc.update("""
                insert into outbox_event(event_id, event_type, job_id, payload)
                values (?, ?, ?, ?::jsonb)
                """, eventId, eventType, jobId, writeJson(payload));
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception serialization) {
            throw new IllegalStateException("Unable to serialize outbox payload", serialization);
        }
    }
}
