package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Emergency purchases (ADR-PROCUREMENT-001 wave 6): a breakdown at the crusher cannot wait for an RFQ. The buyer flags
 * the order as an emergency with a reason; it goes through the normal approval but is listed for an after-the-fact
 * review by someone else ({@code emergency_purchase:review}), so the shortcut stays visible and accountable.
 */
@Service
public class EmergencyPurchaseService {

    private final JdbcClient jdbc;
    private final AuditService audit;

    public EmergencyPurchaseService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Flag an order (not cancelled) as an emergency purchase. */
    public Map<String, Object> mark(String poId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("Say why this purchase could not follow the normal process");
        }
        int n = jdbc.sql("UPDATE \"PurchaseOrder\" SET \"isEmergency\" = true, \"emergencyReason\" = ?, \"emergencyMarkedBy\""
                + " = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL AND"
                + " status::text <> 'cancelled'").params(reason.trim(), user(), poId, org()).update();
        if (n == 0) {
            throw ApiException.notFound("Purchase order not found");
        }
        audit.record(AuditInput.of("PurchaseOrder", poId, "update", null, Obj.of("isEmergency", true, "reason",
                reason.trim())));
        return get(poId);
    }

    /** Record the review (not by the buyer who flagged it or created the order). */
    public Map<String, Object> review(String poId, String note) {
        Map<String, Object> po = get(poId);
        if (!Boolean.TRUE.equals(po.get("isEmergency"))) {
            throw ApiException.badRequest("This order is not an emergency purchase");
        }
        if (po.get("emergencyReviewedAt") != null) {
            throw ApiException.badRequest("This emergency purchase was already reviewed");
        }
        String me = user();
        if (me != null && (me.equals(po.get("emergencyMarkedBy")) || me.equals(po.get("createdBy")))) {
            throw ApiException.forbidden("An emergency purchase must be reviewed by someone other than the buyer");
        }
        jdbc.sql("UPDATE \"PurchaseOrder\" SET \"emergencyReviewedBy\" = ?, \"emergencyReviewedAt\" = " + NOW
                + ", \"emergencyReviewNote\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(me, note, poId, org()).update();
        audit.record(AuditInput.of("PurchaseOrder", poId, "approve", null, Obj.of("emergencyReview", note)));
        return get(poId);
    }

    /** Emergency orders, unreviewed first. */
    public List<Map<String, Object>> report(Boolean reviewed) {
        return SqlRows.list(jdbc.sql("SELECT o.id, o.\"orderNumber\", o.status::text AS status, o.\"orderDate\","
                        + " o.\"totalAmount\"::text AS \"totalAmount\", o.\"currencyCode\", o.\"emergencyReason\","
                        + " o.\"emergencyMarkedBy\", o.\"emergencyReviewedBy\", o.\"emergencyReviewedAt\","
                        + " o.\"emergencyReviewNote\", p.name AS \"partnerName\" FROM \"PurchaseOrder\" o LEFT JOIN \"Partner\" p"
                        + " ON p.id = o.\"partnerId\" WHERE o.\"organizationId\" = ? AND o.\"isEmergency\" AND o.\"deletedAt\""
                        + " IS NULL AND (?::boolean IS NULL OR (o.\"emergencyReviewedAt\" IS NOT NULL) = ?) ORDER BY"
                        + " o.\"emergencyReviewedAt\" NULLS FIRST, o.\"orderDate\" DESC LIMIT 500")
                .params(org(), reviewed, reviewed));
    }

    private Map<String, Object> get(String poId) {
        Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT id, \"orderNumber\", \"createdBy\", \"isEmergency\","
                + " \"emergencyReason\", \"emergencyMarkedBy\", \"emergencyReviewedBy\", \"emergencyReviewedAt\","
                + " \"emergencyReviewNote\" FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(poId, org()));
        if (po == null) {
            throw ApiException.notFound("Purchase order not found");
        }
        return po;
    }
}
