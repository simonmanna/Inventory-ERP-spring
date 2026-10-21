package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.pos.domain.MenuItem;
import com.inventoryerp.pos.domain.MenuItemVariant;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Menu item variants (port of {@code pos-variant.service.ts}). */
@Service
public class PosVariantService {

    private static final String LIVE = PosMenuService.LIVE;

    private final PosDb db;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public PosVariantService(PosDb db, AuditService audit, PlatformTransactionManager txm) {
        this.db = db;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    static Map<String, Object> view(Map<String, Object> v) {
        return Obj.of("id", v.get("id"), "menuItemId", v.get("menuItemId"), "name", v.get("name"),
                "price", PosSql.js(v.get("price")), "sortOrder", v.get("sortOrder"), "isActive", v.get("isActive"));
    }

    public List<Map<String, Object>> listVariants(String menuItemId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : db.list(MenuItemVariant.class, LIVE + " AND \"menuItemId\" = ? AND \"isActive\" = true"
                + " ORDER BY \"sortOrder\" ASC", org(), menuItemId)) {
            out.add(view(v));
        }
        return out;
    }

    public Map<String, Object> createVariant(String menuItemId, Map<String, Object> dto) {
        String orgId = org();
        String name = dto.get("name") == null ? "" : dto.get("name").toString().trim();
        if (name.isEmpty()) {
            throw ApiException.badRequest("Variant name is required");
        }
        if (dto.get("price") == null || PosSql.num(dto.get("price")) < 0) {
            throw ApiException.badRequest("Variant price must be >= 0");
        }
        if (dto.get("qtyMultiplier") != null && !(PosSql.num(dto.get("qtyMultiplier")) > 0)) {
            throw ApiException.badRequest("Recipe multiplier must be positive");
        }
        if (db.first(MenuItem.class, "id = ? AND " + LIVE, menuItemId, orgId) == null) {
            throw ApiException.notFound("MenuItem " + menuItemId + " not found");
        }
        if (db.first(MenuItemVariant.class, LIVE + " AND \"menuItemId\" = ? AND name = ?", orgId, menuItemId, name) != null) {
            throw ApiException.badRequest("Variant \"" + name + "\" already exists for this item");
        }
        Map<String, Object> created = tx.execute(st -> {
            Map<String, Object> v = db.insert(MenuItemVariant.class, Obj.of("organizationId", orgId, "menuItemId", menuItemId,
                    "name", name, "price", dto.get("price"), "sortOrder", dto.get("sortOrder") == null ? 0
                            : dto.get("sortOrder"), "qtyMultiplier", dto.get("qtyMultiplier")));
            audit.recordInTx(AuditInput.of("MenuItemVariant", (String) v.get("id"), "create", null,
                    Obj.of("menuItemId", menuItemId, "name", v.get("name"), "price", PosSql.js(v.get("price")),
                            "sortOrder", v.get("sortOrder"))));
            return v;
        });
        return view(created);
    }

    public Map<String, Object> updateVariant(String id, Map<String, Object> dto) {
        String orgId = org();
        Map<String, Object> existing = db.first(MenuItemVariant.class, "id = ? AND " + LIVE, id, orgId);
        if (existing == null) {
            throw ApiException.notFound("MenuItemVariant " + id + " not found");
        }
        if (dto.containsKey("name") && (dto.get("name") == null || dto.get("name").toString().trim().isEmpty())) {
            throw ApiException.badRequest("Variant name cannot be empty");
        }
        if (dto.containsKey("price") && PosSql.num(dto.get("price")) < 0) {
            throw ApiException.badRequest("Variant price must be >= 0");
        }
        if (dto.get("qtyMultiplier") != null && !(PosSql.num(dto.get("qtyMultiplier")) > 0)) {
            throw ApiException.badRequest("Recipe multiplier must be positive");
        }
        if (PosSql.truthy(dto.get("expectedUpdatedAt"))) {
            Instant expected = PosSql.date(dto.get("expectedUpdatedAt").toString());
            if (!existing.get("updatedAt").equals(expected)) {
                throw ApiException.conflict("This variant was modified by another user. Please reload and try again.");
            }
        }
        Map<String, Object> oldValues = Obj.of("name", existing.get("name"), "price", PosSql.js(existing.get("price")),
                "sortOrder", existing.get("sortOrder"), "isActive", existing.get("isActive"));
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("name")) {
            data.put("name", dto.get("name").toString().trim());
        }
        for (String k : List.of("price", "sortOrder", "isActive", "qtyMultiplier")) {
            if (dto.containsKey(k)) {
                data.put(k, dto.get(k));
            }
        }
        Map<String, Object> updated = tx.execute(st -> {
            Map<String, Object> v = db.update(MenuItemVariant.class, data, "id = ? AND " + LIVE, id, orgId);
            audit.recordInTx(new AuditInput("MenuItemVariant", (String) v.get("id"), "update", oldValues,
                    Obj.of("name", v.get("name"), "price", PosSql.js(v.get("price")), "sortOrder", v.get("sortOrder"),
                            "isActive", v.get("isActive")), (String) dto.get("ipAddress"), (String) dto.get("userAgent")));
            return v;
        });
        return view(updated);
    }

    public void deleteVariant(String id) {
        String orgId = org();
        Map<String, Object> existing = db.first(MenuItemVariant.class, "id = ? AND " + LIVE, id, orgId);
        if (existing == null) {
            throw ApiException.notFound("MenuItemVariant " + id + " not found");
        }
        tx.executeWithoutResult(st -> {
            Map<String, Object> v = db.update(MenuItemVariant.class, Obj.of("deletedAt", PosDb.raw(NOW), "isActive", false),
                    "id = ? AND " + LIVE, id, orgId);
            audit.recordInTx(AuditInput.of("MenuItemVariant", id, "delete",
                    Obj.of("name", existing.get("name"), "price", PosSql.js(existing.get("price"))),
                    Obj.of("name", v.get("name"), "isActive", false)));
        });
    }

    /** Active variant of this item; 400 otherwise. */
    public Map<String, Object> validateVariant(String menuItemId, String variantId) {
        Map<String, Object> v = db.first(MenuItemVariant.class, "id = ? AND \"menuItemId\" = ? AND " + LIVE
                + " AND \"isActive\" = true", variantId, menuItemId, org());
        if (v == null) {
            throw ApiException.badRequest("Invalid variant selection for this menu item");
        }
        return v;
    }
}
