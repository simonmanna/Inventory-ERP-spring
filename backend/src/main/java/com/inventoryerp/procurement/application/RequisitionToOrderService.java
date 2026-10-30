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
import com.inventoryerp.procurement.application.PurchaseOrdersService.CreateInput;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Requisition-to-order (ADR-PROCUREMENT-001 wave 3): the sourcing workbench of approved request lines still to be
 * ordered, ordering them from one supplier at a time (one requisition can feed several orders, several requisitions
 * one order), request cancellation, and the purchase-order lifecycle after approval — send to supplier, supplier
 * acknowledgement, short-close of an unfinished balance and close-out — plus the open-orders / overdue report.
 */
@Service
public class RequisitionToOrderService {

    /**
     * One line to order now: a request line ({@code requestLineId}), or — without one — a free line described by
     * {@code productId} / {@code description} / {@code uomId} (RFQ lines not raised from a request).
     */
    public record OrderLine(String requestLineId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxRate,
            String taxId, String productId, String description, String uomId) {

        public OrderLine(String requestLineId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxRate,
                String taxId) {
            this(requestLineId, quantity, unitPrice, taxRate, taxId, null, null, null);
        }
    }

    /** Order header for the selected lines. */
    public record OrderInput(String partnerId, String warehouseId, String branchId, String paymentType,
            String currencyCode, BigDecimal exchangeRate, String expectedDeliveryDate, String notes, String terms,
            List<OrderLine> lines) {}

    private final JdbcClient jdbc;
    private final PurchaseOrdersService orders;
    private final SupplierService suppliers;
    private final AuditService audit;
    private final EventBus events;
    private final TransactionTemplate tx;

    private final PurchaseCommitments commitments;

    public RequisitionToOrderService(JdbcClient jdbc, PurchaseOrdersService orders, SupplierService suppliers,
            AuditService audit, EventBus events, PlatformTransactionManager txm, PurchaseCommitments commitments) {
        this.jdbc = jdbc;
        this.commitments = commitments;
        this.orders = orders;
        this.suppliers = suppliers;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
    }

    // ── sourcing workbench ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Approved request lines with quantity still to order, oldest need first, with the suggested supplier (preferred
     * on the line, else the preferred catalogue supplier) and the last price paid.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openLines(String requestId, String productId) {
        List<Object> params = new ArrayList<>(List.of(org()));
        // Lines out on a running RFQ wait for its award instead of being ordered directly.
        StringBuilder where = new StringBuilder(" r.\"organizationId\" = ? AND r.status = 'approved' AND r.\"deletedAt\""
                + " IS NULL AND l.quantity > l.\"orderedQuantity\" AND NOT EXISTS (SELECT 1 FROM \"RfqLine\" ql JOIN \"Rfq\" q"
                + " ON q.id = ql.\"rfqId\" WHERE ql.\"requestLineId\" = l.id AND q.status IN ('draft', 'sent', 'closed'))");
        if (requestId != null && !requestId.isBlank()) {
            where.append(" AND r.id = ?");
            params.add(requestId);
        }
        if (productId != null && !productId.isBlank()) {
            where.append(" AND l.\"productId\" = ?");
            params.add(productId);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.id, l.\"purchaseRequestId\", r.\"requestNumber\","
                + " r.\"branchId\", r.\"neededBy\", l.\"productId\", p.code AS \"productCode\", p.name AS \"productName\","
                + " l.description, l.quantity, l.\"orderedQuantity\", l.quantity - l.\"orderedQuantity\" AS \"openQuantity\","
                + " l.\"unitOfMeasureId\", l.\"estimatedUnitPrice\", l.\"requiredDate\", l.\"deliveryLocationId\","
                + " l.\"costCenterId\", l.\"projectId\", l.\"demandSource\", COALESCE(l.\"preferredSupplierId\", (SELECT"
                + " sp.\"partnerId\" FROM \"SupplierProduct\" sp WHERE sp.\"organizationId\" = r.\"organizationId\" AND"
                + " sp.\"productId\" = l.\"productId\" ORDER BY sp.preferred DESC, sp.\"updatedAt\" DESC LIMIT 1),"
                + " r.\"partnerId\") AS \"suggestedSupplierId\" FROM \"PurchaseRequestLine\" l JOIN \"PurchaseRequest\" r ON"
                + " r.id = l.\"purchaseRequestId\" LEFT JOIN \"Product\" p ON p.id = l.\"productId\" AND p.\"organizationId\""
                + " = r.\"organizationId\" WHERE" + where + " ORDER BY COALESCE(l.\"requiredDate\", r.\"neededBy\"::date)"
                + " ASC NULLS LAST, r.\"requestNumber\", l.\"lineNumber\" LIMIT 500").params(params));
        for (Map<String, Object> r : rows) {
            Object supplier = r.get("suggestedSupplierId");
            r.put("suggestedSupplier", supplier == null ? null : SqlRows.one(jdbc.sql("SELECT id, name FROM \"Partner\""
                    + " WHERE id = ? AND \"organizationId\" = ?").params(supplier, org())));
            r.put("lastPrice", r.get("productId") == null ? null : SqlRows.one(jdbc.sql("SELECT sp.\"unitPrice\","
                    + " sp.\"currencyCode\", sp.\"validFrom\", sp.\"partnerId\", p.name AS \"partnerName\" FROM"
                    + " \"SupplierPrice\" sp JOIN \"Partner\" p ON p.id = sp.\"partnerId\" WHERE sp.\"organizationId\" = ?"
                    + " AND sp.\"productId\" = ? ORDER BY sp.\"validFrom\" DESC, sp.\"createdAt\" DESC LIMIT 1")
                    .params(org(), r.get("productId"))));
        }
        return rows;
    }

    /**
     * Order selected request lines from one supplier. Request lines are locked, so two buyers can never order the
     * same open quantity twice; each order line remembers its request line. A request whose lines are all fully
     * ordered becomes {@code converted}.
     */
    public Map<String, Object> createOrder(OrderInput in) {
        if (in.lines() == null || in.lines().isEmpty()) {
            throw ApiException.badRequest("Select at least one request line");
        }
        if (in.partnerId() == null || in.partnerId().isBlank()) {
            throw ApiException.badRequest("Supplier is required");
        }
        String orgId = org();
        return tx.execute(st -> {
            List<String> ids = in.lines().stream().map(OrderLine::requestLineId).filter(java.util.Objects::nonNull)
                    .distinct().sorted().toList();
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.*, r.status::text AS \"requestStatus\","
                    + " r.\"requestNumber\" FROM \"PurchaseRequestLine\" l JOIN \"PurchaseRequest\" r ON r.id ="
                    + " l.\"purchaseRequestId\" WHERE l.\"organizationId\" = ? AND l.id = ANY(?::text[]) ORDER BY l.id"
                    + " FOR UPDATE OF l").params(orgId, ids.toArray(String[]::new)))) {
                byId.put((String) l.get("id"), l);
            }
            List<PurchaseOrdersService.LineInput> poLines = new ArrayList<>();
            Map<String, BigDecimal> taking = new LinkedHashMap<>();
            String requestId = null;
            for (OrderLine ol : in.lines()) {
                if (ol.requestLineId() == null) {
                    if (ol.quantity() == null || ol.quantity().signum() <= 0 || ol.description() == null) {
                        throw ApiException.badRequest("A line without a request line needs a description and a quantity");
                    }
                    poLines.add(new PurchaseOrdersService.LineInput(ol.productId(), ol.description(), ol.quantity(),
                            ol.uomId(), ol.unitPrice() == null ? Money.ZERO : ol.unitPrice(), ol.taxId(), ol.taxRate(), null));
                    continue;
                }
                Map<String, Object> rl = byId.get(ol.requestLineId());
                if (rl == null) {
                    throw ApiException.notFound("Request line " + ol.requestLineId() + " not found");
                }
                if (!"approved".equals(rl.get("requestStatus"))) {
                    throw ApiException.badRequest("Request " + rl.get("requestNumber") + " is " + rl.get("requestStatus")
                            + " — only approved requests can be ordered");
                }
                if (jdbc.sql("SELECT count(*) FROM \"RfqLine\" ql JOIN \"Rfq\" q ON q.id = ql.\"rfqId\" WHERE"
                        + " ql.\"requestLineId\" = ? AND q.status IN ('draft', 'sent', 'closed')").param(rl.get("id"))
                        .query(Long.class).single() > 0) {
                    throw ApiException.badRequest("\"" + rl.get("description") + "\" is on an open RFQ — award the RFQ"
                            + " instead");
                }
                BigDecimal open = Money.dec(rl.get("quantity")).subtract(Money.dec(rl.get("orderedQuantity")))
                        .subtract(taking.getOrDefault(ol.requestLineId(), Money.ZERO));
                BigDecimal qty = ol.quantity() == null ? open : ol.quantity();
                if (qty.signum() <= 0) {
                    throw ApiException.badRequest("Quantity must be positive for \"" + rl.get("description") + "\"");
                }
                if (qty.compareTo(open) > 0) {
                    throw ApiException.badRequest("Only " + JsNumbers.dec(open) + " of \"" + rl.get("description")
                            + "\" on " + rl.get("requestNumber") + " is still to be ordered");
                }
                BigDecimal price = ol.unitPrice() != null ? ol.unitPrice() : Money.dec(rl.get("estimatedUnitPrice"));
                if (price.signum() < 0) {
                    throw ApiException.badRequest("Unit price must not be negative");
                }
                taking.merge(ol.requestLineId(), qty, BigDecimal::add);
                poLines.add(new PurchaseOrdersService.LineInput((String) rl.get("productId"), (String) rl.get("description"),
                        qty, (String) rl.get("unitOfMeasureId"), price, ol.taxId(), ol.taxRate(), null));
                requestId = requestId == null ? (String) rl.get("purchaseRequestId") : requestId;
            }
            Map<String, Object> po = orders.create(new CreateInput(in.partnerId(), in.branchId(), null,
                    in.expectedDeliveryDate(), in.currencyCode(), in.exchangeRate(), in.paymentType() == null ? "credit"
                            : in.paymentType(), in.notes(), in.terms(), in.warehouseId(), requestId, poLines));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> created = (List<Map<String, Object>>) po.get("lines");
            for (int i = 0; i < in.lines().size(); i++) {
                OrderLine ol = in.lines().get(i);
                if (ol.requestLineId() == null) {
                    continue;
                }
                jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"requestLineId\" = ? WHERE id = ? AND \"organizationId\" = ?")
                        .params(ol.requestLineId(), created.get(i).get("id"), orgId).update();
            }
            for (Map.Entry<String, BigDecimal> e : taking.entrySet()) {
                jdbc.sql("UPDATE \"PurchaseRequestLine\" SET \"orderedQuantity\" = \"orderedQuantity\" + ? WHERE id = ? AND"
                        + " \"organizationId\" = ?").params(e.getValue(), e.getKey(), orgId).update();
            }
            List<String> requests = byId.values().stream().map(l -> (String) l.get("purchaseRequestId")).distinct()
                    .toList();
            for (String rid : requests) {
                long open = jdbc.sql("SELECT count(*) FROM \"PurchaseRequestLine\" WHERE \"organizationId\" = ? AND"
                        + " \"purchaseRequestId\" = ? AND quantity > \"orderedQuantity\"").params(orgId, rid)
                        .query(Long.class).single();
                if (open == 0) {
                    int n = jdbc.sql("UPDATE \"PurchaseRequest\" SET status = 'converted', \"updatedAt\" = " + NOW
                            + " WHERE id = ? AND \"organizationId\" = ? AND status = 'approved'").params(rid, orgId)
                            .update();
                    if (n > 0) {
                        List<String> poIds = jdbc.sql("SELECT id FROM \"PurchaseOrder\" WHERE \"organizationId\" = ? AND"
                                + " \"requestId\" = ?").params(orgId, rid).query(String.class).list();
                        events.publish("purchase_request.converted", Obj.of("organizationId", orgId, "requestId", rid,
                                "purchaseOrderIds", poIds));
                    }
                }
            }
            commitments.sync((String) po.get("id"));
            audit.recordInTx(AuditInput.of("PurchaseOrder", (String) po.get("id"), "update", null,
                    Obj.of("fromRequestLines", ids)));
            return po;
        });
    }

    /** Cancel a request that has not been ordered from (draft, submitted or approved). */
    public Map<String, Object> cancelRequest(String requestId, String reason) {
        String orgId = org();
        long ordered = jdbc.sql("SELECT count(*) FROM \"PurchaseRequestLine\" WHERE \"organizationId\" = ? AND"
                + " \"purchaseRequestId\" = ? AND \"orderedQuantity\" > 0").params(orgId, requestId).query(Long.class)
                .single();
        if (ordered > 0) {
            throw ApiException.badRequest("This request already has orders — cancel or short-close the orders instead");
        }
        int n = jdbc.sql("UPDATE \"PurchaseRequest\" SET status = 'cancelled', \"rejectedReason\" = COALESCE(?,"
                + " \"rejectedReason\"), \"updatedBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?"
                + " AND status IN ('draft', 'submitted', 'approved') AND \"deletedAt\" IS NULL")
                .params(reason, user(), requestId, orgId).update();
        if (n == 0) {
            if (jdbc.sql("SELECT count(*) FROM \"PurchaseRequest\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(requestId, orgId).query(Long.class).single() == 0) {
                throw ApiException.notFound("Purchase request not found");
            }
            throw ApiException.badRequest("Only a draft, submitted or approved request can be cancelled");
        }
        audit.record(AuditInput.of("PurchaseRequest", requestId, "cancel", null, Obj.of("reason", reason)));
        return Obj.of("id", requestId, "status", "cancelled");
    }

    // ── order lifecycle after approval ─────────────────────────────────────────────────────────────────────────

    /** Mark the order sent to the supplier (printed / e-mailed). Idempotent: re-sending keeps the first date. */
    public Map<String, Object> send(String poId) {
        Map<String, Object> po = requireOpen(poId);
        jdbc.sql("UPDATE \"PurchaseOrder\" SET \"sentAt\" = COALESCE(\"sentAt\", " + NOW + "), \"sentById\" ="
                + " COALESCE(\"sentById\", ?), \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(user(), poId, org()).update();
        if (po.get("sentAt") == null) {
            audit.record(AuditInput.of("PurchaseOrder", poId, "update", null, Obj.of("sent", true)));
            events.publish("purchase_order.sent", Obj.of("organizationId", org(), "orderId", poId, "sentAt",
                    java.time.Instant.now().toString()));
        }
        return lifecycle(poId);
    }

    /** Record the supplier's confirmation: their reference and promised delivery date. */
    public Map<String, Object> acknowledge(String poId, String supplierReference, String promisedDate) {
        requireOpen(poId);
        java.time.LocalDate promised = promisedDate == null || promisedDate.isBlank() ? null
                : java.time.LocalDate.parse(promisedDate.substring(0, 10));
        jdbc.sql("UPDATE \"PurchaseOrder\" SET \"acknowledgedAt\" = " + NOW + ", \"supplierReference\" = ?,"
                + " \"promisedDate\" = ?, \"sentAt\" = COALESCE(\"sentAt\", " + NOW + "), \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?").params(supplierReference, promised, poId, org()).update();
        audit.record(AuditInput.of("PurchaseOrder", poId, "update", null, Obj.of("acknowledged", true,
                "supplierReference", supplierReference, "promisedDate", promisedDate)));
        return lifecycle(poId);
    }

    /**
     * Short-close: the supplier will not deliver the rest. Every line's undelivered balance is cancelled; the order
     * becomes {@code closed} when anything was received, else {@code cancelled}. Bills and payments still follow
     * what was received.
     */
    public Map<String, Object> shortClose(String poId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A reason is required to short-close an order");
        }
        String orgId = org();
        tx.executeWithoutResult(st -> {
            Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT id, status::text AS status FROM \"PurchaseOrder\" WHERE"
                    + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL FOR UPDATE").params(poId, orgId));
            if (po == null) {
                throw ApiException.notFound("Purchase order not found");
            }
            if (!List.of("active", "partially_received").contains(po.get("status"))) {
                throw ApiException.badRequest("Only an open order can be short-closed (status: " + po.get("status") + ")");
            }
            orders.returnOpenBalance(poId);
            jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"cancelledQuantity\" = GREATEST(0, quantity - \"receivedQuantity\"),"
                    + " version = version + 1 WHERE \"purchaseOrderId\" = ? AND \"organizationId\" = ?").params(poId, orgId)
                    .update();
            boolean anyReceived = jdbc.sql("SELECT count(*) FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ? AND"
                    + " \"organizationId\" = ? AND \"receivedQuantity\" > 0").params(poId, orgId).query(Long.class)
                    .single() > 0;
            String to = anyReceived ? "closed" : "cancelled";
            jdbc.sql("UPDATE \"PurchaseOrder\" SET status = ?::\"PurchaseOrderStatus\", \"closedAt\" = " + NOW
                    + ", \"closedById\" = ?, \"closeReason\" = ?, version = version + 1, \"updatedAt\" = " + NOW
                    + " WHERE id = ? AND \"organizationId\" = ?").params(to, user(), reason.trim(), poId, orgId).update();
            commitments.sync(poId);
            audit.recordInTx(AuditInput.of("PurchaseOrder", poId, "update", Obj.of("status", po.get("status")),
                    Obj.of("status", to, "reason", reason.trim())));
        });
        events.publish("purchase_order.closed", Obj.of("organizationId", orgId, "orderId", poId, "reason", reason.trim()));
        return lifecycle(poId);
    }

    /** Close a fully received order (no further receipts or changes). */
    public Map<String, Object> close(String poId) {
        int n = jdbc.sql("UPDATE \"PurchaseOrder\" SET status = 'closed', \"closedAt\" = " + NOW + ", \"closedById\" = ?,"
                + " version = version + 1, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status ="
                + " 'received' AND \"deletedAt\" IS NULL").params(user(), poId, org()).update();
        if (n == 0) {
            lifecycle(poId);
            throw ApiException.badRequest("Only a fully received order can be closed — short-close an open order instead");
        }
        audit.record(AuditInput.of("PurchaseOrder", poId, "update", null, Obj.of("status", "closed")));
        events.publish("purchase_order.closed", Obj.of("organizationId", org(), "orderId", poId, "reason", ""));
        return lifecycle(poId);
    }

    private Map<String, Object> requireOpen(String poId) {
        Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT id, status::text AS status, \"sentAt\" FROM \"PurchaseOrder\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(poId, org()));
        if (po == null) {
            throw ApiException.notFound("Purchase order not found");
        }
        if (!List.of("active", "partially_received").contains(po.get("status"))) {
            throw ApiException.badRequest("The order is " + po.get("status") + " — only an approved, open order can be"
                    + " sent or acknowledged");
        }
        return po;
    }

    /** Lifecycle view of an order: the parity row plus the S2P fields, lines with open / cancelled quantities. */
    @Transactional(readOnly = true)
    public Map<String, Object> lifecycle(String poId) {
        Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT o.id, o.\"orderNumber\", o.status::text AS status,"
                + " o.\"partnerId\", p.name AS \"partnerName\", o.\"orderDate\", o.\"expectedDeliveryDate\", o.\"promisedDate\","
                + " o.\"sentAt\", o.\"acknowledgedAt\", o.\"supplierReference\", o.\"approvedAt\", o.\"closedAt\","
                + " o.\"closeReason\", o.\"requestId\", o.\"currencyCode\", o.\"totalAmount\", o.\"totalPaid\","
                + " o.\"isEmergency\", o.\"emergencyReason\", o.\"emergencyReviewedAt\" FROM"
                + " \"PurchaseOrder\" o LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\" WHERE o.id = ? AND"
                + " o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL").params(poId, org()));
        if (po == null) {
            throw ApiException.notFound("Purchase order not found");
        }
        po.put("lines", SqlRows.list(jdbc.sql("SELECT id, \"productId\", description, quantity, \"receivedQuantity\","
                + " \"billedQuantity\", \"cancelledQuantity\", GREATEST(0, quantity - \"receivedQuantity\" -"
                + " \"cancelledQuantity\") AS \"openQuantity\", \"unitPrice\", \"requestLineId\", \"lineNumber\" FROM"
                + " \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ? AND \"organizationId\" = ? ORDER BY \"lineNumber\"")
                .params(poId, org())));
        po.put("receipts", SqlRows.list(jdbc.sql("SELECT id, \"receiptNumber\", status::text AS status, \"receivedAt\" FROM"
                + " \"GoodsReceiptNote\" WHERE \"purchaseOrderId\" = ? AND \"organizationId\" = ? ORDER BY \"receivedAt\"")
                .params(poId, org())));
        po.put("bills", SqlRows.list(jdbc.sql("SELECT d.id, d.\"documentNumber\", d.status::text AS status,"
                + " d.\"totalAmount\", d.\"amountResidual\" FROM \"VendorBillLink\" l JOIN \"Document\" d ON d.id ="
                + " l.\"vendorBillId\" WHERE l.\"organizationId\" = ? AND l.\"purchaseOrderId\" = ? ORDER BY d.\"issueDate\"")
                .params(org(), poId)));
        po.put("approvals", SqlRows.list(jdbc.sql("SELECT id, status::text AS status, \"createdAt\", \"decidedAt\" FROM"
                + " \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\" = 'purchase_order' AND \"entityId\" = ?"
                + " ORDER BY \"createdAt\"").params(org(), poId)));
        po.put("stage", stage(po));
        return po;
    }

    /** The one-word stage the UI timeline highlights. */
    private static String stage(Map<String, Object> po) {
        String s = String.valueOf(po.get("status"));
        return switch (s) {
            case "draft" -> "awaiting_approval";
            case "active" -> po.get("acknowledgedAt") != null ? "confirmed" : po.get("sentAt") != null ? "sent" : "approved";
            default -> s;
        };
    }

    // ── reports ────────────────────────────────────────────────────────────────────────────────────────────────

    /** Open order lines (still to be delivered), the late ones first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openOrders(String partnerId, boolean overdueOnly) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" o.\"organizationId\" = ? AND o.status IN ('active', 'partially_received')"
                + " AND o.\"deletedAt\" IS NULL AND l.quantity - l.\"receivedQuantity\" - l.\"cancelledQuantity\" > 0");
        if (partnerId != null && !partnerId.isBlank()) {
            where.append(" AND o.\"partnerId\" = ?");
            params.add(partnerId);
        }
        String due = "COALESCE(o.\"promisedDate\", o.\"expectedDeliveryDate\"::date)";
        if (overdueOnly) {
            where.append(" AND ").append(due).append(" < CURRENT_DATE");
        }
        return SqlRows.list(jdbc.sql("SELECT o.id AS \"purchaseOrderId\", o.\"orderNumber\", o.\"partnerId\", p.name AS"
                + " \"partnerName\", l.id AS \"lineId\", l.description, l.quantity, l.\"receivedQuantity\", l.quantity -"
                + " l.\"receivedQuantity\" - l.\"cancelledQuantity\" AS \"openQuantity\", l.\"unitPrice\", (l.quantity -"
                + " l.\"receivedQuantity\" - l.\"cancelledQuantity\") * l.\"unitPrice\" AS \"openValue\", o.\"currencyCode\","
                + " " + due + " AS \"dueDate\", GREATEST(0, CURRENT_DATE - " + due + ") AS \"daysLate\", o.\"sentAt\","
                + " o.\"acknowledgedAt\" FROM \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\""
                + " LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\" WHERE" + where + " ORDER BY \"daysLate\" DESC NULLS"
                + " LAST, \"dueDate\" ASC NULLS LAST, o.\"orderNumber\" LIMIT 1000").params(params));
    }
}
