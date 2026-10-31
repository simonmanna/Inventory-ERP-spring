package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Incoming inspection (ADR-PROCUREMENT-001 wave 6): quality check of a goods receipt line by line — accepted and
 * rejected quantity, result, defect notes and evidence files (ids from the file store). Completing an inspection with
 * rejected goods raises a quality claim against the supplier for the rejected value at the order price. An open
 * inspection holds the supplier bill when {@code receiving.inspectionRequired} applies (see {@link BillControls}).
 */
@Service
public class IncomingInspectionService {

    /** One line result. */
    public record LineResult(String lineId, BigDecimal acceptedQuantity, BigDecimal rejectedQuantity, String result,
            String defectNotes) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final AuditService audit;
    private final SupplierClaimService claims;
    private final TransactionTemplate tx;

    public IncomingInspectionService(JdbcClient jdbc, SequenceService seq, AuditService audit,
            SupplierClaimService claims, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.audit = audit;
        this.claims = claims;
        this.tx = new TransactionTemplate(txm);
    }

    /** Open an inspection for a posted receipt (idempotent: an existing live inspection is returned). */
    public Map<String, Object> open(String goodsReceiptId) {
        String orgId = org();
        String id = tx.execute(st -> {
            Map<String, Object> grn = SqlRows.one(jdbc.sql("SELECT g.id, g.status::text AS status, g.\"purchaseOrderId\","
                    + " COALESCE(g.\"partnerId\", o.\"partnerId\") AS \"partnerId\" FROM \"GoodsReceiptNote\" g LEFT JOIN"
                    + " \"PurchaseOrder\" o ON o.id = g.\"purchaseOrderId\" WHERE g.id = ? AND g.\"organizationId\" = ?"
                    + " AND g.\"deletedAt\" IS NULL FOR UPDATE OF g").params(goodsReceiptId, orgId));
            if (grn == null) {
                throw ApiException.notFound("Goods receipt not found");
            }
            if (!"posted".equals(grn.get("status"))) {
                throw ApiException.badRequest("Only a posted goods receipt can be inspected");
            }
            String existing = jdbc.sql("SELECT id FROM \"IncomingInspection\" WHERE \"goodsReceiptId\" = ? AND status <>"
                    + " 'cancelled'").param(goodsReceiptId).query(String.class).optional().orElse(null);
            if (existing != null) {
                return existing;
            }
            String newId = UUID.randomUUID().toString();
            int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
            String number = seq.next("inspection:" + year, "QC-" + year + "-", 5);
            jdbc.sql("INSERT INTO \"IncomingInspection\" (id, \"organizationId\", \"inspectionNumber\", \"goodsReceiptId\","
                            + " \"purchaseOrderId\", \"partnerId\", \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?,"
                            + " ?, ?, ?, ?, " + NOW + ", " + NOW + ")")
                    .params(newId, orgId, number, goodsReceiptId, grn.get("purchaseOrderId"), grn.get("partnerId"), user())
                    .update();
            jdbc.sql("INSERT INTO \"IncomingInspectionLine\" (id, \"organizationId\", \"inspectionId\","
                            + " \"goodsReceiptLineId\", \"productId\", description, \"receivedQuantity\", \"unitPrice\")"
                            + " SELECT gen_random_uuid()::text, ?, ?, l.id, l.\"productId\", l.description, l.quantity,"
                            + " COALESCE(pl.\"unitPrice\", l.\"unitCost\", 0) FROM \"GoodsReceiptLine\" l LEFT JOIN"
                            + " \"PurchaseOrderLine\" pl ON pl.id = l.\"purchaseOrderLineId\" WHERE l.\"goodsReceiptId\" = ?"
                            + " AND l.\"organizationId\" = ? ORDER BY l.\"lineNumber\"")
                    .params(orgId, newId, goodsReceiptId, orgId).update();
            audit.recordInTx(AuditInput.of("IncomingInspection", newId, "create", null, Obj.of("inspectionNumber",
                    number, "goodsReceiptId", goodsReceiptId)));
            return newId;
        });
        return get(id);
    }

    /** Save line results, notes and evidence files on a pending inspection. */
    public Map<String, Object> record(String id, List<LineResult> lines, String notes, List<String> fileIds) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> insp = lockPending(id);
            Map<String, Map<String, Object>> byId = lines(id);
            for (LineResult r : lines == null ? List.<LineResult>of() : lines) {
                Map<String, Object> l = byId.get(r.lineId());
                if (l == null) {
                    throw ApiException.badRequest("Line " + r.lineId() + " is not on this inspection");
                }
                BigDecimal received = Money.dec(l.get("receivedQuantity"));
                BigDecimal rejected = r.rejectedQuantity() == null ? BigDecimal.ZERO : r.rejectedQuantity();
                BigDecimal accepted = r.acceptedQuantity() == null ? received.subtract(rejected) : r.acceptedQuantity();
                if (rejected.signum() < 0 || accepted.signum() < 0) {
                    throw ApiException.badRequest("Quantities must not be negative");
                }
                if (accepted.add(rejected).compareTo(received) > 0) {
                    throw ApiException.badRequest("Accepted + rejected for \"" + l.get("description") + "\" cannot exceed"
                            + " the received " + JsNumbers.dec(received));
                }
                String result = r.result() != null ? r.result() : rejected.signum() == 0 ? "pass"
                        : accepted.signum() == 0 ? "fail" : "partial";
                jdbc.sql("UPDATE \"IncomingInspectionLine\" SET \"acceptedQuantity\" = ?, \"rejectedQuantity\" = ?, result = ?,"
                        + " \"defectNotes\" = ? WHERE id = ?").params(accepted, rejected, result, r.defectNotes(), r.lineId())
                        .update();
            }
            jdbc.sql("UPDATE \"IncomingInspection\" SET notes = COALESCE(?, notes), \"fileIds\" = COALESCE(?::jsonb,"
                    + " \"fileIds\"), version = version + 1, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(notes, fileIds == null ? null : EntityJson.toJson(fileIds), insp.get("id")).update();
        });
        return get(id);
    }

    /**
     * Complete: every line needs a result (unrecorded lines pass in full). Rejected goods raise a quality claim for
     * {@code rejected × order price}; the inspection ends {@code passed}, {@code partial} or {@code failed}.
     */
    public Map<String, Object> complete(String id) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> insp = lockPending(id);
            jdbc.sql("UPDATE \"IncomingInspectionLine\" SET \"acceptedQuantity\" = \"receivedQuantity\", result = 'pass'"
                    + " WHERE \"inspectionId\" = ? AND result IS NULL").param(id).update();
            BigDecimal rejectedValue = BigDecimal.ZERO;
            BigDecimal acceptedQty = BigDecimal.ZERO;
            List<String> defects = new ArrayList<>();
            for (Map<String, Object> l : lines(id).values()) {
                BigDecimal rej = Money.dec(l.get("rejectedQuantity"));
                acceptedQty = acceptedQty.add(Money.dec(l.get("acceptedQuantity")));
                if (rej.signum() > 0) {
                    rejectedValue = rejectedValue.add(rej.multiply(Money.dec(l.get("unitPrice"))));
                    defects.add(JsNumbers.dec(rej) + " × " + l.get("description")
                            + (l.get("defectNotes") == null ? "" : " (" + l.get("defectNotes") + ")"));
                }
            }
            String status = defects.isEmpty() ? "passed" : acceptedQty.signum() == 0 ? "failed" : "partial";
            String claimId = null;
            if (!defects.isEmpty() && insp.get("partnerId") != null) {
                Map<String, Object> claim = claims.create(new SupplierClaimService.CreateInput((String) insp.get("partnerId"),
                        "quality", Money.round(rejectedValue), "Rejected at inspection " + insp.get("inspectionNumber") + ": "
                                + String.join("; ", defects), (String) insp.get("purchaseOrderId"),
                        (String) insp.get("goodsReceiptId"), null));
                claimId = (String) claim.get("id");
            }
            jdbc.sql("UPDATE \"IncomingInspection\" SET status = ?, \"claimId\" = ?, \"completedBy\" = ?, \"completedAt\" = "
                    + NOW + ", version = version + 1, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(status, claimId, user(), id).update();
            audit.recordInTx(AuditInput.of("IncomingInspection", id, "update", Obj.of("status", "pending"),
                    Obj.of("status", status, "claimId", claimId)));
        });
        return get(id);
    }

    /** Cancel a pending inspection. */
    public Map<String, Object> cancel(String id) {
        tx.executeWithoutResult(st -> {
            lockPending(id);
            jdbc.sql("UPDATE \"IncomingInspection\" SET status = 'cancelled', \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .param(id).update();
        });
        return get(id);
    }

    /** Newest first; optional status / receipt / order filters. */
    public List<Map<String, Object>> list(String status, String goodsReceiptId, String purchaseOrderId) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE i.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (status != null && !status.isBlank()) {
            sql.append(" AND i.status = ?");
            params.add(status);
        }
        if (goodsReceiptId != null && !goodsReceiptId.isBlank()) {
            sql.append(" AND i.\"goodsReceiptId\" = ?");
            params.add(goodsReceiptId);
        }
        if (purchaseOrderId != null && !purchaseOrderId.isBlank()) {
            sql.append(" AND i.\"purchaseOrderId\" = ?");
            params.add(purchaseOrderId);
        }
        sql.append(" ORDER BY i.\"createdAt\" DESC LIMIT 200");
        return SqlRows.list(jdbc.sql(sql.toString()).params(params));
    }

    /** One inspection with its lines. */
    public Map<String, Object> get(String id) {
        Map<String, Object> i = SqlRows.one(jdbc.sql(SELECT + " WHERE i.id = ? AND i.\"organizationId\" = ?")
                .params(id, org()));
        if (i == null) {
            throw ApiException.notFound("Inspection not found");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : lines(id).values()) {
            for (String k : List.of("receivedQuantity", "acceptedQuantity", "rejectedQuantity", "unitPrice")) {
                l.put(k, l.get(k) == null ? null : JsNumbers.dec(Money.dec(l.get(k))));
            }
            lines.add(l);
        }
        i.put("lines", lines);
        return i;
    }

    /** True when the order has a receipt whose inspection is still pending. */
    boolean pendingForOrder(String purchaseOrderId) {
        return jdbc.sql("SELECT count(*) FROM \"IncomingInspection\" WHERE \"organizationId\" = ? AND \"purchaseOrderId\""
                + " = ? AND status = 'pending'").params(org(), purchaseOrderId).query(Long.class).single() > 0;
    }

    private static final String SELECT = "SELECT i.*, g.\"receiptNumber\", o.\"orderNumber\", p.name AS \"partnerName\","
            + " c.\"claimNumber\" FROM \"IncomingInspection\" i LEFT JOIN \"GoodsReceiptNote\" g ON g.id = i.\"goodsReceiptId\""
            + " LEFT JOIN \"PurchaseOrder\" o ON o.id = i.\"purchaseOrderId\" LEFT JOIN \"Partner\" p ON p.id = i.\"partnerId\""
            + " LEFT JOIN \"SupplierClaim\" c ON c.id = i.\"claimId\"";

    private Map<String, Object> lockPending(String id) {
        Map<String, Object> i = SqlRows.one(jdbc.sql("SELECT * FROM \"IncomingInspection\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (i == null) {
            throw ApiException.notFound("Inspection not found");
        }
        if (!"pending".equals(i.get("status"))) {
            throw ApiException.badRequest("This inspection is already " + i.get("status"));
        }
        return i;
    }

    private Map<String, Map<String, Object>> lines(String id) {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT * FROM \"IncomingInspectionLine\" WHERE"
                + " \"inspectionId\" = ? AND \"organizationId\" = ? ORDER BY description, id").params(id, org()))) {
            out.put((String) l.get("id"), l);
        }
        return out;
    }
}
