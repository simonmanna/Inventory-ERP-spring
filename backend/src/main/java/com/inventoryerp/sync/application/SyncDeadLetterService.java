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

    /** Failure code of dead letters written before V593 (no failure code recorded): legacy / unknown. */
    public static final String LEGACY_CODE = "LEGACY_UNKNOWN";

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
                    "investigatingAt", r.get("investigatingAt"),
                    // ADR-S083 O0 corrections (additive): server receipt time, legacy class, supervisor recovery
                    "receivedAt", r.get("receivedAt"), "legacy", LEGACY_CODE.equals(r.get("failureCode")),
                    "recoverable", SyncPushService.QUARANTINE_CODES.contains(String.valueOf(r.get("failureCode"))),
                    "recoveredById", r.get("recoveredById"), "recoveredAt", r.get("recoveredAt"),
                    "recoveryGapAcknowledged", r.get("recoveryGapAcknowledged")));
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
        if (row.get("failureCode") == null || LEGACY_CODE.equals(row.get("failureCode"))) {
            throw ApiException.badRequest("This operation was dead-lettered before the applied-operation ledger existed;"
                    + " correct and resubmit it from the device");
        }
        if (!Boolean.TRUE.equals(row.get("retryable"))) {
            throw ApiException.badRequest("This failure is a business rejection (" + row.get("failureCode")
                    + "); correct and resubmit it from the device");
        }
        Map<String, Object> device = deviceOf(row);
        SyncPushService.Op op = storedOp(row);
        audit.record(AuditInput.of("SyncOpDeadLetter", id, "update", null, Obj.of("action", "retry", "note", note,
                "opType", row.get("opType"), "opId", row.get("opId"))));
        Map<String, Object> result = push.retry(pushDevice(row, device), op);
        Map<String, Object> after = db.first(SyncOpDeadLetter.class, "id = ?", id);
        return Obj.of("id", id, "status", after == null ? null : after.get("status"), "result", result);
    }

    private Map<String, Object> deviceOf(Map<String, Object> row) {
        return db.first(PosDevice.class, "id = ? AND \"organizationId\" = ?", row.get("deviceId"),
                TenantContext.organizationId());
    }

    private static SyncPushService.Device pushDevice(Map<String, Object> row, Map<String, Object> device) {
        return new SyncPushService.Device((String) row.get("deviceId"), TenantContext.organizationId(),
                device == null ? null : (String) device.get("branchId"));
    }

    /** The stored op, refused when a PIN or password was redacted from it (it cannot be replayed server-side). */
    private static SyncPushService.Op storedOp(Map<String, Object> row) {
        Object payload = PosSql.java(row.get("payload"));
        if (com.inventoryerp.shared.web.EntityJson.toJson(payload).contains("\"[redacted]\"")) {
            throw ApiException.badRequest("This operation carried a PIN or password that is never stored; correct and"
                    + " resubmit it from the device");
        }
        Object occurred = row.get("occurredAt");
        String occurredAt = occurred instanceof java.time.Instant i ? com.inventoryerp.kernel.web.JsonConfig.toJsIso(i)
                : occurred == null ? null : String.valueOf(occurred);
        return new SyncPushService.Op((String) row.get("opId"), ((Number) row.get("deviceSeq")).intValue(),
                (String) row.get("opType"), (String) row.get("actorUserId"), occurredAt, PosSql.map(payload));
    }

    /**
     * ADR-S083 S2 (O0 correction 2): supervisor recovery of an op a revoked or expired device delivered after its
     * cut-off. The device clock is never the evidence. The server checks its own sequence state instead:
     *
     * <ul>
     *   <li>the op's {@code deviceSeq} must be above the device's {@code lastAcceptedSeq} (an unseen op);</li>
     *   <li>quarantined ops are recovered in device order (no earlier one still held);</li>
     *   <li>every sequence number between {@code lastAcceptedSeq} and this op must have reached the server (applied or
     *   dead-lettered); a gap needs {@code acknowledgeGap} and is recorded;</li>
     *   <li>the op then runs once under its original opId, with the actor's current permissions re-checked.</li>
     * </ul>
     * The supervisor's note, the evidence and the outcome are audited.
     */
    public Map<String, Object> recover(String id, String noteIn, boolean acknowledgeGap) {
        String note = requireNote(noteIn);
        Map<String, Object> row = active(id);
        if (!SyncPushService.QUARANTINE_CODES.contains(String.valueOf(row.get("failureCode")))) {
            throw ApiException.badRequest("Only operations held after a device revocation or token expiry can be"
                    + " recovered; use retry, or correct and resubmit it from the device");
        }
        SyncPushService.Op op = storedOp(row);
        Map<String, Object> device = deviceOf(row);
        if (device == null) {
            throw ApiException.notFound("Device not found");
        }
        String orgId = TenantContext.organizationId();
        String deviceId = (String) row.get("deviceId");
        int seq = op.deviceSeq();
        int lastAccepted = device.get("lastAcceptedSeq") == null ? 0 : ((Number) device.get("lastAcceptedSeq")).intValue();
        if (seq <= lastAccepted) {
            throw ApiException.conflict("Operation sequence " + seq + " is not above the device's last accepted sequence "
                    + lastAccepted + ", so it is not an unseen operation from before the cut-off; correct and resubmit"
                    + " it if it is genuine");
        }
        Integer earlier = db.jdbc().sql("SELECT min(\"deviceSeq\") FROM \"SyncOpDeadLetter\" WHERE \"organizationId\" = ?"
                + " AND \"deviceId\" = ? AND \"failureCode\" IN ('DEVICE_REVOKED', 'DEVICE_TOKEN_EXPIRED')"
                + " AND status IN ('open', 'investigating') AND \"deviceSeq\" < ?").params(orgId, deviceId, seq)
                .query(Integer.class).optional().orElse(null);
        if (earlier != null) {
            throw ApiException.conflict("Recover operation sequence " + earlier + " first — held operations are"
                    + " recovered in device order");
        }
        List<Integer> seen = db.jdbc().sql("SELECT \"deviceSeq\" FROM \"SyncAppliedOp\" WHERE \"organizationId\" = ? AND"
                + " \"deviceId\" = ? AND \"deviceSeq\" > ? AND \"deviceSeq\" < ? UNION SELECT \"deviceSeq\" FROM"
                + " \"SyncOpDeadLetter\" WHERE \"organizationId\" = ? AND \"deviceId\" = ? AND \"deviceSeq\" > ? AND"
                + " \"deviceSeq\" < ?").params(orgId, deviceId, lastAccepted, seq, orgId, deviceId, lastAccepted, seq)
                .query(Integer.class).list();
        List<Integer> missing = new ArrayList<>();
        for (int s = lastAccepted + 1; s < seq && missing.size() < 50; s++) {
            if (!seen.contains(s)) {
                missing.add(s);
            }
        }
        if (!missing.isEmpty() && !acknowledgeGap) {
            throw ApiException.conflict("Sequence gap: device operation(s) " + missing + " never reached the server."
                    + " Confirm the gap (acknowledgeGap) with a note to recover this operation anyway");
        }
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("action", "recover");
        evidence.put("note", note);
        evidence.put("opId", row.get("opId"));
        evidence.put("opType", row.get("opType"));
        evidence.put("deviceSeq", seq);
        evidence.put("lastAcceptedSeq", lastAccepted);
        evidence.put("revokedAt", device.get("revokedAt"));
        evidence.put("revokedAtSeq", device.get("revokedAtSeq"));
        evidence.put("receivedAt", row.get("receivedAt"));
        evidence.put("occurredAtClaimedByDevice", row.get("occurredAt"));
        evidence.put("missingSeqs", missing);
        evidence.put("gapAcknowledged", !missing.isEmpty());
        audit.record(AuditInput.of("SyncOpDeadLetter", id, "update", null, evidence));
        Map<String, Object> result = push.recover(pushDevice(row, device), op);
        if (!"failed".equals(result.get("status"))) {
            db.jdbc().sql("UPDATE \"SyncOpDeadLetter\" SET status = 'resolved', \"resolvedById\" = ?, \"resolvedAt\" = "
                    + PosSql.NOW + ", \"resolutionNote\" = ?, \"recoveredById\" = ?, \"recoveredAt\" = " + PosSql.NOW
                    + ", \"recoveryGapAcknowledged\" = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(TenantContext.userId(), "Recovered by supervisor: " + note, TenantContext.userId(),
                            !missing.isEmpty(), id, orgId).update();
        }
        Map<String, Object> after = db.first(SyncOpDeadLetter.class, "id = ?", id);
        return Obj.of("id", id, "status", after == null ? null : after.get("status"), "result", result,
                "missingSeqs", missing);
    }
}
