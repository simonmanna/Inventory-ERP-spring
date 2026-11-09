package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.PlmCommands.ConvertRequest;
import com.inventoryerp.manufacturing.application.PlmCommands.CreateChange;
import com.inventoryerp.manufacturing.application.PlmCommands.ReviewRequest;
import com.inventoryerp.manufacturing.application.PlmCommands.SaveRequest;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.PlmChangeRequest;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Engineering change requests (ADR-S063): anyone with {@code plm:request} raises one (cost reduction, quality
 * issue, customer, supplier, regulatory…); a reviewer accepts or rejects it, and an accepted (or new) request is
 * converted into an ECO of the chosen type.
 */
@Service
public class PlmRequestService {

    private final MfgDb db;
    private final PlmSupport plm;
    private final SequenceService seq;
    private final PlmChangeService changes;

    public PlmRequestService(MfgDb db, PlmSupport plm, SequenceService seq, PlmChangeService changes) {
        this.db = db;
        this.plm = plm;
        this.seq = seq;
        this.changes = changes;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String productId, String q) {
        StringBuilder sql = new StringBuilder("SELECT r.*, e.number AS \"changeNumber\", e.status AS \"changeStatus\""
                + " FROM \"PlmChangeRequest\" r LEFT JOIN \"EngineeringChange\" e ON e.id = r.\"changeId\""
                + " WHERE r.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if ("open".equals(status)) {
            sql.append(" AND r.status IN ('new', 'accepted')");
        } else if (status != null && !status.isBlank()) {
            sql.append(" AND r.status = ?");
            params.add(status);
        }
        if (productId != null && !productId.isBlank()) {
            sql.append(" AND r.\"productId\" = ?");
            params.add(productId);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (r.number ILIKE ? OR r.title ILIKE ?)");
            params.add("%" + q.trim() + "%");
            params.add("%" + q.trim() + "%");
        }
        sql.append(" ORDER BY CASE r.priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 WHEN 'normal' THEN 2 ELSE 3 END,"
                + " r.\"createdAt\" DESC LIMIT 500");
        return decorate(SqlRows.list(db.jdbc().sql(sql.toString()).params(params)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return decorate(List.of(new LinkedHashMap<>(require(id)))).getFirst();
    }

    @Transactional
    public Map<String, Object> create(SaveRequest dto) {
        String title = PlmSupport.blank(dto.title());
        if (title == null) {
            throw ApiException.badRequest("title should not be empty");
        }
        Map<String, Object> data = refs(dto, new LinkedHashMap<>());
        data.put("number", seq.next("plm_change_request", "ECR-", 5));
        data.put("title", title);
        data.put("reasonCode", dto.reasonCode() == null ? "other" : dto.reasonCode());
        data.put("priority", dto.priority() == null ? "normal" : dto.priority());
        data.put("status", "new");
        data.put("requestedById", TenantContext.userId());
        Map<String, Object> r = db.insert(PlmChangeRequest.class, data);
        plm.event(DomainEvents.PLM_CHANGE_REQUEST_CREATED, Obj.of("requestId", r.get("id"), "number",
                r.get("number"), "productId", r.get("productId"), "priority", r.get("priority")));
        return r;
    }

    /** Edit while {@code new}: only the requester or a reviewer ({@code plm:approve}). */
    @Transactional
    public Map<String, Object> update(String id, SaveRequest dto) {
        Map<String, Object> r = require(id);
        if (!"new".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a new change request can be edited (status " + r.get("status") + ").");
        }
        if (!TenantContext.userId().equals(r.get("requestedById"))
                && !TenantContext.has(com.inventoryerp.kernel.security.Permissions.PLM__APPROVE)) {
            throw ApiException.forbidden("Only the requester or a reviewer can edit this change request.");
        }
        Map<String, Object> data = refs(dto, new LinkedHashMap<>());
        if (dto.title() != null) {
            String t = PlmSupport.blank(dto.title());
            if (t == null) {
                throw ApiException.badRequest("title should not be empty");
            }
            data.put("title", t);
        }
        if (dto.reasonCode() != null) {
            data.put("reasonCode", dto.reasonCode());
        }
        if (dto.priority() != null) {
            data.put("priority", dto.priority());
        }
        return data.isEmpty() ? r : db.update(PlmChangeRequest.class, id, data);
    }

    @Transactional
    public Map<String, Object> review(String id, ReviewRequest dto) {
        Map<String, Object> r = require(id);
        if (!"new".equals(r.get("status"))) {
            throw ApiException.badRequest("Change request " + r.get("number") + " was already reviewed (status "
                    + r.get("status") + ").");
        }
        String note = PlmSupport.blank(dto.note());
        if ("reject".equals(dto.decision()) && note == null) {
            throw ApiException.badRequest("Give a reason to reject the change request.");
        }
        return db.update(PlmChangeRequest.class, id, Obj.of("status", "accept".equals(dto.decision()) ? "accepted"
                : "rejected", "reviewedById", TenantContext.userId(), "reviewedAt", Instant.now(), "decisionNote",
                note));
    }

    /** Create the ECO for a new / accepted request (the ECO links back and the request becomes converted). */
    @Transactional
    public Map<String, Object> convert(String id, ConvertRequest dto) {
        Map<String, Object> r = require(id);
        if (!Set.of("new", "accepted").contains(str(r, "status"))) {
            throw ApiException.badRequest("Change request " + r.get("number") + " is " + r.get("status") + ".");
        }
        String bomId = PlmSupport.blank(dto.bomId()) != null ? dto.bomId() : str(r, "bomId");
        if ("bom".equals(dto.type()) && bomId == null && r.get("productId") != null) {
            bomId = db.jdbc().sql("SELECT id FROM \"Bom\" WHERE \"organizationId\" = ? AND \"outputProductId\" = ? AND"
                    + " status::text = 'active' AND \"deletedAt\" IS NULL ORDER BY \"isDefault\" DESC, version DESC"
                    + " LIMIT 1").params(MfgDb.org(), r.get("productId")).query(String.class).optional().orElse(null);
        }
        String title = PlmSupport.blank(dto.title()) != null ? dto.title().trim() : str(r, "title");
        return changes.create(new CreateChange(dto.type(), title, str(r, "description"), reasonLabel(str(r,
                "reasonCode")), str(r, "priority"), str(r, "productId"), bomId, dto.routingId(),
                dto.targetLifecycleState(), dto.documentIds(), null, null, null, null, null, null, id));
    }

    // ---- internals -------------------------------------------------------------------------------------------------

    private Map<String, Object> refs(SaveRequest dto, Map<String, Object> data) {
        if (dto.productId() != null) {
            String p = PlmSupport.blank(dto.productId());
            if (p != null) {
                plm.product(p);
            }
            data.put("productId", p);
        }
        if (dto.bomId() != null) {
            String b = PlmSupport.blank(dto.bomId());
            if (b != null) {
                Map<String, Object> bom = db.byId(Bom.class, b);
                if (bom == null) {
                    throw ApiException.notFound("BOM not found");
                }
                if (!data.containsKey("productId") || data.get("productId") == null) {
                    data.put("productId", bom.get("outputProductId"));
                }
            }
            data.put("bomId", b);
        }
        if (dto.description() != null) {
            data.put("description", PlmSupport.blank(dto.description()));
        }
        if (dto.dueDate() != null) {
            data.put("dueDate", PlmSupport.date(dto.dueDate()));
        }
        return data;
    }

    private List<Map<String, Object>> decorate(List<Map<String, Object>> rows) {
        Set<String> products = new HashSet<>();
        Set<String> users = new HashSet<>();
        rows.forEach(r -> {
            products.add(str(r, "productId"));
            users.add(str(r, "requestedById"));
            users.add(str(r, "reviewedById"));
        });
        Map<String, Map<String, Object>> p = plm.products(products);
        Map<String, String> names = plm.userNames(users);
        java.time.LocalDate today = plm.orgToday();
        rows.forEach(r -> {
            Object due = r.get("dueDate");
            r.put("overdue", due != null && Set.of("new", "accepted").contains(str(r, "status"))
                    && java.time.LocalDate.parse(due.toString().substring(0, 10)).isBefore(today));
            r.put("product", p.get(str(r, "productId")));
            r.put("requestedByName", names.get(str(r, "requestedById")));
            r.put("reviewedByName", names.get(str(r, "reviewedById")));
        });
        return rows;
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> r = db.byId(PlmChangeRequest.class, id);
        if (r == null) {
            throw ApiException.notFound("Change request not found");
        }
        return r;
    }

    private static String reasonLabel(String code) {
        return code == null ? null : code.replace('_', ' ');
    }
}
