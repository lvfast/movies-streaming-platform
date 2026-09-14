package com.lvfast.streaming.administration;

import com.lvfast.streaming.audit.AuditEventPage;
import com.lvfast.streaming.audit.AuditQuery;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only admin audit history. Filters are optional and pagination is stable by event id. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminAuditController {

    private final AuditQuery audit;

    public AdminAuditController(AuditQuery audit) {
        this.audit = audit;
    }

    @GetMapping("/audit")
    AuditEventPage list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new AdminValidationException(
                    "Page must be non-negative and size must be between 1 and 100");
        }
        return audit.list(action, entityType, entityId, page, size);
    }
}
