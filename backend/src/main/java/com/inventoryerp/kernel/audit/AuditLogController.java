package com.inventoryerp.kernel.audit;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /audit-logs} (port of {@code audit-log.controller.ts}). */
@RestController
@RequestMapping("/audit-logs")
@RequirePermissions(Permissions.AUDIT_LOG__READ)
public class AuditLogController {

    private final AuditLogQueries queries;

    public AuditLogController(AuditLogQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) String entity) {
        return queries.list(page, pageSize, entity);
    }
}
