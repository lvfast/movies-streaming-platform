package com.lvfast.streaming.audit;

import java.util.UUID;

/** Read side of the append-only audit trail; any filter may be {@code null}. */
public interface AuditQuery {

    AuditEventPage list(String action, String entityType, UUID entityId, int page, int size);
}
