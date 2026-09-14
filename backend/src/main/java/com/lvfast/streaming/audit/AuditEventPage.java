package com.lvfast.streaming.audit;

import java.util.List;

/** Zero-based paged audit history. */
public record AuditEventPage(List<AuditEventView> items, int page, int size, long total) {
}
