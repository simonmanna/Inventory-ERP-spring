package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.InventoryValuationService;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Landed cost LC-3 (ADR-S053): the reconciliation of every landed-cost balance to the GL, the classification and
 * location checks, the open lineage exceptions, and the drill-down trace from a voucher, charge or journal line to the
 * stock movements and journal lines it produced. Everything is filtered by organisation (and RLS applies).
 */
@Service
public class LandedCostReconciliationService {

    /** Stock-ledger references of landed-cost postings (value-only rows). */
    private static final List<String> LANDED_SOURCES = List.of("landed_cost_voucher", "landed_cost",
            "landed_cost_accrual");

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;
    private final AccountDeterminationService determination;
    private final InventoryValuationService valuation;
    private final StockPostingService stockPosting;

    public LandedCostReconciliationService(JdbcClient jdbc, AccountResolverService accounts,
            AccountDeterminationService determination, InventoryValuationService valuation,
            StockPostingService stockPosting) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.determination = determination;
        this.valuation = valuation;
        this.stockPosting = stockPosting;
    }

    private static BigDecimal dp2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal glBalance(String accountId, boolean creditNormal) {
        if (accountId == null) {
            return Money.ZERO;
        }
        BigDecimal v = jdbc.sql("SELECT coalesce(sum(jl.\"baseDebit\" - jl.\"baseCredit\"), 0) FROM \"JournalLine\" jl JOIN"
                + " \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = ? AND"
                + " jl.\"accountId\" = ? AND e.status <> 'draft'").params(org(), accountId).query(BigDecimal.class)
                .single();
        return creditNormal ? v.negate() : v;
    }

    private static Map<String, Object> row(String key, String label, String accountId, BigDecimal subledger,
            BigDecimal gl, Object drill) {
        BigDecimal diff = dp2(gl).subtract(dp2(subledger));
        return Obj.of("key", key, "label", label, "accountId", accountId, "subledger", JsNumbers.dec(dp2(subledger)),
                "gl", JsNumbers.dec(dp2(gl)), "difference", JsNumbers.dec(diff), "ok", diff.signum() == 0, "drillDown",
                drill);
    }

    /** The six reconciliation rows, the classification and location checks, and the open exceptions. */
    @Transactional
    public Map<String, Object> reconciliation() {
        List<Map<String, Object>> rows = new ArrayList<>();

        // 2155 — supplier invoices outstanding: charges posted to clearing whose supplier bill is not booked.
        String clearing = accounts.ensureByCode("2155", "Landed Cost Clearing", "current_liability",
                "landed_cost_clearing");
        List<Map<String, Object>> open = SqlRows.list(jdbc.sql("SELECT l.id AS \"voucherId\", l.code, c.id AS"
                + " \"chargeId\", c.\"supplierId\", c.\"supplierInvoiceRef\", c.\"billMode\", (coalesce(c.\"baseAmount\","
                + " c.amount) + c.\"taxAmount\" * coalesce(c.\"exchangeRate\", 1)) AS amount FROM \"LandedCostCharge\" c"
                + " JOIN \"LandedCost\" l ON l.id = c.\"landedCostId\" LEFT JOIN \"Document\" d ON d.id = c.\"vendorBillId\""
                + " WHERE l.\"organizationId\" = ? AND l.status = 'posted' AND l.\"voucherType\" IN ('voucher', 'credit')"
                + " AND c.\"creditAccountId\" IS NULL AND c.treatment IS NOT NULL AND (c.\"vendorBillId\" IS NULL OR"
                + " d.status::text = 'cancelled') ORDER BY l.code").param(org()));
        BigDecimal outstanding = open.stream().map(r -> SqlRows.dec(r, "amount")).reduce(Money.ZERO, BigDecimal::add);
        open.forEach(r -> r.put("amount", JsNumbers.dec(dp2(SqlRows.dec(r, "amount")))));
        rows.add(row("2155", "Landed cost clearing — supplier invoices outstanding", clearing, outstanding,
                glBalance(clearing, true), open));

        // 2156 — open accruals.
        String accrual = accounts.ensureByCode("2156", "Landed Cost Accrued", "current_liability",
                "landed_cost_accrual");
        List<Map<String, Object>> accruals = SqlRows.list(jdbc.sql("SELECT id, number, \"goodsReceiptId\", amount FROM"
                + " \"LandedCostAccrual\" WHERE \"organizationId\" = ? AND status = 'posted' ORDER BY number")
                .param(org()));
        BigDecimal accrued = accruals.stream().map(r -> SqlRows.dec(r, "amount")).reduce(Money.ZERO, BigDecimal::add);
        rows.add(row("2156", "Landed cost accrued — open estimates", accrual, accrued, glBalance(accrual, true),
                accruals));

        // 1396 — open adjuncts (price differences: STANDARD landed cost, parked lineage shares, accruals, ML).
        String adjunct = accounts.ensureByCode("1396", "Inventory Price Differences", "inventory",
                "inventory_price_difference");
        List<Map<String, Object>> adjuncts = SqlRows.list(jdbc.sql("SELECT \"sourceType\", \"adjustmentType\","
                + " (\"anchorLedgerId\" IS NULL) AS parked, sum(\"openAmount\") AS amount, count(*) AS adjustments FROM"
                + " \"CostValueAdjustment\" WHERE \"organizationId\" = ? AND \"openAmount\" <> 0 AND \"reversedAt\" IS NULL"
                + " AND coalesce(\"valuationComponent\", '') = 'price_difference' GROUP BY 1, 2, 3 ORDER BY 1, 2")
                .param(org()));
        BigDecimal adjunctOpen = adjuncts.stream().map(r -> SqlRows.dec(r, "amount")).reduce(Money.ZERO,
                BigDecimal::add);
        rows.add(row("1396", "Inventory price differences — open adjuncts", adjunct, adjunctOpen,
                glBalance(adjunct, false), adjuncts));

        // Stock — inventory sub-ledger vs the inventory accounts (landed-cost sources listed).
        Map<String, Object> tie = valuation.glTieOut(null, 0.05);
        BigDecimal sub = new BigDecimal(String.valueOf(tie.get("subledgerValue")));
        BigDecimal gl = new BigDecimal(String.valueOf(tie.get("glBalance")));
        List<Object> landedSources = new ArrayList<>();
        if (tie.get("bySource") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("source") != null
                        && String.valueOf(m.get("source")).startsWith("landed_cost")) {
                    landedSources.add(m);
                }
            }
        }
        rows.add(row("stock", "Inventory — sub-ledger vs GL (landed-cost sources listed)", null, sub, gl,
                landedSources));

        // COGS and WIP — what posted vouchers' dispositions put there vs their journal lines.
        Map<String, BigDecimal> expected = new LinkedHashMap<>();
        List<Map<String, Object>> classification = classification(expected);
        BigDecimal cogsSub = expected.getOrDefault("cogs", Money.ZERO);
        BigDecimal wipSub = expected.getOrDefault("wip", Money.ZERO);
        BigDecimal cogsGl = voucherLines(determination.mapped("cogs"));
        BigDecimal wipGl = voucherLines(determination.mapped("wip"));
        BigDecimal cascaded = jdbc.sql("SELECT coalesce(sum(r.amount), 0) FROM \"CostAdjustmentRunLine\" r JOIN"
                + " \"CostValueAdjustmentLine\" cl ON cl.id = r.\"adjustmentLineId\" JOIN \"CostValueAdjustment\" a ON a.id ="
                + " cl.\"adjustmentId\" JOIN \"LandedCost\" l ON l.code = a.\"sourceId\" AND l.\"organizationId\" ="
                + " a.\"organizationId\" WHERE a.\"organizationId\" = ? AND a.\"sourceType\" = 'landed_cost_voucher' AND"
                + " l.status = 'posted' AND r.status = 'posted'").param(org()).query(BigDecimal.class).single();
        List<Map<String, Object>> openWip = SqlRows.list(jdbc.sql("SELECT cl.\"targetRef\" AS \"order\","
                + " sum(cl.amount) AS amount FROM \"CostValueAdjustmentLine\" cl JOIN \"CostValueAdjustment\" a ON a.id ="
                + " cl.\"adjustmentId\" WHERE a.\"organizationId\" = ? AND a.\"sourceType\" = 'landed_cost_voucher' AND"
                + " a.\"reversedAt\" IS NULL AND cl.target = 'wip' AND NOT EXISTS (SELECT 1 FROM \"CostAdjustmentRunLine\" r"
                + " WHERE r.\"adjustmentLineId\" = cl.id AND r.status = 'posted') GROUP BY 1 ORDER BY 1").param(org()));
        rows.add(row("cogs", "Cost of sales — landed-cost consumption", determination.mapped("cogs"), cogsSub, cogsGl,
                null));
        rows.add(row("wip", "WIP — open landed shares per production order", determination.mapped("wip"),
                wipSub.subtract(cascaded), wipGl.subtract(cascaded), openWip));

        List<Map<String, Object>> locations = locationCheck();
        List<Map<String, Object>> exceptions = SqlRows.list(jdbc.sql("SELECT e.*, p.name AS \"productName\" FROM"
                + " \"LineageException\" e LEFT JOIN \"Product\" p ON p.id = e.\"productId\" WHERE e.\"organizationId\" = ?"
                + " AND e.status = 'open' ORDER BY e.\"createdAt\"").param(org()));
        boolean clean = rows.stream().allMatch(r -> Boolean.TRUE.equals(r.get("ok"))) && classification.isEmpty()
                && locations.isEmpty() && exceptions.isEmpty();
        return Obj.of("rows", rows, "classificationMismatches", classification, "locationMismatches", locations,
                "exceptions", exceptions, "clean", clean);
    }

    /** Σ debit − credit on {@code account} of the posting entries of currently posted vouchers. */
    private BigDecimal voucherLines(String account) {
        return jdbc.sql("SELECT coalesce(sum(jl.\"baseDebit\" - jl.\"baseCredit\"), 0) FROM \"JournalLine\" jl JOIN"
                + " \"LandedCost\" l ON l.\"journalEntryId\" = jl.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                + " l.status = 'posted' AND l.\"voucherType\" IN ('voucher', 'credit') AND jl.\"accountId\" = ?")
                .params(org(), account).query(BigDecimal.class).single();
    }

    /**
     * Classification: per posted voucher, the amount its allocations' dispositions put on each landed target (stock /
     * adjunct / WIP / COGS / returns) compared with the journal lines on that account. Lists mismatches even when the
     * entry balances. {@code totals} receives Σ cogs and wip expected.
     */
    private List<Map<String, Object>> classification(Map<String, BigDecimal> totals) {
        List<Map<String, Object>> out = new ArrayList<>();
        String cogs = determination.mapped("cogs");
        String wip = determination.mapped("wip");
        String adjunct = accounts.ensureByCode("1396", "Inventory Price Differences", "inventory",
                "inventory_price_difference");
        String returns = accounts.ensureByCode("5976", "Landed Cost on Returned Goods", "cost_of_goods_sold",
                "landed_cost_returns");
        for (Map<String, Object> v : SqlRows.list(jdbc.sql("SELECT id, code, \"journalEntryId\" FROM \"LandedCost\" WHERE"
                + " \"organizationId\" = ? AND status = 'posted' AND \"voucherType\" IN ('voucher', 'credit') AND"
                + " \"journalEntryId\" IS NOT NULL").param(org()))) {
            Map<String, BigDecimal> exp = new TreeMap<>();
            boolean lineage = false;
            for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT a.\"productId\", a.disposition::text AS d,"
                    + " p.\"costingMethod\"::text AS method FROM \"LandedCostAllocation\" a JOIN \"Product\" p ON p.id ="
                    + " a.\"productId\" WHERE a.\"landedCostId\" = ?").param(v.get("id")))) {
                if (a.get("d") == null) {
                    continue;
                }
                lineage = true;
                Map<String, Object> d = LandedCostLayers.disposition(Map.of("disposition", a.get("d")));
                String stockAccount = stockPosting.landedAccount((String) a.get("productId"), BigDecimal.ONE);
                exp.merge(stockAccount, Money.dec(d.get("stock")), BigDecimal::add);
                exp.merge(adjunct, Money.dec(d.get("parked")).add(Money.dec(d.getOrDefault("returnParked", 0))),
                        BigDecimal::add);
                exp.merge(wip, Money.dec(d.get("wip")), BigDecimal::add);
                exp.merge(cogs, Money.dec(d.get("cogs")), BigDecimal::add);
                exp.merge(returns, Money.dec(d.get("returned")), BigDecimal::add);
                totals.merge("cogs", Money.dec(d.get("cogs")), BigDecimal::add);
                totals.merge("wip", Money.dec(d.get("wip")), BigDecimal::add);
            }
            if (!lineage) {
                continue;
            }
            for (Map.Entry<String, BigDecimal> e : exp.entrySet()) {
                BigDecimal actual = jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\""
                        + " WHERE \"journalEntryId\" = ? AND \"accountId\" = ?").params(v.get("journalEntryId"), e.getKey())
                        .query(BigDecimal.class).single();
                BigDecimal expectedAmount = dp2(e.getValue());
                if (expectedAmount.compareTo(dp2(actual)) != 0) {
                    out.add(Obj.of("voucherId", v.get("id"), "code", v.get("code"), "accountId", e.getKey(),
                            "expected", JsNumbers.dec(expectedAmount), "posted", JsNumbers.dec(dp2(actual))));
                }
            }
        }
        return out;
    }

    /**
     * Location check: per posted voucher, product and location, the value-only stock rows it wrote (at posting) vs the
     * stock its disposition placed there.
     */
    private List<Map<String, Object>> locationCheck() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : SqlRows.list(jdbc.sql("SELECT id, code, \"postedAt\" FROM \"LandedCost\" WHERE"
                + " \"organizationId\" = ? AND status = 'posted' AND \"voucherType\" IN ('voucher', 'credit') AND"
                + " \"postedDisposition\" IS NOT NULL").param(org()))) {
            Map<String, BigDecimal> expected = new TreeMap<>();
            String json = jdbc.sql("SELECT \"postedDisposition\"::text FROM \"LandedCost\" WHERE id = ?").param(v.get("id"))
                    .query(String.class).single();
            com.inventoryerp.shared.web.EntityJson.tree(json).properties().forEach(e -> {
                if (e.getKey().startsWith("stock:") || e.getKey().startsWith("adjunct:")
                        || e.getKey().startsWith("parked:")) {
                    String[] k = e.getKey().split(":");
                    expected.merge(k[1] + ":" + k[2], new BigDecimal(e.getValue().asString()), BigDecimal::add);
                }
            });
            Map<String, BigDecimal> actual = new TreeMap<>();
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"productId\", \"locationId\", sum(\"totalValue\")"
                    + " AS v FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND type = 'cost_adjustment' AND"
                    + " \"referenceType\" = 'landed_cost_voucher' AND \"referenceId\" = ? AND coalesce(notes, '') NOT LIKE"
                    + " 'Released%' GROUP BY 1, 2").params(org(), v.get("code")))) {
                actual.put(r.get("productId") + ":" + r.get("locationId"), SqlRows.dec(r, "v"));
            }
            java.util.TreeSet<String> keys = new java.util.TreeSet<>(expected.keySet());
            keys.addAll(actual.keySet());
            for (String k : keys) {
                BigDecimal e = dp2(expected.getOrDefault(k, Money.ZERO));
                BigDecimal a = dp2(actual.getOrDefault(k, Money.ZERO));
                if (e.compareTo(a) != 0) {
                    String[] pl = k.split(":");
                    out.add(Obj.of("voucherId", v.get("id"), "code", v.get("code"), "productId", pl[0], "locationId",
                            pl[1], "expected", JsNumbers.dec(e), "stockLedger", JsNumbers.dec(a)));
                }
            }
        }
        return out;
    }

    // ---- trace -----------------------------------------------------------------------------------------------------

    /**
     * Drill-down chain from a voucher, a charge or a journal line: voucher → charges → allocations (receipt, line,
     * product, disposition) → cost adjustments per pool (with transfer hops) and their consumption movements →
     * journal lines. Every hop is filtered by the organisation; an id of another organisation is a 404.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> trace(String voucherId, String chargeId, String journalLineId) {
        String id = voucherId;
        if (id == null && chargeId != null) {
            id = jdbc.sql("SELECT \"landedCostId\" FROM \"LandedCostCharge\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(chargeId, org()).query(String.class).optional().orElse(null);
        }
        if (id == null && journalLineId != null) {
            id = jdbc.sql("SELECT l.id FROM \"JournalLine\" jl JOIN \"LandedCost\" l ON l.\"journalEntryId\" ="
                    + " jl.\"journalEntryId\" OR l.\"reversalJournalEntryId\" = jl.\"journalEntryId\" WHERE jl.id = ? AND"
                    + " jl.\"organizationId\" = ? AND l.\"organizationId\" = ? LIMIT 1").params(journalLineId, org(),
                    org()).query(String.class).optional().orElse(null);
        }
        if (id == null) {
            throw ApiException.notFound("Landed cost voucher not found");
        }
        Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT id, code, status, \"voucherType\", date, \"journalEntryId\","
                + " \"reversalJournalEntryId\", \"originalVoucherId\" FROM \"LandedCost\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (v == null) {
            throw ApiException.notFound("Landed cost voucher not found");
        }
        List<Map<String, Object>> charges = SqlRows.list(jdbc.sql("SELECT id, \"lineNumber\", kind, description,"
                + " \"supplierId\", \"supplierInvoiceRef\", amount, \"baseAmount\", \"taxAmount\", treatment,"
                + " \"allocationMethod\", \"vendorBillId\" FROM \"LandedCostCharge\" WHERE \"landedCostId\" = ? AND"
                + " \"organizationId\" = ?" + (chargeId != null ? " AND id = ?" : "") + " ORDER BY \"lineNumber\"")
                .params(chargeId != null ? List.of(id, org(), chargeId) : List.of(id, org())));
        for (Map<String, Object> c : charges) {
            List<Map<String, Object>> allocs = SqlRows.list(jdbc.sql("SELECT a.id, a.\"goodsReceiptId\", g.\"receiptNumber\","
                    + " a.\"goodsReceiptLineId\", a.\"productId\", p.name AS \"productName\", a.\"locationId\","
                    + " a.\"allocatedAmount\", a.\"capitalizedAmount\", a.disposition FROM \"LandedCostAllocation\" a JOIN"
                    + " \"Product\" p ON p.id = a.\"productId\" LEFT JOIN \"GoodsReceiptNote\" g ON g.id = a.\"goodsReceiptId\""
                    + " WHERE a.\"chargeId\" = ? AND a.\"organizationId\" = ?").params(c.get("id"), org()));
            c.put("allocations", allocs);
        }
        List<Map<String, Object>> adjustments = SqlRows.list(jdbc.sql("SELECT a.id, a.\"adjustmentType\","
                + " a.\"productId\", a.\"locationId\", a.\"layerLedgerId\", a.\"parentAdjustmentId\", a.\"rootAdjustmentId\","
                + " a.amount, a.\"onHandAmount\", a.\"openAmount\", a.\"reversedAt\", lg.type::text AS \"layerType\","
                + " lg.\"referenceType\" AS \"layerReferenceType\", lg.\"referenceId\" AS \"layerReferenceId\" FROM"
                + " \"CostValueAdjustment\" a LEFT JOIN \"InventoryLedger\" lg ON lg.id = a.\"layerLedgerId\" WHERE"
                + " a.\"organizationId\" = ? AND a.\"sourceType\" = 'landed_cost_voucher' AND a.\"sourceId\" = ? ORDER BY"
                + " a.\"createdAt\", a.id").params(org(), v.get("code")));
        for (Map<String, Object> a : adjustments) {
            a.put("lines", SqlRows.list(jdbc.sql("SELECT cl.target, cl.\"targetRef\", cl.amount, cl.\"consumptionLedgerId\","
                    + " m.type::text AS \"moveType\", m.\"referenceType\", m.\"referenceId\", m.\"locationId\","
                    + " m.\"quantityChange\" FROM \"CostValueAdjustmentLine\" cl LEFT JOIN \"InventoryLedger\" m ON m.id ="
                    + " cl.\"consumptionLedgerId\" WHERE cl.\"adjustmentId\" = ? AND cl.\"organizationId\" = ? ORDER BY"
                    + " cl.target").params(a.get("id"), org())));
        }
        List<Map<String, Object>> journal = SqlRows.list(jdbc.sql("SELECT jl.id, jl.\"journalEntryId\", a.code AS"
                + " \"accountCode\", a.name AS \"accountName\", jl.\"baseDebit\" AS debit, jl.\"baseCredit\" AS credit,"
                + " jl.description FROM \"JournalLine\" jl JOIN \"Account\" a ON a.id = jl.\"accountId\" WHERE"
                + " jl.\"organizationId\" = ? AND jl.\"journalEntryId\" IN (SELECT e.id FROM \"JournalEntry\" e WHERE"
                + " e.\"organizationId\" = ? AND (e.id = ? OR e.id = ? OR (e.\"sourceId\" = ? AND e.\"sourceType\" LIKE"
                + " 'landed_cost%'))) ORDER BY jl.\"journalEntryId\", a.code").params(org(), org(),
                v.get("journalEntryId"), v.get("reversalJournalEntryId"), v.get("code")));
        if (journalLineId != null) {
            journal.forEach(j -> j.put("selected", journalLineId.equals(j.get("id"))));
        }
        return Obj.of("voucher", v, "charges", charges, "adjustments", adjustments, "journal", journal);
    }
}
