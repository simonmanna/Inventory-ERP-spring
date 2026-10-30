package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer complaints / service requests (ADR-S011 S7) and quality certificates. A complaint can lead to a return
 * (RMA); an approved fleet delivery discrepancy on a sales order opens a quantity complaint automatically.
 */
@Service
public class SalesComplaintService {

    static final Set<String> CATEGORIES = Set.of("quality", "quantity", "delivery", "billing", "other");
    static final Set<String> STATUSES = Set.of("open", "investigating", "resolved", "closed");

    private final SalesDb db;
    private final SequenceService sequence;
    private final AuditService audit;

    public SalesComplaintService(SalesDb db, SequenceService sequence, AuditService audit) {
        this.db = db;
        this.sequence = sequence;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Map<String, String> p) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ?");
        for (String k : List.of("partnerId", "orderId", "status", "category")) {
            if (!blank(p.get(k))) {
                where.append(" AND c.\"").append(k).append("\" = ?");
                params.add(p.get(k));
            }
        }
        return db.rows("SELECT c.*, pa.name AS \"partnerName\", o.number AS \"orderNumber\", r.number AS \"returnNumber\""
                + " FROM \"CustomerComplaint\" c LEFT JOIN \"Partner\" pa ON pa.id = c.\"partnerId\" LEFT JOIN \"SalesOrder\""
                + " o ON o.id = c.\"orderId\" LEFT JOIN \"SalesReturn\" r ON r.id = c.\"returnId\"" + where
                + " ORDER BY c.\"createdAt\" DESC LIMIT 500", params);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        String partnerId = str(in.get("partnerId"));
        String orderId = blank(str(in.get("orderId"))) ? null : str(in.get("orderId"));
        if (orderId != null) {
            Map<String, Object> o = db.row("SELECT \"partnerId\" FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?",
                    orderId, org());
            if (o == null) {
                throw ApiException.notFound("Sales order not found");
            }
            partnerId = str(o.get("partnerId"));
        }
        if (partnerId == null || !db.exists("Partner", partnerId)) {
            throw ApiException.notFound("Partner not found");
        }
        String category = str(in.get("category"));
        if (category == null || !CATEGORIES.contains(category)) {
            throw ApiException.badRequest("category must be one of: billing, delivery, other, quality, quantity");
        }
        if (blank(str(in.get("description")))) {
            throw ApiException.badRequest("description should not be empty");
        }
        return insert(partnerId, orderId, str(in.get("deliveryOrderId")), null, category, str(in.get("description")));
    }

    Map<String, Object> insert(String partnerId, String orderId, String deliveryOrderId, String discrepancyId,
            String category, String description) {
        int year = LocalDate.now().getYear();
        String id = SalesDb.newId();
        String number = sequence.next("customer_complaint:" + year, "CMP-" + year + "-", 5);
        db.update("INSERT INTO \"CustomerComplaint\" (id, \"organizationId\", number, \"partnerId\", \"orderId\","
                + " \"deliveryOrderId\", \"discrepancyId\", category, description, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?) ON CONFLICT DO NOTHING", id, org(), number, partnerId, orderId, deliveryOrderId, discrepancyId,
                category, description, user());
        audit.record(AuditInput.of("CustomerComplaint", id, "create", null, Obj.of("number", number, "category",
                category)));
        return db.row("SELECT * FROM \"CustomerComplaint\" WHERE id = ?", id);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        Map<String, Object> c = db.row("SELECT * FROM \"CustomerComplaint\" WHERE id = ? AND \"organizationId\" = ? FOR"
                + " UPDATE", id, org());
        if (c == null) {
            throw ApiException.notFound("Complaint not found");
        }
        if (in.containsKey("status")) {
            String s = str(in.get("status"));
            if (!STATUSES.contains(s)) {
                throw ApiException.badRequest("status must be one of: closed, investigating, open, resolved");
            }
            db.update("UPDATE \"CustomerComplaint\" SET status = ?, \"resolvedAt\" = CASE WHEN ? IN ('resolved', 'closed')"
                    + " THEN COALESCE(\"resolvedAt\", now()) ELSE NULL END WHERE id = ?", s, s, id);
        }
        if (in.containsKey("resolution")) {
            db.update("UPDATE \"CustomerComplaint\" SET resolution = ? WHERE id = ?", str(in.get("resolution")), id);
        }
        if (in.containsKey("assignedToId")) {
            db.update("UPDATE \"CustomerComplaint\" SET \"assignedToId\" = ? WHERE id = ?", str(in.get("assignedToId")), id);
        }
        db.update("UPDATE \"CustomerComplaint\" SET \"updatedAt\" = now() WHERE id = ?", id);
        audit.record(AuditInput.of("CustomerComplaint", id, "update", c, in));
        return db.row("SELECT * FROM \"CustomerComplaint\" WHERE id = ?", id);
    }

    /** Approved fleet discrepancy on a sales-order delivery → quantity complaint (idempotent per discrepancy). */
    @Transactional
    public void fromDiscrepancy(String discrepancyId, String deliveryOrderId, String code, String difference) {
        Map<String, Object> d = db.row("SELECT \"sourceType\", \"sourceId\", \"partnerId\" FROM \"FleetDeliveryOrder\""
                + " WHERE id = ? AND \"organizationId\" = ?", deliveryOrderId, org());
        if (d == null || !"sales_order".equals(d.get("sourceType")) || !db.exists("SalesOrder", str(d.get("sourceId")))) {
            return;
        }
        if (db.count("SELECT count(*) FROM \"CustomerComplaint\" WHERE \"discrepancyId\" = ?", List.of(discrepancyId))
                > 0) {
            return;
        }
        insert(str(d.get("partnerId")), str(d.get("sourceId")), deliveryOrderId, discrepancyId, "quantity",
                "Delivery discrepancy " + code + " approved (difference " + difference + ")");
    }

    // ------------------------------------------------------------------ quality certificates

    @Transactional(readOnly = true)
    public List<Map<String, Object>> certificates(String orderId) {
        return db.rows("SELECT c.*, p.name AS \"productName\" FROM \"SalesQualityCertificate\" c LEFT JOIN \"Product\" p ON"
                + " p.id = c.\"productId\" WHERE c.\"organizationId\" = ? AND (?::text IS NULL OR c.\"orderId\" = ?) ORDER BY"
                + " c.\"issuedAt\" DESC", org(), orderId, orderId);
    }

    @Transactional
    public Map<String, Object> addCertificate(Map<String, Object> in) {
        String productId = str(in.get("productId"));
        if (productId == null || !db.exists("Product", productId)) {
            throw ApiException.notFound("Product not found");
        }
        if (blank(str(in.get("certificateNumber")))) {
            throw ApiException.badRequest("certificateNumber should not be empty");
        }
        String id = SalesDb.newId();
        db.update("INSERT INTO \"SalesQualityCertificate\" (id, \"organizationId\", \"orderId\", \"deliveryOrderId\","
                + " \"productId\", \"batchNumber\", \"certificateNumber\", results, \"fileId\", \"issuedAt\", \"createdById\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, COALESCE(?, CURRENT_DATE), ?)", id, org(),
                str(in.get("orderId")), str(in.get("deliveryOrderId")), productId, str(in.get("batchNumber")),
                str(in.get("certificateNumber")), SalesDb.toJson(in.get("results") == null ? Map.of() : in.get("results")),
                str(in.get("fileId")), SalesDb.date(str(in.get("issuedAt"))), user());
        audit.record(AuditInput.of("SalesQualityCertificate", id, "create", null, Obj.of("certificateNumber",
                in.get("certificateNumber"))));
        return db.row("SELECT * FROM \"SalesQualityCertificate\" WHERE id = ?", id);
    }
}
