package com.inventoryerp.repair.application;

import static com.inventoryerp.repair.application.RepairDb.dateOrNull;
import static com.inventoryerp.repair.application.RepairDb.nz;

import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.workflow.LifecycleRegistry;
import com.inventoryerp.kernel.workflow.LifecycleRegistry.Transition;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.repair.domain.RepairAttachment;
import com.inventoryerp.repair.domain.RepairDiagnosis;
import com.inventoryerp.repair.domain.RepairJob;
import com.inventoryerp.repair.domain.RepairOrder;
import com.inventoryerp.repair.domain.RepairOrderItem;
import com.inventoryerp.repair.domain.RepairPart;
import com.inventoryerp.repair.domain.RepairQuotation;
import com.inventoryerp.repair.domain.RepairQuotationLine;
import com.inventoryerp.repair.domain.RepairStatusHistory;
import com.inventoryerp.repair.domain.RepairWarranty;
import com.inventoryerp.repair.domain.RepairWarrantyClaim;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The RMMS spine (port of {@code repair.service.ts}): repair orders, their lifecycle transitions and the
 * status-history audit trail. Billing goes through {@link RepairPostingService}, parts through the inventory stock
 * engine at issue time; this service owns the document and the state machine only.
 */
@Service
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairService {

    private static final List<String> ORDER_SEARCH = List.of("repairNumber", "serialNumber", "imei", "brand", "model",
            "itemType");
    private static final List<String> ITEM_FIELDS = List.of("itemType", "brand", "model", "serialNumber", "imei",
            "assetTag", "plateNumber", "vin", "mileage", "color", "storage", "condition");

    private final RepairDb db;
    private final SequenceService seq;
    private final LifecycleRegistry lifecycles;

    public RepairService(RepairDb db, SequenceService seq, LifecycleRegistry lifecycles) {
        this.db = db;
        this.seq = seq;
        this.lifecycles = lifecycles;
    }

    // ─── orders ───

    /** {@code {rows, total}}, newest first, each with its items. */
    public Map<String, Object> listOrders(Map<String, String> query) {
        RepairDb.Where w = db.where(RepairOrder.class)
                .eqIf("status", query.get("status"))
                .eqIf("priority", query.get("priority"))
                .eqIf("orderType", query.get("orderType"))
                .eqIf("partnerId", query.get("partnerId"))
                .eqIf("technicianId", query.get("technicianId"));
        String search = query.get("search");
        if (PosSql.truthy(search)) {
            List<String> ors = new ArrayList<>();
            List<Object> p = new ArrayList<>();
            for (String c : ORDER_SEARCH) {
                ors.add("\"" + c + "\" ILIKE ?");
                p.add(PosSql.contains(search));
            }
            w.and(String.join(" OR ", ors), p.toArray());
        }
        int take = RepairDb.take(query.get("take"), 50, 200);
        int skip = RepairDb.skip(query.get("skip"));
        List<Map<String, Object>> rows = w.list("\"createdAt\" DESC", take, skip);
        for (Map<String, Object> r : rows) {
            r.put("items", db.many(RepairOrderItem.class, "repairOrderId", r.get("id"), null));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", w.count());
        return out;
    }

    /** The order with items, diagnosis, quotations (+ lines), jobs, parts, history, attachments, warranties. */
    public Map<String, Object> getOrder(String id) {
        Map<String, Object> order = db.byId(RepairOrder.class, id);
        if (order == null) {
            throw ApiException.notFound("Repair order not found");
        }
        order.put("items", db.many(RepairOrderItem.class, "repairOrderId", id, "\"lineNumber\" ASC"));
        order.put("diagnosis", db.many(RepairDiagnosis.class, "repairOrderId", id, "\"diagnosedAt\" DESC"));
        List<Map<String, Object>> quotations = db.many(RepairQuotation.class, "repairOrderId", id,
                "\"createdAt\" DESC");
        for (Map<String, Object> q : quotations) {
            q.put("lines", db.many(RepairQuotationLine.class, "quotationId", q.get("id"), "\"lineNumber\" ASC"));
        }
        order.put("quotations", quotations);
        order.put("jobs", db.many(RepairJob.class, "repairOrderId", id, "\"createdAt\" DESC"));
        order.put("parts", db.many(RepairPart.class, "repairOrderId", id, "\"createdAt\" DESC"));
        order.put("statusHistory", db.many(RepairStatusHistory.class, "repairOrderId", id, "\"changedAt\" DESC"));
        order.put("attachments", db.many(RepairAttachment.class, "repairOrderId", id, "\"createdAt\" DESC"));
        List<Map<String, Object>> warranties = db.many(RepairWarranty.class, "repairOrderId", id, null);
        for (Map<String, Object> w : warranties) {
            w.put("claims", db.many(RepairWarrantyClaim.class, "warrantyId", w.get("id"), null));
        }
        order.put("warranties", warranties);
        return order;
    }

    /** Receive an item: {@code RPR-} number, optional item lines, the {@code received} history row, a notification. */
    public Map<String, Object> createOrder(Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        String orgId = RepairDb.org();
        String userId = RepairDb.user();
        String repairNumber = seq.next("repair_order", "RPR-", 6);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", orgId);
        data.put("repairNumber", repairNumber);
        data.put("partnerId", dto.get("partnerId"));
        data.put("branchId", dto.get("branchId"));
        data.put("priority", nz(dto.get("priority"), "medium"));
        data.put("orderType", nz(dto.get("orderType"), "repair"));
        for (String f : ITEM_FIELDS) {
            data.put(f, dto.get(f));
        }
        data.put("accessories", nz(dto.get("accessories"), List.of()));
        data.put("problemDescription", dto.get("problemDescription"));
        data.put("dueDate", dateOrNull(dto.get("dueDate")));
        data.put("technicianId", dto.get("technicianId"));
        data.put("notes", dto.get("notes"));
        data.put("createdBy", userId);
        Map<String, Object> order = db.inTx(() -> {
            Map<String, Object> created = db.insert(RepairOrder.class, data);
            List<Object> items = dto.get("items") instanceof List<?> l ? new ArrayList<>(l) : List.of();
            for (int idx = 0; idx < items.size(); idx++) {
                Map<String, Object> it = items.get(idx) instanceof Map<?, ?> ? PosSql.map(items.get(idx)) : Map.of();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("organizationId", orgId);
                row.put("repairOrderId", created.get("id"));
                for (String f : ITEM_FIELDS) {
                    row.put(f, it.get(f));
                }
                row.put("accessories", nz(it.get("accessories"), List.of()));
                row.put("problemDescription", it.get("problemDescription"));
                row.put("lineNumber", idx);
                db.insert(RepairOrderItem.class, row);
            }
            db.insert(RepairStatusHistory.class, Obj.of("organizationId", orgId, "repairOrderId", created.get("id"),
                    "toStatus", "received", "action", "create", "note", "Order received", "changedBy", userId));
            return created;
        });
        db.notify(orgId, "Repair order " + repairNumber,
                "Item received — " + RepairDb.s(nz(dto.get("itemType"), "item")) + " ("
                        + RepairDb.s(nz(dto.get("serialNumber"), "no serial")) + ")",
                Obj.of("repairOrderId", order.get("id"), "repairNumber", repairNumber));
        return getOrder((String) order.get("id"));
    }

    // ─── lifecycle transitions ───

    Map<String, Object> transition(String id, String to, String action, Object note, Map<String, Object> extra) {
        String orgId = RepairDb.org();
        String userId = RepairDb.user();
        Map<String, Object> order = db.byId(RepairOrder.class, id);
        if (order == null) {
            throw ApiException.notFound("Repair order not found");
        }
        String from = (String) order.get("status");
        lifecycles.assertTransition(RepairLifecycles.KEY, from, to, action);
        return db.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", to);
            data.put("updatedBy", userId);
            Instant now = Instant.now();
            String stamp = switch (to) {
                case "diagnosis" -> "diagnosedAt";
                case "waiting_approval" -> "quotedAt";
                case "approved" -> "approvedAt";
                case "repairing" -> "startedAt";
                case "testing" -> "testedAt";
                case "ready_pickup" -> "readyAt";
                case "delivered" -> "deliveredAt";
                case "closed" -> "closedAt";
                case "cancelled" -> "cancelledAt";
                default -> null;
            };
            if (stamp != null) {
                data.put(stamp, now);
            }
            if (extra != null) {
                data.putAll(extra);
            }
            Map<String, Object> updated = db.update(RepairOrder.class, id, data);
            db.insert(RepairStatusHistory.class, Obj.of("organizationId", orgId, "repairOrderId", id,
                    "fromStatus", from, "toStatus", to, "action", action, "note", note, "changedBy", userId));
            return updated;
        });
    }

    /**
     * Move the order to {@code status} (or {@code to}); the lifecycle action is resolved from (from → to) when not
     * given. Sends an in-app notification.
     */
    public Map<String, Object> transitionOrder(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Object toRaw = nz(dto.get("status"), dto.get("to"));
        if (!PosSql.truthy(toRaw)) {
            throw ApiException.badRequest("status is required");
        }
        String to = toRaw.toString();
        Object actionRaw = dto.get("action");
        String action;
        if (PosSql.truthy(actionRaw)) {
            action = actionRaw.toString();
        } else {
            Map<String, Object> order = db.byId(RepairOrder.class, id);
            Object status = order == null ? null : order.get("status");
            Transition found = null;
            for (Transition t : RepairLifecycles.REPAIR_ORDER_LIFECYCLE.transitions()) {
                if ((t.from() == null || t.from().equals(status)) && t.to().equals(to)) {
                    found = t;
                    break;
                }
            }
            action = found != null ? found.action() : "transition_to_" + to;
        }
        Map<String, Object> extra = dto.get("extra") instanceof Map<?, ?> ? PosSql.map(dto.get("extra")) : Map.of();
        Map<String, Object> updated = transition(id, to, action, dto.get("note"), extra);
        Map<String, Object> order = getOrder(id);
        Map<String, Object> partner = order.get("partnerId") == null ? null
                : db.byId(Partner.class, order.get("partnerId"));
        Object noteOrAction = nz(dto.get("note"), dto.get("action"));
        String body;
        if (partner != null && PosSql.truthy(partner.get("name"))) {
            body = partner.get("name") + " — " + (noteOrAction == null ? "undefined" : RepairDb.s(noteOrAction));
        } else {
            body = noteOrAction == null ? "" : RepairDb.s(noteOrAction);
        }
        Map<String, Object> payload = Obj.of("repairOrderId", id, "repairNumber", order.get("repairNumber"));
        if (dto.get("to") != null) {
            payload.put("status", dto.get("to"));
        }
        db.notify(RepairDb.org(), order.get("repairNumber") + " → " + to.replaceFirst("_", " "), body, payload);
        return updated;
    }

    public Map<String, Object> assignTechnician(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        if (db.byId(RepairOrder.class, id) == null) {
            throw ApiException.notFound("Repair order not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("technicianId")) {
            data.put("technicianId", dto.get("technicianId"));
        }
        data.put("updatedBy", RepairDb.user());
        return db.update(RepairOrder.class, id, data);
    }

    // ─── items ───

    public Map<String, Object> addItem(String orderId, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        String orgId = RepairDb.org();
        if (db.byId(RepairOrder.class, orderId) == null) {
            throw ApiException.notFound("Repair order not found");
        }
        long lineNumber = db.where(RepairOrderItem.class).eq("repairOrderId", orderId).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", orgId);
        data.put("repairOrderId", orderId);
        data.put("lineNumber", (int) lineNumber);
        data.putAll(dto);
        return db.insert(RepairOrderItem.class, data);
    }

    public Map<String, Object> removeItem(String itemId) {
        return db.where(RepairOrderItem.class).eq("id", itemId).deleteMany();
    }

    // ─── attachments ───

    public Map<String, Object> addAttachment(String orderId, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        return db.insert(RepairAttachment.class, Obj.of("organizationId", RepairDb.org(), "repairOrderId", orderId,
                "kind", nz(dto.get("kind"), "receipt"), "fileId", dto.get("fileId"), "caption", dto.get("caption"),
                "url", dto.get("url"), "createdBy", RepairDb.user()));
    }

    public Map<String, Object> removeAttachment(String id) {
        return db.where(RepairAttachment.class).eq("id", id).deleteMany();
    }

    /** A free-text note on the order's history (at its current status). */
    public Map<String, Object> addNote(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Object note = dto.get("note");
        if (note == null || note.toString().trim().isEmpty()) {
            throw ApiException.badRequest("Note is required");
        }
        Map<String, Object> order = db.byId(RepairOrder.class, id);
        return db.insert(RepairStatusHistory.class, Obj.of("organizationId", RepairDb.org(), "repairOrderId", id,
                "toStatus", order == null || order.get("status") == null ? "received" : order.get("status"),
                "action", "note", "note", note, "changedBy", RepairDb.user()));
    }
}
