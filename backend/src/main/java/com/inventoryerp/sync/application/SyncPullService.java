package com.inventoryerp.sync.application;

import com.inventoryerp.accounting.application.PosPaymentMethodService;
import com.inventoryerp.accounting.domain.CashRegister;
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
            "expenseCategories", "stockLocations", "stockLevels", "suppliers");

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

    public Map<String, Object> pull(String cursorRaw, String scopesRaw) {
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
                "serverTime", JsonConfig.toJsIso(serverTime));
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
        List<String> invalid = requested.stream().filter(s -> !SCOPES.contains(s)).toList();
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
        StringBuilder where = new StringBuilder(TenancyScope.predicate(entity, null));
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
                    p.put("tax", pick(db.first(Tax.class, "id = ?", p.get("taxId")), "id", "rate"));
                }
                yield rows;
            }
            case "productCategories" -> changed(ProductCategory.class, since, null);
            case "productPackagings" -> changed(ProductPackaging.class, since, null).stream()
                    .map(r -> pick(r, "id", "productId", "name", "quantity", "barcode", "isActive", "updatedAt")).toList();
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
            case "stockLocations" -> SqlRows.list(jdbc.sql("SELECT id, code, name, type::text AS type FROM"
                    + " \"InventoryLocation\" WHERE \"organizationId\" = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL"
                    + " AND type::text <> 'transit' ORDER BY code ASC").param(org()));
            case "stockLevels" -> stockLevels(since);
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
                if (cashEq && List.of("cash", "petty_cash", "bank").contains(key)) {
                    roles.add("float_source");
                }
                if (cashEq || List.of("equity", "liability").contains(classification)) {
                    roles.add("pay_in");
                }
                if (cashEq || List.of("expense", "liability", "equity").contains(classification)) {
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
