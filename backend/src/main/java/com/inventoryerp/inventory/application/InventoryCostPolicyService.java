package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.core.application.ProductCostingPolicy;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inventory costing policy (ADR-S007 §1): the valuation method is an approved, effective-dated accounting policy per
 * organisation (optionally per product category), not a per-product toggle. Lifecycle draft → approved → active →
 * superseded. Activation (on or after {@code effectiveFrom}, by hand or by the daily job) re-costs the stock of every
 * product in scope whose method differs, preserving total value:
 * <ul>
 *   <li>AVCO → FIFO: batch tracking on; untracked on-hand quantity becomes one opening lot at the running average;
 *       existing lots keep their cost.</li>
 *   <li>FIFO → AVCO: the location's lots collapse to their weighted average. Products without expiry / serial
 *       tracking drop lot tracking (lots retired, quant carries the average); lot-tracked products keep their lots
 *       re-costed to the average.</li>
 * </ul>
 * STANDARD and SPECIFIC products are never touched. Each re-costed quant is recorded in
 * {@code InventoryCostMigrationLine}; a rounding difference ≥ 0.01 is posted to stock valuation.
 */
@Service
public class InventoryCostPolicyService implements ProductCostingPolicy {

    private static final Logger log = LoggerFactory.getLogger(InventoryCostPolicyService.class);
    private static final Set<String> METHODS = Set.of("AVCO", "FIFO", "SPECIFIC", "STANDARD");
    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";
    private static final BigDecimal CENT = new BigDecimal("0.01");

    /** Create / update body. */
    public record PolicyInput(String scopeType, String categoryId, String method, String effectiveFrom, String notes) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final AuditService audit;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final TransactionTemplate tx;

    public InventoryCostPolicyService(JdbcClient jdbc, SequenceService seq, AuditService audit, PostingService posting,
            AccountDeterminationService determination, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.audit = audit;
        this.posting = posting;
        this.determination = determination;
        this.tx = new TransactionTemplate(txm);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static LocalDate today() {
        return LocalDate.now(BusinessZone.get());
    }

    // ------------------------------------------------------------------ ProductCostingPolicy

    @Override
    public Optional<String> methodFor(String categoryId) {
        String orgId = TenantContext.optionalOrganizationId();
        if (orgId == null) {
            return Optional.empty();
        }
        if (categoryId != null) {
            Optional<String> byCategory = jdbc.sql("SELECT method FROM \"InventoryCostPolicy\" WHERE \"organizationId\" = ?"
                    + " AND status = 'active' AND \"scopeType\" = 'category' AND \"categoryId\" = ?")
                    .params(orgId, categoryId).query(String.class).optional();
            if (byCategory.isPresent()) {
                return byCategory;
            }
        }
        return jdbc.sql("SELECT method FROM \"InventoryCostPolicy\" WHERE \"organizationId\" = ? AND status = 'active'"
                + " AND \"scopeType\" = 'organization'").param(orgId).query(String.class).optional();
    }

    // ------------------------------------------------------------------ queries

    /** Every policy of the organisation, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return SqlRows.list(jdbc.sql("SELECT p.*, c.name AS \"categoryName\" FROM \"InventoryCostPolicy\" p"
                + " LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\" WHERE p.\"organizationId\" = ?"
                + " ORDER BY p.\"createdAt\" DESC").param(org()));
    }

    /** One policy with its migration lines. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> p = require(id);
        p.put("migrationLines", SqlRows.list(jdbc.sql("SELECT m.*, pr.name AS \"productName\", pr.sku FROM"
                + " \"InventoryCostMigrationLine\" m LEFT JOIN \"Product\" pr ON pr.id = m.\"productId\""
                + " WHERE m.\"organizationId\" = ? AND m.\"policyId\" = ? ORDER BY pr.name")
                .params(org(), id)));
        return p;
    }

    /** The active policy per scope plus the org-setting fallback (what a new product would get). */
    @Transactional(readOnly = true)
    public Map<String, Object> current() {
        String fallback = jdbc.sql("SELECT value #>> '{}' FROM \"Setting\" WHERE \"organizationId\" = ?"
                + " AND key = 'inventory.defaultCostingMethod' AND \"scopeType\" = 'organization' LIMIT 1").param(org()).query(String.class).optional().orElse("AVCO");
        return Obj.of("active", SqlRows.list(jdbc.sql("SELECT p.*, c.name AS \"categoryName\" FROM"
                        + " \"InventoryCostPolicy\" p LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\""
                        + " WHERE p.\"organizationId\" = ? AND p.status = 'active' ORDER BY p.\"scopeType\" DESC")
                        .param(org())), "defaultMethod", methodFor(null).orElse(fallback),
                "governed", methodFor(null).isPresent());
    }

    /** What activating the policy would re-cost: products in scope whose method differs, with quantity and value. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String id) {
        Map<String, Object> p = require(id);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = Money.ZERO;
        for (Map<String, Object> prod : productsInScope(p)) {
            BigDecimal[] qv = quantValue((String) prod.get("id"), (String) prod.get("costingMethod"),
                    Boolean.TRUE.equals(prod.get("batchTracking")));
            total = total.add(qv[1]);
            rows.add(Obj.of("productId", prod.get("id"), "name", prod.get("name"), "sku", prod.get("sku"),
                    "methodFrom", prod.get("costingMethod"), "methodTo", p.get("method"), "quantity",
                    JsNumbers.dec(qv[0]), "value", JsNumbers.dec(qv[1])));
        }
        return Obj.of("policy", p, "products", rows, "totalValue", JsNumbers.dec(total));
    }

    // ------------------------------------------------------------------ commands

    /** New draft policy. */
    public Map<String, Object> create(PolicyInput in) {
        validate(in);
        String id = UUID.randomUUID().toString();
        String code = seq.next("inventory_cost_policy", "ICP-", 4);
        int version = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM \"InventoryCostPolicy\" WHERE"
                + " \"organizationId\" = ? AND \"scopeType\" = ? AND \"categoryId\" IS NOT DISTINCT FROM ?")
                .params(org(), in.scopeType(), blank(in.categoryId())).query(Integer.class).single();
        jdbc.sql("INSERT INTO \"InventoryCostPolicy\" (id, \"organizationId\", code, \"scopeType\", \"categoryId\", method,"
                        + " \"effectiveFrom\", version, notes, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?::date, ?, ?, ?)")
                .params(id, org(), code, in.scopeType(), blank(in.categoryId()), in.method(), in.effectiveFrom(), version,
                        in.notes(), TenantContext.userId())
                .update();
        audit.record(AuditInput.of("InventoryCostPolicy", id, "create", null, Obj.of("code", code, "method",
                in.method(), "scopeType", in.scopeType(), "effectiveFrom", in.effectiveFrom())));
        return get(id);
    }

    /** Edit a draft. */
    public Map<String, Object> update(String id, PolicyInput in) {
        Map<String, Object> p = require(id);
        if (!"draft".equals(p.get("status"))) {
            throw ApiException.badRequest("Only a draft cost policy can be edited");
        }
        PolicyInput merged = new PolicyInput(in.scopeType() != null ? in.scopeType() : (String) p.get("scopeType"),
                in.scopeType() != null ? in.categoryId() : (String) p.get("categoryId"),
                in.method() != null ? in.method() : (String) p.get("method"),
                in.effectiveFrom() != null ? in.effectiveFrom() : p.get("effectiveFrom").toString(),
                in.notes() != null ? in.notes() : (String) p.get("notes"));
        validate(merged);
        jdbc.sql("UPDATE \"InventoryCostPolicy\" SET \"scopeType\" = ?, \"categoryId\" = ?, method = ?, \"effectiveFrom\""
                        + " = ?::date, notes = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(merged.scopeType(), blank(merged.categoryId()), merged.method(), merged.effectiveFrom(),
                        merged.notes(), id, org())
                .update();
        return get(id);
    }

    /** Approve a draft (accounting sign-off); activation follows on the effective date. */
    public Map<String, Object> approve(String id) {
        int n = jdbc.sql("UPDATE \"InventoryCostPolicy\" SET status = 'approved', \"approvedBy\" = ?, \"approvedAt\" = "
                + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = 'draft'")
                .params(TenantContext.userId(), id, org()).update();
        if (n == 0) {
            throw ApiException.badRequest("Only a draft cost policy can be approved");
        }
        audit.record(AuditInput.of("InventoryCostPolicy", id, "update", null, Obj.of("kind", "cost_policy_approved")));
        return get(id);
    }

    /** Cancel a draft or approved (not yet active) policy. */
    public Map<String, Object> cancel(String id) {
        int n = jdbc.sql("UPDATE \"InventoryCostPolicy\" SET status = 'cancelled', \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ? AND status IN ('draft', 'approved')").params(id, org()).update();
        if (n == 0) {
            throw ApiException.badRequest("Only a draft or approved cost policy can be cancelled");
        }
        return get(id);
    }

    /**
     * Activate an approved policy whose effective date has come: supersede the scope's active policy and re-cost the
     * stock of the products in scope, all in one transaction.
     */
    public Map<String, Object> activate(String id) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryCostPolicy\" WHERE id = ?"
                    + " AND \"organizationId\" = ? FOR UPDATE").params(id, org()));
            if (p == null) {
                throw ApiException.notFound("Cost policy not found");
            }
            if (!"approved".equals(p.get("status"))) {
                throw ApiException.badRequest("Only an approved cost policy can be activated");
            }
            LocalDate from = (LocalDate) toDate(p.get("effectiveFrom"));
            if (from.isAfter(today())) {
                throw ApiException.badRequest("The policy takes effect on " + from + "; it cannot be activated earlier");
            }
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("cost-policy:" + org()).query()
                    .listOfRows();
            jdbc.sql("UPDATE \"InventoryCostPolicy\" SET status = 'superseded', \"updatedAt\" = " + NOW
                    + " WHERE \"organizationId\" = ? AND status = 'active' AND \"scopeType\" = ?"
                    + " AND \"categoryId\" IS NOT DISTINCT FROM ?").params(org(), p.get("scopeType"), p.get("categoryId"))
                    .update();
            Map<String, Object> summary = migrate(p);
            jdbc.sql("UPDATE \"InventoryCostPolicy\" SET status = 'active', \"activatedAt\" = " + NOW + ","
                            + " \"migrationSummary\" = ?::jsonb, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(EntityJson.toJson(summary), id).update();
            audit.recordInTx(AuditInput.of("InventoryCostPolicy", id, "update", null, Obj.of("kind",
                    "cost_policy_activated", "method", p.get("method"), "summary", summary)));
        });
        return get(id);
    }

    /** Daily: activate approved policies whose effective date has come, for every organisation. */
    @Scheduled(cron = "0 15 0 * * *", zone = "UTC")
    @SchedulerLock(name = "inventory-cost-policy-activation", lockAtMostFor = "PT1H")
    public void activateDue() {
        List<Map<String, Object>> due = jdbc.sql("SELECT id, \"organizationId\" FROM \"InventoryCostPolicy\""
                + " WHERE status = 'approved' AND \"effectiveFrom\" <= CURRENT_DATE ORDER BY \"effectiveFrom\"")
                .query().listOfRows();
        for (Map<String, Object> d : due) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization((String) d.get("organizationId")),
                        () -> activate((String) d.get("id")));
            } catch (RuntimeException e) {
                log.warn("[cost-policy] activation of {} failed: {}", d.get("id"), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ migration

    private List<Map<String, Object>> productsInScope(Map<String, Object> p) {
        String method = (String) p.get("method");
        String sql = "SELECT id, name, sku, \"costingMethod\"::text AS \"costingMethod\", \"batchTracking\","
                + " \"expiryTracking\", \"serialTracking\" FROM \"Product\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND \"costingMethod\"::text IN ('AVCO', 'FIFO') AND \"costingMethod\"::text <> ?";
        if ("category".equals(p.get("scopeType"))) {
            return SqlRows.list(jdbc.sql(sql + " AND \"categoryId\" = ? ORDER BY name").params(org(), method,
                    p.get("categoryId")));
        }
        // Organisation scope: products not governed by an active category policy.
        return SqlRows.list(jdbc.sql(sql + " AND (\"categoryId\" IS NULL OR \"categoryId\" NOT IN (SELECT \"categoryId\""
                + " FROM \"InventoryCostPolicy\" WHERE \"organizationId\" = ? AND status = 'active'"
                + " AND \"scopeType\" = 'category')) ORDER BY name").params(org(), method, org()));
    }

    /** {quantity, value} of a product as the valuation report counts it. */
    private BigDecimal[] quantValue(String productId, String method, boolean lots) {
        BigDecimal qty = Money.ZERO;
        BigDecimal value = Money.ZERO;
        for (Map<String, Object> q : quants(productId, false)) {
            BigDecimal[] qv = quantValue(productId, q, method, lots);
            qty = qty.add(qv[0]);
            value = value.add(qv[1]);
        }
        return new BigDecimal[] {qty, value};
    }

    private List<Map<String, Object>> quants(String productId, boolean lock) {
        return jdbc.sql("SELECT id, \"variantId\", \"variantKey\", \"locationId\", quantity, \"runningAverageCost\""
                + " FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND quantity <> 0"
                + (lock ? " FOR UPDATE" : "")).params(org(), productId).query().listOfRows();
    }

    private BigDecimal[] quantValue(String productId, Map<String, Object> q, String method, boolean lots) {
        BigDecimal qty = Money.dec(q.get("quantity"));
        BigDecimal avg = Money.dec(q.get("runningAverageCost"));
        if (!"FIFO".equals(method) && !lots) {
            return new BigDecimal[] {qty, qty.multiply(avg)};
        }
        Map<String, Object> lot = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) AS qty, COALESCE(SUM(quantity *"
                        + " COALESCE(\"unitCost\", 0)), 0) AS value FROM \"InventoryBatch\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND COALESCE(\"variantId\", '') = ? AND \"locationId\" = ?"
                        + " AND \"isActive\" = true AND quantity > 0")
                .params(org(), productId, q.get("variantKey"), q.get("locationId")).query().singleRow();
        BigDecimal lotQty = Money.dec(lot.get("qty"));
        BigDecimal lotValue = Money.dec(lot.get("value"));
        return new BigDecimal[] {qty, lotValue.add(qty.subtract(lotQty).multiply(avg))};
    }

    private Map<String, Object> migrate(Map<String, Object> p) {
        String policyId = (String) p.get("id");
        String to = (String) p.get("method");
        String policyCode = (String) p.get("code");
        int products = 0;
        int quants = 0;
        BigDecimal before = Money.ZERO;
        BigDecimal after = Money.ZERO;
        if (!"AVCO".equals(to) && !"FIFO".equals(to)) {
            return Obj.of("products", 0, "quants", 0, "valueBefore", 0, "valueAfter", 0, "difference", 0);
        }
        for (Map<String, Object> prod : productsInScope(p)) {
            String productId = (String) prod.get("id");
            String from = (String) prod.get("costingMethod");
            boolean lots = Boolean.TRUE.equals(prod.get("batchTracking"));
            boolean keepLots = Boolean.TRUE.equals(prod.get("expiryTracking"))
                    || Boolean.TRUE.equals(prod.get("serialTracking"));
            products++;
            for (Map<String, Object> q : quants(productId, true)) {
                BigDecimal[] qv = quantValue(productId, q, from, lots);
                BigDecimal qty = qv[0];
                BigDecimal valueBefore = qv[1];
                BigDecimal valueAfter;
                if ("FIFO".equals(to)) {
                    valueAfter = toFifo(productId, q, policyCode, lots);
                } else {
                    valueAfter = toAvco(productId, q, valueBefore, keepLots);
                }
                quants++;
                before = before.add(valueBefore);
                after = after.add(valueAfter);
                jdbc.sql("INSERT INTO \"InventoryCostMigrationLine\" (id, \"organizationId\", \"policyId\", \"productId\","
                                + " \"locationId\", \"methodFrom\", \"methodTo\", quantity, \"valueBefore\", \"valueAfter\")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(UUID.randomUUID().toString(), org(), policyId, productId, q.get("locationId"), from, to,
                                qty, valueBefore, valueAfter)
                        .update();
            }
            boolean batchTracking = "FIFO".equals(to) || (lots && keepLots);
            jdbc.sql("UPDATE \"Product\" SET \"costingMethod\" = ?::\"CostingMethod\", \"batchTracking\" = ?,"
                    + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                    .params(to, batchTracking, productId, org()).update();
        }
        BigDecimal diff = after.subtract(before).setScale(6, RoundingMode.HALF_UP);
        String je = null;
        if (diff.abs().compareTo(CENT) >= 0) {
            je = postRounding(diff, policyCode);
        }
        return Obj.of("products", products, "quants", quants, "valueBefore", JsNumbers.dec(before), "valueAfter",
                JsNumbers.dec(after), "difference", JsNumbers.dec(diff), "journalEntryId", je);
    }

    /** AVCO → FIFO: lot tracking on; untracked quantity becomes one opening lot at the running average. */
    private BigDecimal toFifo(String productId, Map<String, Object> q, String policyCode, boolean lots) {
        BigDecimal qty = Money.dec(q.get("quantity"));
        BigDecimal avg = Money.dec(q.get("runningAverageCost"));
        BigDecimal lotQty = lots ? jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE"
                        + " \"organizationId\" = ? AND \"productId\" = ? AND COALESCE(\"variantId\", '') = ?"
                        + " AND \"locationId\" = ? AND \"isActive\" = true AND quantity > 0")
                .params(org(), productId, q.get("variantKey"), q.get("locationId")).query(BigDecimal.class).single()
                : Money.ZERO;
        BigDecimal untracked = qty.subtract(lotQty);
        if (untracked.signum() > 0) {
            jdbc.sql("INSERT INTO \"InventoryBatch\" (id, \"organizationId\", \"productId\", \"variantId\", \"locationId\","
                            + " \"batchNumber\", quantity, \"unitCost\", \"receivedAt\", \"isActive\", \"createdAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", true, " + NOW + ", " + NOW + ")")
                    .params(UUID.randomUUID().toString(), org(), productId, q.get("variantId"), q.get("locationId"),
                            "OPEN-" + policyCode, untracked, avg)
                    .update();
        }
        return quantValue(productId, q, "FIFO", true)[1];
    }

    /** FIFO → AVCO: the quant carries the weighted average; lots retired, or re-costed when they must stay. */
    private BigDecimal toAvco(String productId, Map<String, Object> q, BigDecimal value, boolean keepLots) {
        BigDecimal qty = Money.dec(q.get("quantity"));
        if (qty.signum() <= 0) {
            return value;
        }
        BigDecimal avg = Money.div(value, qty).setScale(6, RoundingMode.HALF_UP);
        jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(avg, q.get("id")).update();
        String lotWhere = " WHERE \"organizationId\" = ? AND \"productId\" = ? AND COALESCE(\"variantId\", '') = ?"
                + " AND \"locationId\" = ? AND \"isActive\" = true AND quantity > 0";
        if (keepLots) {
            jdbc.sql("UPDATE \"InventoryBatch\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + lotWhere)
                    .params(avg, org(), productId, q.get("variantKey"), q.get("locationId")).update();
            return quantValue(productId, q, "AVCO", true)[1];
        }
        jdbc.sql("UPDATE \"InventoryBatch\" SET \"isActive\" = false, \"updatedAt\" = " + NOW + lotWhere)
                .params(org(), productId, q.get("variantKey"), q.get("locationId")).update();
        return qty.multiply(avg);
    }

    private String postRounding(BigDecimal diff, String policyCode) {
        String sv = determination.mapped("stock_valuation");
        String amount = JsNumbers.dec(diff.abs());
        String description = "Costing method change " + policyCode + " · rounding";
        List<Line> lines = diff.signum() > 0
                ? List.of(Line.debit(sv, amount, description),
                        Line.credit(determination.mapped("stock_adjustment_income"), amount, description))
                : List.of(Line.debit(determination.mapped("stock_adjustment_expense"), amount, description),
                        Line.credit(sv, amount, description));
        Map<String, Object> je = posting.post(PostingRequest.of("INV", Instant.now(), description, "cost_policy",
                policyCode, "inventory:cost_policy:" + org() + ":" + policyCode, lines));
        return je == null ? null : (String) je.get("id");
    }

    // ------------------------------------------------------------------ helpers

    private void validate(PolicyInput in) {
        if (!"organization".equals(in.scopeType()) && !"category".equals(in.scopeType())) {
            throw ApiException.badRequest("scopeType must be one of the following values: organization, category");
        }
        if (in.method() == null || !METHODS.contains(in.method())) {
            throw ApiException.badRequest("method must be one of the following values: AVCO, FIFO, SPECIFIC, STANDARD");
        }
        if ("category".equals(in.scopeType())) {
            if (blank(in.categoryId()) == null) {
                throw ApiException.badRequest("categoryId is required for a category policy");
            }
            long n = jdbc.sql("SELECT count(*) FROM \"ProductCategory\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(in.categoryId(), org()).query(Long.class).single();
            if (n == 0) {
                throw ApiException.badRequest("Product category not found");
            }
        } else if (blank(in.categoryId()) != null) {
            throw ApiException.badRequest("categoryId is only allowed on a category policy");
        }
        if (in.effectiveFrom() == null) {
            throw ApiException.badRequest("effectiveFrom is required");
        }
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT p.*, c.name AS \"categoryName\" FROM \"InventoryCostPolicy\" p"
                + " LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\" WHERE p.id = ? AND p.\"organizationId\" = ?")
                .params(id, org()));
        if (p == null) {
            throw ApiException.notFound("Cost policy not found");
        }
        return p;
    }

    private static Object toDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(v.toString().substring(0, 10));
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
