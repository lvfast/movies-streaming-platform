package com.lvfast.streaming.audit;

import java.util.Map;
import java.util.UUID;

/** Append-only record of role, editorial, publication and retry changes. */
public interface AuditService {

    void record(
            UUID actorId,
            String actorType,
            String action,
            String entityType,
            UUID entityId,
            String requestId,
            Map<String, Object> before,
            Map<String, Object> after);
}
