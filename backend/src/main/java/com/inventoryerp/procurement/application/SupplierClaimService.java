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
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
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
 * Supplier claims (ADR-PROCUREMENT-001 wave 5): shortages, damage, quality rejects, price differences and late delivery
 * raised against a supplier, optionally tied to an order, receipt or bill. Lifecycle {@code open → submitted →
 * accepted / rejected}; an accepted claim is settled by a draft outbound debit note for the agreed amount, which then
 * posts through the normal debit-note flow (Dr AP / Cr expense). {@code open} claims can be cancelled.
 */
@Service
public class SupplierClaimService {

    /** Raise body. */
    public record CreateInput(String partnerId, String type, BigDecimal amount, String description,
            String purchaseOrderId, String goodsReceiptId, String vendorBillId) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final AuditService audit;
    private final EventBus events;
    private final DebitNotesService debitNotes;
    private final TransactionTemplate tx;

    public SupplierClaimService(JdbcClient jdbc, SequenceService seq, AuditService audit, EventBus events,
            DebitNotesService debitNotes, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.audit = audit;
        this.events = events;
        this.debitNotes = debitNotes;
        this.tx = new TransactionTemplate(txm);
    }

    /** Raise a claim. Linked documents must belong to the same supplier. */
    public Map<String, Object> create(CreateInput in) {
        String orgId = org();
        if (in.amount() == null || in.amount().signum() < 0) {
            throw ApiException.badRequest("Claim amount must not be negative");
        }
        if (jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(in.partnerId(), orgId).query(Long.class).single() == 0) {
            throw ApiException.notFound("Supplier not found");
        }
        assertLinked("PurchaseOrder", in.purchaseOrderId(), in.partnerId(), "Purchase order");
        if (in.goodsReceiptId() != null && !in.goodsReceiptId().isBlank()
                && jdbc.sql("SELECT count(*) FROM \"GoodsReceiptNote\" g LEFT JOIN \"PurchaseOrder\" o ON o.id ="
                        + " g.\"purchaseOrderId\" WHERE g.id = ? AND g.\"organizationId\" = ? AND COALESCE(g.\"partnerId\","
                        + " o.\"partnerId\") = ?").params(in.goodsReceiptId(), orgId, in.partnerId()).query(Long.class)
                        .single() == 0) {
            throw ApiException.notFound("Goods receipt not found for this supplier");
        }
        if (in.vendorBillId() != null && !in.vendorBillId().isBlank()
                && jdbc.sql("SELECT count(*) FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? AND \"documentType\""
                        + " = 'vendor_bill' AND \"partnerId\" = ?").params(in.vendorBillId(), orgId, in.partnerId())
                        .query(Long.class).single() == 0) {
            throw ApiException.notFound("Supplier bill not found for this supplier");
        }
        String id = UUID.randomUUID().toString();
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        tx.executeWithoutResult(st -> {
            String number = seq.next("supplier_claim:" + year, "CLM-" + year + "-", 5);
            jdbc.sql("INSERT INTO \"SupplierClaim\" (id, \"organizationId\", \"claimNumber\", \"partnerId\","
                            + " \"purchaseOrderId\", \"goodsReceiptId\", \"vendorBillId\", type, amount, description,"
                            + " \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW
                            + ", " + NOW + ")")
                    .params(id, orgId, number, in.partnerId(), blank(in.purchaseOrderId()), blank(in.goodsReceiptId()),
                            blank(in.vendorBillId()), in.type(), in.amount(), in.description().trim(), user())
                    .update();
            audit.recordInTx(AuditInput.of("SupplierClaim", id, "create", null, Obj.of("claimNumber", number, "type",
                    in.type(), "amount", JsNumbers.dec(in.amount()))));
        });
        events.publish("supplier_claim.raised", Obj.of("organizationId", orgId, "claimId", id, "partnerId",
                in.partnerId(), "amount", JsNumbers.dec(in.amount())));
        return get(id);
    }

    /** Send the claim to the supplier. */
    public Map<String, Object> submit(String id) {
        return transition(id, List.of("open"), "submitted", ", \"submittedAt\" = " + NOW, null, null);
    }

    /** Supplier accepted (optionally for a different amount). */
    public Map<String, Object> accept(String id, BigDecimal agreedAmount, String response) {
        if (agreedAmount != null && agreedAmount.signum() < 0) {
            throw ApiException.badRequest("Agreed amount must not be negative");
        }
        return transition(id, List.of("open", "submitted"), "accepted", ", \"decidedAt\" = " + NOW
                + ", amount = COALESCE(?, amount), \"supplierResponse\" = ?", agreedAmount, response);
    }

    /** Supplier rejected the claim. */
    public Map<String, Object> reject(String id, String response) {
        if (response == null || response.isBlank()) {
            throw ApiException.badRequest("Record the supplier's reason for rejecting the claim");
        }
        return transition(id, List.of("open", "submitted"), "rejected", ", \"decidedAt\" = " + NOW
                + ", amount = COALESCE(?, amount), \"supplierResponse\" = ?", null, response.trim());
    }

    /** Withdraw an open claim. */
    public Map<String, Object> cancel(String id) {
        return transition(id, List.of("open"), "cancelled", "", null, null);
    }

    /**
     * Settle an accepted claim with a draft outbound debit note for the claim amount (linked to the claimed bill when
     * there is one). Idempotent: a settled claim returns as is.
     */
    public Map<String, Object> settle(String id) {
        String orgId = org();
        tx.executeWithoutResult(st -> {
            Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"SupplierClaim\" WHERE id = ? AND"
                    + " \"organizationId\" = ? FOR UPDATE").params(id, orgId));
            if (c == null) {
                throw ApiException.notFound("Claim not found");
            }
            if ("settled".equals(c.get("status"))) {
                return;
            }
            if (!"accepted".equals(c.get("status"))) {
                throw ApiException.badRequest("Only an accepted claim can be settled (status: " + c.get("status") + ")");
            }
            BigDecimal amount = Money.dec(c.get("amount"));
            if (amount.signum() <= 0) {
                throw ApiException.badRequest("Nothing to settle — the agreed amount is 0");
            }
            String reason = switch (String.valueOf(c.get("type"))) {
                case "price" -> "price_adjustment";
                case "shortage" -> "overcharge";
                default -> "other";
            };
            String currency = c.get("purchaseOrderId") == null ? null : jdbc.sql("SELECT \"currencyCode\" FROM"
                    + " \"PurchaseOrder\" WHERE id = ?").param(c.get("purchaseOrderId")).query(String.class).optional()
                    .orElse(null);
            Map<String, Object> note = debitNotes.create(new DebitNotesService.CreateInput("outbound",
                    (String) c.get("partnerId"), (String) c.get("vendorBillId"), reason, "Claim " + c.get("claimNumber"),
                    null, currency, null, (String) c.get("description"), null,
                    List.of(new DebitNotesService.LineInput(null, "Claim " + c.get("claimNumber") + ": "
                            + c.get("description"), BigDecimal.ONE, amount, null, null))));
            jdbc.sql("UPDATE \"SupplierClaim\" SET status = 'settled', \"settledAmount\" = ?, \"debitNoteId\" = ?,"
                    + " \"settledAt\" = " + NOW + ", version = version + 1, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(amount, note.get("id"), id).update();
            audit.recordInTx(AuditInput.of("SupplierClaim", id, "update", Obj.of("status", "accepted"),
                    Obj.of("status", "settled", "debitNoteId", note.get("id"))));
            events.publish("supplier_claim.settled", Obj.of("organizationId", orgId, "claimId", id, "debitNoteId",
                    note.get("id")));
        });
        return get(id);
    }

    /** {@code {data, meta}} newest first. */
    public PageResult<Map<String, Object>> list(String status, String partnerId, String purchaseOrderId, int page,
            int limit) {
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (status != null && !status.isBlank()) {
            where.append(" AND c.status = ?");
            params.add(status);
        }
        if (partnerId != null && !partnerId.isBlank()) {
            where.append(" AND c.\"partnerId\" = ?");
            params.add(partnerId);
        }
        if (purchaseOrderId != null && !purchaseOrderId.isBlank()) {
            where.append(" AND c.\"purchaseOrderId\" = ?");
            params.add(purchaseOrderId);
        }
        long total = jdbc.sql("SELECT count(*) FROM \"SupplierClaim\" c" + where).params(params).query(Long.class)
                .single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(limit);
        pp.add((long) (page - 1) * limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(SELECT + where + " ORDER BY c.\"createdAt\" DESC, c.id LIMIT ?"
                + " OFFSET ?").params(pp))) {
            rows.add(wire(r));
        }
        return PageResult.of(rows, page, limit, total);
    }

    /** One claim with supplier, order and debit-note numbers. */
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql(SELECT + " WHERE c.id = ? AND c.\"organizationId\" = ?")
                .params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Claim not found");
        }
        return wire(r);
    }

    private static final String SELECT = "SELECT c.*, p.name AS \"partnerName\", o.\"orderNumber\", g.\"receiptNumber\","
            + " d.\"documentNumber\" AS \"billNumber\", n.\"noteNumber\" AS \"debitNoteNumber\", n.status::text AS"
            + " \"debitNoteStatus\" FROM \"SupplierClaim\" c LEFT JOIN \"Partner\" p ON p.id = c.\"partnerId\" LEFT JOIN"
            + " \"PurchaseOrder\" o ON o.id = c.\"purchaseOrderId\" LEFT JOIN \"GoodsReceiptNote\" g ON g.id ="
            + " c.\"goodsReceiptId\" LEFT JOIN \"Document\" d ON d.id = c.\"vendorBillId\" LEFT JOIN \"DebitNote\" n ON"
            + " n.id = c.\"debitNoteId\"";

    private Map<String, Object> transition(String id, List<String> from, String to, String extraSet, BigDecimal amount,
            String response) {
        List<Object> params = new ArrayList<>();
        params.add(to);
        if (extraSet.contains("?")) {
            params.add(amount);
            params.add(response);
        }
        params.add(id);
        params.add(org());
        params.add(from.toArray(String[]::new));
        int n = jdbc.sql("UPDATE \"SupplierClaim\" SET status = ?" + extraSet + ", version = version + 1, \"updatedAt\" = "
                + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = ANY(?::text[])").params(params).update();
        Map<String, Object> c = get(id);
        if (n == 0) {
            throw ApiException.badRequest("Cannot change a " + c.get("status") + " claim to " + to);
        }
        audit.record(AuditInput.of("SupplierClaim", id, "update", null, Obj.of("status", to)));
        return c;
    }

    private void assertLinked(String table, String id, String partnerId, String label) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ? AND \"partnerId\" = ?")
                .params(id, org(), partnerId).query(Long.class).single() == 0) {
            throw ApiException.notFound(label + " not found for this supplier");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Map<String, Object> wire(Map<String, Object> r) {
        r.put("amount", JsNumbers.dec(Money.dec(r.get("amount"))));
        r.put("settledAmount", JsNumbers.dec(Money.dec(r.get("settledAmount"))));
        return r;
    }
}
