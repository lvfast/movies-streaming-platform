package com.lvfast.streaming.audit;

import java.util.Map;

/**
 * A single audit record as it leaves the API. Stored payloads are passed through a redactor before
 * this projection is built, so secret-like fields never reach a client.
 */
public record AuditEventView(
        long id,
        String actorId,
        String actorType,
        String action,
        String entityType,
        String entityId,
        String requestId,
        Map<String, Object> before,
        Map<String, Object> after,
        String createdAt) {
}
