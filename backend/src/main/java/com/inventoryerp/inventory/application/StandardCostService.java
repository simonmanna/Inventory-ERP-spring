package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.OrgDates;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Standard costing with GL variances (ADR-S008 §1). A product becomes <em>standard-governed</em> once it has an
 * active {@code ProductStandardCost} (versioned, effective-dated, draft → approved → active → superseded):
 * <ul>
 *   <li>its stock is carried at the standard (quants, lots and {@code costPrice});</li>
 *   <li>every receipt enters stock at standard and the difference to the actual cost goes to purchase price
 *       variance ({@link #postReceipt});</li>
 *   <li>production of a governed product receives the output at standard; the rest of the order's WIP is posted to
 *       material price / usage, labour, overhead, subcontract and yield variance accounts
 *       ({@link #postProductionVariances});</li>
 *   <li>activating a new standard revalues the stock on hand (Dr / Cr stock valuation against standard cost
 *       revaluation), and with {@code convertMethod} switches an AVCO / FIFO product to standard cost.</li>
 * </ul>
 * Products without an active standard — including legacy {@code STANDARD} products — post exactly as before.
 */
@Service
public class StandardCostService {

    private static final Logger log = LoggerFactory.getLogger(StandardCostService.class);
    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";
    private static final BigDecimal CENT = new BigDecimal("0.01");

    /** Create body. */
    public record StandardInput(String productId, String effectiveFrom, BigDecimal materialCost,
            BigDecimal labourCost, BigDecimal overheadCost, BigDecimal subcontractCost, String notes,
            Boolean convertMethod, String source, String bomStandardCostId) {}

    /** Variance account per kind: code, name, mapping key. */
    private static final Map<String, String[]> ACCOUNTS = Map.of(
            "purchase_price", new String[] {"5905", "Purchase Price Variance (standard)", "purchase_price_variance"},
            "material_price", new String[] {"5910", "Material Price Variance", "material_price_variance"},
            "material_usage", new String[] {"5915", "Material Usage Variance", "material_usage_variance"},
            "labour", new String[] {"5920", "Labour Variance", "labour_variance"},
            "overhead", new String[] {"5930", "Overhead Variance", "overhead_variance"},
            "subcontract", new String[] {"5940", "Subcontract Variance", "subcontract_variance"},
            "yield", new String[] {"5950", "Yield & Other Production Variance", "production_yield_variance"},
            "revaluation", new String[] {"5960", "Standard Cost Revaluation", "standard_cost_revaluation"});

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AccountResolverService accounts;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final CostLineageService lineage;

    public StandardCostService(JdbcClient jdbc, PostingService posting, AccountDeterminationService determination,
            AccountResolverService accounts, AuditService audit, PlatformTransactionManager txm, CostLineageService lineage) {
        this.lineage = lineage;
        this.jdbc = jdbc;
        this.posting = posting;
        this.determination = determination;
        this.accounts = accounts;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Variance / revaluation account for a kind (created and mapped on first use). */
    public String varianceAccount(String kind) {
        String[] a = ACCOUNTS.getOrDefault(kind, ACCOUNTS.get("yield"));
        return accounts.ensureByCode(a[0], a[1], "cost_of_goods_sold", a[2]);
    }

    // ------------------------------------------------------------------ lookups used by the stock engine

    /** The active standard unit cost of a product, if it is standard-governed. */
    public Optional<BigDecimal> activeStandard(String productId) {
        String orgId = TenantContext.optionalOrganizationId();
        if (orgId == null || productId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT \"totalCost\" FROM \"ProductStandardCost\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND status = 'active'").params(orgId, productId).query(BigDecimal.class)
                .optional();
    }

    /**
     * Receipt of a governed product: Dr stock at standard, Cr the receipt's credit (GRNI / rule) at actual, the
     * difference to purchase price variance. Returns the variance (positive = unfavourable).
     */
    public BigDecimal postReceipt(String productId, BigDecimal quantity, BigDecimal actualUnit, BigDecimal standardUnit,
            List<Line> actualLines, Instant date, String sourceType, String sourceId, String description) {
        BigDecimal actual = actualUnit.multiply(quantity).setScale(6, RoundingMode.HALF_UP);
        BigDecimal standard = standardUnit.multiply(quantity).setScale(6, RoundingMode.HALF_UP);
        BigDecimal variance = actual.subtract(standard);
        List<Line> lines = new ArrayList<>();
        boolean adjusted = false;
        for (Line l : actualLines) {
            if (!adjusted && l.debit() != null && Money.dec(l.debit()).signum() > 0) {
                if (standard.signum() > 0) {
                    lines.add(new Line(l.accountId(), JsNumbers.dec(standard), null, l.partnerId(), l.description(),
                            l.branchId(), l.costCenterId(), l.dimensions()));
                }
                adjusted = true;
            } else if ((l.debit() != null && Money.dec(l.debit()).signum() != 0)
                    || (l.credit() != null && Money.dec(l.credit()).signum() != 0)) {
                lines.add(l);
            }
        }
        if (variance.signum() != 0) {
            String ppv = varianceAccount("purchase_price");
            String v = JsNumbers.dec(variance.abs());
            lines.add(variance.signum() > 0 ? Line.debit(ppv, v, "Purchase price variance")
                    : Line.credit(ppv, v, "Purchase price variance"));
        }
        if (lines.size() >= 2) {
            Map<String, Object> je = posting.post(PostingRequest.of("INV", date, description, sourceType, sourceId,
                    null, lines));
            recordVariance(productId, "purchase_price", sourceType, sourceId, quantity, standard, actual, variance,
                    je == null ? null : (String) je.get("id"));
        }
        return variance;
    }

    /**
     * Production of a governed product: the WIP left after the output was received at standard ({@code residual} =
     * actual pool − standard × produced) is split over the order's recorded variances by kind; whatever they do not
     * explain goes to yield / other variance. Dr variances / Cr WIP (signs follow each amount).
     */
    public String postProductionVariances(String orderId, String orderCode, String productId, BigDecimal residual,
            BigDecimal producedQty, BigDecimal standardTotal, String wipAccountId, Instant date) {
        residual = residual.setScale(6, RoundingMode.HALF_UP);
        if (residual.signum() == 0) {
            return null;
        }
        Map<String, BigDecimal> byKind = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT kind::text AS kind, SUM(amount) AS amount FROM"
                + " \"ProductionVariance\" WHERE \"organizationId\" = ? AND \"orderId\" = ? AND kind::text IN"
                + " ('material_price', 'material_usage', 'labour', 'overhead', 'subcontract') GROUP BY 1")
                .params(org(), orderId))) {
            BigDecimal a = SqlRows.dec(r, "amount").setScale(6, RoundingMode.HALF_UP);
            if (a.signum() != 0) {
                byKind.put((String) r.get("kind"), a);
            }
        }
        BigDecimal explained = byKind.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rest = residual.subtract(explained);
        if (rest.signum() != 0) {
            byKind.merge("yield", rest, BigDecimal::add);
        }
        List<Line> lines = new ArrayList<>();
        String description = "Production variances · " + orderCode;
        for (Map.Entry<String, BigDecimal> e : byKind.entrySet()) {
            BigDecimal a = e.getValue();
            if (a.signum() == 0) {
                continue;
            }
            String acc = varianceAccount(e.getKey());
            lines.add(a.signum() > 0 ? Line.debit(acc, JsNumbers.dec(a), description + " · " + e.getKey())
                    : Line.credit(acc, JsNumbers.dec(a.abs()), description + " · " + e.getKey()));
        }
        lines.add(residual.signum() > 0 ? Line.credit(wipAccountId, JsNumbers.dec(residual), description)
                : Line.debit(wipAccountId, JsNumbers.dec(residual.abs()), description));
        Map<String, Object> je = posting.post(PostingRequest.of("INV", date, description, "production_variance", orderCode,
                "inventory:production_variance:" + org() + ":" + orderId, lines));
        String jeId = je == null ? null : (String) je.get("id");
        for (Map.Entry<String, BigDecimal> e : byKind.entrySet()) {
            recordVariance(productId, e.getKey(), "production_order", orderId, producedQty, standardTotal,
                    standardTotal.add(residual), e.getValue(), jeId);
        }
        return jeId;
    }

    /**
     * Stage output of a governed product (ADR-S034): the difference between the stage's allocated value and the
     * standard ({@code variance}, positive = unfavourable) goes to the yield / other variance account against WIP and
     * is recorded in the variance register like a single-leg order's. A stage output carries no element split, so it
     * is reported as yield.
     */
    public String postStageVariance(String orderId, String orderCode, String productId, BigDecimal variance,
            BigDecimal quantity, BigDecimal standardTotal, String wipAccountId, Instant date, String sourceType,
            String sourceId, String label) {
        variance = variance.setScale(6, RoundingMode.HALF_UP);
        if (variance.signum() == 0) {
            return null;
        }
        String yieldAccount = varianceAccount("yield");
        String v = JsNumbers.dec(variance.abs());
        Map<String, Object> je = posting.post(PostingRequest.of("INV", date, "Stage standard-cost variance · "
                + orderCode, sourceType, sourceId, null, variance.signum() > 0
                        ? List.of(Line.debit(yieldAccount, v, label), Line.credit(wipAccountId, v, label))
                        : List.of(Line.debit(wipAccountId, v, label), Line.credit(yieldAccount, v, label))));
        String jeId = je == null ? null : (String) je.get("id");
        recordVariance(productId, "yield", "production_order", orderId, quantity, standardTotal,
                standardTotal.add(variance), variance, jeId);
        return jeId;
    }

    /** Reverse a variance entry (production order reversed). */
    public void reverseJournal(String journalEntryId, String description) {
        posting.reverse(journalEntryId, Instant.now(), description);
    }

    private void recordVariance(String productId, String kind, String sourceType, String sourceId, BigDecimal qty,
            BigDecimal standard, BigDecimal actual, BigDecimal amount, String jeId) {
        jdbc.sql("INSERT INTO \"StandardCostVariance\" (id, \"organizationId\", \"productId\", kind, \"sourceType\","
                        + " \"sourceId\", quantity, \"standardCost\", \"actualCost\", amount, \"journalEntryId\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org(), productId, kind, sourceType, sourceId, qty, standard, actual,
                        amount, jeId)
                .update();
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String productId, String status) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ?");
        if (productId != null && !productId.isEmpty()) {
            where.append(" AND s.\"productId\" = ?");
            params.add(productId);
        }
        if (status != null && !status.isEmpty()) {
            where.append(" AND s.status = ?");
            params.add(status);
        }
        return SqlRows.list(jdbc.sql("SELECT s.*, p.name AS \"productName\", p.sku, p.\"costingMethod\"::text AS"
                + " \"productCostingMethod\" FROM \"ProductStandardCost\" s JOIN \"Product\" p ON p.id = s.\"productId\""
                + where + " ORDER BY s.\"createdAt\" DESC LIMIT 500").params(params));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT s.*, p.name AS \"productName\", p.sku,"
                + " p.\"costingMethod\"::text AS \"productCostingMethod\" FROM \"ProductStandardCost\" s"
                + " JOIN \"Product\" p ON p.id = s.\"productId\" WHERE s.id = ? AND s.\"organizationId\" = ?")
                .params(id, org()));
        if (s == null) {
            throw ApiException.notFound("Standard cost not found");
        }
        return s;
    }

    /** Variances in a window (all kinds, or one), with totals by kind. */
    @Transactional(readOnly = true)
    public Map<String, Object> variances(LocalDate from, LocalDate to, String kind, String productId) {
        List<Object> params = new ArrayList<>(List.of(org(), from, to.plusDays(1)));
        StringBuilder where = new StringBuilder(" WHERE v.\"organizationId\" = ? AND v.\"createdAt\" >= ?"
                + " AND v.\"createdAt\" < ?");
        if (kind != null && !kind.isEmpty()) {
            where.append(" AND v.kind = ?");
            params.add(kind);
        }
        if (productId != null && !productId.isEmpty()) {
            where.append(" AND v.\"productId\" = ?");
            params.add(productId);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT v.*, p.name AS \"productName\" FROM"
                + " \"StandardCostVariance\" v LEFT JOIN \"Product\" p ON p.id = v.\"productId\"" + where
                + " ORDER BY v.\"createdAt\" DESC LIMIT 1000").params(params));
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal a = SqlRows.dec(r, "amount");
            totals.merge((String) r.get("kind"), a, BigDecimal::add);
            total = total.add(a);
        }
        Map<String, String> t = new LinkedHashMap<>();
        totals.forEach((k, v) -> t.put(k, JsNumbers.dec(v)));
        return Obj.of("rows", rows, "totals", t, "total", JsNumbers.dec(total));
    }

    // ------------------------------------------------------------------ commands

    /** New draft standard for a product. */
    @Transactional
    public Map<String, Object> create(StandardInput in) {
        Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT id, \"serialTracking\" FROM \"Product\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(in.productId(), org()));
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        if (Boolean.TRUE.equals(product.get("serialTracking"))) {
            throw ApiException.badRequest("Serial-tracked products keep specific identification; standard cost does"
                    + " not apply");
        }
        if (in.effectiveFrom() == null) {
            throw ApiException.badRequest("effectiveFrom is required");
        }
        BigDecimal m = nz(in.materialCost());
        BigDecimal l = nz(in.labourCost());
        BigDecimal o = nz(in.overheadCost());
        BigDecimal s = nz(in.subcontractCost());
        for (BigDecimal v : List.of(m, l, o, s)) {
            if (v.signum() < 0) {
                throw ApiException.badRequest("Standard cost components must not be negative");
            }
        }
        BigDecimal total = m.add(l).add(o).add(s);
        int version = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM \"ProductStandardCost\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ?").params(org(), in.productId()).query(Integer.class)
                .single();
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProductStandardCost\" (id, \"organizationId\", \"productId\", version, \"effectiveFrom\","
                        + " \"materialCost\", \"labourCost\", \"overheadCost\", \"subcontractCost\", \"totalCost\", source,"
                        + " \"bomStandardCostId\", \"convertMethod\", notes, \"createdBy\") VALUES (?, ?, ?, ?, ?::date, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), in.productId(), version, in.effectiveFrom(), m, l, o, s, total,
                        in.source() == null ? "manual" : in.source(), in.bomStandardCostId(),
                        Boolean.TRUE.equals(in.convertMethod()), in.notes(), TenantContext.userId())
                .update();
        return get(id);
    }

    @Transactional
    public Map<String, Object> approve(String id) {
        int n = jdbc.sql("UPDATE \"ProductStandardCost\" SET status = 'approved', \"approvedBy\" = ?, \"approvedAt\" = "
                + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = 'draft'")
                .params(TenantContext.userId(), id, org()).update();
        if (n == 0) {
            get(id);
            throw ApiException.badRequest("Only a draft standard cost can be approved");
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        int n = jdbc.sql("UPDATE \"ProductStandardCost\" SET status = 'cancelled', \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ? AND status IN ('draft', 'approved')").params(id, org())
                .update();
        if (n == 0) {
            get(id);
            throw ApiException.badRequest("Only a draft or approved standard cost can be cancelled");
        }
        return get(id);
    }

    /**
     * Activate an approved standard whose date has come: revalue the stock on hand to it, supersede the previous
     * standard, and carry the product at it from now on (one transaction).
     */
    public Map<String, Object> activate(String id) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT * FROM \"ProductStandardCost\" WHERE id = ? AND"
                    + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
            if (s == null) {
                throw ApiException.notFound("Standard cost not found");
            }
            if (!"approved".equals(s.get("status"))) {
                throw ApiException.badRequest("Only an approved standard cost can be activated");
            }
            LocalDate from = (LocalDate) s.get("effectiveFrom");
            if (from.isAfter(LocalDate.now(OrgDates.orgTimezone(jdbc, org())))) {
                throw ApiException.badRequest("The standard takes effect on " + from + "; it cannot be activated earlier");
            }
            String productId = (String) s.get("productId");
            Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT id, name, \"costingMethod\"::text AS method,"
                    + " \"costPrice\", \"batchTracking\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                    + " FOR UPDATE").params(productId, org()));
            String method = (String) product.get("method");
            if (!"STANDARD".equals(method) && !Boolean.TRUE.equals(s.get("convertMethod"))) {
                throw ApiException.badRequest(product.get("name") + " is costed at " + method + "; set convertMethod to"
                        + " switch it to standard cost");
            }
            BigDecimal std = SqlRows.dec(s, "totalCost");
            String description = "Standard cost revaluation · " + product.get("name") + " v" + s.get("version");
            Revaluation rv = revalueOnHand(productId, method, Boolean.TRUE.equals(product.get("batchTracking")),
                    Money.dec(product.get("costPrice")), std, "standard_cost", id,
                    "inventory:standard_cost:" + org() + ":" + id, description,
                    "Standard cost revaluation v" + s.get("version"));
            BigDecimal qty = rv.quantity();
            BigDecimal before = rv.before();
            BigDecimal diff = rv.difference();
            String je = rv.journalEntryId();
            jdbc.sql("UPDATE \"ProductStandardCost\" SET status = 'superseded', \"updatedAt\" = " + NOW + " WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ? AND status = 'active'").params(org(), productId)
                    .update();
            jdbc.sql("UPDATE \"Product\" SET \"costingMethod\" = 'STANDARD'::\"CostingMethod\", \"costPrice\" = ?,"
                    + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(std, productId).update();
            jdbc.sql("UPDATE \"ProductStandardCost\" SET status = 'active', \"activatedAt\" = " + NOW + ", \"previousCost\""
                            + " = ?, \"revaluationAmount\" = ?, \"revaluationQuantity\" = ?, \"revaluationJournalEntryId\" = ?,"
                            + " \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(qty.signum() == 0 ? product.get("costPrice") : Money.div(before, qty), diff, qty, je, id)
                    .update();
            audit.recordInTx(AuditInput.of("ProductStandardCost", id, "update", null, Obj.of("kind",
                    "standard_cost_activated", "productId", productId, "standard", JsNumbers.dec(std), "revaluation",
                    JsNumbers.dec(diff))));
        });
        return get(id);
    }

    /** A revaluation of a product's stock on hand: quantity, method-correct value before, posted difference, JE. */
    public record Revaluation(BigDecimal quantity, BigDecimal before, BigDecimal difference, String journalEntryId) {}

    /**
     * Revalue every quant of a product to {@code unit} (caller holds the product row lock, inside its transaction):
     * quant averages and open lots take the new cost; the difference to the method-correct value before is posted
     * (Dr / Cr stock valuation against standard cost revaluation 5960, when ≥ 0.01) and carried in value-only ledger
     * rows per quant ({@code sourceType} / {@code sourceId}), so the stock ledger, the valuation and the GL agree.
     */
    Revaluation revalueOnHand(String productId, String method, boolean lots, BigDecimal costPrice, BigDecimal unit,
            String sourceType, String sourceId, String postingKey, String description, String ledgerNote) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal before = BigDecimal.ZERO;
        List<Object[]> quantDeltas = new java.util.ArrayList<>();
        for (Map<String, Object> q : jdbc.sql("SELECT id, \"variantKey\", \"locationId\", quantity,"
                + " \"runningAverageCost\" FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " ORDER BY \"locationId\", \"variantKey\" FOR UPDATE").params(org(), productId).query().listOfRows()) {
            BigDecimal qq = Money.dec(q.get("quantity"));
            qty = qty.add(qq);
            BigDecimal qv = quantValue(productId, q, method, lots, costPrice);
            before = before.add(qv);
            quantDeltas.add(new Object[] {q, qq.multiply(unit).subtract(qv)});
            jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(unit, q.get("id")).update();
        }
        jdbc.sql("UPDATE \"InventoryBatch\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"isActive\" = true AND quantity > 0")
                .params(unit, org(), productId).update();
        BigDecimal after = qty.multiply(unit).setScale(6, RoundingMode.HALF_UP);
        BigDecimal diff = after.subtract(before).setScale(6, RoundingMode.HALF_UP);
        String je = null;
        if (diff.abs().compareTo(CENT) >= 0) {
            String sv = determination.mapped("stock_valuation");
            String reval = varianceAccount("revaluation");
            String v = JsNumbers.dec(diff.abs());
            List<Line> lines = diff.signum() > 0
                    ? List.of(Line.debit(sv, v, description), Line.credit(reval, v, description))
                    : List.of(Line.debit(reval, v, description), Line.credit(sv, v, description));
            Map<String, Object> posted = posting.post(PostingRequest.of("INV", Instant.now(), description, sourceType,
                    sourceId, postingKey, lines));
            je = posted == null ? null : (String) posted.get("id");
            // CM-1: the stock ledger carries the revaluation per quant (value-only rows).
            for (Object[] qd : quantDeltas) {
                @SuppressWarnings("unchecked")
                Map<String, Object> q = (Map<String, Object>) qd[0];
                String variant = (String) q.get("variantKey");
                lineage.recordValueOnly(productId, variant == null || variant.isEmpty() ? null : variant,
                        (String) q.get("locationId"), null, (BigDecimal) qd[1], sourceType, sourceId, null, ledgerNote);
            }
        }
        return new Revaluation(qty, before, diff, je);
    }

    /**
     * A STANDARD product's cost price edited on the product (no active standard cost governs it): the stock on hand is
     * revalued from {@code oldCost} to {@code newCost} in the caller's transaction, like a standard activation (source
     * {@code product_cost_price}, Dr / Cr stock valuation against 5960). Returns the journal entry id (null when the
     * difference is below 0.01 or nothing is on hand).
     */
    @Transactional
    public String revalueCostPriceChange(String productId, BigDecimal oldCost, BigDecimal newCost) {
        Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT id, name, \"costingMethod\"::text AS method,"
                + " \"batchTracking\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(productId, org()));
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        String description = "Cost price change · " + product.get("name") + ": " + JsNumbers.dec(nz(oldCost)) + " → "
                + JsNumbers.dec(nz(newCost));
        Revaluation rv = revalueOnHand(productId, (String) product.get("method"),
                Boolean.TRUE.equals(product.get("batchTracking")), nz(oldCost), nz(newCost), "product_cost_price",
                productId, "inventory:cost_price:" + org() + ":" + productId + ":" + java.util.UUID.randomUUID(),
                description, description);
        audit.recordInTx(AuditInput.of("Product", productId, "update", null, Obj.of("kind", "cost_price_revaluation",
                "oldCost", JsNumbers.dec(nz(oldCost)), "newCost", JsNumbers.dec(nz(newCost)), "quantity",
                JsNumbers.dec(rv.quantity()), "revaluation", JsNumbers.dec(rv.difference()), "journalEntryId",
                rv.journalEntryId())));
        return rv.journalEntryId();
    }

    /**
     * Hourly: activate approved standards whose effective date has come on their organisation's calendar (candidates
     * up to tomorrow UTC, each checked against its organisation's today; a standard no longer approved is skipped).
     */
    @Scheduled(cron = "0 20 * * * *", zone = "UTC")
    @SchedulerLock(name = "standard-cost-activation", lockAtMostFor = "PT50M")
    public void activateDue() {
        for (Map<String, Object> d : jdbc.sql("SELECT id, \"organizationId\", \"effectiveFrom\" FROM \"ProductStandardCost\""
                + " WHERE status = 'approved' AND \"effectiveFrom\" <= ? ORDER BY \"effectiveFrom\"")
                .param(LocalDate.now(ZoneOffset.UTC).plusDays(1)).query().listOfRows()) {
            String orgId = (String) d.get("organizationId");
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    Object from = d.get("effectiveFrom");
                    LocalDate day = from instanceof java.sql.Date sd ? sd.toLocalDate() : (LocalDate) from;
                    if (day.isAfter(LocalDate.now(OrgDates.orgTimezone(jdbc, orgId)))) {
                        return;
                    }
                    activate((String) d.get("id"));
                });
            } catch (RuntimeException e) {
                log.warn("[standard-cost] activation of {} failed: {}", d.get("id"), e.getMessage());
            }
        }
    }

    private BigDecimal quantValue(String productId, Map<String, Object> q, String method, boolean lots,
            BigDecimal costPrice) {
        BigDecimal qty = Money.dec(q.get("quantity"));
        BigDecimal avg = Money.dec(q.get("runningAverageCost"));
        BigDecimal unit = "STANDARD".equals(method) ? costPrice : avg;
        if (!"FIFO".equals(method) && !lots) {
            return qty.multiply(unit);
        }
        Map<String, Object> lot = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) AS qty, COALESCE(SUM(quantity *"
                        + " COALESCE(\"unitCost\", 0)), 0) AS value FROM \"InventoryBatch\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND COALESCE(\"variantId\", '') = ? AND \"locationId\" = ?"
                        + " AND \"isActive\" = true AND quantity > 0")
                .params(org(), productId, q.get("variantKey"), q.get("locationId")).query().singleRow();
        return Money.dec(lot.get("value")).add(qty.subtract(Money.dec(lot.get("qty"))).multiply(unit));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
