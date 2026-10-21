package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.domain.Combo;
import com.inventoryerp.pos.domain.ComboItem;
import com.inventoryerp.pos.domain.MenuItem;
import com.inventoryerp.pos.domain.Modifier;
import com.inventoryerp.pos.domain.ModifierGroup;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Modifier groups / modifiers / combos (port of {@code pos-modifiers.service.ts}). */
@Service
public class PosModifiersService {

    private static final Logger log = LoggerFactory.getLogger(PosModifiersService.class);
    private static final String LIVE = PosMenuService.LIVE;

    /** A resolved, DB-priced modifier selection. */
    public record Resolved(String modifierId, String name, String kitchenPrintName, double priceDelta) {}

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public PosModifiersService(PosDb db, AuditService audit, PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    private static String trimmed(Object v) {
        return v == null ? null : v.toString().trim();
    }

    private static Object orNull(Object v) {
        String s = trimmed(v);
        return s == null || s.isEmpty() ? null : s;
    }

    private static Map<String, Object> modView(Map<String, Object> m) {
        return Obj.of("id", m.get("id"), "name", m.get("name"), "kitchenPrintName", m.get("kitchenPrintName"),
                "description", m.get("description"), "priceDelta", PosSql.js(m.get("priceDelta")),
                "isDefault", m.get("isDefault"), "sortOrder", m.get("sortOrder"));
    }

    private static Map<String, Object> groupView(Map<String, Object> g, Object sortOrder, List<Map<String, Object>> mods) {
        List<Map<String, Object>> out = new ArrayList<>();
        mods.forEach(m -> out.add(modView(m)));
        return Obj.of("id", g.get("id"), "name", g.get("name"), "category", g.get("category"),
                "description", g.get("description"), "color", g.get("color"), "icon", g.get("icon"),
                "groupType", g.get("groupType") == null ? "ADD_ON" : g.get("groupType"),
                "minSelect", g.get("minSelect"), "maxSelect", g.get("maxSelect"), "sortOrder", sortOrder,
                "version", g.get("version"), "modifiers", out);
    }

    // ─── groups ───

    public Map<String, Object> listGroups(String search, Boolean isActive, Integer pageIn, Integer pageSizeIn) {
        String orgId = org();
        StringBuilder where = new StringBuilder(LIVE);
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (isActive != null) {
            where.append(" AND \"isActive\" = ?");
            params.add(isActive);
        }
        if (search != null && !search.isEmpty()) {
            where.append(" AND name ILIKE ?");
            params.add(PosSql.contains(search));
        }
        int page = pageIn == null ? 1 : pageIn;
        int pageSize = pageSizeIn == null ? 50 : pageSizeIn;
        List<Object> pp = new ArrayList<>(params);
        pp.add(pageSize);
        pp.add((page - 1) * pageSize);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> g : SqlRows.list(ModifierGroup.class, jdbc.sql("SELECT * FROM \"ModifierGroup\" WHERE "
                + where + " ORDER BY \"sortOrder\" ASC LIMIT ? OFFSET ?").params(pp))) {
            data.add(groupView(g, g.get("sortOrder"), db.list(Modifier.class, "\"groupId\" = ? AND \"isActive\" = true"
                    + " AND \"deletedAt\" IS NULL ORDER BY \"sortOrder\" ASC", g.get("id"))));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"ModifierGroup\" WHERE " + where).params(params)
                .query(Long.class).single();
        return Obj.of("data", data, "total", total, "page", page, "pageSize", pageSize);
    }

    public Map<String, Object> createGroup(Map<String, Object> dto) {
        String orgId = org();
        String name = trimmed(dto.get("name"));
        if (name == null || name.isEmpty()) {
            throw ApiException.badRequest("Group name is required");
        }
        Object groupType = dto.get("groupType");
        if (PosSql.truthy(groupType) && !List.of("ADD_ON", "MODIFIER").contains(groupType)) {
            throw ApiException.badRequest("groupType must be ADD_ON or MODIFIER");
        }
        double minSelect = dto.get("minSelect") == null ? 0 : PosSql.num(dto.get("minSelect"));
        double maxSelect = dto.get("maxSelect") == null ? 1 : PosSql.num(dto.get("maxSelect"));
        if (minSelect > maxSelect) {
            throw ApiException.badRequest("minSelect cannot be greater than maxSelect");
        }
        if (db.first(ModifierGroup.class, LIVE + " AND name = ?", orgId, name) != null) {
            throw ApiException.badRequest("Group \"" + name + "\" already exists");
        }
        return tx.execute(st -> {
            Map<String, Object> g = db.insert(ModifierGroup.class, Obj.of("organizationId", orgId, "name", name,
                    "category", orNull(dto.get("category")), "description", orNull(dto.get("description")),
                    "color", orNull(dto.get("color")), "icon", orNull(dto.get("icon")),
                    "groupType", groupType == null ? "ADD_ON" : groupType,
                    "minSelect", dto.get("minSelect") == null ? 0 : dto.get("minSelect"),
                    "maxSelect", dto.get("maxSelect") == null ? 1 : dto.get("maxSelect"),
                    "sortOrder", dto.get("sortOrder") == null ? 0 : dto.get("sortOrder"), "createdBy", user()));
            audit.recordInTx(AuditInput.of("ModifierGroup", (String) g.get("id"), "create", null,
                    Obj.of("name", g.get("name"), "category", g.get("category"), "groupType", g.get("groupType"),
                            "minSelect", g.get("minSelect"), "maxSelect", g.get("maxSelect"))));
            return g;
        });
    }

    public Map<String, Object> createModifier(Map<String, Object> dto) {
        String name = trimmed(dto.get("name"));
        if (name == null || name.isEmpty()) {
            throw ApiException.badRequest("Modifier name is required");
        }
        if (dto.containsKey("consumptionQty") && !(PosSql.num(dto.get("consumptionQty")) > 0)) {
            throw ApiException.badRequest("Consumption quantity must be positive");
        }
        String orgId = org();
        if (db.first(ModifierGroup.class, "id = ? AND " + LIVE, dto.get("groupId"), orgId) == null) {
            throw ApiException.notFound("Modifier group not found");
        }
        return tx.execute(st -> {
            Map<String, Object> m = db.insert(Modifier.class, Obj.of("organizationId", orgId, "groupId", dto.get("groupId"),
                    "name", name, "kitchenPrintName", orNull(dto.get("kitchenPrintName")),
                    "description", orNull(dto.get("description")),
                    "priceDelta", dto.get("priceDelta") == null ? 0 : dto.get("priceDelta"),
                    "isDefault", dto.get("isDefault") == null ? false : dto.get("isDefault"),
                    "sortOrder", dto.get("sortOrder") == null ? 0 : dto.get("sortOrder"),
                    "inventoryItemId", dto.get("inventoryItemId"),
                    "consumptionQty", dto.get("consumptionQty") == null ? 1 : dto.get("consumptionQty"),
                    "consumptionUomId", dto.get("consumptionUomId"), "createdBy", user()));
            audit.recordInTx(AuditInput.of("Modifier", (String) m.get("id"), "create", null,
                    Obj.of("groupId", dto.get("groupId"), "name", m.get("name"), "priceDelta", PosSql.js(m.get("priceDelta")))));
            return m;
        });
    }

    // ─── bundles ───

    private List<Map<String, Object>> linkedGroups(String linkTable, String ownerCol, String ownerId) {
        String orgId = org();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> link : SqlRows.list(jdbc.sql("SELECT l.\"modifierGroupId\", l.\"sortOrder\" FROM \""
                + linkTable + "\" l JOIN \"ModifierGroup\" g ON g.id = l.\"modifierGroupId\" WHERE l.\"" + ownerCol
                + "\" = ? AND l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL AND g.\"isActive\" = true"
                + " ORDER BY l.\"sortOrder\" ASC").params(ownerId, orgId))) {
            Map<String, Object> g = db.first(ModifierGroup.class, "id = ?", link.get("modifierGroupId"));
            groups.add(groupView(g, link.get("sortOrder"), db.list(Modifier.class, "\"groupId\" = ? AND \"isActive\" = true"
                    + " ORDER BY \"sortOrder\" ASC", g.get("id"))));
        }
        return groups;
    }

    public Map<String, Object> getProductBundle(String productId) {
        Map<String, Object> product = db.first(Product.class, "id = ? AND " + LIVE + " AND \"isActive\" = true",
                productId, org());
        if (product == null) {
            return null;
        }
        return Obj.of("product", Obj.of("id", product.get("id"), "name", product.get("name"),
                        "unitPrice", PosSql.js(product.get("salesPrice")), "sku", product.get("sku"),
                        "productType", product.get("productType")),
                "groups", linkedGroups("ProductModifierGroup", "productId", productId));
    }

    public Map<String, Object> getMenuItemBundle(String menuItemId) {
        Map<String, Object> item = db.first(MenuItem.class, "id = ? AND " + LIVE, menuItemId, org());
        if (item == null) {
            return null;
        }
        return Obj.of("product", Obj.of("id", item.get("id"), "name", item.get("name"),
                        "unitPrice", PosSql.js(item.get("basePrice")), "sku", item.get("code"), "productType", "menu"),
                "groups", linkedGroups("MenuItemModifierGroup", "menuItemId", menuItemId));
    }

    private void upsertLink(String table, String ownerCol, String ownerId, String groupId, Object sortOrder,
            String auditEntity) {
        String orgId = org();
        String id = jdbc.sql("INSERT INTO \"" + table + "\" (id, \"organizationId\", \"" + ownerCol + "\","
                        + " \"modifierGroupId\", \"sortOrder\") VALUES (?, ?, ?, ?, ?) ON CONFLICT (\"" + ownerCol + "\","
                        + " \"modifierGroupId\") DO UPDATE SET \"sortOrder\" = EXCLUDED.\"sortOrder\" RETURNING id")
                .params(UUID.randomUUID().toString(), orgId, ownerId, groupId, sortOrder).query(String.class).single();
        audit.record(AuditInput.of(auditEntity, id, "assign", null,
                Obj.of(ownerCol, ownerId, "modifierGroupId", groupId, "sortOrder", sortOrder)));
    }

    public void assignGroupToMenuItem(String menuItemId, String modifierGroupId, Object sortOrderIn) {
        Object sortOrder = sortOrderIn == null ? 0 : sortOrderIn;
        Map<String, Object> m = db.first(MenuItem.class, "id = ? AND " + LIVE, menuItemId, org());
        Map<String, Object> g = db.first(ModifierGroup.class, "id = ? AND " + LIVE, modifierGroupId, org());
        if (m == null || g == null) {
            throw ApiException.notFound("Menu item or modifier group not found");
        }
        upsertLink("MenuItemModifierGroup", "menuItemId", menuItemId, modifierGroupId, sortOrder,
                "MenuItemModifierGroup");
    }

    public void assignGroupToProduct(String productId, String modifierGroupId, Object sortOrderIn) {
        Object sortOrder = sortOrderIn == null ? 0 : sortOrderIn;
        Map<String, Object> p = db.first(Product.class, "id = ? AND " + LIVE, productId, org());
        Map<String, Object> g = db.first(ModifierGroup.class, "id = ? AND " + LIVE, modifierGroupId, org());
        if (p == null || g == null) {
            throw ApiException.notFound("Product or modifier group not found");
        }
        upsertLink("ProductModifierGroup", "productId", productId, modifierGroupId, sortOrder, "ProductModifierGroup");
    }

    private void unassign(String table, String ownerCol, String ownerId, String groupId) {
        String id = jdbc.sql("SELECT id FROM \"" + table + "\" WHERE \"" + ownerCol + "\" = ? AND \"modifierGroupId\" = ?"
                        + " AND " + LIVE + " LIMIT 1")
                .params(ownerId, groupId, org()).query(String.class).optional().orElse(null);
        if (id == null) {
            return;
        }
        tx.executeWithoutResult(st -> {
            jdbc.sql("UPDATE \"" + table + "\" SET \"deletedAt\" = " + NOW + " WHERE id = ?").param(id).update();
            audit.recordInTx(AuditInput.of(table, id, "unassign", Obj.of(ownerCol, ownerId, "modifierGroupId", groupId),
                    null));
        });
    }

    public void unassignGroupFromMenuItem(String menuItemId, String modifierGroupId) {
        unassign("MenuItemModifierGroup", "menuItemId", menuItemId, modifierGroupId);
    }

    public void unassignGroupFromProduct(String productId, String modifierGroupId) {
        unassign("ProductModifierGroup", "productId", productId, modifierGroupId);
    }

    @SuppressWarnings("unchecked")
    private static void enforceRules(Map<String, Object> bundle, List<String> selectedIds, boolean bypassRequired) {
        Set<String> selected = new HashSet<>(selectedIds);
        for (Map<String, Object> g : (List<Map<String, Object>>) bundle.get("groups")) {
            int inGroup = 0;
            for (Map<String, Object> m : (List<Map<String, Object>>) g.get("modifiers")) {
                if (selected.contains((String) m.get("id"))) {
                    inGroup++;
                }
            }
            int min = PosSql.intOf(g.get("minSelect"));
            int max = PosSql.intOf(g.get("maxSelect"));
            if (!bypassRequired && inGroup < min) {
                throw ApiException.badRequest("\"" + g.get("name") + "\" requires at least " + min + " modifier(s). "
                        + inGroup + " selected.");
            }
            if (max > 0 && inGroup > max) {
                throw ApiException.badRequest("\"" + g.get("name") + "\" allows at most " + max + " modifier(s). "
                        + inGroup + " selected.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean noGroups(Map<String, Object> bundle) {
        return bundle == null || ((List<Object>) bundle.get("groups")).isEmpty();
    }

    /** Validate every line's modifier selection (lines: {@code productId}, {@code menuItemId}, {@code modifiers}). */
    public void validateSelections(List<Map<String, Object>> lines, boolean bypassRequired) {
        for (Map<String, Object> l : lines) {
            List<String> ids = new ArrayList<>();
            for (Object o : PosSql.list(l.get("modifiers"))) {
                ids.add(PosSql.str(PosSql.map(o), "modifierId"));
            }
            Map<String, Object> bundle = null;
            if (PosSql.truthy(l.get("menuItemId"))) {
                bundle = getMenuItemBundle((String) l.get("menuItemId"));
            } else if (PosSql.truthy(l.get("productId"))) {
                bundle = getProductBundle((String) l.get("productId"));
            } else {
                continue;
            }
            if (!noGroups(bundle)) {
                enforceRules(bundle, ids, bypassRequired);
            }
        }
    }

    /** DB-authoritative modifiers for a line; unknown ids are dropped (anti-tamper, stale carts still ring up). */
    @SuppressWarnings("unchecked")
    public List<Resolved> resolveSelectedModifiers(String menuItemId, String productId, List<String> modifierIds) {
        if (modifierIds == null || modifierIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Object> bundle = menuItemId != null && !menuItemId.isEmpty() ? getMenuItemBundle(menuItemId)
                : productId != null && !productId.isEmpty() ? getProductBundle(productId) : null;
        if (bundle == null) {
            log.warn("[modifier] no config for item; ignoring {} selected modifier(s).", modifierIds.size());
            return new ArrayList<>();
        }
        Map<String, Map<String, Object>> allowed = new HashMap<>();
        for (Map<String, Object> g : (List<Map<String, Object>>) bundle.get("groups")) {
            for (Map<String, Object> m : (List<Map<String, Object>>) g.get("modifiers")) {
                allowed.put((String) m.get("id"), m);
            }
        }
        List<Resolved> out = new ArrayList<>();
        for (String id : modifierIds) {
            Map<String, Object> m = allowed.get(id);
            if (m == null) {
                log.warn("[modifier] \"{}\" not available — ignored.", id);
                continue;
            }
            out.add(new Resolved(id, (String) m.get("name"), (String) m.get("kitchenPrintName"),
                    PosSql.num(m.get("priceDelta"))));
        }
        return out;
    }

    // ─── edit / delete ───

    public Map<String, Object> updateGroup(String id, Map<String, Object> dto) {
        String orgId = org();
        Map<String, Object> existing = db.first(ModifierGroup.class, "id = ? AND " + LIVE, id, orgId);
        if (existing == null) {
            throw ApiException.notFound("Modifier group not found");
        }
        Object groupType = dto.get("groupType");
        if (PosSql.truthy(groupType) && !List.of("ADD_ON", "MODIFIER").contains(groupType)) {
            throw ApiException.badRequest("groupType must be ADD_ON or MODIFIER");
        }
        double minSelect = dto.get("minSelect") != null ? PosSql.num(dto.get("minSelect")) : PosSql.num(existing.get("minSelect"));
        double maxSelect = dto.get("maxSelect") != null ? PosSql.num(dto.get("maxSelect")) : PosSql.num(existing.get("maxSelect"));
        if (minSelect > maxSelect) {
            throw ApiException.badRequest("minSelect cannot be greater than maxSelect");
        }
        if (dto.containsKey("name") && !trimmed(dto.get("name")).equals(existing.get("name"))) {
            if (db.first(ModifierGroup.class, LIVE + " AND name = ? AND id <> ?", orgId, trimmed(dto.get("name")), id)
                    != null) {
                throw ApiException.badRequest("Group \"" + trimmed(dto.get("name")) + "\" already exists");
            }
        }
        Map<String, Object> oldValues = audited(existing);
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("name")) {
            data.put("name", trimmed(dto.get("name")));
        }
        for (String k : List.of("category", "description", "color", "icon")) {
            if (dto.containsKey(k)) {
                data.put(k, orNull(dto.get(k)));
            }
        }
        for (String k : List.of("groupType", "minSelect", "maxSelect", "sortOrder", "isActive")) {
            if (dto.containsKey(k)) {
                data.put(k, dto.get(k));
            }
        }
        data.put("updatedBy", user());
        data.put("version", PosDb.raw("version + 1"));
        return tx.execute(st -> {
            Map<String, Object> result;
            if (dto.containsKey("expectedVersion")) {
                int n = db.updateMany(ModifierGroup.class, data, "id = ? AND version = ? AND " + LIVE, id,
                        dto.get("expectedVersion"), orgId);
                if (n == 0) {
                    throw ApiException.conflict(
                            "This modifier group was modified by another user. Please reload and try again.");
                }
                result = db.first(ModifierGroup.class, "id = ? AND " + LIVE, id, orgId);
            } else {
                result = db.update(ModifierGroup.class, data, "id = ? AND " + LIVE, id, orgId);
            }
            audit.recordInTx(new AuditInput("ModifierGroup", id, "update", oldValues, audited(result),
                    (String) dto.get("ipAddress"), (String) dto.get("userAgent")));
            return result;
        });
    }

    private static Map<String, Object> audited(Map<String, Object> g) {
        return Obj.of("name", g.get("name"), "category", g.get("category"), "description", g.get("description"),
                "color", g.get("color"), "icon", g.get("icon"), "groupType", g.get("groupType"),
                "minSelect", g.get("minSelect"), "maxSelect", g.get("maxSelect"), "sortOrder", g.get("sortOrder"),
                "isActive", g.get("isActive"));
    }

    public void deleteGroup(String id) {
        String orgId = org();
        Map<String, Object> existing = db.first(ModifierGroup.class, "id = ? AND " + LIVE, id, orgId);
        if (existing == null) {
            throw ApiException.notFound("Modifier group not found");
        }
        long product = jdbc.sql("SELECT count(*) FROM \"ProductModifierGroup\" WHERE \"modifierGroupId\" = ? AND " + LIVE)
                .params(id, orgId).query(Long.class).single();
        long menu = jdbc.sql("SELECT count(*) FROM \"MenuItemModifierGroup\" WHERE \"modifierGroupId\" = ? AND " + LIVE)
                .params(id, orgId).query(Long.class).single();
        long total = product + menu;
        if (total > 0) {
            throw ApiException.conflict("Cannot delete \"" + existing.get("name") + "\" — assigned to " + total
                    + " product(s)/menu item(s). Deactivate instead.");
        }
        tx.executeWithoutResult(st -> {
            db.updateMany(Modifier.class, Obj.of("deletedAt", PosDb.raw(NOW), "isActive", false),
                    "\"groupId\" = ? AND " + LIVE, id, orgId);
            db.update(ModifierGroup.class, Obj.of("deletedAt", PosDb.raw(NOW), "isActive", false),
                    "id = ? AND " + LIVE, id, orgId);
            audit.recordInTx(AuditInput.of("ModifierGroup", id, "delete", Obj.of("name", existing.get("name")),
                    Obj.of("name", existing.get("name"), "isActive", false)));
        });
    }

    public Map<String, Object> updateModifier(String id, Map<String, Object> dto) {
        if (dto.containsKey("consumptionQty") && !(PosSql.num(dto.get("consumptionQty")) > 0)) {
            throw ApiException.badRequest("Consumption quantity must be positive");
        }
        String orgId = org();
        Map<String, Object> existing = db.first(Modifier.class, "id = ? AND " + LIVE, id, orgId);
        if (existing == null) {
            throw ApiException.notFound("Modifier not found");
        }
        if (PosSql.truthy(dto.get("expectedUpdatedAt"))) {
            Instant expected = PosSql.date(dto.get("expectedUpdatedAt").toString());
            if (!existing.get("updatedAt").equals(expected)) {
                throw ApiException.conflict("This modifier was modified by another user. Please reload and try again.");
            }
        }
        if (dto.containsKey("priceDelta") && PosSql.num(dto.get("priceDelta")) < 0) {
            throw ApiException.badRequest("Modifier priceDelta cannot be negative");
        }
        Map<String, Object> oldValues = modAudit(existing);
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("name")) {
            data.put("name", trimmed(dto.get("name")));
        }
        for (String k : List.of("kitchenPrintName", "description")) {
            if (dto.containsKey(k)) {
                data.put(k, orNull(dto.get(k)));
            }
        }
        for (String k : List.of("priceDelta", "isDefault", "sortOrder", "isActive", "inventoryItemId", "consumptionQty",
                "consumptionUomId")) {
            if (dto.containsKey(k)) {
                data.put(k, dto.get(k));
            }
        }
        data.put("updatedBy", user());
        return tx.execute(st -> {
            Map<String, Object> m = db.update(Modifier.class, data, "id = ? AND " + LIVE, id, orgId);
            audit.recordInTx(new AuditInput("Modifier", id, "update", oldValues, modAudit(m),
                    (String) dto.get("ipAddress"), (String) dto.get("userAgent")));
            return m;
        });
    }

    private static Map<String, Object> modAudit(Map<String, Object> m) {
        return Obj.of("name", m.get("name"), "kitchenPrintName", m.get("kitchenPrintName"),
                "description", m.get("description"), "priceDelta", PosSql.js(m.get("priceDelta")),
                "isDefault", m.get("isDefault"), "sortOrder", m.get("sortOrder"), "isActive", m.get("isActive"));
    }

    public void deleteModifier(String id) {
        String orgId = org();
        Map<String, Object> existing = db.first(Modifier.class, "id = ? AND " + LIVE, id, orgId);
        if (existing == null) {
            throw ApiException.notFound("Modifier not found");
        }
        long usage = 0;
        for (String t : List.of("OrderItemModifier", "DocumentLineModifier", "InvoiceItemModifier")) {
            usage += jdbc.sql("SELECT count(*) FROM \"" + t + "\" WHERE \"modifierId\" = ? AND \"organizationId\" = ?")
                    .params(id, orgId).query(Long.class).single();
        }
        if (usage > 0) {
            throw ApiException.conflict("\"" + existing.get("name") + "\" is used in " + usage
                    + " historical order line(s). Deactivate instead of deleting.");
        }
        tx.executeWithoutResult(st -> {
            Map<String, Object> m = db.update(Modifier.class, Obj.of("deletedAt", PosDb.raw(NOW), "isActive", false),
                    "id = ? AND " + LIVE, id, orgId);
            audit.recordInTx(AuditInput.of("Modifier", id, "delete",
                    Obj.of("name", existing.get("name"), "priceDelta", PosSql.js(existing.get("priceDelta"))),
                    Obj.of("name", m.get("name"), "isActive", false)));
        });
    }

    /** Count + add-on revenue per modifier name over posted/paid documents. */
    public List<Map<String, Object>> modifierSalesReport(String from, String to) {
        String orgId = org();
        StringBuilder where = new StringBuilder("m.\"organizationId\" = ? AND d.status::text IN ('posted', 'paid')");
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (from != null && !from.isEmpty()) {
            where.append(" AND m.\"createdAt\" >= ?");
            params.add(SqlRows.ts(PosSql.date(from)));
        }
        if (to != null && !to.isEmpty()) {
            where.append(" AND m.\"createdAt\" <= ?");
            params.add(SqlRows.ts(PosSql.date(to)));
        }
        Map<String, double[]> map = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT m.name, m.\"priceDelta\", dl.quantity FROM"
                + " \"DocumentLineModifier\" m JOIN \"DocumentLine\" dl ON dl.id = m.\"documentLineId\" JOIN \"Document\" d"
                + " ON d.id = dl.\"documentId\" WHERE " + where).params(params))) {
            double[] cur = map.computeIfAbsent((String) r.get("name"), k -> new double[2]);
            double q = r.get("quantity") == null ? 1 : PosSql.num(r.get("quantity"));
            cur[0] += q;
            cur[1] += PosSql.num(r.get("priceDelta")) * q;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        map.forEach((name, v) -> out.add(Obj.of("name", name, "count", PosSql.js(v[0]), "revenue", PosSql.js(v[1]))));
        out.sort((a, b) -> Double.compare(PosSql.num(b.get("count")), PosSql.num(a.get("count"))));
        return out;
    }

    // ─── combos ───

    private List<Map<String, Object>> comboItemsWithProduct(Object comboId) {
        List<Map<String, Object>> items = db.list(ComboItem.class, "\"comboId\" = ?", comboId);
        for (Map<String, Object> it : items) {
            it.put("product", db.first(Product.class, "id = ?", it.get("productId")));
        }
        return items;
    }

    public Map<String, Object> updateCombo(String id, Map<String, Object> dto) {
        String orgId = org();
        Map<String, Object> existing = db.first(Combo.class, "id = ? AND \"organizationId\" = ?", id, orgId);
        if (existing == null) {
            throw ApiException.notFound("Combo " + id + " not found");
        }
        if (dto.containsKey("name") && (dto.get("name") == null || trimmed(dto.get("name")).isEmpty())) {
            throw ApiException.badRequest("Combo name cannot be empty");
        }
        Map<String, Object> oldValues = Obj.of("name", existing.get("name"), "price", PosSql.js(existing.get("price")),
                "description", existing.get("description"), "imageUrl", existing.get("imageUrl"));
        return tx.execute(st -> {
            Map<String, Object> data = new LinkedHashMap<>();
            if (dto.containsKey("name")) {
                data.put("name", trimmed(dto.get("name")));
            }
            for (String k : List.of("price", "description", "imageUrl")) {
                if (dto.containsKey(k)) {
                    data.put(k, dto.get(k));
                }
            }
            if (!data.isEmpty()) {
                db.update(Combo.class, data, "id = ? AND \"organizationId\" = ?", id, orgId);
            }
            if (dto.containsKey("items")) {
                List<Object> items = PosSql.list(dto.get("items"));
                if (items.isEmpty()) {
                    throw ApiException.badRequest("Combo must have at least one item");
                }
                jdbc.sql("DELETE FROM \"ComboItem\" WHERE \"comboId\" = ? AND \"organizationId\" = ?").params(id, orgId)
                        .update();
                insertComboItems(id, items);
            }
            Map<String, Object> updated = db.first(Combo.class, "id = ?", id);
            List<Map<String, Object>> items = comboItemsWithProduct(id);
            updated.put("items", items);
            audit.recordInTx(new AuditInput("Combo", id, "update", oldValues, Obj.of("name", updated.get("name"),
                    "price", PosSql.js(updated.get("price")), "description", updated.get("description"),
                    "imageUrl", updated.get("imageUrl"), "items", items.size()), (String) dto.get("ipAddress"),
                    (String) dto.get("userAgent")));
            return updated;
        });
    }

    private void insertComboItems(String comboId, List<Object> items) {
        String orgId = org();
        for (Object o : items) {
            Map<String, Object> it = PosSql.map(o);
            if (db.first(Product.class, "id = ? AND " + LIVE, it.get("productId"), orgId) == null) {
                throw ApiException.badRequest("Combo references unknown product: " + it.get("productId"));
            }
            Object q = it.get("quantity");
            db.insert(ComboItem.class, Obj.of("organizationId", orgId, "comboId", comboId, "productId", it.get("productId"),
                    "quantity", PosSql.truthy(q) ? q : 1));
        }
    }

    public void deleteCombo(String id) {
        String orgId = org();
        Map<String, Object> existing = db.first(Combo.class, "id = ? AND \"organizationId\" = ?", id, orgId);
        if (existing == null) {
            throw ApiException.notFound("Combo " + id + " not found");
        }
        tx.executeWithoutResult(st -> {
            jdbc.sql("DELETE FROM \"ComboItem\" WHERE \"comboId\" = ? AND \"organizationId\" = ?").params(id, orgId).update();
            db.update(Combo.class, Obj.of("isActive", false), "id = ? AND \"organizationId\" = ?", id, orgId);
            audit.recordInTx(AuditInput.of("Combo", id, "delete",
                    Obj.of("name", existing.get("name"), "price", PosSql.js(existing.get("price"))),
                    Obj.of("isActive", false)));
        });
    }

    private Map<String, Object> comboView(Map<String, Object> c) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> it : comboItemsWithProduct(c.get("id"))) {
            Map<String, Object> p = PosSql.map(it.get("product"));
            items.add(Obj.of("productId", it.get("productId"), "productName", p == null || p.get("name") == null ? "?"
                    : p.get("name"), "quantity", it.get("quantity")));
        }
        return Obj.of("id", c.get("id"), "name", c.get("name"), "price", PosSql.js(c.get("price")),
                "description", c.get("description"), "imageUrl", c.get("imageUrl"), "items", items);
    }

    public List<Map<String, Object>> listCombos() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : db.list(Combo.class, "\"organizationId\" = ? AND \"isActive\" = true"
                + " ORDER BY \"sortOrder\" ASC", org())) {
            out.add(comboView(c));
        }
        return out;
    }

    public Map<String, Object> getCombo(String comboId) {
        Map<String, Object> c = db.first(Combo.class, "id = ? AND \"organizationId\" = ?", comboId, org());
        return c == null ? null : comboView(c);
    }

    public Map<String, Object> createCombo(Map<String, Object> dto) {
        String orgId = org();
        String name = trimmed(dto.get("name"));
        if (name == null || name.isEmpty()) {
            throw ApiException.badRequest("Combo name is required");
        }
        List<Object> items = PosSql.list(dto.get("items"));
        if (items.isEmpty()) {
            throw ApiException.badRequest("Combo must have at least one item");
        }
        return tx.execute(st -> {
            Map<String, Object> combo = db.insert(Combo.class, Obj.of("organizationId", orgId, "name", name,
                    "price", dto.get("price"), "description", dto.get("description"), "imageUrl", dto.get("imageUrl")));
            insertComboItems((String) combo.get("id"), items);
            return combo;
        });
    }

    public List<Map<String, Object>> getGroupMenuItems(String groupId) {
        String orgId = org();
        if (db.first(ModifierGroup.class, "id = ? AND " + LIVE, groupId, orgId) == null) {
            throw ApiException.notFound("Modifier group not found");
        }
        Set<String> assigned = new HashSet<>(jdbc.sql("SELECT \"menuItemId\" FROM \"MenuItemModifierGroup\" WHERE"
                + " \"modifierGroupId\" = ? AND " + LIVE).params(groupId, orgId).query(String.class).list());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT id, name FROM \"MenuItem\" WHERE " + LIVE
                + " AND \"isAvailable\" = true ORDER BY name ASC").param(orgId))) {
            out.add(Obj.of("id", m.get("id"), "name", m.get("name"), "isAssigned", assigned.contains((String) m.get("id"))));
        }
        return out;
    }
}
