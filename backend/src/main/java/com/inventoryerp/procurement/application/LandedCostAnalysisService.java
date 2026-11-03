package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.inventory.application.LineageFollower;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Landed cost LC-6 reports (ADR-S053). Built on what the engine already records — stored dispositions, lineage lines,
 * accrual clearings and the stock ledger — never a second engine:
 *
 * <ul>
 *   <li><b>variance</b>: estimate (accrual cleared) vs actual per accrual clearing, with the realised FX of the
 *       payments of the vouchers' supplier bills in its own column;</li>
 *   <li><b>valuation impact</b> and <b>by warehouse</b>: what posted vouchers put into stock / adjunct / WIP / COGS per
 *       warehouse, product and costing method;</li>
 *   <li><b>by cost centre</b>: charges by the cost centre they carry (charge, else voucher);</li>
 *   <li><b>profitability</b>: the same units sold in the period, cost of sales split by the cost layers those units
 *       carried (purchase, landed, conversion, distribution); selling and admin expenses shown apart;</li>
 *   <li><b>operational cost structure</b>: per completed production run — input, good output in the output unit, yield,
 *       materials (of which landed), conversion, cost per output unit.</li>
 * </ul>
 */
@Service
public class LandedCostAnalysisService {

    private static final String POSTED = " l.\"organizationId\" = ? AND l.status = 'posted' AND l.\"voucherType\" IN"
            + " ('voucher', 'credit') AND l.date >= ? AND l.date < ?";

    private final JdbcClient jdbc;
    private final AccountDeterminationService determination;
    private final LineageFollower follower;

    public LandedCostAnalysisService(JdbcClient jdbc, AccountDeterminationService determination,
            LineageFollower follower) {
        this.jdbc = jdbc;
        this.determination = determination;
        this.follower = follower;
    }

    private static String d(BigDecimal v) {
        return JsNumbers.dec(v.setScale(2, RoundingMode.HALF_UP));
    }

    private static Object[] period(LocalDate from, LocalDate to) {
        return new Object[] {org(), java.sql.Date.valueOf(from), java.sql.Date.valueOf(to.plusDays(1))};
    }

    /** Estimate vs actual per accrual clearing; realised FX of the vouchers' bill payments apart. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> variance(LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT c.id, a.number AS accrual, g.\"receiptNumber\","
                + " c.kind, l.code AS voucher, c.\"voucherId\", c.\"functionalAmount\" AS estimate, c.\"actualFunctional\""
                + " AS actual, c.variance, c.\"originalCurrency\", c.\"originalAmount\", c.\"actualCurrency\","
                + " c.\"actualAmount\", c.\"actualRate\", c.\"createdAt\" FROM \"LandedCostAccrualClearing\" c JOIN"
                + " \"LandedCostAccrual\" a ON a.id = c.\"accrualId\" JOIN \"GoodsReceiptNote\" g ON g.id ="
                + " a.\"goodsReceiptId\" LEFT JOIN \"LandedCost\" l ON l.id = c.\"voucherId\" WHERE c.\"organizationId\" = ?"
                + " AND c.\"createdAt\" >= ? AND c.\"createdAt\" < ? ORDER BY c.\"createdAt\"").params(period(from, to)));
        for (Map<String, Object> r : rows) {
            r.put("realisedFx", r.get("voucherId") == null ? "0" : d(realisedFx((String) r.get("voucherId"))));
            for (String k : List.of("estimate", "actual", "variance")) {
                r.put(k, r.get(k) == null ? null : d(Money.dec(r.get(k))));
            }
        }
        return rows;
    }

    /** Realised FX (gain +) of the payments allocated to the bills a voucher generated or linked, pro rata. */
    BigDecimal realisedFx(String voucherId) {
        String gain = determination.mapped("fx_gain");
        String loss = determination.mapped("fx_loss");
        return jdbc.sql("SELECT coalesce(sum((jl.\"baseCredit\" - jl.\"baseDebit\") * pa.amount / nullif(p.amount, 0)), 0)"
                + " FROM \"PaymentAllocation\" pa JOIN \"Payment\" p ON p.id = pa.\"paymentId\" JOIN \"JournalLine\" jl ON"
                + " jl.\"journalEntryId\" = p.\"journalEntryId\" WHERE pa.\"organizationId\" = ? AND jl.\"accountId\" IN"
                + " (?, ?) AND p.status::text <> 'cancelled' AND pa.\"documentId\" IN (SELECT DISTINCT \"vendorBillId\" FROM"
                + " \"LandedCostCharge\" WHERE \"landedCostId\" = ? AND \"vendorBillId\" IS NOT NULL)")
                .params(org(), gain, loss, voucherId).query(BigDecimal.class).single();
    }

    /** Σ of the posted dispositions per target key over the period's vouchers. */
    private Map<String, BigDecimal> dispositions(LocalDate from, LocalDate to) {
        Map<String, BigDecimal> out = new TreeMap<>();
        for (String json : jdbc.sql("SELECT l.\"postedDisposition\"::text FROM \"LandedCost\" l WHERE" + POSTED
                + " AND l.\"postedDisposition\" IS NOT NULL").params(period(from, to)).query(String.class).list()) {
            EntityJson.tree(json).properties().forEach(e -> out.merge(e.getKey(), new BigDecimal(e.getValue()
                    .asString()), BigDecimal::add));
        }
        return out;
    }

    /** What posted vouchers put into inventory per warehouse, product and costing method. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> valuationImpact(LocalDate from, LocalDate to) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        dispositions(from, to).forEach((key, amount) -> {
            String[] k = key.split(":");
            if (k.length < 3 || !List.of("stock", "adjunct", "parked").contains(k[0])) {
                return;
            }
            Map<String, Object> r = rows.computeIfAbsent(k[1] + ":" + k[2], x -> {
                Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT p.name, p.\"costingMethod\"::text AS method,"
                        + " (SELECT name FROM \"InventoryLocation\" WHERE id = ?) AS warehouse FROM \"Product\" p WHERE"
                        + " p.id = ?").params(k[2], k[1]));
                return new LinkedHashMap<>(Obj.of("productId", k[1], "productName", p == null ? null : p.get("name"),
                        "costingMethod", p == null ? null : p.get("method"), "locationId", k[2], "warehouse",
                        p == null ? null : p.get("warehouse"), "stock", BigDecimal.ZERO, "adjunct", BigDecimal.ZERO,
                        "parked", BigDecimal.ZERO));
            });
            r.merge(k[0], amount, (a, b) -> ((BigDecimal) a).add((BigDecimal) b));
        });
        List<Map<String, Object>> out = new ArrayList<>(rows.values());
        for (Map<String, Object> r : out) {
            BigDecimal total = ((BigDecimal) r.get("stock")).add((BigDecimal) r.get("adjunct"))
                    .add((BigDecimal) r.get("parked"));
            for (String k : List.of("stock", "adjunct", "parked")) {
                r.put(k, d((BigDecimal) r.get(k)));
            }
            r.put("total", d(total));
        }
        return out;
    }

    /** Per warehouse: added to stock (incl. adjunct and parked) by the period's vouchers. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> byWarehouse(LocalDate from, LocalDate to) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> r : valuationImpact(from, to)) {
            totals.merge((String) r.get("locationId"), new BigDecimal((String) r.get("total")), BigDecimal::add);
            names.putIfAbsent((String) r.get("locationId"), (String) r.get("warehouse"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        totals.forEach((loc, v) -> out.add(Obj.of("locationId", loc, "warehouse", names.get(loc), "addedToInventory",
                d(v))));
        return out;
    }

    /** Charges per cost centre (the charge's, else the voucher's), capitalised vs expensed. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> byCostCentre(LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT coalesce(c.\"costCenterId\", l.\"costCenterId\") AS"
                + " \"costCenterId\", cc.code, cc.name, count(*) AS charges, sum(CASE WHEN c.treatment = 'capitalize' THEN"
                + " coalesce(c.\"baseAmount\", c.amount) ELSE 0 END) AS capitalised, sum(CASE WHEN c.treatment = 'expense'"
                + " THEN coalesce(c.\"baseAmount\", c.amount) ELSE 0 END) AS expensed FROM \"LandedCostCharge\" c JOIN"
                + " \"LandedCost\" l ON l.id = c.\"landedCostId\" LEFT JOIN \"CostCenter\" cc ON cc.id ="
                + " coalesce(c.\"costCenterId\", l.\"costCenterId\") WHERE" + POSTED + " GROUP BY 1, 2, 3 ORDER BY 2")
                .params(period(from, to)));
        for (Map<String, Object> r : rows) {
            r.put("capitalised", d(Money.dec(r.get("capitalised"))));
            r.put("expensed", d(Money.dec(r.get("expensed"))));
        }
        return rows;
    }

    /**
     * Profitability before / after landed cost: the units sold in the period, cost of sales from their issue rows,
     * split by the later cost adjustments those very units carried (lineage lines whose consumption is the sale):
     * landed (vouchers, receipt landed cost, accruals), conversion (overhead settlement, cost adjustment runs),
     * distribution (branch transfer freight); purchase = the rest. Selling and admin expenses are reported apart and
     * never added to product cost.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> profitability(LocalDate from, LocalDate to) {
        Object[] p = period(from, to);
        Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"productId\", pr.name, -sum(l.\"quantityChange\") AS"
                + " units, sum(l.\"totalValue\") AS cogs FROM \"InventoryLedger\" l JOIN \"Product\" pr ON pr.id ="
                + " l.\"productId\" WHERE l.\"organizationId\" = ? AND l.type = 'issue' AND l.\"createdAt\" >= ? AND"
                + " l.\"createdAt\" < ? GROUP BY 1, 2").params(p))) {
            byProduct.put((String) r.get("productId"), new LinkedHashMap<>(r));
        }
        Map<String, Map<String, BigDecimal>> layers = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"productId\", a.\"adjustmentType\", sum(cl.amount)"
                + " AS amount FROM \"CostValueAdjustmentLine\" cl JOIN \"CostValueAdjustment\" a ON a.id = cl.\"adjustmentId\""
                + " JOIN \"InventoryLedger\" l ON l.id = cl.\"consumptionLedgerId\" WHERE l.\"organizationId\" = ? AND"
                + " l.type = 'issue' AND l.\"createdAt\" >= ? AND l.\"createdAt\" < ? AND a.\"reversedAt\" IS NULL GROUP BY"
                + " 1, 2").params(p))) {
            String layer = switch ((String) r.get("adjustmentType")) {
                case "landed_cost", "std_price_difference", "landed_accrual" -> "landed";
                case "overhead_settlement", "cost_adjustment", "stripping_amortisation", "rehab_production" ->
                        "conversion";
                case "transfer_freight" -> "distribution";
                default -> "other";
            };
            layers.computeIfAbsent((String) r.get("productId"), k -> new LinkedHashMap<>()).merge(layer,
                    SqlRows.dec(r, "amount"), BigDecimal::add);
        }
        // Units sold after the charge posted carry it inside the running average: follow each landed value row (the
        // same lineage the reversal uses) and take the share of this period's sales.
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT a.\"productId\", a.\"onHandAmount\", g.* FROM"
                + " \"CostValueAdjustment\" a JOIN \"InventoryLedger\" g ON g.id = a.\"anchorLedgerId\" WHERE"
                + " a.\"organizationId\" = ? AND a.\"reversedAt\" IS NULL AND a.\"valuationComponent\" IS NULL AND"
                + " a.\"adjustmentType\" IN ('landed_cost', 'landed_accrual') AND a.\"onHandAmount\" <> 0 AND"
                + " g.\"createdAt\" < ?").params(org(), p[2]))) {
            BigDecimal pool = SqlRows.dec(a, "balanceAfter");
            if (pool.signum() <= 0) {
                continue;
            }
            for (var placement : follower.follow(a, pool, BigDecimal.ZERO, SqlRows.dec(a, "onHandAmount"))
                    .placements()) {
                if (placement.consumptionLedgerId() == null || !"issue".equals(placement.moveType())) {
                    continue;
                }
                Map<String, Object> issue = SqlRows.one(jdbc.sql("SELECT \"productId\" FROM \"InventoryLedger\" WHERE"
                        + " id = ? AND \"createdAt\" >= ? AND \"createdAt\" < ?").params(placement.consumptionLedgerId(),
                        p[1], p[2]));
                if (issue != null) {
                    layers.computeIfAbsent((String) issue.get("productId"), k -> new LinkedHashMap<>()).merge("landed",
                            placement.amount(), BigDecimal::add);
                }
            }
        }
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT i.\"productId\", sum(i.subtotal) AS revenue FROM"
                + " \"InvoiceItem\" i JOIN \"Invoice\" v ON v.id = i.\"invoiceId\" WHERE v.\"organizationId\" = ? AND"
                + " v.status IN ('posted', 'paid') AND v.\"issueDate\" >= ? AND v.\"issueDate\" < ? GROUP BY 1")
                .params(p))) {
            revenue.put((String) r.get("productId"), SqlRows.dec(r, "revenue"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : byProduct.values()) {
            String id = (String) r.get("productId");
            Map<String, BigDecimal> l = layers.getOrDefault(id, Map.of());
            BigDecimal cogs = SqlRows.dec(r, "cogs").abs();
            BigDecimal landed = l.getOrDefault("landed", BigDecimal.ZERO);
            BigDecimal conversion = l.getOrDefault("conversion", BigDecimal.ZERO);
            BigDecimal distribution = l.getOrDefault("distribution", BigDecimal.ZERO);
            BigDecimal purchase = cogs.subtract(landed).subtract(conversion).subtract(distribution);
            BigDecimal rev = revenue.getOrDefault(id, BigDecimal.ZERO);
            rows.add(Obj.of("productId", id, "name", r.get("name"), "unitsSold", JsNumbers.dec(SqlRows.dec(r, "units")),
                    "revenue", d(rev), "purchase", d(purchase), "landed", d(landed), "conversion", d(conversion),
                    "distribution", d(distribution), "cogs", d(cogs), "grossProfitBeforeLanded",
                    d(rev.subtract(cogs).add(landed)), "grossProfit", d(rev.subtract(cogs))));
        }
        BigDecimal sellingAdmin = jdbc.sql("SELECT coalesce(sum(jl.\"baseDebit\" - jl.\"baseCredit\"), 0) FROM"
                + " \"JournalLine\" jl JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" JOIN \"Account\" a ON a.id ="
                + " jl.\"accountId\" JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE jl.\"organizationId\" = ? AND"
                + " e.status <> 'draft' AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ? AND c.\"reportSection\"::text ="
                + " 'operating_expense'").params(p).query(BigDecimal.class).single();
        return Obj.of("products", rows, "sellingAndAdminExpenses", d(sellingAdmin), "note",
                "Selling and administrative expenses are period costs, shown apart and never added into product cost");
    }

    /**
     * Freight and supplier cost history (LC-7): per month, supplier and charge kind — charges, base amount, the goods
     * value and quantity they landed on, and the cost per unit and as % of goods value.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> freightHistory(LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT to_char(l.date, 'YYYY-MM') AS month,"
                + " coalesce(pt.name, '(none)') AS supplier, c.kind, count(DISTINCT c.id) AS charges,"
                + " sum(a.\"allocatedAmount\") AS amount, sum(a.\"receivedQty\") AS quantity, sum(a.\"basisValue\") FILTER"
                + " (WHERE a.\"basisMethod\" = 'value') AS \"goodsValue\" FROM \"LandedCostCharge\" c JOIN \"LandedCost\" l ON"
                + " l.id = c.\"landedCostId\" JOIN \"LandedCostAllocation\" a ON a.\"chargeId\" = c.id LEFT JOIN \"Partner\" pt"
                + " ON pt.id = c.\"supplierId\" WHERE" + POSTED + " GROUP BY 1, 2, 3 ORDER BY 1, 2, 3")
                .params(period(from, to)));
        for (Map<String, Object> r : rows) {
            BigDecimal amount = SqlRows.dec(r, "amount");
            BigDecimal qty = SqlRows.dec(r, "quantity");
            BigDecimal goods = r.get("goodsValue") == null ? null : SqlRows.dec(r, "goodsValue");
            r.put("amount", d(amount));
            r.put("perUnit", qty.signum() == 0 ? null : JsNumbers.dec(amount.divide(qty, 6, RoundingMode.HALF_UP)));
            r.put("pctOfGoods", goods == null || goods.signum() == 0 ? null : JsNumbers.dec(amount
                    .multiply(BigDecimal.valueOf(100)).divide(goods, 2, RoundingMode.HALF_UP)));
            r.put("goodsValue", goods == null ? null : d(goods));
        }
        return rows;
    }

    /**
     * Operational cost structure per completed production run in the period: input quantity, good output in the output
     * unit, yield (output ÷ input), materials (of which landed cost), conversion, output value and cost per output unit
     * — unit cost on the actual good output, never on the input.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> productionStructure(LocalDate from, LocalDate to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : SqlRows.list(jdbc.sql("SELECT o.id, o.\"orderCode\", o.status::text AS status,"
                + " p.name AS \"outputProduct\", u.code AS \"outputUom\" FROM \"ProductionOrder\" o LEFT JOIN \"Product\" p ON"
                + " p.id = o.\"outputProductId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE"
                + " o.\"organizationId\" = ? AND o.status::text IN ('completed', 'closed') AND o.\"updatedAt\" >= ? AND"
                + " o.\"updatedAt\" < ? ORDER BY o.\"orderCode\"").params(period(from, to)))) {
            Object[] refs = {org(), o.get("id"), o.get("orderCode")};
            Map<String, Object> mv = SqlRows.one(jdbc.sql("SELECT coalesce(sum(CASE WHEN type = 'production_consume' THEN"
                    + " -\"quantityChange\" END), 0) AS input, coalesce(sum(CASE WHEN type = 'production_consume' THEN"
                    + " \"totalValue\" END), 0) AS materials, coalesce(sum(CASE WHEN type = 'production_output' THEN"
                    + " \"quantityChange\" END), 0) AS output, coalesce(sum(CASE WHEN type = 'production_output' THEN"
                    + " \"totalValue\" END), 0) AS \"outputValue\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                    + " \"referenceId\" IN (?, ?)").params(refs));
            BigDecimal landed = jdbc.sql("SELECT coalesce(sum(cl.amount), 0) FROM \"CostValueAdjustmentLine\" cl JOIN"
                    + " \"CostValueAdjustment\" a ON a.id = cl.\"adjustmentId\" WHERE cl.\"organizationId\" = ? AND"
                    + " cl.target = 'wip' AND cl.\"targetRef\" IN (?, ?) AND a.\"reversedAt\" IS NULL AND a.\"adjustmentType\""
                    + " IN ('landed_cost', 'std_price_difference', 'landed_accrual')").params(refs)
                    .query(BigDecimal.class).single();
            BigDecimal input = SqlRows.dec(mv, "input");
            BigDecimal output = SqlRows.dec(mv, "output");
            BigDecimal materials = SqlRows.dec(mv, "materials").abs();
            BigDecimal outputValue = SqlRows.dec(mv, "outputValue").abs().add(landed);
            BigDecimal conversion = outputValue.subtract(materials).subtract(landed).max(BigDecimal.ZERO);
            out.add(Obj.of("orderCode", o.get("orderCode"), "status", o.get("status"), "outputProduct",
                    o.get("outputProduct"), "outputUom", o.get("outputUom"), "inputQty", JsNumbers.dec(input),
                    "goodOutputQty", JsNumbers.dec(output), "yieldPct", input.signum() == 0 ? null
                            : JsNumbers.dec(output.multiply(BigDecimal.valueOf(100)).divide(input, 2,
                                    RoundingMode.HALF_UP)),
                    "materials", d(materials), "landedInMaterials", d(landed), "conversion", d(conversion),
                    "totalCost", d(outputValue), "costPerOutputUnit", output.signum() == 0 ? null
                            : JsNumbers.dec(outputValue.divide(output, 6, RoundingMode.HALF_UP))));
        }
        return out;
    }
}
