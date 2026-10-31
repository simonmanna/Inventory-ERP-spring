package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.StockService.NOW;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replenishment rules and alerts (INV-3). A scan compares each rule's available stock (on hand less active
 * reservations) with its minimum and raises one live alert per rule; stock recovering resolves the alert. Asking for a
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
                   s.name AS "supplierName"
            FROM "InventoryReplenishmentRule" r
            JOIN "Product" p ON p.id = r."productId"
            JOIN "InventoryLocation" l ON l.id = r."locationId"
            LEFT JOIN "Partner" s ON s.id = COALESCE(r."supplierId", p."supplierId")
            """;
    private static final String ALERT_SELECT = """
            SELECT a.*, p.code AS "productCode", p.name AS "productName", p."uomId", l.code AS "locationCode",
                   l.name AS "locationName", r."leadTimeDays", COALESCE(r."supplierId", p."supplierId") AS "supplierId",
                   s.name AS "supplierName"
            FROM "InventoryReplenishmentAlert" a
            JOIN "InventoryReplenishmentRule" r ON r.id = a."ruleId"
            JOIN "Product" p ON p.id = a."productId"
            JOIN "InventoryLocation" l ON l.id = a."locationId"
            LEFT JOIN "Partner" s ON s.id = COALESCE(r."supplierId", p."supplierId")
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventOutboxService outbox;
    private final NotificationsService notifications;

    public ReplenishmentService(JdbcClient jdbc, AuditService audit, EventOutboxService outbox,
            NotificationsService notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.notifications = notifications;
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
        if (!blank(q.get("locationId"))) {
            where.append(" AND r.\"locationId\" = ?");
            params.add(q.get("locationId"));
        }
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
        Levels levels = levels(dto, null);
        Long taken = jdbc.sql("SELECT count(*) FROM \"InventoryReplenishmentRule\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ? AND \"deletedAt\" IS NULL").params(org(), productId,
                locationId)
                .query(Long.class).single();
        if (taken > 0) {
            throw ApiException.badRequest("This product already has a rule at this location");
        }
        String supplierId = str(dto.get("supplierId"));
        if (supplierId != null) {
            assertExists("Partner", supplierId, "Supplier not found");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryReplenishmentRule\" (id, \"organizationId\", \"productId\", \"locationId\","
                + " \"minQty\", \"maxQty\", \"reorderQty\", \"supplierId\", \"leadTimeDays\", \"autoRequisition\","
                + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), productId, locationId, levels.min(), levels.max(), levels.reorder(), supplierId,
                        leadTime(dto.get("leadTimeDays"), 0), Boolean.TRUE.equals(dto.get("autoRequisition")),
                        TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("InventoryReplenishmentRule", id, "create", null, Obj.of("productId", productId,
                "locationId", locationId, "minQty", levels.min().toPlainString())));
        return rule(id);
    }

    @Transactional
    public Map<String, Object> updateRule(String id, Map<String, Object> dto) {
        Map<String, Object> row = rule(id);
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
        jdbc.sql("UPDATE \"InventoryReplenishmentRule\" SET \"minQty\" = ?, \"maxQty\" = ?, \"reorderQty\" = ?,"
                + " \"supplierId\" = ?, \"leadTimeDays\" = ?, \"autoRequisition\" = ?, \"isActive\" = ?, \"updatedBy\""
                + " = ?,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(levels.min(), levels.max(), levels.reorder(), supplierId, lead, auto, active,
                        TenantContext.userId(), id, org()).update();
        audit.recordInTx(AuditInput.of("InventoryReplenishmentRule", id, "update", null, Obj.of("minQty",
                levels.min().toPlainString(), "isActive", active)));
        return rule(id);
    }

    /** Delete a rule; its open alert is dismissed. */
    @Transactional
    public Map<String, Object> deleteRule(String id) {
        rule(id);
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

    private record Levels(BigDecimal min, BigDecimal max, BigDecimal reorder) {}

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
        if (!blank(q.get("locationId"))) {
            where.append(" AND a.\"locationId\" = ?");
            params.add(q.get("locationId"));
        }
        return page(ALERT_SELECT + where, " ORDER BY a.\"createdAt\" DESC, a.id", "SELECT count(*) FROM"
                + " \"InventoryReplenishmentAlert\" a" + where, params, q);
    }

    @Transactional
    public Map<String, Object> dismiss(String id, Map<String, Object> dto) {
        if (blank(dto.get("reason"))) {
            throw ApiException.badRequest("A reason is required");
        }
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

    /** Check every active rule. A dismissed alert stays dismissed until stock recovers and falls again. */
    @Transactional
    public Map<String, Object> scan() {
        int raised = 0;
        int refreshed = 0;
        int resolved = 0;
        List<String> auto = new ArrayList<>();
        List<Map<String, Object>> rules = SqlRows.list(jdbc.sql(RULE_SELECT + " WHERE r.\"organizationId\" = ? AND"
                + " r.\"isActive\" AND r.\"deletedAt\" IS NULL ORDER BY r.id").param(org()));
        for (Map<String, Object> rule : rules) {
            String ruleId = (String) rule.get("id");
            BigDecimal onHand = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE"
                    + " \"organizationId\" = ?"
                    + " AND \"productId\" = ? AND \"locationId\" = ?").params(org(), rule.get("productId"),
                    rule.get("locationId")).query(BigDecimal.class).single();
            BigDecimal reserved = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\" WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ? AND status = 'active'")
                    .params(org(), rule.get("productId"), rule.get("locationId")).query(BigDecimal.class).single();
            BigDecimal available = onHand.subtract(reserved);
            BigDecimal min = SqlRows.dec(rule, "minQty");
            BigDecimal suggested = ReplenishmentCalculator.suggest(available, min,
                    rule.get("maxQty") == null ? null : SqlRows.dec(rule, "maxQty"),
                    rule.get("reorderQty") == null ? null : SqlRows.dec(rule, "reorderQty"));
            Map<String, Object> live = SqlRows.one(jdbc.sql("SELECT id, status FROM \"InventoryReplenishmentAlert\""
                    + " WHERE"
                    + " \"ruleId\" = ? AND \"organizationId\" = ? AND status IN " + LIVE + " FOR UPDATE")
                    .params(ruleId, org()));
            if (suggested.signum() == 0) {
                // Stock is back: live alerts are over, and a dismissed one no longer silences the rule.
                resolved += jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'resolved', \"resolvedAt\" = "
                        + NOW + ", \"updatedAt\" = " + NOW + " WHERE \"ruleId\" = ? AND \"organizationId\" = ? AND"
                        + " status"
                        + " IN ('open', 'requested', 'requisitioned', 'dismissed')").params(ruleId, org()).update();
                continue;
            }
            if (live != null) {
                if ("open".equals(live.get("status"))) {
                    jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET \"onHand\" = ?, reserved = ?, available = ?,"
                            + " \"suggestedQty\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                            .params(onHand, reserved, available, suggested, live.get("id")).update();
                    refreshed++;
                }
                continue;
            }
            if (dismissedSinceRecovery(ruleId)) {
                continue;
            }
            String id = UUID.randomUUID().toString();
            int inserted = jdbc.sql("INSERT INTO \"InventoryReplenishmentAlert\" (id, \"organizationId\", \"ruleId\","
                    + " \"productId\", \"locationId\", \"onHand\", reserved, available, \"minQty\", \"suggestedQty\")"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(id, org(), ruleId, rule.get("productId"), rule.get("locationId"), onHand, reserved,
                    available,
                            min, suggested).update();
            if (inserted > 0) {
                raised++;
                if (Boolean.TRUE.equals(rule.get("autoRequisition"))) {
                    auto.add(id);
                }
            }
        }
        int requested = auto.isEmpty() ? 0 : request(auto);
        if (raised > 0) {
            notifyPlanners(raised);
        }
        return Obj.of("rules", rules.size(), "raised", raised, "refreshed", refreshed, "resolved", resolved,
                "requested",
                requested);
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
        int requested = request(ids);
        if (requested == 0) {
            throw ApiException.badRequest("None of these alerts is open");
        }
        return Obj.of("requested", requested);
    }

    private int request(List<String> ids) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(ALERT_SELECT + " WHERE a.\"organizationId\" = ? AND"
                + " a.status"
                + " = 'open' AND a.id = ANY(?::text[]) FOR UPDATE OF a").params(org(), ids.toArray(String[]::new)));
        if (rows.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> a : rows) {
            jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'requested', \"updatedAt\" = " + NOW
                    + " WHERE id = ?").param(a.get("id")).update();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("alertId", a.get("id"));
            line.put("productId", a.get("productId"));
            line.put("description", a.get("productCode") + " " + a.get("productName") + " for "
                    + a.get("locationName"));
            line.put("quantity", SqlRows.dec(a, "suggestedQty").toPlainString());
            line.put("uomId", a.get("uomId"));
            line.put("supplierId", a.get("supplierId"));
            line.put("neededBy", today.plusDays(((Number) a.get("leadTimeDays")).longValue()).toString());
            lines.add(line);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", org());
        payload.put("requestedBy", TenantContext.userId());
        payload.put("lines", lines);
        outbox.publish(DomainEvents.INVENTORY_REPLENISHMENT_REQUESTED, payload);
        return rows.size();
    }

    /** Which of these alerts still wait for a purchase request (the event is delivered at least once). */
    @Transactional(readOnly = true)
    public List<String> stillRequested(List<String> alertIds) {
        return jdbc.sql("SELECT id FROM \"InventoryReplenishmentAlert\" WHERE \"organizationId\" = ? AND status ="
                + " 'requested' AND id = ANY(?::text[])").params(org(), alertIds.toArray(String[]::new))
                .query(String.class).list();
    }

    /** Procurement raised the purchase request for these alerts. */
    @Transactional
    public void markRequisitioned(List<String> alertIds, String purchaseRequestId, String requestNumber) {
        jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'requisitioned', \"purchaseRequestId\" = ?,"
                + " \"requestNumber\" = ?, \"updatedAt\" = " + NOW + " WHERE \"organizationId\" = ? AND status ="
                + " 'requested' AND id = ANY(?::text[])")
                .params(purchaseRequestId, requestNumber, org(), alertIds.toArray(String[]::new)).update();
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
