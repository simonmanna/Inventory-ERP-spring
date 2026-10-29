package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.DispatchCommands.DiscrepancyQuery;
import com.inventoryerp.fleet.domain.FleetDeliveryDiscrepancy;
import com.inventoryerp.fleet.domain.FleetLoadLine;
import com.inventoryerp.invoicing.application.CreditNoteService;
import com.inventoryerp.invoicing.application.CreditNoteService.CreditNoteInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.kernel.approvals.ApprovalAdminService;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivered-vs-dispatched discrepancies (fleet 1.3): raised by the proof of delivery (shortage / excess net of returns,
 * and returned quantities) or a full return, each with a reason, and decided through the kernel approval engine
 * (entity type {@value #APPROVAL_ENTITY}; default workflow "Fleet delivery discrepancy", step "Dispatch manager",
 * approver permission {@code fleet_dispatch:approve_discrepancy}, created on first use; an inactive workflow
 * auto-approves). The person who recorded the delivery and the trip's driver cannot decide it (403).
 *
 * <p>An <b>approved</b> discrepancy adjusts the delivery record — the load line's billable quantity drops by the
 * shortage / returned quantity (or rises by an excess) — and, when the goods were already invoiced, raises a
 * <b>draft credit note</b> in invoicing for the shortage at the line price (product-less revenue lines, so posting it
 * never restocks: fleet already moved the goods). Nothing is posted silently; AR posts the credit note. A rejected
 * discrepancy leaves the customer billed for the dispatched quantity.
 */
@Service
public class FleetDiscrepancyService {

    public static final String APPROVAL_ENTITY = "fleet_delivery_discrepancy";
    static final String AUDIT_ENTITY = "FleetDeliveryDiscrepancy";

    private static final String SELECT = """
            SELECT x.*, t.code AS "tripCode", t."driverId", o.code AS "deliveryOrderCode", o."partnerId",
                   p.name AS "partnerName", pr.code AS "productCode", pr.name AS "productName", u.code AS "uomCode"
            FROM "FleetDeliveryDiscrepancy" x
            JOIN "FleetLoad" t ON t.id = x."loadId"
            JOIN "FleetDeliveryOrder" o ON o.id = x."deliveryOrderId"
            LEFT JOIN "Partner" p ON p.id = o."partnerId"
            LEFT JOIN "Product" pr ON pr.id = x."productId"
            LEFT JOIN "UnitOfMeasure" u ON u.id = x."uomId"
            """;

    private final FleetDb db;
    private final SequenceService sequence;
    private final ApprovalsService approvals;
    private final ApprovalAdminService approvalAdmin;
    private final CreditNoteService creditNotes;
    private final FleetDeliveryOrderService orders;
    private final AuditService audit;
    private final EventBus events;

    public FleetDiscrepancyService(FleetDb db, SequenceService sequence, ApprovalsService approvals,
            ApprovalAdminService approvalAdmin, CreditNoteService creditNotes, FleetDeliveryOrderService orders,
            AuditService audit, EventBus events) {
        this.db = db;
        this.sequence = sequence;
        this.approvals = approvals;
        this.approvalAdmin = approvalAdmin;
        this.creditNotes = creditNotes;
        this.orders = orders;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(DiscrepancyQuery q) {
        int p = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 25 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE x.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(q.status())) {
            where.append(" AND x.status = ?");
            params.add(q.status());
        }
        if (FleetVehicleService.notBlank(q.kind())) {
            where.append(" AND x.kind = ?");
            params.add(q.kind());
        }
        if (FleetVehicleService.notBlank(q.deliveryOrderId())) {
            where.append(" AND x.\"deliveryOrderId\" = ?");
            params.add(q.deliveryOrderId());
        }
        if (FleetVehicleService.notBlank(q.partnerId())) {
            where.append(" AND o.\"partnerId\" = ?");
            params.add(q.partnerId());
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetDeliveryDiscrepancy\" x JOIN \"FleetDeliveryOrder\" o ON"
                + " o.id = x.\"deliveryOrderId\"" + where).params(params).query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY x.\"createdAt\" DESC, x.id LIMIT ?"
                + " OFFSET ?").params(pp)), p, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> x = SqlRows.one(db.jdbc().sql(SELECT + " WHERE x.id = ? AND x.\"organizationId\" = ?")
                .params(id, FleetDb.org()));
        if (x == null) {
            throw ApiException.notFound("Discrepancy not found");
        }
        x.put("approvalRequest", x.get("approvalRequestId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id,"
                + " status::text AS status, \"currentStep\" FROM \"ApprovalRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(x.get("approvalRequestId"), FleetDb.org())));
        return x;
    }

    /**
     * Record a discrepancy of a load line and submit it for approval (called inside the POD / return transaction).
     * {@code differenceQty}: how much the billable quantity drops when approved (negative for an excess).
     */
    Map<String, Object> raise(Map<String, Object> trip, Map<String, Object> line, String kind, String reason,
            BigDecimal dispatched, BigDecimal delivered, BigDecimal differenceQty, String notes) {
        Map<String, Object> row = db.insert(FleetDeliveryDiscrepancy.class, Obj.of(
                "code", sequence.next("fleet_delivery_discrepancy", "DSC-", 6),
                "deliveryOrderId", trip.get("deliveryOrderId"),
                "loadId", trip.get("id"),
                "loadLineId", line.get("id"),
                "productId", line.get("productId"),
                "kind", kind,
                "reason", reason,
                "dispatchedQty", dispatched,
                "deliveredQty", delivered,
                "differenceQty", differenceQty,
                "uomId", line.get("uomId"),
                "notes", FleetVehicleService.trim(notes),
                "status", "pending",
                "raisedBy", TenantContext.userId()));
        String id = (String) row.get("id");
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, row));
        ensureWorkflow();
        Map<String, Object> gate = approvals.checkOrRequestApproval(APPROVAL_ENTITY, id, Obj.of("discrepancy",
                row.get("code"), "trip", trip.get("code"), "kind", kind, "reason", reason, "quantity",
                JsNumbers.dec(differenceQty)));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            db.update(FleetDeliveryDiscrepancy.class, id, Obj.of("approvalRequestId", gate.get("requestId")));
        } else {
            decide(id, true, null, "Approved automatically (no active approval workflow)");
        }
        return db.require(FleetDeliveryDiscrepancy.class, id, false, "Discrepancy not found");
    }

    @Transactional
    public Map<String, Object> approve(String id, String notes) {
        return decideByUser(id, true, FleetVehicleService.trim(notes));
    }

    @Transactional
    public Map<String, Object> reject(String id, String notes) {
        String n = FleetVehicleService.trim(notes);
        if (n == null) {
            throw ApiException.badRequest("A reason is required to reject a discrepancy");
        }
        return decideByUser(id, false, n);
    }

    private Map<String, Object> decideByUser(String id, boolean approved, String notes) {
        Map<String, Object> x = lock(id);
        if (!"pending".equals(x.get("status"))) {
            throw ApiException.badRequest("Discrepancy " + x.get("code") + " is already " + x.get("status"));
        }
        String actor = TenantContext.userId();
        if (actor != null && actor.equals(x.get("raisedBy"))) {
            throw ApiException.forbidden("The person who recorded a discrepancy cannot approve or reject it");
        }
        String driverUser = db.jdbc().sql("SELECT e.\"userId\" FROM \"FleetLoad\" t JOIN \"FleetDriver\" d ON d.id ="
                + " t.\"driverId\" JOIN \"HrEmployee\" e ON e.id = d.\"employeeId\" WHERE t.id = ? AND"
                + " t.\"organizationId\" = ?").params(x.get("loadId"), FleetDb.org()).query(String.class).optional()
                .orElse(null);
        if (actor != null && actor.equals(driverUser)) {
            throw ApiException.forbidden("The driver of the trip cannot approve or reject its discrepancy");
        }
        String requestId = (String) x.get("approvalRequestId");
        if (requestId != null) {
            approvals.decide(requestId, approved ? "approved" : "rejected", notes);
            String status = requestStatus(requestId);
            if ("pending".equals(status)) {
                return get(id);
            }
            decide(id, "approved".equals(status), actor, notes);
            return get(id);
        }
        decide(id, approved, actor, notes);
        return get(id);
    }

    /** Apply the approval engine's decision ({@code approval.decided}); idempotent. */
    @Transactional
    public Map<String, Object> applyDecision(String id, boolean approved, String deciderId) {
        decide(id, approved, deciderId, null);
        return db.require(FleetDeliveryDiscrepancy.class, id, false, "Discrepancy not found");
    }

    /** Apply a decision taken in the approvals inbox now; 400 while pending. */
    @Transactional
    public Map<String, Object> sync(String id) {
        Map<String, Object> x = lock(id);
        if (!"pending".equals(x.get("status"))) {
            return get(id);
        }
        String requestId = (String) x.get("approvalRequestId");
        if (requestId == null) {
            decide(id, true, null, null);
            return get(id);
        }
        String status = requestStatus(requestId);
        if ("pending".equals(status)) {
            throw ApiException.badRequest("The discrepancy is waiting for approval (request " + requestId + ")");
        }
        String decider = db.jdbc().sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
        decide(id, "approved".equals(status), decider, null);
        return get(id);
    }

    private void decide(String id, boolean approved, String deciderId, String notes) {
        Map<String, Object> x = lock(id);
        if (!"pending".equals(x.get("status"))) {
            return;
        }
        Map<String, Object> data = Obj.of("status", approved ? "approved" : "rejected", "decidedBy", deciderId,
                "decidedAt", Instant.now(), "decisionNotes", notes);
        if (approved) {
            Map<String, Object> line = db.require(FleetLoadLine.class, (String) x.get("loadLineId"), true,
                    "Trip line not found");
            BigDecimal diff = SqlRows.dec(x, "differenceQty");
            BigDecimal billable = line.get("billableQty") == null ? SqlRows.dec(line, "dispatchedQty")
                    : SqlRows.dec(line, "billableQty");
            BigDecimal next = billable.subtract(diff).max(BigDecimal.ZERO);
            db.update(FleetLoadLine.class, (String) line.get("id"), Obj.of("billableQty", next));
            Map<String, Object> order = orders.require((String) x.get("deliveryOrderId"), false);
            if (order.get("goodsInvoiceId") != null && diff.signum() > 0) {
                Map<String, Object> cn = creditNoteRequest(x, order, diff);
                if (cn != null) {
                    data.put("creditNoteId", cn.get("id"));
                    data.put("creditNoteNumber", cn.get("documentNumber"));
                    data.put("creditAmount", cn.get("totalAmount"));
                }
            }
        }
        Map<String, Object> after = db.update(FleetDeliveryDiscrepancy.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, approved ? "approve" : "reject", x, after));
        events.publish(DomainEvents.FLEET_DELIVERY_DISCREPANCY_DECIDED, Obj.of("organizationId", FleetDb.org(),
                "discrepancyId", id, "code", x.get("code"), "loadId", x.get("loadId"), "deliveryOrderId",
                x.get("deliveryOrderId"), "status", approved ? "approved" : "rejected", "differenceQty",
                JsNumbers.dec(SqlRows.dec(x, "differenceQty")), "creditNoteId", after.get("creditNoteId")));
    }

    /** Draft credit note for an approved shortage on invoiced goods (null when the line has no price). */
    private Map<String, Object> creditNoteRequest(Map<String, Object> x, Map<String, Object> order, BigDecimal qty) {
        Map<String, Object> line = SqlRows.one(db.jdbc().sql("SELECT ol.\"unitPrice\", ol.\"discountPercent\", ol.\"taxId\","
                + " ol.description, p.name, p.\"salesPrice\", c.\"incomeAccountId\" FROM \"FleetLoadLine\" ll JOIN"
                + " \"FleetDeliveryOrderLine\" ol ON ol.id = ll.\"deliveryOrderLineId\" JOIN \"Product\" p ON p.id ="
                + " ll.\"productId\" LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\" WHERE ll.id = ? AND"
                + " ll.\"organizationId\" = ?").params(x.get("loadLineId"), FleetDb.org()));
        BigDecimal price = line.get("unitPrice") != null ? SqlRows.dec(line, "unitPrice")
                : line.get("salesPrice") == null ? BigDecimal.ZERO : SqlRows.dec(line, "salesPrice");
        if (price.signum() <= 0) {
            return null;
        }
        String trip = db.jdbc().sql("SELECT code FROM \"FleetLoad\" WHERE id = ?").param(x.get("loadId"))
                .query(String.class).single();
        String description = "Delivery " + kindLabel((String) x.get("kind")) + " " + x.get("code") + " · "
                + line.get("name") + " · trip " + trip;
        boolean document = "document".equals(order.get("goodsInvoiceKind"));
        String notes = "Credit-note request from fleet delivery discrepancy " + x.get("code") + " (delivery order "
                + order.get("code") + ", invoice " + order.get("goodsInvoiceNumber") + "). The goods were handled by"
                + " fleet dispatch: the lines carry no product, so posting does not move stock.";
        return creditNotes.create(new CreditNoteInput((String) order.get("partnerId"),
                FleetDeliveryOrderService.today().toString(), null, (String) order.get("code"), notes,
                document ? (String) order.get("goodsInvoiceId") : null,
                List.of(LineInput.of(null, (String) line.get("incomeAccountId"), description, qty, price,
                        (BigDecimal) line.get("discountPercent"), (String) line.get("taxId"), null))));
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "returned" -> "return";
            case "excess" -> "excess";
            default -> "shortage";
        };
    }

    private Map<String, Object> lock(String id) {
        return db.require(FleetDeliveryDiscrepancy.class, id, true, "Discrepancy not found");
    }

    private String requestStatus(String requestId) {
        return db.jdbc().sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ? AND \"organizationId\" = ?")
                .params(requestId, FleetDb.org()).query(String.class).optional().orElse("pending");
    }

    /** Creates the default discrepancy workflow when the organization has none (active or not). */
    private void ensureWorkflow() {
        long n = db.jdbc().sql("SELECT count(*) FROM \"ApprovalWorkflow\" WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = ?").params(FleetDb.org(), APPROVAL_ENTITY).query(Long.class).single();
        if (n == 0) {
            approvalAdmin.createWorkflow("Fleet delivery discrepancy", APPROVAL_ENTITY, null, true, true,
                    List.of(new ApprovalAdminService.Step(1, "Dispatch manager",
                            List.of(Permissions.FLEET_DISPATCH__APPROVE_DISCREPANCY), 1, null, null)));
        }
    }
}
