package com.inventoryerp.sync.application;

import com.inventoryerp.accounting.application.PosPaymentMethodService;
import com.inventoryerp.accounting.domain.CashRegister;
import com.inventoryerp.core.application.ProductVariantService;
import com.inventoryerp.communication.domain.Conversation;
import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.core.domain.Product;
import com.inventoryerp.core.domain.ProductCategory;
import com.inventoryerp.core.domain.ProductPackaging;
import com.inventoryerp.core.domain.Tax;
import com.inventoryerp.core.domain.UnitOfMeasure;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.crud.TenancyScope;
import com.inventoryerp.kernel.domain.Setting;
import com.inventoryerp.kernel.security.PosEligibility;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.pos.domain.AccompanimentGroup;
import com.inventoryerp.pos.domain.AccompanimentOption;
import com.inventoryerp.pos.domain.MenuCategory;
import com.inventoryerp.pos.domain.MenuItem;
import com.inventoryerp.pos.domain.MenuItemAccompanimentGroup;
import com.inventoryerp.pos.domain.MenuItemModifierGroup;
import com.inventoryerp.pos.domain.MenuItemVariant;
import com.inventoryerp.pos.domain.Modifier;
import com.inventoryerp.pos.domain.ModifierGroup;
import com.inventoryerp.pos.domain.PosTable;
import com.inventoryerp.pos.domain.PosTableReservation;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Incremental pull for offline devices (port of {@code sync-pull.service.ts}). Cursor = base64url(JSON) with one
 * server-clock ISO watermark per scope; each delta scope returns rows with {@code updatedAt > watermark − 5 s}
 * through the tenancy predicate (soft-deleted rows of {@code SOFT_DELETE} models never reach a device, exactly as the
 * Prisma extension filters them). Reference sets are returned in full on every pull.
 */
@Service
public class SyncPullService {

    /** Scopes a device may pull ({@code SYNC_PULL_SCOPES}). */
    public static final List<String> SCOPES = List.of("menuItems", "menuCategories", "modifierGroups", "taxes",
            "posTables", "cashRegisters", "staff", "settings", "products", "productCategories", "productPackagings",
            "partners", "reservations", "conversations", "messages", "paymentMethods", "ledgerAccounts",
            "expenseCategories", "stockLocations", "stockLevels", "suppliers", "productVariants");

    /**
     * ADR-S083 O4 (Extension): warehouse scopes, sent only when asked for by name — a pull without {@code scopes}
     * keeps returning {@link #SCOPES} exactly. Limited to the device's branch when it has one.
     * {@code openPurchaseOrders}, {@code transfers} and {@code uomConversions} are full sets; {@code lots} and
     * {@code serials} are deltas (a snapshot sends only stock on hand; later deltas include rows that reached zero).
     */
    public static final List<String> EXTENDED_SCOPES = List.of("openPurchaseOrders", "transfers", "lots", "serials",
            "uomConversions");

    /** Every scope a device may name. */
    public static boolean known(String scope) {
        return SCOPES.contains(scope) || EXTENDED_SCOPES.contains(scope);
    }

    /** The pulling device's branch (null = every location), for the warehouse scopes. */
    private static final ThreadLocal<String> BRANCH = new ThreadLocal<>();

    static final long OVERLAP_MS = 5_000;
    static final int MESSAGE_PAGE = 200;
    static final long SEQ_COMMIT_LAG_MS = 2_000;

    private final PosDb db;
    private final JdbcClient jdbc;
    private final PosPaymentMethodService paymentMethods;

    public SyncPullService(PosDb db, PosPaymentMethodService paymentMethods) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.paymentMethods = paymentMethods;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** ADR-S083 S7: protocol-2 pulls also ship soft-deleted rows changed since the watermark, as tombstones. */
    private static final ThreadLocal<Boolean> TOMBSTONES = new ThreadLocal<>();

    public Map<String, Object> pull(String cursorRaw, String scopesRaw) {
        return pull(cursorRaw, scopesRaw, false);
    }

    /** Pull; with {@code tombstones} (protocol 2) deltas include soft-deleted rows carrying their {@code deletedAt}. */
    public Map<String, Object> pull(String cursorRaw, String scopesRaw, boolean tombstones) {
        return pull(cursorRaw, scopesRaw, tombstones, null);
    }

    /** Pull for a device bound to {@code branchId} (warehouse scopes are limited to that branch's locations). */
    public Map<String, Object> pull(String cursorRaw, String scopesRaw, boolean tombstones, String branchId) {
        TOMBSTONES.set(tombstones);
        BRANCH.set(branchId);
        try {
            return pullInner(cursorRaw, scopesRaw);
        } finally {
            TOMBSTONES.remove();
            BRANCH.remove();
        }
    }

    /**
     * ADR-S083 C5: every row of one scope as a snapshot page source (no watermark), in a stable id order. Messages are
     * not snapshotted (they page by their own sequence cursor).
     */
    public List<Map<String, Object>> snapshotRows(String scope) {
        return snapshotRows(scope, null);
    }

    /** Snapshot rows for a device bound to {@code branchId}. */
    public List<Map<String, Object>> snapshotRows(String scope, String branchId) {
        if ("messages".equals(scope) || !known(scope)) {
            throw ApiException.badRequest("Scope " + scope + " cannot be snapshotted");
        }
        BRANCH.set(branchId);
        Object rows;
        try {
            rows = readScope(scope, null);
        } finally {
            BRANCH.remove();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows instanceof List<?> l) {
            for (Object o : l) {
                out.add(PosSql.map(o));
            }
        }
        out.sort(java.util.Comparator.comparing(r -> String.valueOf(r.get("id"))));
        return out;
    }

    /** A cursor whose watermarks are all {@code at}: deltas after a completed snapshot start there. */
    public static String cursorAt(List<String> scopes, Instant at) {
        Map<String, Object> c = new LinkedHashMap<>();
        for (String s : scopes) {
            if (!"messages".equals(s)) {
                c.put(s, JsonConfig.toJsIso(at));
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(EntityJson.toJson(c)
                .getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> pullInner(String cursorRaw, String scopesRaw) {
        List<String> scopes = parseScopes(scopesRaw);
        Map<String, Object> cursor = parseCursor(cursorRaw);
        Instant serverTime = Instant.now();
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> nextCursor = new LinkedHashMap<>(cursor);
        for (String scope : scopes) {
            if ("messages".equals(scope)) {
                Object[] page = readMessagesPaged(cursor.get("messagesSeq"));
                data.put("messages", page[0]);
                nextCursor.put("messagesSeq", page[1]);
                nextCursor.put("messagesHasMore", (Boolean) page[2] ? "1" : "0");
                continue;
            }
            Instant since = null;
            Object mark = cursor.get(scope);
            if (PosSql.truthy(mark)) {
                Instant at;
                try {
                    at = Instant.parse(mark.toString());
                } catch (RuntimeException e) {
                    at = com.inventoryerp.kernel.crud.EntityPatch.toInstant(mark.toString());
                }
                since = at.minusMillis(OVERLAP_MS);
            }
            data.put(scope, readScope(scope, since));
            nextCursor.put(scope, JsonConfig.toJsIso(serverTime));
        }
        String json = EntityJson.toJson(nextCursor);
        return Obj.of("data", data,
                "cursor", Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8)),
                "serverTime", JsonConfig.toJsIso(serverTime), "currency", currency());
    }

    /**
     * Phase I6 (additive): the organisation currency and its decimal places, the scale the server rounds line tax to,
     * so an offline device prices a sale to the same total ({@code expectedTotal}).
     */
    private Map<String, Object> currency() {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT o.\"currencyCode\" AS code, c.\"decimalPlaces\" AS"
                + " \"decimalPlaces\" FROM \"Organization\" o LEFT JOIN \"Currency\" c ON c.code = o.\"currencyCode\""
                + " WHERE o.id = ?").param(org()));
        return row == null || row.get("code") == null ? null
                : Obj.of("code", row.get("code"), "decimalPlaces", row.get("decimalPlaces"));
    }

    static List<String> parseScopes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return SCOPES;
        }
        List<String> requested = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (!s.trim().isEmpty()) {
                requested.add(s.trim());
            }
        }
        List<String> invalid = requested.stream().filter(s -> !known(s)).toList();
        if (!invalid.isEmpty()) {
            throw ApiException.badRequest("Unknown sync scope(s): " + String.join(", ", invalid));
        }
        return requested;
    }

    static Map<String, Object> parseCursor(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            String text = new String(Base64.getUrlDecoder().decode(raw.replace('+', '-').replace('/', '_')
                    .replace("=", "")), StandardCharsets.UTF_8);
            JsonNode decoded = EntityJson.tree(text);
            Map<String, Object> out = new LinkedHashMap<>();
            if (decoded != null && decoded.isObject()) {
                for (Map.Entry<String, JsonNode> e : decoded.properties()) {
                    JsonNode v = e.getValue();
                    out.put(e.getKey(), v.isString() ? v.asString() : v.isNull() ? null : v.isNumber() ? v.numberValue()
                            : v.isBoolean() ? (Object) v.booleanValue() : v);
                }
            }
            return out;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Malformed sync cursor — pull from scratch (omit the cursor)");
        }
    }

    // ─── scoped reads ───

    /** Rows of a model under the tenancy predicate, changed since {@code since}, ordered by {@code updatedAt}. */
    private List<Map<String, Object>> changed(Class<?> entity, Instant since, String extra, Object... extraParams) {
        boolean tombstones = since != null && Boolean.TRUE.equals(TOMBSTONES.get()) && TenancyScope.softDeletes(entity);
        StringBuilder where = new StringBuilder(tombstones
                ? (TenancyScope.orgScoped(entity) ? "\"organizationId\" = ?" : "true")
                : TenancyScope.predicate(entity, null));
        List<Object> params = new ArrayList<>();
        if (TenancyScope.orgScoped(entity)) {
            params.add(org());
        }
        if (since != null) {
            where.append(" AND \"updatedAt\" > ?");
            params.add(SqlRows.ts(since));
        }
        if (extra != null) {
            where.append(" AND (").append(extra).append(')');
            params.addAll(List.of(extraParams));
        }
        return db.list(entity, where + " ORDER BY \"updatedAt\" ASC", params.toArray());
    }

    private List<Map<String, Object>> children(Class<?> entity, String fk, Object id, String orderBy) {
        return db.list(entity, "\"" + fk + "\" = ? AND \"deletedAt\" IS NULL ORDER BY " + orderBy, id);
    }

    private Object readScope(String scope, Instant since) {
        return switch (scope) {
            case "menuItems" -> menuItems(since);
            case "menuCategories" -> changed(MenuCategory.class, since, null);
            case "modifierGroups" -> {
                List<Map<String, Object>> rows = changed(ModifierGroup.class, since, null);
                rows.forEach(g -> g.put("modifiers", children(Modifier.class, "groupId", g.get("id"),
                        "\"sortOrder\" ASC")));
                yield rows;
            }
            case "taxes" -> changed(Tax.class, since, null);
            case "posTables" -> changed(PosTable.class, since, null);
            case "cashRegisters" -> changed(CashRegister.class, since, null);
            case "staff" -> staff(since);
            case "products" -> {
                List<Map<String, Object>> rows = changed(Product.class, since, null);
                for (Map<String, Object> p : rows) {
                    p.put("category", pick(db.first(ProductCategory.class, "id = ?", p.get("categoryId")), "id", "name"));
                    p.put("uom", pick(db.first(UnitOfMeasure.class, "id = ?", p.get("uomId")), "id", "name", "code"));
                    // Phase I6: isInclusive lets the device compute the tax the way the server does (additive).
                    p.put("tax", pick(db.first(Tax.class, "id = ?", p.get("taxId")), "id", "rate", "isInclusive"));
                }
                yield rows;
            }
            case "productCategories" -> changed(ProductCategory.class, since, null);
            case "productPackagings" -> changed(ProductPackaging.class, since, null).stream()
                    .map(r -> pick(r, "id", "productId", "name", "quantity", "barcode", "isActive", "updatedAt", "deletedAt"))
                    .toList();
            case "settings" -> changed(Setting.class, since, "\"scopeType\" = 'organization' AND (key LIKE 'pos.%'"
                    + " OR key LIKE 'inventory.%')");
            case "partners" -> changed(Partner.class, since, "\"isCustomer\" = true").stream()
                    .map(r -> pick(r, "id", "code", "name", "phone", "email", "notes", "customFields", "createdAt",
                            "updatedAt", "deletedAt")).toList();
            case "conversations" -> changed(Conversation.class, since, "\"syncToDevices\" = true").stream()
                    .map(r -> pick(r, "id", "kind", "name", "contextType", "contextId", "updatedAt", "deletedAt")).toList();
            case "paymentMethods" -> paymentMethods.terminalPaymentMethods().stream()
                    .map(PosPaymentMethodService.View::toMap).toList();
            case "ledgerAccounts" -> ledgerAccounts();
            case "expenseCategories" -> expenseCategories();
            // branchId: ADR-S083 O4 (additive), so a warehouse device offers its own branch's locations.
            case "stockLocations" -> SqlRows.list(jdbc.sql("SELECT id, code, name, type::text AS type, \"branchId\" FROM"
                    + " \"InventoryLocation\" WHERE \"organizationId\" = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL"
                    + " AND type::text <> 'transit' ORDER BY code ASC").param(org()));
            case "stockLevels" -> stockLevels(since);
            case "openPurchaseOrders" -> openPurchaseOrders();
            case "transfers" -> openTransfers();
            case "lots" -> lots(since);
            case "serials" -> serials(since);
            case "uomConversions" -> uomConversions();
            case "productVariants" -> productVariants(since);
            case "suppliers" -> changed(Partner.class, since, "\"isSupplier\" = true").stream()
                    .map(r -> pick(r, "id", "name", "phone", "notes", "updatedAt", "deletedAt")).toList();
            case "reservations" -> changed(PosTableReservation.class, since, "\"startAt\" >= ?",
                    SqlRows.ts(Instant.now().minusMillis(24L * 60 * 60 * 1000))).stream()
                    .map(r -> pick(r, "id", "tableId", "customerName", "phone", "partySize", "startAt", "endAt", "status",
                            "notes", "seatedOrderId", "updatedAt")).toList();
            default -> List.of();
        };
    }

    private static Map<String, Object> pick(Map<String, Object> row, String... cols) {
        if (row == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String c : cols) {
            out.put(c, row.get(c));
        }
        return out;
    }

    /** MenuItem as a full aggregate: variants, modifier-group links (+ modifiers), accompaniment links (+ options). */
    private List<Map<String, Object>> menuItems(Instant since) {
        List<Map<String, Object>> items = changed(MenuItem.class, since, null);
        for (Map<String, Object> item : items) {
            Object id = item.get("id");
            item.put("variants", children(MenuItemVariant.class, "menuItemId", id, "\"sortOrder\" ASC"));
            List<Map<String, Object>> mods = children(MenuItemModifierGroup.class, "menuItemId", id, "\"sortOrder\" ASC");
            for (Map<String, Object> link : mods) {
                Map<String, Object> group = db.first(ModifierGroup.class, "id = ?", link.get("modifierGroupId"));
                if (group != null) {
                    group.put("modifiers", children(Modifier.class, "groupId", group.get("id"), "\"sortOrder\" ASC"));
                }
                link.put("modifierGroup", group);
            }
            item.put("modifierGroups", mods);
            List<Map<String, Object>> accs = children(MenuItemAccompanimentGroup.class, "menuItemId", id,
                    "\"sortOrder\" ASC");
            for (Map<String, Object> link : accs) {
                Map<String, Object> group = db.first(AccompanimentGroup.class, "id = ?", link.get("accompanimentGroupId"));
                if (group != null) {
                    group.put("options", children(AccompanimentOption.class, "groupId", group.get("id"),
                            "\"sortOrder\" ASC"));
                }
                link.put("accompanimentGroup", group);
            }
            item.put("accompanimentGroups", accs);
        }
        return items;
    }

    /**
     * Staff for offline PIN login: raw (no soft-delete filter, no isActive filter) so revocations arrive as
     * tombstones; {@code isActive} = POS eligibility, and a revoked account never ships a usable PIN hash.
     */
    private List<Map<String, Object>> staff(Instant since) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = "u.\"organizationId\" = ?";
        if (since != null) {
            where += " AND u.\"updatedAt\" > ?";
            params.add(SqlRows.ts(since));
        }
        List<Map<String, Object>> users = SqlRows.list(jdbc.sql("SELECT u.id, u.\"firstName\", u.\"lastName\", u.email,"
                + " u.\"pinHash\", u.\"isActive\", u.\"deletedAt\", u.\"updatedAt\", e.\"employmentStatus\"::text AS es,"
                + " e.\"deletedAt\" AS \"employeeDeletedAt\", e.id AS \"employeeId\" FROM \"User\" u LEFT JOIN \"HrEmployee\" e"
                + " ON e.\"userId\" = u.id WHERE " + where + " ORDER BY u.\"updatedAt\" ASC").params(params));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> u : users) {
            PosEligibility.Employee employee = u.get("employeeId") == null ? null
                    : new PosEligibility.Employee((String) u.get("es"), (Instant) u.get("employeeDeletedAt"));
            boolean allowed = PosEligibility.userMayOperatePos(Boolean.TRUE.equals(u.get("isActive")),
                    (Instant) u.get("deletedAt"), employee);
            List<Map<String, Object>> roles = new ArrayList<>();
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT r.name, r.permissions FROM \"Role\" r JOIN"
                    + " \"_UserRoles\" ur ON ur.\"A\" = r.id WHERE ur.\"B\" = ? ORDER BY r.id").param(u.get("id")))) {
                roles.add(Obj.of("name", r.get("name"), "permissions", r.get("permissions")));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.get("id"));
            row.put("firstName", u.get("firstName"));
            row.put("lastName", u.get("lastName"));
            row.put("email", u.get("email"));
            row.put("pinHash", allowed ? u.get("pinHash") : null);
            row.put("isActive", allowed);
            row.put("deletedAt", u.get("deletedAt"));
            row.put("updatedAt", u.get("updatedAt"));
            row.put("roles", roles);
            out.add(row);
        }
        return out;
    }

    /**
     * Phase I6: the product variants a device can sell, with the effective price the server charges
     * ({@link ProductVariantService#PRICE_SQL}). Read raw so deleted variants arrive as tombstones; a product price
     * change re-sends its variants (their price may follow it).
     */
    private List<Map<String, Object>> productVariants(Instant since) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = "v.\"organizationId\" = ?";
        if (since != null) {
            where += " AND (v.\"updatedAt\" > ? OR p.\"updatedAt\" > ?)";
            params.add(SqlRows.ts(since));
            params.add(SqlRows.ts(since));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT v.id, v.\"productId\", v.sku, v.barcode, v.name,"
                + " " + ProductVariantService.PRICE_SQL + " AS price, v.\"isActive\", COALESCE(v.\"deletedAt\","
                + " p.\"deletedAt\") AS \"deletedAt\", v.\"updatedAt\" FROM \"ProductVariant\" v JOIN \"Product\" p"
                + " ON p.id = v.\"productId\" AND p.\"organizationId\" = v.\"organizationId\" WHERE " + where
                + " ORDER BY v.\"updatedAt\" ASC, v.id ASC").params(params))) {
            r.put("price", PosSql.js(r.get("price")));
            out.add(r);
        }
        return out;
    }

    // ---- ADR-S083 O4 warehouse scopes ----

    /** SQL fragment: the location column belongs to the device's branch (or any location without a branch filter). */
    private String inBranch(String column, List<Object> params) {
        String branch = BRANCH.get();
        if (branch == null) {
            return "true";
        }
        params.add(branch);
        return column + " IN (SELECT id FROM \"InventoryLocation\" WHERE \"branchId\" = ? OR \"branchId\" IS NULL)";
    }

    private static Object jsNum(Object v) {
        return v == null ? null : PosSql.js(v);
    }

    /** Purchase orders that can still be received (branch's, or without a branch), with their lines. */
    private List<Map<String, Object>> openPurchaseOrders() {
        List<Object> params = new ArrayList<>(List.of(org()));
        String branch = BRANCH.get();
        String branchSql = "";
        if (branch != null) {
            branchSql = " AND (po.\"branchId\" = ? OR po.\"branchId\" IS NULL)";
            params.add(branch);
        }
        List<Map<String, Object>> orders = SqlRows.list(jdbc.sql("SELECT po.id, po.\"orderNumber\", po.\"partnerId\","
                + " p.name AS \"partnerName\", po.\"branchId\", po.status::text AS status, po.\"expectedDeliveryDate\","
                + " po.\"updatedAt\" FROM \"PurchaseOrder\" po LEFT JOIN \"Partner\" p ON p.id = po.\"partnerId\""
                + " WHERE po.\"organizationId\" = ? AND po.\"deletedAt\" IS NULL AND po.status::text IN ('approved', 'sent',"
                + " 'acknowledged', 'partially_received')" + branchSql + " ORDER BY po.\"orderDate\" DESC LIMIT 500")
                .params(params));
        if (orders.isEmpty()) {
            return orders;
        }
        Map<Object, List<Map<String, Object>>> lines = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, \"purchaseOrderId\", \"productId\", \"variantId\","
                + " description, quantity, \"receivedQuantity\", \"cancelledQuantity\", \"unitOfMeasureId\", \"uomFactor\","
                + " \"lineNumber\" FROM \"PurchaseOrderLine\" WHERE \"organizationId\" = ? AND \"purchaseOrderId\" ="
                + " ANY(?::text[]) ORDER BY \"lineNumber\" ASC").params(org(),
                        orders.stream().map(o -> String.valueOf(o.get("id"))).toArray(String[]::new)))) {
            for (String k : List.of("quantity", "receivedQuantity", "cancelledQuantity", "uomFactor")) {
                l.put(k, jsNum(l.get(k)));
            }
            lines.computeIfAbsent(l.remove("purchaseOrderId"), x -> new ArrayList<>()).add(l);
        }
        orders.forEach(o -> o.put("lines", lines.getOrDefault(o.get("id"), List.of())));
        return orders;
    }

    /** Transit transfers not yet fully received that touch the branch, with their lines. */
    private List<Map<String, Object>> openTransfers() {
        List<Object> params = new ArrayList<>(List.of(org()));
        String from = inBranch("t.\"fromLocId\"", params);
        String to = inBranch("t.\"toLocId\"", params);
        List<Map<String, Object>> transfers = SqlRows.list(jdbc.sql("SELECT t.id, t.\"transferCode\", t.status::text AS"
                + " status, t.\"fromLocId\", t.\"toLocId\", t.\"dispatchedAt\", t.notes, t.\"updatedAt\" FROM"
                + " \"StockTransfer\" t WHERE t.\"organizationId\" = ? AND t.mode = 'transit' AND t.status::text IN"
                + " ('approved', 'in_transit', 'partially_received') AND (" + from + " OR " + to + ")"
                + " ORDER BY t.\"createdAt\" DESC LIMIT 500").params(params));
        if (transfers.isEmpty()) {
            return transfers;
        }
        Map<Object, List<Map<String, Object>>> items = new LinkedHashMap<>();
        for (Map<String, Object> i : SqlRows.list(jdbc.sql("SELECT id, \"transferId\", \"productId\", \"variantId\","
                + " \"productName\", unit, \"batchNumber\", \"qtyRequested\", \"qtyDispatched\", \"qtyReceived\","
                + " \"qtyDamaged\", \"qtyShort\", \"qtyRecalled\" FROM \"StockTransferItem\" WHERE \"organizationId\" = ?"
                + " AND \"transferId\" = ANY(?::text[])").params(org(),
                        transfers.stream().map(t -> String.valueOf(t.get("id"))).toArray(String[]::new)))) {
            for (String k : List.of("qtyRequested", "qtyDispatched", "qtyReceived", "qtyDamaged", "qtyShort",
                    "qtyRecalled")) {
                i.put(k, jsNum(i.get(k)));
            }
            items.computeIfAbsent(i.remove("transferId"), x -> new ArrayList<>()).add(i);
        }
        transfers.forEach(t -> t.put("items", items.getOrDefault(t.get("id"), List.of())));
        return transfers;
    }

    /** Lots at the branch's locations: on hand for a snapshot, every change for a delta. */
    private List<Map<String, Object>> lots(Instant since) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = since == null ? " AND quantity > 0 AND \"isActive\" = true" : " AND \"updatedAt\" > ?";
        if (since != null) {
            params.add(SqlRows.ts(since));
        }
        String branch = inBranch("\"locationId\"", params);
        List<Map<String, Object>> out = SqlRows.list(jdbc.sql("SELECT id, \"productId\", \"variantId\", \"locationId\","
                + " \"batchNumber\", quantity, \"expiryDate\", \"isActive\", \"updatedAt\" FROM \"InventoryBatch\" WHERE"
                + " \"organizationId\" = ?" + where + " AND " + branch + " ORDER BY \"updatedAt\" ASC").params(params));
        out.forEach(r -> r.put("quantity", jsNum(r.get("quantity"))));
        return out;
    }

    /** Serial numbers at the branch's locations: in stock for a snapshot, every change for a delta. */
    private List<Map<String, Object>> serials(Instant since) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = since == null ? " AND status::text = 'in_stock'" : " AND \"updatedAt\" > ?";
        if (since != null) {
            params.add(SqlRows.ts(since));
        }
        String branch = inBranch("\"locationId\"", params);
        return SqlRows.list(jdbc.sql("SELECT id, \"productId\", \"variantId\", \"locationId\", \"serialNumber\","
                + " status::text AS status, \"updatedAt\" FROM \"InventorySerial\" WHERE \"organizationId\" = ?" + where
                + " AND " + branch + " ORDER BY \"updatedAt\" ASC").params(params));
    }

    /** Active product units with their factor to the product's base unit. */
    private List<Map<String, Object>> uomConversions() {
        List<Map<String, Object>> out = SqlRows.list(jdbc.sql("SELECT pu.id, pu.\"productId\", pu.\"uomId\", u.code AS"
                + " \"uomCode\", u.name AS \"uomName\", pu.factor, pu.\"isPurchase\", pu.\"isSales\" FROM \"ProductUom\" pu"
                + " JOIN \"UnitOfMeasure\" u ON u.id = pu.\"uomId\" WHERE pu.\"organizationId\" = ? AND pu.\"isActive\" = true"
                + " ORDER BY pu.\"productId\", u.code").param(org()));
        out.forEach(r -> r.put("factor", jsNum(r.get("factor"))));
        return out;
    }

    private List<Map<String, Object>> stockLevels(Instant since) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = "\"organizationId\" = ?";
        if (since != null) {
            where += " AND \"updatedAt\" > ?";
            params.add(SqlRows.ts(since));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, \"productId\", \"variantId\", \"locationId\","
                + " quantity, \"updatedAt\" FROM \"StockItem\" WHERE " + where + " ORDER BY \"updatedAt\" ASC")
                .params(params))) {
            r.put("quantity", PosSql.js(r.get("quantity")));
            out.add(r);
        }
        return out;
    }

    /** Device-visible messages in seq order; the cursor only advances past rows older than the commit-lag window. */
    private Object[] readMessagesPaged(Object sinceSeqRaw) {
        BigInteger sinceSeq = PosSql.truthy(sinceSeqRaw) ? new BigInteger(sinceSeqRaw.toString()) : BigInteger.ZERO;
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT m.id, m.\"conversationId\", m.\"senderType\","
                + " m.\"senderUserId\", m.body, m.\"contentType\", m.\"occurredAt\", m.seq, m.\"createdAt\" FROM \"Message\" m"
                + " JOIN \"Conversation\" c ON c.id = m.\"conversationId\" WHERE m.\"organizationId\" = ? AND m.seq > ?"
                + " AND m.\"deletedAt\" IS NULL AND c.\"syncToDevices\" = true AND c.\"deletedAt\" IS NULL"
                + " ORDER BY m.seq ASC LIMIT " + MESSAGE_PAGE).params(org(), sinceSeq.longValue()));
        long safeBefore = System.currentTimeMillis() - SEQ_COMMIT_LAG_MS;
        String nextSeq = sinceSeq.toString();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (((Instant) r.get("createdAt")).toEpochMilli() < safeBefore) {
                nextSeq = r.get("seq").toString();
            }
            out.add(Obj.of("id", r.get("id"), "conversationId", r.get("conversationId"), "senderType", r.get("senderType"),
                    "senderUserId", r.get("senderUserId"), "body", r.get("body"), "contentType", r.get("contentType"),
                    "occurredAt", JsonConfig.toJsIso((Instant) r.get("occurredAt")), "syncSequence", r.get("seq").toString()));
        }
        return new Object[] {out, nextSeq, rows.size() == MESSAGE_PAGE};
    }

    /** Accounts a device may book cash against, with the roles the till needs (drawer, float source, pay in/out). */
    private List<Map<String, Object>> ledgerAccounts() {
        String orgId = org();
        List<Map<String, Object>> accounts = SqlRows.list(jdbc.sql("SELECT a.id, a.code, a.name, c.key AS \"categoryKey\","
                + " c.classification::text AS classification, c.\"isCashEquivalent\" AS \"cashEq\", c.id AS \"catId\""
                + " FROM \"Account\" a LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ?"
                + " AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL AND a.\"isPostable\" = true ORDER BY a.code ASC")
                .param(orgId));
        Set<Object> drawers = new LinkedHashSet<>(jdbc.sql("SELECT \"defaultAccountId\" FROM \"CashRegister\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").param(orgId).query(String.class).list());
        Map<Object, List<String>> mappedRoles = new HashMap<>();
        for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT key, \"accountId\" FROM \"AccountMapping\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND key IN ('cash_short_over', 'default_expense')")
                .param(orgId))) {
            if (m.get("accountId") != null) {
                mappedRoles.computeIfAbsent(m.get("accountId"), k -> new ArrayList<>()).add((String) m.get("key"));
            }
        }
        List<Object> cashIds = new ArrayList<>();
        accounts.forEach(a -> {
            if (Boolean.TRUE.equals(a.get("cashEq"))) {
                cashIds.add(a.get("id"));
            }
        });
        Map<Object, Double> balance = new HashMap<>();
        if (!cashIds.isEmpty()) {
            List<Object> params = new ArrayList<>(List.of(orgId));
            params.addAll(cashIds);
            for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseDebit\") AS d,"
                    + " sum(l.\"baseCredit\") AS c FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                    + " WHERE l.\"organizationId\" = ? AND l.\"accountId\" IN (" + PosSql.marks(cashIds.size()) + ")"
                    + " AND e.status::text IN ('posted', 'reversed') GROUP BY l.\"accountId\"").params(params))) {
                balance.put(b.get("accountId"), PosSql.num(b.get("d")) - PosSql.num(b.get("c")));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : accounts) {
            Object key = a.get("categoryKey");
            Object classification = a.get("classification");
            boolean cashEq = Boolean.TRUE.equals(a.get("cashEq"));
            boolean isDrawer = drawers.contains(a.get("id"));
            List<String> roles = new ArrayList<>(mappedRoles.getOrDefault(a.get("id"), List.of()));
            if (isDrawer) {
                roles.add("drawer");
            } else {
                if (cashEq && key != null && List.of("cash", "petty_cash", "bank").contains(key)) {
                    roles.add("float_source");
                }
                if (cashEq || classification != null && List.of("equity", "liability").contains(classification)) {
                    roles.add("pay_in");
                }
                if (cashEq || classification != null && List.of("expense", "liability", "equity").contains(classification)) {
                    roles.add("pay_out");
                }
                if (cashEq) {
                    roles.add("expense_payment");
                }
            }
            if (roles.isEmpty()) {
                continue;
            }
            out.add(Obj.of("id", a.get("id"), "code", a.get("code"), "name", a.get("name"), "categoryKey", key,
                    "classification", classification, "isCashEquivalent", cashEq,
                    "balance", cashEq ? PosSql.js(balance.getOrDefault(a.get("id"), 0.0)) : null, "roles", roles));
        }
        return out;
    }

    /** Active expense categories with the GL account a drawer pay-out for them debits. */
    private List<Map<String, Object>> expenseCategories() {
        String orgId = org();
        List<Map<String, Object>> categories = SqlRows.list(jdbc.sql("SELECT id, name, \"ledgerAccountId\" FROM"
                + " \"ExpenseCategory\" WHERE \"organizationId\" = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL"
                + " ORDER BY name ASC").param(orgId));
        String expenseAccounts = "FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND a.\"isPostable\" = true AND a.\"isActive\" = true"
                + " AND c.classification::text = 'expense'";
        Set<String> valid = new LinkedHashSet<>(jdbc.sql("SELECT a.id " + expenseAccounts).param(orgId)
                .query(String.class).list());
        String mapped = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL AND key = 'default_expense' LIMIT 1").param(orgId).query(String.class).optional()
                .orElse(null);
        String fallback = jdbc.sql("SELECT a.id " + expenseAccounts + " AND a.\"deprecatedAt\" IS NULL ORDER BY"
                + " a.\"sortOrder\" ASC, a.code ASC LIMIT 1").param(orgId).query(String.class).optional().orElse(null);
        String orgDefault = mapped != null ? mapped : fallback;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : categories) {
            Map<String, Object> row = new LinkedHashMap<>(c);
            Object ledger = c.get("ledgerAccountId");
            row.put("accountId", ledger != null && valid.contains(ledger) ? ledger : orgDefault);
            out.add(row);
        }
        return out;
    }
}
