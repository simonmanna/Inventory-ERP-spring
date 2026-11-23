package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.CostingDimensionGuard;
import com.inventoryerp.accounting.application.FiscalPeriodControl;
import com.inventoryerp.accounting.application.OrgDates;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.core.application.ProductCostingPolicy;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
 * {@code InventoryCostMigrationLine}; a rounding difference is posted to the products' stock accounts.
 *
 * <p>Readiness gaps (h-02): activation holds the organisation's costing key exclusively, so no stock move runs while
 * the methods change and every move after it reads the new method (F2); the creator cannot approve while the
 * {@code inventory_valuation} separation-of-duties pair is on, and a draft is edited only while it is a draft (F5); the
 * rounding posts per product to its STOCK_IN account (F6) with a ledger row for every quant whose value changed (F9);
 * the effective date must lie in an open period, the rounding entry is never dated into a closed one, and a failed
 * scheduled activation is counted and listed until it succeeds (F7).
 */
@Service
public class InventoryCostPolicyService implements ProductCostingPolicy {

    private static final Set<String> METHODS = Set.of("AVCO", "FIFO", "SPECIFIC", "STANDARD");
    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Create / update body. */
    public record PolicyInput(String scopeType, String categoryId, String method, String effectiveFrom, String notes) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final AuditService audit;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final TransactionTemplate tx;
    private final CostLineageService lineage;
    private final StockPostingService stockPosting;
    private final SegregationOfDutiesService sod;
    private final CostActivationFailures failures;
    private final FiscalPeriodControl fiscal;
    private final CostingDimensionGuard costingGuard;

    public InventoryCostPolicyService(JdbcClient jdbc, SequenceService seq, AuditService audit, PostingService posting,
            AccountDeterminationService determination, PlatformTransactionManager txm, CostLineageService lineage,
            StockPostingService stockPosting, SegregationOfDutiesService sod, CostActivationFailures failures,
            FiscalPeriodControl fiscal, CostingDimensionGuard costingGuard) {
        this.lineage = lineage;
        this.jdbc = jdbc;
        this.seq = seq;
        this.audit = audit;
        this.posting = posting;
        this.determination = determination;
        this.tx = new TransactionTemplate(txm);
        this.stockPosting = stockPosting;
        this.sod = sod;
        this.failures = failures;
        this.fiscal = fiscal;
        this.costingGuard = costingGuard;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Today on the organisation's calendar (its time zone; UTC when unset). */
    private LocalDate today() {
        return LocalDate.now(OrgDates.orgTimezone(jdbc, org()));
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
        // Readiness gaps h-02 F5: only while it is still a draft (an approval in between wins).
        int n = jdbc.sql("UPDATE \"InventoryCostPolicy\" SET \"scopeType\" = ?, \"categoryId\" = ?, method = ?,"
                        + " \"effectiveFrom\" = ?::date, notes = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                        + " \"organizationId\" = ? AND status = 'draft'")
                .params(merged.scopeType(), blank(merged.categoryId()), merged.method(), merged.effectiveFrom(),
                        merged.notes(), id, org())
                .update();
        if (n == 0) {
            throw ApiException.badRequest("Only a draft cost policy can be edited");
        }
        return get(id);
    }

    /**
     * Approve a draft (accounting sign-off); activation follows on the effective date. Readiness gaps h-02: not by its
     * creator while the {@code inventory_valuation} separation-of-duties pair is on (F5), and only with an effective
     * date still in an open period (F7).
     */
    public Map<String, Object> approve(String id) {
        Map<String, Object> draft = SqlRows.one(jdbc.sql("SELECT status, \"createdBy\", \"effectiveFrom\" FROM"
                + " \"InventoryCostPolicy\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()));
        if (draft != null && "draft".equals(draft.get("status"))) {
            List<String> makers = new ArrayList<>();
            makers.add((String) draft.get("createdBy"));
            sod.assertSeparated(SegregationOfDutiesService.INVENTORY_VALUATION, "InventoryCostPolicy", id, makers);
            assertEffectiveOpen((LocalDate) toDate(draft.get("effectiveFrom")));
        }
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
        failures.resolved(id, "Cost policy cancelled");
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
            // Readiness gaps h-02 F2: no stock move of the organisation runs while the methods change — every move
            // holds this key shared from before it reads its product's method until it commits.
            CostingLocks.lockOrganisation(jdbc, org());
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
            failures.resolved(id, "Cost policy activated");
        });
        return get(id);
    }

    /**
     * Hourly: activate approved policies whose effective date has come on their organisation's calendar. Candidates
     * are dated up to tomorrow (UTC) so organisations east of UTC are covered; each is checked against its
     * organisation's today. Activation is idempotent: a policy that is no longer approved is skipped.
     */
    @Scheduled(cron = "0 15 * * * *", zone = "UTC")
    @SchedulerLock(name = "inventory-cost-policy-activation", lockAtMostFor = "PT50M")
    public void activateDue() {
        List<Map<String, Object>> due = jdbc.sql("SELECT id, \"organizationId\", \"effectiveFrom\", code FROM"
                + " \"InventoryCostPolicy\" WHERE status = 'approved' AND \"effectiveFrom\" <= ? ORDER BY \"effectiveFrom\"")
                .param(LocalDate.now(ZoneOffset.UTC).plusDays(1)).query().listOfRows();
        for (Map<String, Object> d : due) {
            String orgId = (String) d.get("organizationId");
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    if (((LocalDate) toDate(d.get("effectiveFrom"))).isAfter(today())) {
                        return;
                    }
                    activate((String) d.get("id"));
                });
            } catch (RuntimeException e) {
                // Readiness gaps h-02 F7: counted (erp_job_failures_total) and kept visible until it activates.
                failures.failed("inventory-cost-policy-activation", orgId, "Cost policy", (String) d.get("id"),
                        (String) d.get("code"), null, e);
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
        if (lock) {
            // h-02 F2: rows only, in the engine's order — the organisation's costing key already keeps every move out
            // (one advisory key per quant of a whole catalogue would not fit the lock table).
            return CostingLocks.lockQuants(jdbc, org(), productId, true, false);
        }
        return jdbc.sql("SELECT id, \"variantId\", \"variantKey\", \"locationId\", quantity, \"runningAverageCost\""
                + " FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND quantity <> 0")
                .params(org(), productId).query().listOfRows();
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
        List<Object[]> quantDeltas = new java.util.ArrayList<>();
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        if (!"AVCO".equals(to) && !"FIFO".equals(to)) {
            return Obj.of("products", 0, "quants", 0, "valueBefore", 0, "valueAfter", 0, "difference", 0);
        }
        // Products in id order, each product's quants in the engine's order: a fixed lock order (h-02 F2).
        List<Map<String, Object>> scope = new ArrayList<>(productsInScope(p));
        scope.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("id")));
        for (Map<String, Object> prod : scope) {
            String productId = (String) prod.get("id");
            BigDecimal productDelta = Money.ZERO;
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
                productDelta = productDelta.add(valueAfter.subtract(valueBefore));
                quantDeltas.add(new Object[] {productId, q, valueAfter.subtract(valueBefore)});
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
            if (productDelta.signum() != 0) {
                // h-02 F6: each product's rounding on the stock account its movements post to.
                // R3: one line per product (stamped with it), account first.
                byAccount.merge(stockPosting.stockAccount(productId, productDelta.abs()) + "|" + productId,
                        productDelta, BigDecimal::add);
            }
        }
        BigDecimal diff = after.subtract(before).setScale(6, RoundingMode.HALF_UP);
        String je = postRounding(byAccount, policyCode, postingInstant(p));
        // CM-1: the stock ledger carries the re-costing per quant (value-only rows, source cost_policy) — h-02 F9: every
        // quant whose value changed, also when the changes net to nothing.
        for (Object[] qd : quantDeltas) {
            @SuppressWarnings("unchecked")
            Map<String, Object> q = (Map<String, Object>) qd[1];
            BigDecimal delta = ((BigDecimal) qd[2]).setScale(6, RoundingMode.HALF_UP);
            if (delta.signum() != 0) {
                lineage.recordValueOnly((String) qd[0], (String) q.get("variantId"), (String) q.get("locationId"), null,
                        delta, "cost_policy", policyCode, null, "Costing method change " + policyCode);
            }
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

    /** The effective date's posting instant (start of day, UTC). */
    private static Instant effectiveInstant(Map<String, Object> p) {
        Object from = p.get("effectiveFrom");
        LocalDate day = from instanceof LocalDate d ? d : from instanceof java.sql.Date sd ? sd.toLocalDate()
                : from == null ? null : LocalDate.parse(from.toString().substring(0, 10));
        return day == null ? Instant.now() : day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }

    /**
     * Posting date of a method change (readiness gaps h-02 F7): its effective date while that is open for posting
     * (books lock, fiscal period, costing lock), otherwise now — never earlier than the lock.
     */
    private Instant postingInstant(Map<String, Object> p) {
        Instant at = effectiveInstant(p);
        return fiscal.isOpen(at) && costingGuard.lockedPeriod(at) == null ? at : Instant.now();
    }

    /**
     * Readiness gaps h-02 F7: a policy taking effect today or earlier must take effect in an open period — after the
     * books lock date, outside closed / locked fiscal periods and locked costing periods — since its rounding entry is
     * dated at it. A later date is checked again when the policy is approved.
     */
    private void assertEffectiveOpen(LocalDate day) {
        if (day == null || day.isAfter(today())) {
            return;
        }
        Instant at = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        try {
            fiscal.assertOpen(at);
        } catch (ApiException e) {
            throw ApiException.badRequest("The cost policy cannot take effect on " + day + ": " + e.getMessage()
                    + ". Choose an effective date in an open period");
        }
        String locked = costingGuard.lockedPeriod(at);
        if (locked != null) {
            throw ApiException.badRequest("The cost policy cannot take effect on " + day + ": the costing period "
                    + locked + " is locked. Choose an effective date in an open period");
        }
    }

    /**
     * The rounding of a method change (h-02 F6): each stock account's net difference against stock adjustment income /
     * expense — a pure reclass when the accounts' differences net to nothing. Null when nothing moved.
     */
    private String postRounding(Map<String, BigDecimal> byAccount, String policyCode, Instant date) {
        String description = "Costing method change " + policyCode + " · rounding";
        List<Line> lines = new ArrayList<>();
        BigDecimal total = Money.ZERO;
        for (Map.Entry<String, BigDecimal> e : byAccount.entrySet()) {
            BigDecimal v = e.getValue().setScale(6, RoundingMode.HALF_UP);
            if (v.signum() == 0) {
                continue;
            }
            String[] key = e.getKey().split("\\|", 2);
            String product = key.length > 1 ? key[1] : null;
            lines.add(StockPostingService.stampLine(v.signum() > 0 ? Line.debit(key[0], JsNumbers.dec(v), description)
                    : Line.credit(key[0], JsNumbers.dec(v.negate()), description), product, null));
            total = total.add(v);
        }
        if (total.signum() > 0) {
            lines.add(Line.credit(determination.mapped("stock_adjustment_income"), JsNumbers.dec(total), description));
        } else if (total.signum() < 0) {
            lines.add(Line.debit(determination.mapped("stock_adjustment_expense"), JsNumbers.dec(total.negate()),
                    description));
        }
        if (lines.size() < 2) {
            return null;
        }
        Map<String, Object> je = posting.post(PostingRequest.of("INV", date, description, "cost_policy",
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
        LocalDate day;
        try {
            day = LocalDate.parse(in.effectiveFrom().trim().length() > 10 ? in.effectiveFrom().trim().substring(0, 10)
                    : in.effectiveFrom().trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("effectiveFrom must be a date (YYYY-MM-DD)");
        }
        assertEffectiveOpen(day);
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
