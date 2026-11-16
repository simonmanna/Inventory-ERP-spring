package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase A A3 (plan §9): the quantity history of a delivery line ({@code FleetLoadLineQuantityEvent}, immutable). The
 * owning Fleet service writes a row in the same transaction as each change; {@link #events} reads them, and lines
 * from before A3 get a best-effort reconstruction (marked {@code reconstructed}).
 */
@Component
public class LoadLineHistory {

    private final FleetDb db;

    public LoadLineHistory(FleetDb db) {
        this.db = db;
    }

    /** One change of a line. {@code prev} / {@code next} in the line's unit; the delta is derived. */
    void record(String loadId, String lineId, String kind, String bucket, BigDecimal prev, BigDecimal next,
            String uomId, String reason, List<String> evidence, String refType, String refId) {
        BigDecimal delta = next == null ? null : next.subtract(prev == null ? BigDecimal.ZERO : prev);
        db.jdbc().sql("INSERT INTO \"FleetLoadLineQuantityEvent\" (id, \"organizationId\", \"loadId\", \"loadLineId\","
                + " kind, bucket, \"previousQty\", \"newQty\", \"deltaQty\", \"uomId\", reason, \"userId\", at,"
                + " \"evidenceFileIds\", \"refType\", \"refId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + FleetDb.NOW + ", ?::text[], ?, ?)").params(UUID.randomUUID().toString(), FleetDb.org(), loadId, lineId,
                        kind, bucket, prev, next, delta, uomId, reason, TenantContext.userId(),
                        (evidence == null ? List.<String>of() : evidence).toArray(String[]::new), refType, refId)
                .update();
    }

    /** {@link #record} for a load-line row ({@code id}, {@code loadId}, {@code uomId}). */
    void record(Map<String, Object> line, String kind, String bucket, BigDecimal prev, BigDecimal next, String reason,
            List<String> evidence, String refType, String refId) {
        record((String) line.get("loadId"), (String) line.get("id"), kind, bucket, prev, next,
                (String) line.get("uomId"), reason, evidence, refType, refId);
    }

    /** Whether the line has recorded history (lines from before A3 have none). */
    boolean hasEvents(String lineId) {
        return db.jdbc().sql("SELECT count(*) FROM \"FleetLoadLineQuantityEvent\" WHERE \"loadLineId\" = ? AND"
                + " \"organizationId\" = ?").params(lineId, FleetDb.org()).query(Long.class).single() > 0;
    }

    /**
     * The line's history, oldest first: recorded events, or (before A3) a reconstruction from the line, its POD and its
     * claims, each marked {@code reconstructed: true}.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(String lineId) {
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT e.id, e.kind, e.bucket, e.\"previousQty\","
                + " e.\"newQty\", e.\"deltaQty\", e.\"uomId\", e.reason, e.\"userId\", COALESCE(NULLIF(trim(concat_ws(' ',"
                + " u.\"firstName\", u.\"lastName\")), ''), u.email) AS \"userName\", e.at, e.\"evidenceFileIds\","
                + " e.\"refType\", e.\"refId\", false AS reconstructed FROM \"FleetLoadLineQuantityEvent\" e LEFT JOIN"
                + " \"User\" u ON u.id = e.\"userId\" WHERE e.\"loadLineId\" = ? AND e.\"organizationId\" = ? ORDER BY"
                + " e.at, e.id").params(lineId, FleetDb.org()));
        return rows.isEmpty() ? reconstructed(lineId) : rows;
    }

    private List<Map<String, Object>> reconstructed(String lineId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> l = SqlRows.one(db.jdbc().sql("SELECT ll.*, t.\"dispatchedAt\", t.\"deliveredAt\","
                + " t.\"returnedAt\", t.status AS \"tripStatus\", p.\"recordedBy\" FROM \"FleetLoadLine\" ll JOIN \"FleetLoad\" t"
                + " ON t.id = ll.\"loadId\" LEFT JOIN \"FleetProofOfDelivery\" p ON p.\"loadId\" = t.id WHERE ll.id = ? AND"
                + " ll.\"organizationId\" = ?").params(lineId, FleetDb.org()));
        if (l == null) {
            return out;
        }
        if (l.get("dispatchedAt") != null) {
            out.add(row("dispatched", "dispatched", null, l.get("dispatchedQty"), l.get("dispatchedAt"), null, null));
        }
        if (l.get("deliveredAt") != null) {
            out.add(row("pod_recorded", "delivered", null, l.get("deliveredQty"), l.get("deliveredAt"),
                    (String) l.get("recordedBy"), null));
            if (l.get("damagedQty") != null && SqlRows.dec(l, "damagedQty").signum() > 0) {
                out.add(row("damaged_recorded", "damaged", null, l.get("damagedQty"), l.get("deliveredAt"),
                        (String) l.get("recordedBy"), null));
            }
        }
        if (l.get("returnedQty") != null && SqlRows.dec(l, "returnedQty").signum() > 0) {
            Object at = l.get("returnedAt") != null ? l.get("returnedAt") : l.get("deliveredAt");
            out.add(row("returned", "returned", null, l.get("returnedQty"), at, (String) l.get("recordedBy"), null));
        }
        for (Map<String, Object> c : SqlRows.list(db.jdbc().sql("SELECT id, code, kind, status, \"differenceQty\","
                + " \"raisedBy\", \"createdAt\", \"decidedBy\", \"decidedAt\", \"decisionNotes\", \"withdrawnAt\" FROM"
                + " \"FleetDeliveryDiscrepancy\" WHERE \"loadLineId\" = ? AND \"organizationId\" = ? ORDER BY \"createdAt\"")
                .params(lineId, FleetDb.org()))) {
            Map<String, Object> opened = row("claim_opened", bucketOf((String) c.get("kind")), null, c.get("differenceQty"),
                    c.get("createdAt"), (String) c.get("raisedBy"), (String) c.get("id"));
            opened.put("reason", c.get("kind") + " " + c.get("code"));
            out.add(opened);
            if (c.get("decidedAt") != null) {
                String decision = LoadLineQuantities.Claim.decision((String) c.get("status"), c.get("withdrawnAt") != null);
                Map<String, Object> d = row("claim_" + decision, bucketOf((String) c.get("kind")), null,
                        c.get("differenceQty"), c.get("decidedAt"), (String) c.get("decidedBy"), (String) c.get("id"));
                d.put("reason", c.get("decisionNotes"));
                out.add(d);
            }
        }
        out.sort((a, b) -> String.valueOf(a.get("at")).compareTo(String.valueOf(b.get("at"))));
        return out;
    }

    private static Map<String, Object> row(String kind, String bucket, Object prev, Object next, Object at, String user,
            String refId) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", null);
        m.put("kind", kind);
        m.put("bucket", bucket);
        m.put("previousQty", prev);
        m.put("newQty", next);
        m.put("deltaQty", next);
        m.put("uomId", null);
        m.put("reason", null);
        m.put("userId", user);
        m.put("userName", null);
        m.put("at", at);
        m.put("evidenceFileIds", List.of());
        m.put("refType", refId == null ? null : "discrepancy");
        m.put("refId", refId);
        m.put("reconstructed", true);
        return m;
    }

    /** Bucket a claim kind's quantity sits in while open. */
    static String bucketOf(String kind) {
        return switch (kind == null ? "" : kind) {
            case "returned" -> "returned";
            case "damaged" -> "damaged";
            case "excess" -> "excess";
            default -> "pending_disputed";
        };
    }
}
