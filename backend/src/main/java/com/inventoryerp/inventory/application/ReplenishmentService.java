package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.StockService.NOW;

import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replenishment rules and alerts (INV-3). A scan compares each rule's stock position (on hand less active
 * reservations, plus supply already ordered or requested for the location from {@link IncomingSupplyProvider}s) with
 * its minimum and raises one live alert per rule; stock recovering resolves the alert. Asking for a
 * requisition marks alerts {@code requested} and publishes {@code inventory.replenishment.requested} in the same
 * transaction; procurement, which depends on inventory and not the other way round, raises a draft purchase request per
 * supplier and calls {@link #markRequisitioned}. Nothing here moves stock or posts to the ledger.
 */
@Service
public class ReplenishmentService {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentService.class);
    private static final String LIVE = "('open', 'requested', 'requisitioned')";
    private static final String RULE_SELECT = """
            SELECT r.*, p.code AS "productCode", p.name AS "productName", p."uomId", l.code AS "locationCode",
                   l.name AS "locationName", COALESCE(r."supplierId", p."supplierId") AS "effectiveSupplierId",
                   s.name AS "supplierName", sl.code AS "sourceLocationCode", sl.name AS "sourceLocationName",
                   pv.name AS "variantName"
            FROM "InventoryReplenishmentRule" r
            JOIN "Product" p ON p.id = r."productId"
            LEFT JOIN "ProductVariant" pv ON pv.id = r."variantId"
            JOIN "InventoryLocation" l ON l.id = r."locationId"
            LEFT JOIN "InventoryLocation" sl ON sl.id = r."sourceLocationId"
            LEFT JOIN "Partner" s ON s.id = COALESCE(r."supplierId", p."supplierId")
              AND s."organizationId" = r."organizationId"
            """;
    private static final String ALERT_SELECT = """
            SELECT a.*, p.code AS "productCode", p.name AS "productName", p."uomId", l.code AS "locationCode",
                   l.name AS "locationName", r."leadTimeDays", COALESCE(r."supplierId", p."supplierId") AS "supplierId",
                   s.name AS "supplierName", r."sourceLocationId", sl.name AS "sourceLocationName",
                   r.route AS "ruleRoute", pv.name AS "variantName"
            FROM "InventoryReplenishmentAlert" a
            JOIN "InventoryReplenishmentRule" r ON r.id = a."ruleId"
            JOIN "Product" p ON p.id = a."productId"
            LEFT JOIN "ProductVariant" pv ON pv.id = a."variantId"
            JOIN "InventoryLocation" l ON l.id = a."locationId"
            LEFT JOIN "InventoryLocation" sl ON sl.id = r."sourceLocationId"
            LEFT JOIN "Partner" s ON s.id = COALESCE(r."supplierId", p."supplierId")
              AND s."organizationId" = a."organizationId"
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventOutboxService outbox;
    private final NotificationsService notifications;
    private final List<IncomingSupplyProvider> incomingSupply;
    private final StockDocService stockDocs;
    private final LocationScope scope;
    private final TransactionTemplate tx;

    public ReplenishmentService(JdbcClient jdbc, AuditService audit, EventOutboxService outbox,
            NotificationsService notifications, List<IncomingSupplyProvider> incomingSupply,
            StockDocService stockDocs, LocationScope scope, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.notifications = notifications;
        this.incomingSupply = incomingSupply;
        this.stockDocs = stockDocs;
        this.scope = scope;
        this.tx = new TransactionTemplate(txm);
    }

    /**
     * h-01 F4: a branch-restricted user sees and manages only the rules and alerts of their locations. An explicit
     * {@code locationId} outside their access is refused; without one the list keeps to their locations.
     */
    private void scopeFilter(Map<String, String> q, String column, StringBuilder where, List<Object> params) {
        if (!blank(q.get("locationId"))) {
            scope.assertAllowed(q.get("locationId"));
            where.append(" AND ").append(column).append(" = ?");
            params.add(q.get("locationId"));
        } else {
            DataScope s = scope.current();
            if (s.restricted()) {
                where.append(" AND ").append(column).append(" = ANY(?::text[])");
                params.add(s.locationArray());
            }
        }
    }

    private BigDecimal incoming(String productId, String variantId, String locationId, boolean onlyRule) {
        BigDecimal total = BigDecimal.ZERO;
        for (IncomingSupplyProvider p : incomingSupply) {
            total = total.add(p.incoming(productId, variantId, locationId, onlyRule));
        }
        return total;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    private static String str(Object v) {
        return blank(v) ? null : v.toString().trim();
    }

    private static BigDecimal dec(Object v, String what) {
        if (blank(v)) {
            return null;
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid number for " + what + ": " + v);
        }
    }

    private static int intParam(String v, int fallback) {
        try {
            return blank(v) ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ─── rules ───

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> listRules(Map<String, String> q) {
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        scopeFilter(q, "r.\"locationId\"", where, params);
        if (!blank(q.get("q"))) {
            String like = "%" + BaseCrudService.escapeLike(q.get("q").trim()) + "%";
            where.append(" AND (p.code ILIKE ? OR p.name ILIKE ?)");
            params.addAll(List.of(like, like));
        }
        return page(RULE_SELECT + where, " ORDER BY p.code, l.code", "SELECT count(*) FROM"
                + " \"InventoryReplenishmentRule\" r"
                + " JOIN \"Product\" p ON p.id = r.\"productId\"" + where, params, q);
    }

    private PageResult<Map<String, Object>> page(String select, String order, String count, List<Object> params,
            Map<String, String> q) {
        int page = Math.max(1, intParam(q.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, intParam(q.get("pageSize"), 25)));
        long total = jdbc.sql(count).params(params).query(Long.class).single();
        List<Object> paged = new ArrayList<>(params);
        paged.add(pageSize);
        paged.add((page - 1) * pageSize);
        return PageResult.of(SqlRows.list(jdbc.sql(select + order + " LIMIT ? OFFSET ?").params(paged)), page, pageSize,
                total);
    }

    @Transactional
    public Map<String, Object> createRule(Map<String, Object> dto) {
        String productId = str(dto.get("productId"));
        String locationId = str(dto.get("locationId"));
        if (productId == null || locationId == null) {
            throw ApiException.badRequest("productId and locationId are required");
        }
        assertExists("Product", productId, "Product not found");
        assertExists("InventoryLocation", locationId, "Location not found");
        scope.assertAllowed(locationId);
        String variantId = ruleVariant(productId, str(dto.get("variantId")));
        Levels levels = levels(dto, null);
        Long taken = jdbc.sql("SELECT count(*) FROM \"InventoryReplenishmentRule\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(org(), productId, variantId, locationId)
                .query(Long.class).single();
        if (taken > 0) {
            throw ApiException.badRequest("This product already has a rule at this location");
        }
        String supplierId = str(dto.get("supplierId"));
        if (supplierId != null) {
            assertExists("Partner", supplierId, "Supplier not found");
        }
        Route route = route(dto, null, locationId);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryReplenishmentRule\" (id, \"organizationId\", \"productId\", \"locationId\","
                + " \"minQty\", \"maxQty\", \"reorderQty\", \"supplierId\", \"leadTimeDays\", \"autoRequisition\","
                + " \"createdBy\", route, \"sourceLocationId\", \"variantId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?)")
                .params(id, org(), productId, locationId, levels.min(), levels.max(), levels.reorder(), supplierId,
                        leadTime(dto.get("leadTimeDays"), 0), Boolean.TRUE.equals(dto.get("autoRequisition")),
                        TenantContext.userId(), route.route(), route.sourceLocationId(), variantId).update();
        audit.recordInTx(AuditInput.of("InventoryReplenishmentRule", id, "create", null, Obj.of("productId", productId,
                "locationId", locationId, "minQty", levels.min().toPlainString(), "route", route.route())));
        return rule(id);
    }

    @Transactional
    public Map<String, Object> updateRule(String id, Map<String, Object> dto) {
        Map<String, Object> row = rule(id);
        scope.assertAllowed((String) row.get("locationId"));
        Levels levels = levels(dto, row);
        String supplierId = dto.containsKey("supplierId") ? str(dto.get("supplierId")) : (String) row.get("supplierId");
        if (supplierId != null) {
            assertExists("Partner", supplierId, "Supplier not found");
        }
        int lead = leadTime(dto.get("leadTimeDays"), ((Number) row.get("leadTimeDays")).intValue());
        boolean auto = dto.containsKey("autoRequisition") ? Boolean.TRUE.equals(dto.get("autoRequisition"))
                : Boolean.TRUE.equals(row.get("autoRequisition"));
        boolean active = dto.containsKey("isActive") ? Boolean.TRUE.equals(dto.get("isActive"))
                : Boolean.TRUE.equals(row.get("isActive"));
        Route route = route(dto, row, (String) row.get("locationId"));
        jdbc.sql("UPDATE \"InventoryReplenishmentRule\" SET \"minQty\" = ?, \"maxQty\" = ?, \"reorderQty\" = ?,"
                + " \"supplierId\" = ?, \"leadTimeDays\" = ?, \"autoRequisition\" = ?, \"isActive\" = ?, \"updatedBy\""
                + " = ?, route = ?, \"sourceLocationId\" = ?,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(levels.min(), levels.max(), levels.reorder(), supplierId, lead, auto, active,
                        TenantContext.userId(), route.route(), route.sourceLocationId(), id, org()).update();
        audit.recordInTx(AuditInput.of("InventoryReplenishmentRule", id, "update", null, Obj.of("minQty",
                levels.min().toPlainString(), "isActive", active, "route", route.route())));
        return rule(id);
    }

    /** Delete a rule; its open alert is dismissed. */
    @Transactional
    public Map<String, Object> deleteRule(String id) {
        scope.assertAllowed((String) rule(id).get("locationId"));
        jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'dismissed', \"statusReason\" = 'Rule deleted',"
                + " \"updatedAt\" = " + NOW + " WHERE \"ruleId\" = ? AND \"organizationId\" = ? AND status = 'open'")
                .params(id, org()).update();
        jdbc.sql("UPDATE \"InventoryReplenishmentRule\" SET \"deletedAt\" = " + NOW + ", \"isActive\" = false,"
                + " \"updatedBy\" = ? WHERE id = ? AND \"organizationId\" = ?").params(TenantContext.userId(), id,
                org())
                .update();
        audit.recordInTx(AuditInput.of("InventoryReplenishmentRule", id, "update", null, Obj.of("deleted", true)));
        return Obj.of("ok", true);
    }

    /**
     * Phase I2 (audit V-10, D2): a rule for a product with variants watches one active variant of its own (stock is
     * held and bought per variant); a product without variants takes no variant.
     */
    private String ruleVariant(String productId, String variantId) {
        boolean hasVariants = Boolean.TRUE.equals(jdbc.sql("SELECT \"hasVariants\" FROM \"Product\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(productId, org()).query(Boolean.class).optional().orElse(false));
        if (variantId == null) {
            if (hasVariants) {
                String name = jdbc.sql("SELECT name FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(productId, org()).query(String.class).single();
                throw ApiException.badRequest("Choose a variant for " + name);
            }
            return null;
        }
        Long ok = jdbc.sql("SELECT count(*) FROM \"ProductVariant\" WHERE id = ? AND \"productId\" = ? AND"
                + " \"organizationId\" = ? AND \"isActive\" AND \"deletedAt\" IS NULL").params(variantId, productId, org())
                .query(Long.class).single();
        if (ok == 0) {
            throw ApiException.badRequest("Variant not found");
        }
        return variantId;
    }

    private record Levels(BigDecimal min, BigDecimal max, BigDecimal reorder) {}

    /** How a rule is replenished (PR-5): bought, manufactured, or transferred from {@code sourceLocationId}. */
    private record Route(String route, String sourceLocationId) {}

    private static final List<String> ROUTES = List.of("buy", "manufacture", "transfer");

    private Route route(Map<String, Object> dto, Map<String, Object> row, String locationId) {
        String route = dto.containsKey("route") ? str(dto.get("route"))
                : row == null ? null : (String) row.get("route");
        if (route == null) {
            route = "buy";
        }
        if (!ROUTES.contains(route)) {
            throw ApiException.badRequest("route must be one of: buy, manufacture, transfer");
        }
        if (!"transfer".equals(route)) {
            return new Route(route, null);
        }
        String source = dto.containsKey("sourceLocationId") ? str(dto.get("sourceLocationId"))
                : row == null ? null : (String) row.get("sourceLocationId");
        if (source == null) {
            throw ApiException.badRequest("A transfer rule needs a sourceLocationId");
        }
        if (source.equals(locationId)) {
            throw ApiException.badRequest("The source location must differ from the rule's location");
        }
        assertExists("InventoryLocation", source, "Source location not found");
        // R2: the caller must be allowed to move stock out of a source they name (a kept source is not re-checked, so
        // a branch user can still edit the levels of a rule an unrestricted user set up).
        if (row == null || !source.equals(row.get("sourceLocationId"))) {
            scope.assertAllowed(source);
        }
        return new Route(route, source);
    }

    private static Levels levels(Map<String, Object> dto, Map<String, Object> row) {
        BigDecimal min = dto.containsKey("minQty") || row == null ? dec(dto.get("minQty"), "minQty")
                : SqlRows.dec(row, "minQty");
        BigDecimal max = dto.containsKey("maxQty") || row == null ? dec(dto.get("maxQty"), "maxQty")
                : row.get("maxQty") == null ? null : SqlRows.dec(row, "maxQty");
        BigDecimal reorder = dto.containsKey("reorderQty") || row == null ? dec(dto.get("reorderQty"), "reorderQty")
                : row.get("reorderQty") == null ? null : SqlRows.dec(row, "reorderQty");
        if (min == null || min.signum() <= 0) {
            throw ApiException.badRequest("minQty must be positive");
        }
        if (max != null && max.compareTo(min) < 0) {
            throw ApiException.badRequest("maxQty cannot be below minQty");
        }
        if (reorder != null && reorder.signum() <= 0) {
            throw ApiException.badRequest("reorderQty must be positive");
        }
        return new Levels(min, max, reorder);
    }

    private static int leadTime(Object v, int fallback) {
        BigDecimal n = dec(v, "leadTimeDays");
        int days = n == null ? fallback : n.intValue();
        if (days < 0) {
            throw ApiException.badRequest("leadTimeDays cannot be negative");
        }
        return days;
    }

    private Map<String, Object> rule(String id) {
        Map<String, Object> row = SqlRows.one(jdbc.sql(RULE_SELECT + " WHERE r.id = ? AND r.\"organizationId\" = ? AND"
                + " r.\"deletedAt\" IS NULL").params(id, org()));
        if (row == null) {
            throw ApiException.notFound("Replenishment rule not found");
        }
        return row;
    }

    private void assertExists(String table, String id, String message) {
        Long n = jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.notFound(message);
        }
    }

    // ─── alerts ───

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> listAlerts(Map<String, String> q) {
        StringBuilder where = new StringBuilder(" WHERE a.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if ("live".equals(q.get("status"))) {
            where.append(" AND a.status IN ").append(LIVE);
        } else if (!blank(q.get("status"))) {
            where.append(" AND a.status = ?");
            params.add(q.get("status"));
        }
        scopeFilter(q, "a.\"locationId\"", where, params);
        return page(ALERT_SELECT + where, " ORDER BY a.\"createdAt\" DESC, a.id", "SELECT count(*) FROM"
                + " \"InventoryReplenishmentAlert\" a" + where, params, q);
    }

    @Transactional
    public Map<String, Object> dismiss(String id, Map<String, Object> dto) {
        if (blank(dto.get("reason"))) {
            throw ApiException.badRequest("A reason is required");
        }
        jdbc.sql("SELECT \"locationId\" FROM \"InventoryReplenishmentAlert\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).query(String.class).optional().ifPresent(scope::assertAllowed);
        int n = jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'dismissed', \"statusReason\" = ?,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = 'open'")
                .params(str(dto.get("reason")), id, org()).update();
        if (n == 0) {
            throw ApiException.badRequest("Only an open alert can be dismissed");
        }
        audit.recordInTx(AuditInput.of("InventoryReplenishmentAlert", id, "update", Obj.of("status", "open"),
                Obj.of("status", "dismissed", "reason", dto.get("reason"))));
        return alert(id);
    }

    private Map<String, Object> alert(String id) {
        return SqlRows.one(jdbc.sql(ALERT_SELECT + " WHERE a.id = ? AND a.\"organizationId\" = ?").params(id, org()));
    }

    /** What checking one rule did: {@code raisedId} is the new alert, when one was raised. */
    private record Checked(String raisedId, boolean refreshed, int resolved) {

        static final Checked NOTHING = new Checked(null, false, 0);
    }

    /**
     * Check every active rule (a branch-restricted user: the rules of their locations, h-01 F4). A dismissed alert stays
     * dismissed until stock recovers and falls again.
     *
     * <p>h-01 F5: each rule is checked in its own transaction, and the automatic requests of the new alerts run after
     * the checks, each request (all buy and manufacture lines together, each transfer on its own) in its own
     * transaction. A rule or request that fails (a deleted source location, a transfer the user may not raise) is
     * logged and counted in {@code failed} / {@code requestFailed}; its alert stays open with the reason, and the rest
     * of the scan is kept.
     */
    public Map<String, Object> scan() {
        List<Map<String, Object>> rules = tx.execute(s -> SqlRows.list(jdbc.sql(RULE_SELECT
                + " WHERE r.\"organizationId\" = ? AND r.\"isActive\" AND r.\"deletedAt\" IS NULL ORDER BY r.id")
                .param(org())));
        Map<Object, Long> rulesPerProduct = new java.util.HashMap<>();
        for (Map<String, Object> rule : rules) {
            rulesPerProduct.merge(rule.get("productId") + "::" + rule.get("variantId"), 1L, Long::sum);
        }
        DataScope within = scope.current();
        if (within.restricted()) {
            rules = rules.stream().filter(r -> within.allowsLocation((String) r.get("locationId"))).toList();
        }
        int raised = 0;
        int refreshed = 0;
        int resolved = 0;
        int failed = 0;
        List<String> buyOrMake = new ArrayList<>();
        Map<String, List<String>> transfers = new LinkedHashMap<>();
        for (Map<String, Object> rule : rules) {
            boolean onlyRule = rulesPerProduct.get(rule.get("productId") + "::" + rule.get("variantId")) == 1L;
            Checked c;
            try {
                c = tx.execute(s -> check(rule, onlyRule));
            } catch (RuntimeException e) {
                failed++;
                log.warn("Replenishment rule {} could not be checked: {}", rule.get("id"), e.toString());
                continue;
            }
            if (c == null) {
                continue;
            }
            refreshed += c.refreshed() ? 1 : 0;
            resolved += c.resolved();
            if (c.raisedId() != null) {
                raised++;
                if (Boolean.TRUE.equals(rule.get("autoRequisition"))) {
                    if ("transfer".equals(rule.get("route"))) {
                        transfers.computeIfAbsent(rule.get("sourceLocationId") + "|" + rule.get("locationId"),
                                k -> new ArrayList<>()).add(c.raisedId());
                    } else {
                        buyOrMake.add(c.raisedId());
                    }
                }
            }
        }
        int requested = 0;
        int requestFailed = 0;
        List<List<String>> requests = new ArrayList<>(transfers.values());
        if (!buyOrMake.isEmpty()) {
            requests.addFirst(buyOrMake);
        }
        for (List<String> ids : requests) {
            try {
                Integer n = tx.execute(s -> request(ids));
                requested += n == null ? 0 : n;
            } catch (RuntimeException e) {
                requestFailed += ids.size();
                log.warn("Automatic replenishment request for alert(s) {} failed: {}", ids, e.toString());
                noteRequestFailure(ids, e);
            }
        }
        if (raised > 0) {
            int n = raised;
            tx.executeWithoutResult(s -> notifyPlanners(n));
        }
        return Obj.of("rules", rules.size(), "raised", raised, "refreshed", refreshed, "resolved", resolved,
                "requested", requested, "failed", failed, "requestFailed", requestFailed);
    }

    /** One rule of {@link #scan}, inside its own transaction. */
    private Checked check(Map<String, Object> rule, boolean onlyRule) {
        String ruleId = (String) rule.get("id");
        // Phase I2 (audit V-10): a variant rule compares that variant's quant, reservations and supply; a rule
        // without a variant keeps the product total.
        String variantId = (String) rule.get("variantId");
        BigDecimal onHand = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ?"
                + " AND (?::text IS NULL OR \"variantId\" = ?::text)").params(org(), rule.get("productId"),
                rule.get("locationId"), variantId, variantId).query(BigDecimal.class).single();
        BigDecimal reserved = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ? AND status = 'active'"
                + " AND (?::text IS NULL OR \"variantKey\" = ?::text)")
                .params(org(), rule.get("productId"), rule.get("locationId"), variantId, variantId)
                .query(BigDecimal.class).single();
        BigDecimal available = onHand.subtract(reserved);
        BigDecimal incoming = incoming((String) rule.get("productId"), variantId, (String) rule.get("locationId"),
                onlyRule);
        BigDecimal min = SqlRows.dec(rule, "minQty");
        BigDecimal suggested = ReplenishmentCalculator.suggest(available.add(incoming), min,
                rule.get("maxQty") == null ? null : SqlRows.dec(rule, "maxQty"),
                rule.get("reorderQty") == null ? null : SqlRows.dec(rule, "reorderQty"));
        Map<String, Object> live = SqlRows.one(jdbc.sql("SELECT id, status FROM \"InventoryReplenishmentAlert\""
                + " WHERE"
                + " \"ruleId\" = ? AND \"organizationId\" = ? AND status IN " + LIVE + " FOR UPDATE")
                .params(ruleId, org()));
        boolean shortOnHand = available.compareTo(min) < 0;
        if (suggested.signum() == 0 && shortOnHand && live != null && !"open".equals(live.get("status"))) {
            // Covered by what this alert already ordered: keep it until the goods arrive.
            return Checked.NOTHING;
        }
        if (suggested.signum() == 0) {
            // Stock is back (or already on order): live alerts are over, and a dismissed one no longer silences the
            // rule.
            return new Checked(null, false, jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'resolved',"
                    + " \"resolvedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE \"ruleId\" = ? AND"
                    + " \"organizationId\" = ? AND status IN ('open', 'requested', 'requisitioned', 'dismissed')")
                    .params(ruleId, org()).update());
        }
        if (live != null) {
            if ("open".equals(live.get("status"))) {
                jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET \"onHand\" = ?, reserved = ?, available = ?,"
                        + " incoming = ?, \"suggestedQty\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                        .params(onHand, reserved, available, incoming, suggested, live.get("id")).update();
                return new Checked(null, true, 0);
            }
            return Checked.NOTHING;
        }
        if (dismissedSinceRecovery(ruleId)) {
            return Checked.NOTHING;
        }
        String id = UUID.randomUUID().toString();
        int inserted = jdbc.sql("INSERT INTO \"InventoryReplenishmentAlert\" (id, \"organizationId\", \"ruleId\","
                + " \"productId\", \"locationId\", \"onHand\", reserved, available, incoming, \"minQty\","
                + " \"suggestedQty\", route, \"variantId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT DO NOTHING")
                .params(id, org(), ruleId, rule.get("productId"), rule.get("locationId"), onHand, reserved,
                        available, incoming, min, suggested, rule.get("route"), variantId).update();
        return inserted > 0 ? new Checked(id, false, 0) : Checked.NOTHING;
    }

    /** An automatic request failed: its alerts stay open, with the reason for the planner. */
    private void noteRequestFailure(List<String> ids, RuntimeException e) {
        String message = e instanceof ApiException api ? api.getMessage() : "unexpected error";
        String reason = "Automatic request failed: " + message;
        try {
            tx.executeWithoutResult(s -> jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET \"statusReason\" = ?,"
                    + " \"updatedAt\" = " + NOW + " WHERE \"organizationId\" = ? AND status = 'open'"
                    + " AND id = ANY(?::text[])")
                    .params(reason.length() > 500 ? reason.substring(0, 500) : reason, org(),
                            ids.toArray(String[]::new)).update());
        } catch (RuntimeException ignored) {
            log.warn("Could not record why replenishment alert(s) {} were not requested", ids);
        }
    }

    /** The rule's newest alert was dismissed: stay quiet until stock recovers (the scan then resolves it). */
    private boolean dismissedSinceRecovery(String ruleId) {
        List<String> last = jdbc.sql("SELECT status FROM \"InventoryReplenishmentAlert\" WHERE \"ruleId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY \"createdAt\" DESC, id LIMIT 1").params(ruleId, org())
                .query(String.class).list();
        return !last.isEmpty() && "dismissed".equals(last.getFirst());
    }

    /** Ask procurement for purchase requests for these open alerts. */
    @Transactional
    public Map<String, Object> requestRequisition(Map<String, Object> dto) {
        List<String> ids = new ArrayList<>();
        if (dto.get("alertIds") instanceof List<?> list) {
            list.forEach(o -> ids.add(String.valueOf(o)));
        }
        if (ids.isEmpty()) {
            throw ApiException.badRequest("alertIds is required");
        }
        if (scope.restricted()) {
            // h-01 F4: a branch-restricted user asks only for their own locations.
            jdbc.sql("SELECT DISTINCT \"locationId\" FROM \"InventoryReplenishmentAlert\" WHERE \"organizationId\" = ?"
                    + " AND id = ANY(?::text[])").params(org(), ids.toArray(String[]::new)).query(String.class).list()
                    .forEach(scope::assertAllowed);
        }
        int requested = request(ids);
        if (requested == 0) {
            throw ApiException.badRequest("None of these alerts is open");
        }
        return Obj.of("requested", requested);
    }

    /**
     * Answer open alerts by their rule's route (PR-5). Transfers are created here, in this transaction, as pending stock
     * transfers (one per source and destination). Buy and manufacture lines go out in one
     * {@code inventory.replenishment.requested} event, each line tagged with its {@code route}: procurement answers buy
     * lines with draft purchase requests, manufacturing answers manufacture lines with a production request.
     */
    private int request(List<String> ids) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(ALERT_SELECT + " WHERE a.\"organizationId\" = ? AND"
                + " a.status"
                + " = 'open' AND a.id = ANY(?::text[]) FOR UPDATE OF a").params(org(), ids.toArray(String[]::new)));
        if (rows.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Map<String, Object>> lines = new ArrayList<>();
        Map<String, List<Map<String, Object>>> transfers = new LinkedHashMap<>();
        for (Map<String, Object> a : rows) {
            String route = a.get("ruleRoute") == null ? "buy" : a.get("ruleRoute").toString();
            jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'requested', route = ?, \"statusReason\" = NULL,"
                    + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(route, a.get("id")).update();
            if ("transfer".equals(route)) {
                transfers.computeIfAbsent(a.get("sourceLocationId") + "|" + a.get("locationId"),
                        k -> new ArrayList<>()).add(a);
                continue;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("alertId", a.get("id"));
            line.put("route", route);
            line.put("locationId", a.get("locationId"));
            line.put("productId", a.get("productId"));
            line.put("variantId", a.get("variantId"));
            line.put("description", a.get("productCode") + " " + a.get("productName")
                    + (a.get("variantName") == null ? "" : " (" + a.get("variantName") + ")") + " for "
                    + a.get("locationName"));
            line.put("quantity", SqlRows.dec(a, "suggestedQty").toPlainString());
            line.put("uomId", a.get("uomId"));
            line.put("supplierId", a.get("supplierId"));
            line.put("neededBy", today.plusDays(((Number) a.get("leadTimeDays")).longValue()).toString());
            lines.add(line);
        }
        for (List<Map<String, Object>> group : transfers.values()) {
            raiseTransfer(group);
        }
        if (!lines.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("organizationId", org());
            payload.put("requestedBy", TenantContext.userId());
            payload.put("lines", lines);
            outbox.publish(DomainEvents.INVENTORY_REPLENISHMENT_REQUESTED, payload);
        }
        return rows.size();
    }

    /** One pending stock transfer for alerts sharing a source and a destination; it still needs approval. */
    private void raiseTransfer(List<Map<String, Object>> alerts) {
        List<StockDocService.DocLine> items = new ArrayList<>();
        for (Map<String, Object> a : alerts) {
            items.add(new StockDocService.DocLine((String) a.get("productId"), (String) a.get("variantId"), null, null,
                    null, null, null,
                    null, SqlRows.dec(a, "suggestedQty")));
        }
        Map<String, Object> first = alerts.getFirst();
        // h-01 F5: a rule can outlive its source location (deleting a location does not look at the rules using it).
        String source = (String) first.get("sourceLocationId");
        if (source == null || jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(source, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("The source location of the transfer rule for " + first.get("productName")
                    + " at " + first.get("locationName") + " no longer exists; choose another source location on the"
                    + " rule");
        }
        Map<String, Object> transfer = stockDocs.createTransfer(new StockDocService.DocInput(null,
                (String) first.get("sourceLocationId"), (String) first.get("locationId"), "immediate", null, null, null,
                null, "Replenishment: " + items.size() + " product(s) below minimum", items));
        markDocumented(alerts.stream().map(a -> (String) a.get("id")).toList(), "stock_transfer",
                (String) transfer.get("id"), (String) transfer.get("transferCode"));
    }

    /**
     * Which of these alerts still wait for a document (the event is delivered at least once). h-01 F12: the rows are
     * locked ({@code FOR UPDATE}, in id order) until the caller's transaction ends, so a second delivery of the same
     * event running at the same time waits, then finds them answered and raises nothing.
     */
    @Transactional
    public List<String> stillRequested(List<String> alertIds) {
        return jdbc.sql("SELECT id FROM \"InventoryReplenishmentAlert\" WHERE \"organizationId\" = ? AND status ="
                + " 'requested' AND id = ANY(?::text[]) ORDER BY id FOR UPDATE").params(org(),
                alertIds.toArray(String[]::new)).query(String.class).list();
    }

    /**
     * h-01 F12: run {@code answer} on the alerts still waiting ({@link #stillRequested}, never called with an empty
     * list) inside one transaction that keeps them locked until it commits. For handlers that have no transaction of
     * their own. Returns what {@code answer} returned, or {@code null} when no alert was still waiting.
     */
    @Transactional
    public <T> T answerRequested(List<String> alertIds, Function<List<String>, T> answer) {
        List<String> waiting = stillRequested(alertIds);
        return waiting.isEmpty() ? null : answer.apply(waiting);
    }

    /**
     * Procurement raised the purchase request for these alerts. Returns how many alerts it answered; fewer than asked
     * means another delivery got there first, and the caller must roll back (h-01 F12).
     */
    @Transactional
    public int markRequisitioned(List<String> alertIds, String purchaseRequestId, String requestNumber) {
        return jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'requisitioned', \"purchaseRequestId\" = ?,"
                + " \"requestNumber\" = ?, \"documentType\" = 'purchase_request', \"documentId\" = ?,"
                + " \"documentNumber\" = ?, \"updatedAt\" = " + NOW + " WHERE \"organizationId\" = ? AND status ="
                + " 'requested' AND id = ANY(?::text[])")
                .params(purchaseRequestId, requestNumber, purchaseRequestId, requestNumber, org(),
                        alertIds.toArray(String[]::new)).update();
    }

    /**
     * These alerts were answered by a document other than a purchase request (PR-5): {@code production_request}
     * (manufacturing) or {@code stock_transfer} (here). Returns how many alerts it answered (see
     * {@link #markRequisitioned}).
     */
    @Transactional
    public int markDocumented(List<String> alertIds, String documentType, String documentId,
            String documentNumber) {
        return jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'requisitioned', \"documentType\" = ?,"
                + " \"documentId\" = ?, \"documentNumber\" = ?, \"updatedAt\" = " + NOW + " WHERE"
                + " \"organizationId\" = ? AND status = 'requested' AND id = ANY(?::text[])")
                .params(documentType, documentId, documentNumber, org(), alertIds.toArray(String[]::new)).update();
    }

    /** h-01 F12: refuse to keep a document raised for alerts another delivery already answered. */
    public static void assertAllMarked(int marked, List<String> alertIds) {
        if (marked != alertIds.size()) {
            throw new IllegalStateException("Only " + marked + " of " + alertIds.size() + " replenishment alert(s)"
                    + " were still waiting; another delivery answered them, so this document is rolled back");
        }
    }

    private void notifyPlanners(int raised) {
        List<String> users = jdbc.sql("SELECT DISTINCT u.id FROM \"User\" u JOIN \"_UserRoles\" ur ON ur.\"B\" = u.id"
                + " JOIN"
                + " \"Role\" r ON r.id = ur.\"A\" WHERE u.\"organizationId\" = ? AND u.\"isActive\" AND"
                + " u.\"deletedAt\" IS"
                + " NULL AND 'inventory:replenish' = ANY(r.permissions) LIMIT"
                + " 25").param(org()).query(String.class).list();
        for (String user : users) {
            try {
                notifications.send(new SendInput(org(), user, "in_app", "inventory", "Stock below minimum",
                        raised + " new replenishment alert(s)", Obj.of("kind", "inventory.replenishment", "link",
                                "/inventory/replenishment")));
            } catch (RuntimeException e) {
                log.warn("Replenishment notification not delivered: {}", e.toString());
            }
        }
    }
}
