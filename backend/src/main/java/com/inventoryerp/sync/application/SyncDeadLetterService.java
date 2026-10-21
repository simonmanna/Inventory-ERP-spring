package com.inventoryerp.sync.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.sync.domain.PosDevice;
import com.inventoryerp.sync.domain.SyncOpDeadLetter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Review of offline ops the server refused (port of {@code sync-dead-letter.service.ts}). They stay {@code open}
 * until a manager records what happened; nothing expires and nothing is retried blindly.
 */
@Service
public class SyncDeadLetterService {

    private final PosDb db;
    private final AuditService audit;

    public SyncDeadLetterService(PosDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(String status) {
        String orgId = TenantContext.organizationId();
        List<Map<String, Object>> rows = "all".equals(status)
                ? db.list(SyncOpDeadLetter.class, "\"organizationId\" = ? ORDER BY \"createdAt\" DESC LIMIT 200", orgId)
                : db.list(SyncOpDeadLetter.class, "\"organizationId\" = ? AND status = ? ORDER BY \"createdAt\" DESC"
                        + " LIMIT 200", orgId, status);
        LinkedHashSet<Object> deviceIds = new LinkedHashSet<>();
        rows.forEach(r -> deviceIds.add(r.get("deviceId")));
        Map<Object, Object> nameById = new HashMap<>();
        if (!deviceIds.isEmpty()) {
            List<Object> params = new ArrayList<>(List.of(orgId));
            params.addAll(deviceIds);
            db.list(PosDevice.class, "\"organizationId\" = ? AND id IN (" + PosSql.marks(deviceIds.size()) + ")",
                    params.toArray()).forEach(d -> nameById.put(d.get("id"), d.get("name")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object name = nameById.get(r.get("deviceId"));
            out.add(Obj.of("id", r.get("id"), "opId", r.get("opId"), "deviceId", r.get("deviceId"),
                    "deviceName", name == null ? "unknown device" : name, "deviceSeq", r.get("deviceSeq"),
                    "opType", r.get("opType"), "actorUserId", r.get("actorUserId"), "occurredAt", r.get("occurredAt"),
                    "error", r.get("error"), "httpStatus", r.get("httpStatus"), "status", r.get("status"),
                    "payload", r.get("payload"), "resolutionNote", r.get("resolutionNote"), "resolvedAt", r.get("resolvedAt"),
                    "createdAt", r.get("createdAt")));
        }
        return out;
    }

    public Map<String, Object> counts() {
        String orgId = TenantContext.organizationId();
        return Obj.of("open", count(orgId, "open"), "resolved", count(orgId, "resolved"),
                "discarded", count(orgId, "discarded"));
    }

    private long count(String orgId, String status) {
        return db.count(SyncOpDeadLetter.class, "\"organizationId\" = ? AND status = ?", orgId, status);
    }

    public Map<String, Object> resolve(String id, String status, String note) {
        if (note == null || note.trim().isEmpty()) {
            throw ApiException.badRequest("A note is required — record what happened to this sale");
        }
        String orgId = TenantContext.organizationId();
        Map<String, Object> row = db.first(SyncOpDeadLetter.class, "id = ? AND \"organizationId\" = ?", id, orgId);
        if (row == null) {
            throw ApiException.notFound("Dead-lettered op not found");
        }
        if (!"open".equals(row.get("status"))) {
            throw ApiException.badRequest("Already " + row.get("status"));
        }
        Map<String, Object> updated = db.update(SyncOpDeadLetter.class, Obj.of("status", status,
                "resolutionNote", note.trim(), "resolvedById", TenantContext.userId(), "resolvedAt", PosDb.raw(PosSql.NOW)),
                "id = ? AND \"organizationId\" = ?", id, orgId);
        audit.record(AuditInput.of("SyncOpDeadLetter", id, "update", null, Obj.of("status", status, "note", note.trim(),
                "opType", row.get("opType"), "opId", row.get("opId"))));
        return Obj.of("id", updated.get("id"), "status", updated.get("status"), "resolvedAt", updated.get("resolvedAt"));
    }
}
