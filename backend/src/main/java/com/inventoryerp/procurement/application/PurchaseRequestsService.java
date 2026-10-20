package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
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
 */
@Service
public class PurchaseRequestsService {

    /** {@code CreatePRInput}. */
    public record CreateInput(String partnerId, String branchId, String description, String neededBy,
            List<LineInput> lines) {}

    /** One {@code PRLineDto}. */
    public record LineInput(String productId, String description, BigDecimal quantity, String unitOfMeasureId,
            BigDecimal estimatedUnitPrice, String notes) {}

    private final JdbcClient jdbc;
    private final SequenceService sequence;
    private final EventBus events;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public PurchaseRequestsService(JdbcClient jdbc, SequenceService sequence, EventBus events, AuditService audit,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
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
                        + " \"organizationId\", \"requestNumber\", \"requestedById\", \"partnerId\", \"branchId\", description,"
                        + " \"neededBy\", status, \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                        + " 'draft', ?, " + NOW + ", " + NOW + ") RETURNING *")
                .params(id, orgId, requestNumber, user(), input.partnerId(), input.branchId(), input.description(),
                        SqlRows.ts(ProcurementSql.date(input.neededBy())), user()));
        List<Map<String, Object>> lines = new ArrayList<>();
        int idx = 0;
        for (LineInput ln : input.lines()) {
            lines.add(SqlRows.one(PurchaseRequestLine.class, jdbc.sql("INSERT INTO \"PurchaseRequestLine\" (id,"
                            + " \"organizationId\", \"purchaseRequestId\", \"productId\", description, quantity,"
                            + " \"unitOfMeasureId\", \"estimatedUnitPrice\", notes, \"lineNumber\") VALUES (?, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?, ?) RETURNING *")
                    .params(UUID.randomUUID().toString(), orgId, id, ln.productId(), ln.description(), ln.quantity(),
                            ln.unitOfMeasureId(), ln.estimatedUnitPrice(), ln.notes(), ++idx)));
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
        Map<String, Object> updated = update(id, "status = 'submitted'");
        audit.record(AuditInput.of("PurchaseRequest", id, "update", null, Obj.of("status", "submitted")));
        events.publish("purchase_request.submitted", Obj.of("organizationId", org(), "requestId", id));
        return updated;
    }

    /** submitted → approved. */
    public Map<String, Object> approve(String id) {
        Map<String, Object> pr = requireOwned(id);
        if (!"submitted".equals(pr.get("status"))) {
            throw ApiException.badRequest("Cannot approve PR in status " + pr.get("status"));
        }
        Map<String, Object> updated = update(id, "status = 'approved', \"approvedAt\" = " + NOW + ", \"approvedById\" = ?",
                user());
        audit.record(AuditInput.of("PurchaseRequest", id, "approve", null, Obj.of("status", "approved")));
        events.publish("purchase_request.approved", ProcurementSql.defined("organizationId", org(), "requestId", id,
                "approverId", user()));
        return updated;
    }

    /** draft | submitted → rejected. */
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> pr = requireOwned(id);
        if (!List.of("submitted", "draft").contains(pr.get("status"))) {
            throw ApiException.badRequest("Cannot reject PR in status " + pr.get("status"));
        }
        Map<String, Object> updated = update(id, "status = 'rejected', \"rejectedReason\" = ?", reason);
        events.publish("purchase_request.rejected", Obj.of("organizationId", org(), "requestId", id, "reason", reason));
        return updated;
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
