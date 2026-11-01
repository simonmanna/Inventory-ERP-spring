package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.procurement.domain.PurchaseOrder;
import com.inventoryerp.procurement.domain.PurchaseRequest;
import com.inventoryerp.procurement.domain.PurchaseRequestLine;
import com.inventoryerp.shared.web.ApiException;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purchase requests (port of {@code purchase-requests.service.ts}): draft → submitted → approved | rejected; a PO
 * created from an approved request links back via {@code requestId}.
 *
 * <p>PROC-5: with a {@value #APPROVAL_ENTITY} workflow, submit raises an approval request and the outcome comes from
 * the engine, whether decided here through approve / reject or in the approvals inbox
 * ({@link PurchaseRequestApprovalSubscriber}). Without a workflow, approve and reject work as a plain status change.
 */
@Service
public class PurchaseRequestsService {

    public static final String APPROVAL_ENTITY = "purchase_request";

    /** {@code CreatePRInput}. */
    public record CreateInput(String partnerId, String branchId, String description, String neededBy,
            List<LineInput> lines, String departmentId) {}

    /** One {@code PRLineDto}. */
    public record LineInput(String productId, String description, BigDecimal quantity, String unitOfMeasureId,
            BigDecimal estimatedUnitPrice, String notes, String requiredDate, String deliveryLocationId,
            String preferredSupplierId, String costCenterId, String projectId, String demandSource,
            String demandRef) {

        /** The parity line (no ADR-PROCUREMENT-001 extras). */
        public LineInput(String productId, String description, BigDecimal quantity, String unitOfMeasureId,
                BigDecimal estimatedUnitPrice, String notes) {
            this(productId, description, quantity, unitOfMeasureId, estimatedUnitPrice, notes, null, null, null, null,
                    null, null, null);
        }
    }

    private final JdbcClient jdbc;
    private final SequenceService sequence;
    private final EventBus events;
    private final AuditService audit;
    private final ProcurementBudgetService budgets;
    private final ApprovalsService approvals;
    private final TransactionTemplate tx;

    public PurchaseRequestsService(JdbcClient jdbc, SequenceService sequence, EventBus events, AuditService audit,
            ProcurementBudgetService budgets, ApprovalsService approvals, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.budgets = budgets;
        this.approvals = approvals;
        this.sequence = sequence;
        this.events = events;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    /** Create a draft request with its lines. */
    public Map<String, Object> create(CreateInput input) {
        String orgId = org();
        if (input.lines() == null || input.lines().isEmpty()) {
            throw ApiException.badRequest("At least one line is required");
        }
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        String requestNumber = sequence.next("pr:" + year, "PR-" + year + "-", 5);
        Map<String, Object> request = tx.execute(st -> insert(orgId, requestNumber, input));
        audit.record(AuditInput.of("PurchaseRequest", (String) request.get("id"), "create", null,
                Obj.of("requestNumber", requestNumber)));
        events.publish("purchase_request.created", Obj.of("organizationId", orgId, "requestId", request.get("id"),
                "requestNumber", requestNumber));
        return request;
    }

    private Map<String, Object> insert(String orgId, String requestNumber, CreateInput input) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> pr = SqlRows.one(PurchaseRequest.class, jdbc.sql("INSERT INTO \"PurchaseRequest\" (id,"
                        + " \"organizationId\", \"requestNumber\", \"requestedById\", \"partnerId\", \"branchId\","
                        + " \"departmentId\", description, \"neededBy\", status, \"createdBy\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?, " + NOW + ", " + NOW
                        + ") RETURNING *")
                .params(id, orgId, requestNumber, user(), input.partnerId(), input.branchId(), input.departmentId(),
                        input.description(), SqlRows.ts(ProcurementSql.date(input.neededBy())), user()));
        List<Map<String, Object>> lines = new ArrayList<>();
        int idx = 0;
        for (LineInput ln : input.lines()) {
            if (ln.projectId() != null && !ln.projectId().isBlank() && jdbc.sql("SELECT count(*) FROM \"Project\""
                    + " WHERE id = ? AND \"organizationId\" = ?").params(ln.projectId(), orgId).query(Long.class)
                    .single() == 0) {
                throw ApiException.badRequest("Project not found");
            }
            String lineId = UUID.randomUUID().toString();
            lines.add(SqlRows.one(PurchaseRequestLine.class, jdbc.sql("INSERT INTO \"PurchaseRequestLine\" (id,"
                            + " \"organizationId\", \"purchaseRequestId\", \"productId\", description, quantity,"
                            + " \"unitOfMeasureId\", \"estimatedUnitPrice\", notes, \"lineNumber\", \"requiredDate\","
                            + " \"deliveryLocationId\", \"preferredSupplierId\", \"costCenterId\", \"projectId\","
                            + " \"demandSource\", \"demandRef\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?, ?, ?, ?,"
                            + " COALESCE(?, 'manual'), ?) RETURNING *")
                    .params(lineId, orgId, id, ln.productId(), ln.description(), ln.quantity(),
                            ln.unitOfMeasureId(), ln.estimatedUnitPrice(), ln.notes(), ++idx,
                            ln.requiredDate() == null || ln.requiredDate().isBlank() ? null
                                    : ln.requiredDate().substring(0, 10),
                            ln.deliveryLocationId(), ln.preferredSupplierId(), ln.costCenterId(), ln.projectId(),
                            ln.demandSource(), ln.demandRef())));
        }
        pr.put("lines", lines);
        return pr;
    }

    /** Latest 200 requests with lines and the count of orders raised from each. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = " \"organizationId\" = ? AND \"deletedAt\" IS NULL";
        if (status != null && !status.isEmpty()) {
            where += " AND status = ?::\"PurchaseRequestStatus\"";
            params.add(status);
        }
        List<Map<String, Object>> rows = SqlRows.list(PurchaseRequest.class, jdbc.sql("SELECT * FROM \"PurchaseRequest\""
                + " WHERE" + where + " ORDER BY \"createdAt\" DESC LIMIT 200").params(params));
        for (Map<String, Object> r : rows) {
            r.put("lines", lines(r.get("id")));
            r.put("_count", Obj.of("orders", jdbc.sql("SELECT count(*) FROM \"PurchaseOrder\" WHERE \"requestId\" = ?")
                    .param(r.get("id")).query(Long.class).single()));
        }
        return rows;
    }

    /** Request with lines and orders; {@code null} when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> pr = find(id);
        if (pr == null) {
            return null;
        }
        pr.put("lines", lines(id));
        pr.put("orders", SqlRows.list(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE \"requestId\" = ?")
                .param(id)));
        return pr;
    }

    private List<Map<String, Object>> lines(Object requestId) {
        return SqlRows.list(PurchaseRequestLine.class, jdbc.sql("SELECT * FROM \"PurchaseRequestLine\" WHERE"
                + " \"purchaseRequestId\" = ?").param(requestId));
    }

    private Map<String, Object> find(String id) {
        return SqlRows.one(PurchaseRequest.class, jdbc.sql("SELECT * FROM \"PurchaseRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
    }

    private Map<String, Object> requireOwned(String id) {
        Map<String, Object> pr = find(id);
        if (pr == null) {
            throw ApiException.notFound("Purchase request not found");
        }
        return pr;
    }

    private Map<String, Object> update(String id, String set, Object... params) {
        List<Object> all = new ArrayList<>(List.of(params));
        all.add(id);
        all.add(org());
        Map<String, Object> row = SqlRows.one(PurchaseRequest.class, jdbc.sql("UPDATE \"PurchaseRequest\" SET " + set
                + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " RETURNING *").params(all));
        if (row == null) {
            throw new IllegalStateException("No record was found for an update.");
        }
        return row;
    }

    /** draft → submitted. */
    public Map<String, Object> submit(String id) {
        Map<String, Object> pr = requireOwned(id);
        if (!"draft".equals(pr.get("status"))) {
            throw ApiException.badRequest("Cannot submit PR in status " + pr.get("status"));
        }
        Map<String, Object> updated = tx.execute(st -> {
            ProcurementBudgetService.Doc doc = budgetDoc(pr);
            budgets.enforce(doc);
            Map<String, Object> submitted = update(id, "status = 'submitted'");
            BigDecimal value = doc.lines().stream().map(ProcurementBudgetService.DocLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            approvals.requestApproval(APPROVAL_ENTITY, id, ProcurementSql.defined("amount", JsNumbers.js(value),
                    "requestNumber", pr.get("requestNumber"), "branchId", doc.branchId(), "departmentId",
                    doc.departmentId(), "createdBy", user()), null);
            return submitted;
        });
        audit.record(AuditInput.of("PurchaseRequest", id, "update", null, Obj.of("status", "submitted")));
        events.publish("purchase_request.submitted", Obj.of("organizationId", org(), "requestId", id));
        return updated;
    }

    /** The request as the budgets see it: estimated value of each line, in base currency. */
    private ProcurementBudgetService.Doc budgetDoc(Map<String, Object> pr) {
        String id = (String) pr.get("id");
        List<ProcurementBudgetService.DocLine> lines = lines(id).stream()
                .map(l -> new ProcurementBudgetService.DocLine((String) l.get("productId"), SqlRows.dec(l, "quantity")
                        .multiply(SqlRows.dec(l, "estimatedUnitPrice")))).toList();
        return new ProcurementBudgetService.Doc(ProcurementBudgetService.REQUEST, id, (String) pr.get("branchId"),
                departmentOf(id), lines, null);
    }

    /** The column is not on the entity, so it stays out of the route JSON. */
    private String departmentOf(String id) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT \"departmentId\" FROM \"PurchaseRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        return row == null ? null : (String) row.get("departmentId");
    }

    /** submitted → approved, or the next step of the approval workflow when one is running. */
    public Map<String, Object> approve(String id) {
        Map<String, Object> pr = requireOwned(id);
        if (!"submitted".equals(pr.get("status"))) {
            throw ApiException.badRequest("Cannot approve PR in status " + pr.get("status"));
        }
        return tx.execute(st -> {
            Map<String, Object> request = latestRequest(id);
            if (request == null) {
                return markApproved(id, user());
            }
            String status = (String) request.get("status");
            if ("pending".equals(status)) {
                approvals.decide((String) request.get("id"), "approved", null);
                status = (String) latestRequest(id).get("status");
            }
            return switch (status) {
                case "approved" -> markApproved(id, user());
                case "rejected" -> markRejected(id, lastDecision((String) request.get("id")).get("comment"));
                default -> requireOwned(id);
            };
        });
    }

    private Map<String, Object> markApproved(String id, String approverId) {
        Map<String, Object> updated = update(id, "status = 'approved', \"approvedAt\" = " + NOW + ", \"approvedById\" = ?",
                approverId);
        audit.record(AuditInput.of("PurchaseRequest", id, "approve", null, Obj.of("status", "approved")));
        events.publish("purchase_request.approved", ProcurementSql.defined("organizationId", org(), "requestId", id,
                "approverId", approverId));
        return updated;
    }

    /** draft | submitted → rejected. */
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> pr = requireOwned(id);
        if (!List.of("submitted", "draft").contains(pr.get("status"))) {
            throw ApiException.badRequest("Cannot reject PR in status " + pr.get("status"));
        }
        return tx.execute(st -> {
            Map<String, Object> request = latestRequest(id);
            if (request != null && "pending".equals(request.get("status"))) {
                approvals.decide((String) request.get("id"), "rejected", reason);
            }
            return markRejected(id, reason);
        });
    }

    private Map<String, Object> markRejected(String id, Object comment) {
        String reason = comment == null ? "" : comment.toString();
        Map<String, Object> updated = update(id, "status = 'rejected', \"rejectedReason\" = ?", reason);
        events.publish("purchase_request.rejected", Obj.of("organizationId", org(), "requestId", id, "reason", reason));
        return updated;
    }

    /**
     * Apply a decision taken in the approvals inbox ({@code approval.decided}, delivered at least once). A request
     * no longer waiting is left alone.
     */
    public void applyDecision(String id, boolean approved, String approvalRequestId) {
        Map<String, Object> pr = find(id);
        if (pr == null || !"submitted".equals(pr.get("status"))) {
            return;
        }
        Map<String, Object> decision = lastDecision(approvalRequestId);
        tx.executeWithoutResult(st -> {
            if (approved) {
                markApproved(id, (String) decision.get("approverId"));
            } else {
                markRejected(id, decision.get("comment"));
            }
        });
    }

    /** The approval request of a purchase request with its decisions; {@code null} when it never needed one. */
    public Map<String, Object> approval(String id) {
        requireOwned(id);
        return approvals.latestRequest(APPROVAL_ENTITY, id);
    }

    /** The most recent approval request: {@code {id, status}}. */
    private Map<String, Object> latestRequest(String id) {
        return SqlRows.one(jdbc.sql("SELECT id, status::text AS status FROM \"ApprovalRequest\" WHERE \"organizationId\""
                + " = ? AND \"entityType\" = ? AND \"entityId\" = ? ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(org(), APPROVAL_ENTITY, id));
    }

    /** Who decided last and what they said. */
    private Map<String, Object> lastDecision(String approvalRequestId) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT \"approverId\", comment FROM \"ApprovalDecision\" WHERE"
                + " \"requestId\" = ? AND \"organizationId\" = ? ORDER BY \"decidedAt\" DESC LIMIT 1")
                .params(approvalRequestId, org()));
        return row != null ? row : Obj.of("approverId", null, "comment", null);
    }

    /** Hard delete of a draft. */
    public Map<String, Object> remove(String id) {
        Map<String, Object> pr = requireOwned(id);
        if (!"draft".equals(pr.get("status"))) {
            throw ApiException.badRequest("Only drafts can be deleted");
        }
        jdbc.sql("DELETE FROM \"PurchaseRequest\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(id, org()).update();
        return Obj.of("ok", true);
    }
}
