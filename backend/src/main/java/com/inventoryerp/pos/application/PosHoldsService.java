package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.num;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.pos.domain.PosHold;
import com.inventoryerp.pos.domain.PosHoldLine;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Parked carts (port of {@code pos-holds.service.ts}). */
@Service
public class PosHoldsService {

    private final PosDb db;
    private final AuditService audit;
    private final EventBus events;
    private final TransactionTemplate tx;

    public PosHoldsService(PosDb db, AuditService audit, EventBus events, PlatformTransactionManager txm) {
        this.db = db;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
    }

    private Map<String, Object> withLines(Map<String, Object> hold) {
        hold.put("lines", db.list(PosHoldLine.class, "\"holdId\" = ? ORDER BY \"lineNumber\" ASC", hold.get("id")));
        return hold;
    }

    public Map<String, Object> create(Map<String, Object> dto) {
        String orgId = org();
        List<Map<String, Object>> lines = PosOrdersService.maps(dto.get("lines"));
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Hold must contain at least one line");
        }
        String name = dto.get("name") == null ? "" : dto.get("name").toString().trim();
        if (name.isEmpty()) {
            throw ApiException.badRequest("Hold name is required");
        }
        return tx.execute(st -> {
            double total = 0;
            for (Map<String, Object> ln : lines) {
                total += num(ln.get("quantity")) * num(ln.get("unitPrice")) * (1 - num(ln.get("discountPercent")) / 100);
            }
            Map<String, Object> hold = db.insert(PosHold.class, Obj.of("organizationId", orgId, "name", name,
                    "partnerId", dto.get("partnerId"), "branchId", dto.get("branchId"), "cashSessionId", dto.get("cashSessionId"),
                    "notes", dto.get("notes"), "totalAmount", PosSql.decOf(total), "heldById", user()));
            int i = 0;
            for (Map<String, Object> ln : lines) {
                db.insert(PosHoldLine.class, Obj.of("organizationId", orgId, "holdId", hold.get("id"),
                        "productId", ln.get("productId"), "description", ln.get("description"),
                        "quantity", PosSql.dec(ln.get("quantity")), "unitPrice", PosSql.dec(ln.get("unitPrice")),
                        "discountPercent", ln.get("discountPercent") == null ? 0 : PosSql.dec(ln.get("discountPercent")),
                        "taxId", ln.get("taxId"), "lineNumber", ++i, "note", ln.get("note")));
            }
            String totalStr = JsFormat.str(total);
            audit.recordInTx(AuditInput.of("PosHold", (String) hold.get("id"), "create", null, Obj.of("name", hold.get("name"),
                    "totalAmount", totalStr)));
            events.publish(DomainEvents.POS_HOLD_CREATED, Obj.of("organizationId", orgId, "holdId", hold.get("id"),
                    "name", hold.get("name"), "total", totalStr, "heldById", user() == null ? "" : user()));
            return withLines(hold);
        });
    }

    public List<Map<String, Object>> list(String status, String branchId, String cashSessionId) {
        StringBuilder where = new StringBuilder("\"organizationId\" = ? AND status = ?::\"PosHoldStatus\"");
        List<Object> params = new ArrayList<>(List.of(org(), status != null && !status.isEmpty() ? status : "open"));
        if (branchId != null && !branchId.isEmpty()) {
            where.append(" AND \"branchId\" = ?");
            params.add(branchId);
        }
        if (cashSessionId != null && !cashSessionId.isEmpty()) {
            where.append(" AND \"cashSessionId\" = ?");
            params.add(cashSessionId);
        }
        List<Map<String, Object>> rows = SqlRows.list(PosHold.class, db.jdbc().sql("SELECT * FROM \"PosHold\" WHERE " + where
                + " ORDER BY \"createdAt\" DESC LIMIT 100").params(params));
        rows.forEach(this::withLines);
        return rows;
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> hold = db.first(PosHold.class, "id = ? AND \"organizationId\" = ?", id, org());
        if (hold == null) {
            throw ApiException.notFound("Hold not found");
        }
        return withLines(hold);
    }

    private Map<String, Object> openHold(String id) {
        Map<String, Object> existing = db.first(PosHold.class, "id = ? AND \"organizationId\" = ?", id, org());
        if (existing == null) {
            throw ApiException.notFound("Hold not found");
        }
        if (!"open".equals(existing.get("status"))) {
            throw ApiException.badRequest("Hold is " + existing.get("status") + ", not open");
        }
        return existing;
    }

    public Map<String, Object> recall(String id) {
        String orgId = org();
        return tx.execute(st -> {
            openHold(id);
            Map<String, Object> updated = db.update(PosHold.class, Obj.of("status", "recalled", "recalledById", user(),
                    "recalledAt", PosDb.raw(NOW)), "id = ? AND \"organizationId\" = ?", id, orgId);
            audit.recordInTx(AuditInput.of("PosHold", id, "update", Obj.of("status", "open"),
                    Obj.of("status", "recalled", "recalledById", user())));
            events.publish(DomainEvents.POS_HOLD_RECALLED, Obj.of("organizationId", orgId, "holdId", id,
                    "recalledById", user() == null ? "" : user()));
            return withLines(updated);
        });
    }

    public Map<String, Object> cancel(String id) {
        String orgId = org();
        return tx.execute(st -> {
            openHold(id);
            Map<String, Object> updated = db.update(PosHold.class, Obj.of("status", "cancelled", "cancelledById", user(),
                    "cancelledAt", PosDb.raw(NOW)), "id = ? AND \"organizationId\" = ?", id, orgId);
            audit.recordInTx(AuditInput.of("PosHold", id, "update", Obj.of("status", "open"),
                    Obj.of("status", "cancelled", "cancelledById", user())));
            events.publish(DomainEvents.POS_HOLD_DELETED, Obj.of("organizationId", orgId, "holdId", id));
            return updated;
        });
    }

    /** Untyped body {@code { notes }}. */
    public Map<String, Object> updateNotes(String id, Object notes) {
        Map<String, Object> hold = db.first(PosHold.class, "id = ? AND \"organizationId\" = ?", id, org());
        if (hold == null) {
            throw ApiException.notFound("Hold not found");
        }
        if (!"open".equals(hold.get("status"))) {
            throw ApiException.badRequest("Hold is not open");
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (notes != null) {
            data.put("notes", notes);
        }
        return db.update(PosHold.class, data, "id = ? AND \"organizationId\" = ?", id, org());
    }
}
