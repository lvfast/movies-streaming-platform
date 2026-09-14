package com.lvfast.streaming.audit;

import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read-only audit history with redaction. Stored {@code before}/{@code after} payloads are parsed
 * and any secret-like key is dropped before the event is exposed, so a payload that accidentally
 * captured a token or credential can never be replayed to an administrator.
 */
@Repository
class JdbcAuditQueryRepository implements AuditQuery {

    private static final Pattern SECRET_LIKE = Pattern.compile(
            ".*(secret|token|password|passwd|credential|authorization|api[_-]?key|private[_-]?key|signing[_-]?key).*");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcAuditQueryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public AuditEventPage list(String action, String entityType, UUID entityId, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1=1");
        if (action != null && !action.isBlank()) {
            where.append(" and action=?");
            args.add(action);
        }
        if (entityType != null && !entityType.isBlank()) {
            where.append(" and entity_type=?");
            args.add(entityType);
        }
        if (entityId != null) {
            where.append(" and entity_id=?");
            args.add(entityId);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from audit_event" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<AuditEventView> items = jdbc.query("""
                select id, actor_id, actor_type, action, entity_type, entity_id, request_id,
                       before, after, created_at
                from audit_event
                """ + where + " order by id desc limit ? offset ?",
                row(), pageArgs.toArray());
        return new AuditEventPage(items, page, size, total == null ? 0 : total);
    }

    private RowMapper<AuditEventView> row() {
        return (rs, index) -> new AuditEventView(
                rs.getLong("id"),
                nullableUuid(rs, "actor_id"),
                rs.getString("actor_type"),
                rs.getString("action"),
                rs.getString("entity_type"),
                nullableUuid(rs, "entity_id"),
                rs.getString("request_id"),
                sanitize(rs.getString("before")),
                sanitize(rs.getString("after")),
                instant(rs, "created_at"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitize(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = json.readValue(payload, Object.class);
            Object redacted = redact(parsed);
            return redacted instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (Exception unreadable) {
            return Map.of();
        }
    }

    /**
     * Redacts secret-like keys at every depth. A stored payload may nest tokens or credentials in
     * objects and lists, so a top-level-only filter would still leak them.
     */
    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                String name = String.valueOf(key);
                if (!isSecretLike(name)) {
                    safe.put(name, redact(nested));
                }
            });
            return safe;
        }
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>(list.size());
            for (Object item : list) {
                safe.add(redact(item));
            }
            return safe;
        }
        return value;
    }

    private boolean isSecretLike(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("key") || SECRET_LIKE.matcher(normalized).matches();
    }

    private String nullableUuid(ResultSet rs, String column) throws SQLException {
        UUID value = rs.getObject(column, UUID.class);
        return value == null ? null : value.toString();
    }

    private String instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant().toString();
    }
}
