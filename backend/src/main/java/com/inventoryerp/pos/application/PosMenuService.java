package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.pos.domain.AccompanimentGroup;
import com.inventoryerp.pos.domain.AccompanimentOption;
import com.inventoryerp.pos.domain.KitchenStation;
import com.inventoryerp.pos.domain.MenuCategory;
import com.inventoryerp.pos.domain.MenuItem;
import com.inventoryerp.pos.domain.MenuItemVariant;
import com.inventoryerp.pos.domain.MenuProduct;
import com.inventoryerp.pos.domain.Modifier;
import com.inventoryerp.pos.domain.ModifierGroup;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Menu categories, items and their recipes (port of {@code pos-menu.service.ts}). */
@Service
public class PosMenuService {

    private static final Pattern UUID_IN = Pattern.compile(
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", Pattern.CASE_INSENSITIVE);
    static final String LIVE = "\"organizationId\" = ? AND \"deletedAt\" IS NULL";

    private final PosDb db;
    private final JdbcClient jdbc;
    private final FilesService files;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public PosMenuService(PosDb db, FilesService files, AuditService audit, PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.files = files;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    /** A stored image (file id, signed URL, absolute URL) as a fresh signed URL. */
    String resolveImage(Object image) {
        if (image == null || image.toString().isEmpty()) {
            return null;
        }
        String s = image.toString();
        if (s.startsWith("http")) {
            return s;
        }
        Matcher m = UUID_IN.matcher(s);
        if (!m.find()) {
            return s;
        }
        try {
            return (String) files.signDownload(m.group(1)).get("url");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<String, Object> withImage(Map<String, Object> row) {
        Map<String, Object> r = new LinkedHashMap<>(row);
        r.put("image", resolveImage(row.get("image")));
        return r;
    }

    // ───────────────────────── Categories ─────────────────────────

    public List<Map<String, Object>> listCategories() {
        return db.list(MenuCategory.class, LIVE + " AND \"isActive\" = true ORDER BY \"displayOrder\" ASC, name ASC",
                org());
    }

    public Map<String, Object> createCategory(Map<String, Object> input) {
        return tx.execute(st -> {
            Map<String, Object> created = db.insert(MenuCategory.class, Obj.of("organizationId", org(),
                    "name", input.get("name"), "parentId", input.get("parentId"), "image", input.get("image"),
                    "icon", input.get("icon"), "displayOrder", input.get("displayOrder") == null ? 0
                            : input.get("displayOrder"), "createdBy", user(), "updatedBy", user()));
            audit.recordInTx(AuditInput.of("MenuCategory", (String) created.get("id"), "create", null, created));
            return created;
        });
    }

    /** Untyped body (Nest {@code Partial<CreateCategoryDto>} is not validated): unknown keys fail in Prisma (500). */
    public Map<String, Object> updateCategory(String id, Map<String, Object> data) {
        Map<String, Object> existing = db.first(MenuCategory.class, "id = ? AND " + LIVE, id, org());
        if (existing == null) {
            throw ApiException.notFound("MenuCategory " + id + " not found");
        }
        Map<String, Object> patch = checked(MenuCategory.class, data);
        patch.put("updatedBy", user());
        return tx.execute(st -> {
            Map<String, Object> updated = db.update(MenuCategory.class, patch, "id = ? AND " + LIVE, id, org());
            audit.recordInTx(AuditInput.of("MenuCategory", id, "update", existing, updated));
            return updated;
        });
    }

    /** Prisma rejects a {@code data} key that is not a column (PrismaClientValidationError → 500). */
    static Map<String, Object> checked(Class<?> entity, Map<String, Object> data) {
        List<String> cols = EntityJson.columnNames(entity);
        Map<String, Object> out = new LinkedHashMap<>();
        if (data != null) {
            for (Map.Entry<String, Object> e : data.entrySet()) {
                if (!cols.contains(e.getKey())) {
                    throw new IllegalStateException("Unknown argument `" + e.getKey() + "`.");
                }
                Object v = e.getValue();
                out.put(e.getKey(), v instanceof Map || v instanceof List ? PosDb.json(v) : v);
            }
        }
        return out;
    }

    public Map<String, Object> deleteCategory(String id) {
        Map<String, Object> existing = db.first(MenuCategory.class, "id = ? AND " + LIVE, id, org());
        if (existing == null) {
            throw ApiException.notFound("MenuCategory " + id + " not found");
        }
        return tx.execute(st -> {
            Map<String, Object> updated = db.update(MenuCategory.class, Obj.of("deletedAt", PosDb.raw(NOW),
                    "isActive", false, "updatedBy", user()), "id = ? AND " + LIVE, id, org());
            audit.recordInTx(AuditInput.of("MenuCategory", id, "delete", existing, updated));
            return updated;
        });
    }

    /**
     * Nest parity: the soft-delete extension adds {@code deletedAt: null} to the {@code update} where, so restoring a
     * deleted row matches nothing (P2025 → 500). Kept on purpose until parity is signed off.
     */
    public Map<String, Object> restoreCategory(String id) {
        Map<String, Object> existing = db.first(MenuCategory.class,
                "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NOT NULL", id, org());
        if (existing == null) {
            throw ApiException.notFound("Deleted MenuCategory " + id + " not found");
        }
        return tx.execute(st -> {
            Map<String, Object> updated = db.update(MenuCategory.class, Obj.of("deletedAt", null, "isActive", true,
                    "updatedBy", user()), "id = ? AND " + LIVE, id, org());
            audit.recordInTx(AuditInput.of("MenuCategory", id, "restore", existing, updated));
            return updated;
        });
    }

    public List<Map<String, Object>> listDeletedCategories() {
        return db.list(MenuCategory.class, "\"organizationId\" = ? AND \"deletedAt\" IS NOT NULL ORDER BY \"deletedAt\" DESC",
                org());
    }

    // ─────────────────────────── Items ────────────────────────────

    public Map<String, Object> listAvailable() {
        List<Map<String, Object>> cats = new ArrayList<>();
        for (Map<String, Object> c : listCategories()) {
            cats.add(withImage(c));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> it : db.list(MenuItem.class, LIVE + " AND \"isAvailable\" = true"
                + " ORDER BY \"displayOrder\" ASC, name ASC", org())) {
            items.add(withImage(it));
        }
        return Obj.of("categories", cats, "items", items);
    }

    private static final Set<String> SORTABLE = Set.copyOf(EntityJson.columnNames(MenuItem.class));

    public Map<String, Object> listAll(PageQuery query, String categoryId) {
        // PaginationDto field initialisers: page = 1, pageSize = DEFAULT_PAGE_SIZE (25).
        int page = Math.max(1, query.pageOrDefault());
        int pageSize = Math.min(100, Math.max(1, query.pageSizeOrDefault()));
        String orgId = org();
        StringBuilder where = new StringBuilder(LIVE);
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (query.search() != null && !query.search().isEmpty()) {
            String like = PosSql.contains(query.search());
            where.append(" AND (name ILIKE ? OR code ILIKE ? OR description ILIKE ?)");
            params.addAll(List.of(like, like, like));
        }
        String categoryWhere = where.toString();
        List<Object> categoryParams = new ArrayList<>(params);
        if (categoryId != null && !categoryId.isEmpty()) {
            where.append(" AND \"categoryId\" = ?");
            params.add(categoryId);
        }
        String orderBy;
        if (query.sortBy() != null && !query.sortBy().isEmpty()) {
            if (!SORTABLE.contains(query.sortBy())) {
                throw new IllegalStateException("Unknown argument `" + query.sortBy() + "`.");
            }
            orderBy = "\"" + query.sortBy() + "\" " + ("desc".equals(query.sortOrder()) ? "DESC" : "ASC");
        } else {
            orderBy = "\"displayOrder\" ASC, name ASC";
        }
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pageSize);
        pageParams.add((page - 1) * pageSize);
        List<Map<String, Object>> rows = SqlRows.list(MenuItem.class, jdbc.sql("SELECT * FROM \"MenuItem\" WHERE "
                + where + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?").params(pageParams));
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> it : rows) {
            Map<String, Object> r = new LinkedHashMap<>(it);
            r.put("category", category(it.get("categoryId")));
            List<Map<String, Object>> ings = new ArrayList<>();
            for (Map<String, Object> mp : ingredientRows(it.get("id"))) {
                Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT id, code, name, station FROM \"Product\" WHERE id = ?")
                        .param(mp.get("productId")));
                mp.put("product", p);
                ings.add(mp);
            }
            r.put("ingredients", ings);
            r.put("image", resolveImage(it.get("image")));
            data.add(r);
        }
        long total = jdbc.sql("SELECT count(*) FROM \"MenuItem\" WHERE " + where).params(params).query(Long.class).single();
        Object avg = jdbc.sql("SELECT avg(\"basePrice\") FROM \"MenuItem\" WHERE " + where).params(params)
                .query(PosSql.SCALAR).optional().orElse(null);
        long availableCount = jdbc.sql("SELECT count(*) FROM \"MenuItem\" WHERE " + where + " AND \"isAvailable\" = true")
                .params(params).query(Long.class).single();
        Map<String, Object> categoryCounts = new LinkedHashMap<>();
        for (Map<String, Object> g : SqlRows.list(jdbc.sql("SELECT \"categoryId\", count(*) AS n FROM \"MenuItem\" WHERE "
                + categoryWhere + " GROUP BY \"categoryId\"").params(categoryParams))) {
            if (g.get("categoryId") != null) {
                categoryCounts.put((String) g.get("categoryId"), g.get("n"));
            }
        }
        Map<String, Object> meta = Obj.of("page", page, "pageSize", pageSize, "total", total,
                "totalPages", Math.max(1, (long) Math.ceil(total / (double) pageSize)), "availableCount", availableCount,
                "avgPrice", avg == null ? null : PosSql.js(avg), "categoryCounts", categoryCounts);
        return Obj.of("data", data, "meta", meta);
    }

    private Map<String, Object> category(Object categoryId) {
        return categoryId == null ? null : db.first(MenuCategory.class, "id = ?", categoryId);
    }

    private List<Map<String, Object>> ingredientRows(Object menuItemId) {
        return db.list(MenuProduct.class, "\"menuItemId\" = ?", menuItemId);
    }

    /** {@code include: { ingredients: { include: { product: true } }, category: true }}. */
    private Map<String, Object> withIngredientsAndCategory(Map<String, Object> item) {
        Map<String, Object> r = new LinkedHashMap<>(item);
        List<Map<String, Object>> ings = new ArrayList<>();
        for (Map<String, Object> mp : ingredientRows(item.get("id"))) {
            mp.put("product", db.first(Product.class, "id = ?", mp.get("productId")));
            ings.add(mp);
        }
        r.put("ingredients", ings);
        r.put("category", category(item.get("categoryId")));
        return r;
    }

    public Map<String, Object> getOne(String id) {
        Map<String, Object> item = db.first(MenuItem.class, "id = ? AND " + LIVE, id, org());
        if (item == null) {
            throw ApiException.notFound("MenuItem " + id + " not found");
        }
        Map<String, Object> r = withIngredientsAndCategory(item);
        r.put("image", resolveImage(item.get("image")));
        return r;
    }

    public Map<String, Object> create(Map<String, Object> input) {
        boolean tracked = !Boolean.FALSE.equals(input.get("isInventoryTracked"));
        List<Map<String, Object>> ingredients = validateIngredients(PosSql.list(input.get("ingredients")), tracked);
        String stationCode = normalizeStationCode(input.containsKey("stationCode"), input.get("stationCode"));
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> item = db.insert(MenuItem.class, Obj.of("organizationId", orgId,
                    "code", input.get("code"), "name", input.get("name"), "description", input.get("description"),
                    "categoryId", input.get("categoryId"), "basePrice", input.get("basePrice"),
                    "isInventoryTracked", tracked, "image", input.get("image"),
                    "preparationTime", input.get("preparationTime"), "stationCode", stationCode,
                    "isAvailable", input.get("isAvailable") == null ? true : input.get("isAvailable"),
                    "displayOrder", input.get("displayOrder") == null ? 0 : input.get("displayOrder")));
            for (Map<String, Object> ing : ingredients) {
                db.insert(MenuProduct.class, Obj.of("organizationId", orgId, "menuItemId", item.get("id"),
                        "productId", ing.get("productId"), "quantity", ing.get("quantity")));
            }
            Map<String, Object> created = withIngredientsAndCategory(db.first(MenuItem.class, "id = ?", item.get("id")));
            audit.recordInTx(AuditInput.of("MenuItem", (String) item.get("id"), "create", null, created));
            return created;
        });
    }

    public Map<String, Object> update(String id, Map<String, Object> patch) {
        Map<String, Object> data = new LinkedHashMap<>(patch);
        boolean ingredientsPresent = data.containsKey("ingredients");
        boolean hasIngredients = ingredientsPresent && data.get("ingredients") != null;
        Object ingredients = data.remove("ingredients");
        Object isInventoryTracked = data.remove("isInventoryTracked");
        Object expectedUpdatedAt = data.remove("expectedUpdatedAt");
        if (data.containsKey("stationCode")) {
            data.put("stationCode", normalizeStationCode(true, data.get("stationCode")));
        }
        String orgId = org();
        return tx.execute(st -> {
            jdbc.sql("SELECT id FROM \"MenuItem\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, orgId)
                    .query().listOfRows();
            Map<String, Object> existing = db.first(MenuItem.class, "id = ? AND " + LIVE, id, orgId);
            if (existing == null) {
                throw ApiException.notFound("MenuItem " + id + " not found");
            }
            List<Map<String, Object>> existingIngredients = ingredientRows(id);
            existing.put("ingredients", existingIngredients);
            if (PosSql.truthy(expectedUpdatedAt)) {
                Instant expected = PosSql.date(expectedUpdatedAt.toString());
                if (!existing.get("updatedAt").equals(expected)) {
                    throw ApiException.conflict(
                            "This menu item was changed by another user. Refresh it and apply your changes again.");
                }
            }
            boolean tracked = isInventoryTracked != null ? Boolean.TRUE.equals(isInventoryTracked)
                    : Boolean.TRUE.equals(existing.get("isInventoryTracked"));
            List<Map<String, Object>> normalized;
            if (!ingredientsPresent) {
                normalized = new ArrayList<>();
                for (Map<String, Object> ing : existingIngredients) {
                    normalized.add(Obj.of("productId", ing.get("productId"), "quantity",
                            PosSql.dec(ing.get("quantity")).doubleValue()));
                }
            } else if (ingredients == null) {
                throw new IllegalStateException("Cannot read properties of null (reading 'length')");
            } else {
                normalized = validateIngredients(PosSql.list(ingredients), tracked);
            }
            if (tracked && normalized.isEmpty()) {
                throw ApiException.badRequest("Inventory-tracked menu items require at least one recipe ingredient");
            }
            if (!data.isEmpty()) {
                Map<String, Object> d = new LinkedHashMap<>(data);
                if (isInventoryTracked != null) {
                    d.put("isInventoryTracked", tracked);
                }
                db.update(MenuItem.class, d, "id = ? AND " + LIVE, id, orgId);
            }
            if (hasIngredients) {
                jdbc.sql("DELETE FROM \"MenuProduct\" WHERE \"menuItemId\" = ? AND \"organizationId\" = ?")
                        .params(id, orgId).update();
                for (Map<String, Object> ing : normalized) {
                    db.insert(MenuProduct.class, Obj.of("organizationId", orgId, "menuItemId", id,
                            "productId", ing.get("productId"), "quantity", ing.get("quantity")));
                }
            }
            Map<String, Object> updated = withIngredientsAndCategory(db.first(MenuItem.class, "id = ?", id));
            audit.recordInTx(AuditInput.of("MenuItem", id, "update", existing, updated));
            return updated;
        });
    }

    private static List<Map<String, Object>> validateIngredients(List<Object> ingredients, boolean tracked) {
        if (tracked && ingredients.isEmpty()) {
            throw ApiException.badRequest("Inventory-tracked menu items require at least one recipe ingredient");
        }
        Set<Object> seen = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : ingredients) {
            Map<String, Object> ing = PosSql.map(o);
            double quantity = ing.get("quantity") == null ? 1 : PosSql.num(ing.get("quantity"));
            if (!Double.isFinite(quantity) || quantity <= 0) {
                throw ApiException.badRequest("Recipe ingredient quantities must be greater than zero");
            }
            if (!seen.add(ing.get("productId"))) {
                throw ApiException.badRequest("A product can appear only once in a menu recipe");
            }
            out.add(Obj.of("productId", ing.get("productId"), "quantity", quantity));
        }
        return out;
    }

    /** Empty / null clears; a code must name a live, active station. */
    private String normalizeStationCode(boolean present, Object code) {
        if (!present) {
            return null;
        }
        String trimmed = code == null ? "" : code.toString().trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Map<String, Object> station = db.first(KitchenStation.class, "code = ? AND " + LIVE, trimmed, org());
        if (station == null) {
            throw ApiException.badRequest("Unknown kitchen station \"" + trimmed + "\"");
        }
        if (!Boolean.TRUE.equals(station.get("isActive"))) {
            throw ApiException.badRequest("Kitchen station \"" + trimmed + "\" is inactive");
        }
        return (String) station.get("code");
    }

    /** Untyped body: an absent {@code isAvailable} is a no-op update (Prisma drops undefined). */
    public Map<String, Object> setAvailability(String id, Map<String, Object> body) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (body.containsKey("isAvailable")) {
            data.put("isAvailable", body.get("isAvailable"));
        }
        return auditItemMutation(id, "update", data, false);
    }

    public Map<String, Object> disable(String id) {
        return auditItemMutation(id, "update", Obj.of("isAvailable", false), false);
    }

    public Map<String, Object> deleteItem(String id) {
        if (db.first(MenuItem.class, "id = ? AND " + LIVE, id, org()) == null) {
            throw ApiException.notFound("MenuItem " + id + " not found");
        }
        return auditItemMutation(id, "delete", Obj.of("deletedAt", PosDb.raw(NOW), "isAvailable", false), false);
    }

    /** Nest parity: P2025 → 500 on the update (see {@link #restoreCategory}). */
    public Map<String, Object> restoreItem(String id) {
        if (db.first(MenuItem.class, "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NOT NULL", id, org()) == null) {
            throw ApiException.notFound("Deleted MenuItem " + id + " not found");
        }
        return auditItemMutation(id, "restore", Obj.of("deletedAt", null, "isAvailable", true), true);
    }

    public List<Map<String, Object>> listDeletedItems() {
        return db.list(MenuItem.class, "\"organizationId\" = ? AND \"deletedAt\" IS NOT NULL ORDER BY \"deletedAt\" DESC",
                org());
    }

    private Map<String, Object> auditItemMutation(String id, String action, Map<String, Object> data,
            boolean includeDeleted) {
        return tx.execute(st -> {
            Map<String, Object> existing = db.first(MenuItem.class, "id = ? AND \"organizationId\" = ?"
                    + (includeDeleted ? "" : " AND \"deletedAt\" IS NULL"), id, org());
            if (existing == null) {
                throw ApiException.notFound("MenuItem " + id + " not found");
            }
            Map<String, Object> updated = db.update(MenuItem.class, data, "id = ? AND " + LIVE, id, org());
            audit.recordInTx(AuditInput.of("MenuItem", id, action, existing, updated));
            return updated;
        });
    }

    // ───────────────────── Full bundle (terminal) ─────────────────────

    public Map<String, Object> getFullBundle(String menuItemId) {
        String orgId = org();
        Map<String, Object> item = db.first(MenuItem.class, "id = ? AND " + LIVE, menuItemId, orgId);
        if (item == null) {
            return null;
        }
        List<Map<String, Object>> variants = new ArrayList<>();
        for (Map<String, Object> v : db.list(MenuItemVariant.class, LIVE + " AND \"menuItemId\" = ? AND \"isActive\" = true"
                + " ORDER BY \"sortOrder\" ASC", orgId, menuItemId)) {
            variants.add(Obj.of("id", v.get("id"), "name", v.get("name"), "price", PosSql.js(v.get("price")),
                    "sortOrder", v.get("sortOrder")));
        }
        List<Map<String, Object>> accGroups = new ArrayList<>();
        for (Map<String, Object> link : SqlRows.list(jdbc.sql("SELECT l.\"accompanimentGroupId\" FROM"
                + " \"MenuItemAccompanimentGroup\" l JOIN \"MenuItem\" mi ON mi.id = l.\"menuItemId\" WHERE"
                + " l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL AND l.\"menuItemId\" = ? AND mi.\"organizationId\" = ?"
                + " ORDER BY l.\"sortOrder\" ASC").params(orgId, menuItemId, orgId))) {
            Map<String, Object> g = db.first(AccompanimentGroup.class, "id = ?", link.get("accompanimentGroupId"));
            if (g == null || !Boolean.TRUE.equals(g.get("isActive"))) {
                continue;
            }
            List<Map<String, Object>> options = new ArrayList<>();
            for (Map<String, Object> o : db.list(AccompanimentOption.class, "\"groupId\" = ? AND \"isActive\" = true"
                    + " ORDER BY \"sortOrder\" ASC", g.get("id"))) {
                options.add(Obj.of("id", o.get("id"), "name", o.get("name"), "priceImpact", PosSql.js(o.get("priceImpact")),
                        "isDefault", o.get("isDefault"), "sortOrder", o.get("sortOrder")));
            }
            accGroups.add(Obj.of("id", g.get("id"), "name", g.get("name"), "isRequired", g.get("isRequired"),
                    "minSelect", g.get("minSelect"), "maxSelect", g.get("maxSelect"), "sortOrder", g.get("sortOrder"),
                    "options", options));
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> link : SqlRows.list(jdbc.sql("SELECT \"modifierGroupId\", \"sortOrder\" FROM"
                + " \"MenuItemModifierGroup\" WHERE " + LIVE + " AND \"menuItemId\" = ? ORDER BY \"sortOrder\" ASC")
                .params(orgId, menuItemId))) {
            Map<String, Object> g = db.first(ModifierGroup.class, "id = ?", link.get("modifierGroupId"));
            if (g == null || !Boolean.TRUE.equals(g.get("isActive"))) {
                continue;
            }
            List<Map<String, Object>> mods = new ArrayList<>();
            for (Map<String, Object> m : db.list(Modifier.class, "\"groupId\" = ? AND \"isActive\" = true"
                    + " ORDER BY \"sortOrder\" ASC", g.get("id"))) {
                mods.add(Obj.of("id", m.get("id"), "name", m.get("name"), "priceDelta", PosSql.js(m.get("priceDelta")),
                        "isDefault", m.get("isDefault"), "sortOrder", m.get("sortOrder")));
            }
            groups.add(Obj.of("id", g.get("id"), "name", g.get("name"),
                    "groupType", g.get("groupType") == null ? "ADD_ON" : g.get("groupType"),
                    "minSelect", g.get("minSelect"), "maxSelect", g.get("maxSelect"), "sortOrder", link.get("sortOrder"),
                    "modifiers", mods));
        }
        return Obj.of("product", Obj.of("id", item.get("id"), "name", item.get("name"),
                        "unitPrice", PosSql.js(item.get("basePrice")), "sku", item.get("code"), "productType", "menu"),
                "variants", variants, "accompanimentGroups", accGroups, "groups", groups);
    }
}
