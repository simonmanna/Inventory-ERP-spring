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

    /** ADR-S083 S5 statuses. */
    public static final List<String> STATUSES = List.of("open", "investigating", "resolved", "retried", "discarded");

    private final PosDb db;
    private final AuditService audit;
    private SyncPushService push;

    public SyncDeadLetterService(PosDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setPush(SyncPushService push) {
        this.push = push;
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
                    "createdAt", r.get("createdAt"),
                    // ADR-S083 S5 (additive)
                    "failureCode", r.get("failureCode"), "retryable", r.get("retryable"), "attempts", r.get("attempts"),
                    "lastAttemptAt", r.get("lastAttemptAt"), "branchId", r.get("branchId"),
                    "resolvedByOpId", r.get("resolvedByOpId"), "investigationNote", r.get("investigationNote"),
                    "investigatingAt", r.get("investigatingAt")));
        }
        return out;
    }

    public Map<String, Object> counts() {
        String orgId = TenantContext.organizationId();
        return Obj.of("open", count(orgId, "open"), "resolved", count(orgId, "resolved"),
                "discarded", count(orgId, "discarded"),
                // ADR-S083 S5 (additive)
                "investigating", count(orgId, "investigating"), "retried", count(orgId, "retried"));
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
        if (!"open".equals(row.get("status")) && !"investigating".equals(row.get("status"))) {
            throw ApiException.badRequest("Already " + row.get("status"));
        }
        Map<String, Object> updated = db.update(SyncOpDeadLetter.class, Obj.of("status", status,
                "resolutionNote", note.trim(), "resolvedById", TenantContext.userId(), "resolvedAt", PosDb.raw(PosSql.NOW)),
                "id = ? AND \"organizationId\" = ?", id, orgId);
        audit.record(AuditInput.of("SyncOpDeadLetter", id, "update", null, Obj.of("status", status, "note", note.trim(),
                "opType", row.get("opType"), "opId", row.get("opId"))));
        return Obj.of("id", updated.get("id"), "status", updated.get("status"), "resolvedAt", updated.get("resolvedAt"));
    }

    private Map<String, Object> active(String id) {
        Map<String, Object> row = db.first(SyncOpDeadLetter.class, "id = ? AND \"organizationId\" = ?", id,
                TenantContext.organizationId());
        if (row == null) {
            throw ApiException.notFound("Dead-lettered op not found");
        }
        if (!"open".equals(row.get("status")) && !"investigating".equals(row.get("status"))) {
            throw ApiException.badRequest("Already " + row.get("status"));
        }
        return row;
    }

    private static String requireNote(String note) {
        if (note == null || note.trim().isEmpty()) {
            throw ApiException.badRequest("A note is required — record what happened to this sale");
        }
        return note.trim();
    }

    /** ADR-S083 S5: a supervisor takes the dead letter ({@code open → investigating}), with a note. Audited. */
    public Map<String, Object> investigate(String id, String noteIn) {
        String note = requireNote(noteIn);
        Map<String, Object> row = active(id);
        String orgId = TenantContext.organizationId();
        db.jdbc().sql("UPDATE \"SyncOpDeadLetter\" SET status = 'investigating', \"investigatingById\" = ?,"
                + " \"investigatingAt\" = " + PosSql.NOW + ", \"investigationNote\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(TenantContext.userId(), note, id, orgId).update();
        audit.record(AuditInput.of("SyncOpDeadLetter", id, "update", null, Obj.of("status", "investigating", "note", note,
                "opType", row.get("opType"), "opId", row.get("opId"))));
        return Obj.of("id", id, "status", "investigating");
    }

    /**
     * ADR-S083 S5: run a dead-lettered op again under its ORIGINAL opId. Only failures marked retryable (transient or
     * a failed dependency) qualify; a business rejection is corrected and resubmitted from the device as a new op with
     * {@code supersedesOpId}. An op whose stored payload had a PIN or password redacted cannot be replayed server-side.
     */
    public Map<String, Object> retry(String id, String noteIn) {
        String note = requireNote(noteIn);
        Map<String, Object> row = active(id);
        if (row.get("failureCode") == null) {
            throw ApiException.badRequest("This operation was dead-lettered before the applied-operation ledger existed;"
                    + " correct and resubmit it from the device");
        }
        if (!Boolean.TRUE.equals(row.get("retryable"))) {
            throw ApiException.badRequest("This failure is a business rejection (" + row.get("failureCode")
                    + "); correct and resubmit it from the device");
        }
        Object payload = PosSql.java(row.get("payload"));
        if (com.inventoryerp.shared.web.EntityJson.toJson(payload).contains("\"[redacted]\"")) {
            throw ApiException.badRequest("This operation carried a PIN or password that is never stored; correct and"
                    + " resubmit it from the device");
        }
        String orgId = TenantContext.organizationId();
        Map<String, Object> device = db.first(PosDevice.class, "id = ? AND \"organizationId\" = ?", row.get("deviceId"),
                orgId);
        SyncPushService.Device d = new SyncPushService.Device((String) row.get("deviceId"), orgId,
                device == null ? null : (String) device.get("branchId"));
        Object occurred = row.get("occurredAt");
        String occurredAt = occurred instanceof java.time.Instant i ? com.inventoryerp.kernel.web.JsonConfig.toJsIso(i)
                : occurred == null ? null : String.valueOf(occurred);
        SyncPushService.Op op = new SyncPushService.Op((String) row.get("opId"), ((Number) row.get("deviceSeq")).intValue(),
                (String) row.get("opType"), (String) row.get("actorUserId"), occurredAt, PosSql.map(payload));
        audit.record(AuditInput.of("SyncOpDeadLetter", id, "update", null, Obj.of("action", "retry", "note", note,
                "opType", row.get("opType"), "opId", row.get("opId"))));
        Map<String, Object> result = push.retry(d, op);
        Map<String, Object> after = db.first(SyncOpDeadLetter.class, "id = ?", id);
        return Obj.of("id", id, "status", after == null ? null : after.get("status"), "result", result);
    }
}
