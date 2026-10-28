package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.QcPlanInput;
import com.inventoryerp.manufacturing.application.MfgCommands.ScrapReasonInput;
import com.inventoryerp.manufacturing.domain.QcPlan;
import com.inventoryerp.manufacturing.domain.ScrapReason;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quality masters (ADR-S006 package C): scrap / waste reason codes and QC inspection plans, plus the validation the
 * production engine applies with them (reason codes when the master holds any; checklists against the order's plan).
 */
@Service
public class QualityService {

    private final MfgDb db;

    public QualityService(MfgDb db) {
        this.db = db;
    }

    // ================================================================== scrap reasons

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listReasons() {
        return db.list(ScrapReason.class, " ORDER BY code ASC");
    }

    @Transactional
    public Map<String, Object> createReason(ScrapReasonInput dto) {
        if (dto.code() == null || dto.name() == null) {
            throw ApiException.badRequest("code and name are required");
        }
        if (!db.list(ScrapReason.class, " AND code = ?", dto.code()).isEmpty()) {
            throw ApiException.conflict("Scrap reason " + dto.code() + " already exists");
        }
        return db.insert(ScrapReason.class, Obj.of("code", dto.code(), "name", dto.name(), "isActive",
                dto.isActive() != null ? dto.isActive() : true));
    }

    @Transactional
    public Map<String, Object> updateReason(String id, ScrapReasonInput dto) {
        requireReason(id);
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.code() != null) {
            data.put("code", dto.code());
        }
        if (dto.name() != null) {
            data.put("name", dto.name());
        }
        if (dto.isActive() != null) {
            data.put("isActive", dto.isActive());
        }
        return db.update(ScrapReason.class, id, data);
    }

    @Transactional
    public void removeReason(String id) {
        requireReason(id);
        db.update(ScrapReason.class, id, Obj.of("deletedAt", Instant.now(), "isActive", false));
    }

    private void requireReason(String id) {
        if (db.byId(ScrapReason.class, id) == null) {
            throw ApiException.notFound("Scrap reason not found");
        }
    }

    /** A reason code must be an active master code once the organization maintains the master. */
    void validateReason(String code) {
        if (code == null || code.isEmpty()) {
            return;
        }
        List<Map<String, Object>> active = db.list(ScrapReason.class, " AND \"isActive\" = true");
        if (!active.isEmpty() && active.stream().noneMatch(r -> code.equals(r.get("code")))) {
            throw ApiException.badRequest("Unknown scrap reason " + code + ".");
        }
    }

    // ================================================================== QC plans

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPlans(String productId) {
        return productId != null ? db.list(QcPlan.class, " AND \"productId\" = ? ORDER BY name ASC", productId)
                : db.list(QcPlan.class, " ORDER BY name ASC");
    }

    @Transactional
    public Map<String, Object> createPlan(QcPlanInput dto) {
        if (dto.name() == null) {
            throw ApiException.badRequest("name is required");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", dto.name());
        data.put("productId", dto.productId());
        data.put("bomId", dto.bomId());
        data.put("items", new MfgDb.Json(dto.items() != null ? dto.items() : List.of()));
        data.put("isActive", dto.isActive() != null ? dto.isActive() : true);
        data.put("createdBy", TenantContext.userId());
        return db.insert(QcPlan.class, data);
    }

    @Transactional
    public Map<String, Object> updatePlan(String id, QcPlanInput dto) {
        requirePlan(id);
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.name() != null) {
            data.put("name", dto.name());
        }
        if (dto.productId() != null) {
            data.put("productId", dto.productId());
        }
        if (dto.bomId() != null) {
            data.put("bomId", dto.bomId());
        }
        if (dto.items() != null) {
            data.put("items", new MfgDb.Json(dto.items()));
        }
        if (dto.isActive() != null) {
            data.put("isActive", dto.isActive());
        }
        return db.update(QcPlan.class, id, data);
    }

    @Transactional
    public void removePlan(String id) {
        requirePlan(id);
        db.update(QcPlan.class, id, Obj.of("deletedAt", Instant.now(), "isActive", false));
    }

    private void requirePlan(String id) {
        if (db.byId(QcPlan.class, id) == null) {
            throw ApiException.notFound("QC plan not found");
        }
    }

    /** The QC plan that applies to a production order (null when none). */
    @Transactional(readOnly = true)
    public Map<String, Object> planForOrder(String orderId) {
        Map<String, Object> order = db.byId(com.inventoryerp.manufacturing.domain.ProductionOrder.class, orderId);
        if (order == null) {
            throw ApiException.notFound("Production order not found");
        }
        return planFor(order);
    }

    /** The active plan of an order: one for its BOM, else one for its output product, else null. */
    Map<String, Object> planFor(Map<String, Object> order) {
        if (order.get("bomId") != null) {
            List<Map<String, Object>> byBom = db.list(QcPlan.class, " AND \"isActive\" = true AND \"bomId\" = ?"
                    + " ORDER BY \"createdAt\" DESC LIMIT 1", order.get("bomId"));
            if (!byBom.isEmpty()) {
                return byBom.getFirst();
            }
        }
        List<Map<String, Object>> byProduct = db.list(QcPlan.class, " AND \"isActive\" = true AND \"productId\" = ?"
                + " AND \"bomId\" IS NULL ORDER BY \"createdAt\" DESC LIMIT 1", order.get("outputProductId"));
        return byProduct.isEmpty() ? null : byProduct.getFirst();
    }

    /**
     * Validate a submitted checklist ({@code [{key, value}]}) against the order's plan items
     * ({@code [{key, label, type, required, min, max}]}): required items present, numbers within range, booleans
     * boolean. No plan = no validation (Nest behaviour).
     */
    void validateChecklist(Map<String, Object> order, List<Object> checklist) {
        Map<String, Object> plan = planFor(order);
        if (plan == null || !(plan.get("items") instanceof JsonNode items) || !items.isArray()) {
            return;
        }
        Map<String, Object> answers = new HashMap<>();
        for (Object o : checklist != null ? checklist : List.of()) {
            if (o instanceof Map<?, ?> m && m.get("key") != null) {
                answers.put(String.valueOf(m.get("key")), m.get("value"));
            }
        }
        for (JsonNode item : items) {
            String key = text(item, "key");
            String label = text(item, "label") != null ? text(item, "label") : key;
            Object value = answers.get(key);
            if (value == null) {
                if (item.has("required") && item.get("required").asBoolean()) {
                    throw ApiException.badRequest("Checklist item " + label + " is required.");
                }
                continue;
            }
            String type = text(item, "type") != null ? text(item, "type") : "text";
            if ("number".equals(type)) {
                double v;
                try {
                    v = Double.parseDouble(String.valueOf(value));
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("Checklist item " + label + " must be a number.");
                }
                boolean low = item.has("min") && item.get("min").isNumber() && v < item.get("min").asDouble();
                boolean high = item.has("max") && item.get("max").isNumber() && v > item.get("max").asDouble();
                if (low || high) {
                    throw ApiException.badRequest("Checklist item " + label + " is out of range.");
                }
            } else if ("boolean".equals(type) && !(value instanceof Boolean)) {
                throw ApiException.badRequest("Checklist item " + label + " must be true or false.");
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
