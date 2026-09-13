package com.lvfast.streaming.audit;

import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuditRepository implements AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcAuditRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void record(
            UUID actorId,
            String actorType,
            String action,
            String entityType,
            UUID entityId,
            String requestId,
            Map<String, Object> before,
            Map<String, Object> after) {
        jdbc.update("""
                insert into audit_event(actor_id, actor_type, action, entity_type, entity_id,
                                        request_id, before, after)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """,
                actorId,
                actorType,
                action,
                entityType,
                entityId,
                requestId,
                toJson(before),
                toJson(after));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception serialization) {
            throw new IllegalStateException("Unable to serialize audit payload", serialization);
        }
    }
}
