package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Landed cost reports (ADR-S007 §7): by shipment / voucher, by item, by supplier, the landed-cost clearing account,
 * and product profitability with landed cost in COGS. Posted landed costs only (legacy and vouchers); base currency.
 */
@Service
public class LandedCostReportService {

    private static final String POSTED = " l.\"organizationId\" = ? AND l.status = 'posted' AND l.date >= ? AND l.date < ?";

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;

    public LandedCostReportService(JdbcClient jdbc, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    private static String dec(Object v) {
        return JsNumbers.dec(Money.dec(v));
    }

    /** Per voucher: purchase value of its receipts, each component, total landed and cost per item. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> byShipment(LocalDate from, LocalDate to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.id, l.code, l.date, l.\"voucherType\","
                + " l.\"totalAmount\", l.\"capitalizedAmount\", l.\"expensedAmount\", l.\"taxAmount\", s.id AS \"shipmentId\","
                + " s.code AS \"shipmentCode\", s.reference AS \"shipmentReference\" FROM \"LandedCost\" l"
                + " LEFT JOIN \"Shipment\" s ON s.id = l.\"shipmentId\" WHERE" + POSTED + " ORDER BY l.date DESC")
                .params(org(), from, to.plusDays(1)))) {
            String id = (String) l.get("id");
            BigDecimal purchase = jdbc.sql("SELECT COALESCE(SUM(il.\"totalValue\"), 0) FROM \"InventoryLedger\" il"
                    + " WHERE il.\"organizationId\" = ? AND il.\"referenceType\" = 'goods_receipt' AND il.\"quantityChange\" > 0"
                    + " AND il.\"referenceId\" IN (SELECT \"goodsReceiptId\" FROM \"LandedCostReceipt\" WHERE \"landedCostId\" = ?"
                    + " UNION SELECT \"goodsReceiptId\" FROM \"LandedCost\" WHERE id = ?)")
                    .params(org(), id, id).query(BigDecimal.class).single();
            List<Map<String, Object>> comps = SqlRows.list(jdbc.sql("SELECT COALESCE(cc.code, upper(c.kind)) AS component,"
                    + " COALESCE(cc.name, c.kind) AS name, COALESCE(c.treatment, 'capitalize') AS treatment,"
                    + " SUM(COALESCE(c.\"baseAmount\", c.amount)) AS amount FROM \"LandedCostCharge\" c"
                    + " LEFT JOIN \"CostComponent\" cc ON cc.id = c.\"costComponentId\" WHERE c.\"landedCostId\" = ?"
                    + " GROUP BY 1, 2, 3 ORDER BY 1").param(id));
            comps.forEach(c -> c.put("amount", dec(c.get("amount"))));
            List<Map<String, Object>> items = SqlRows.list(jdbc.sql("SELECT a.\"productId\", p.name, p.sku,"
                    + " SUM(a.\"allocatedAmount\") AS allocated, SUM(a.\"capitalizedAmount\") AS capitalized FROM"
                    + " \"LandedCostAllocation\" a JOIN \"Product\" p ON p.id = a.\"productId\" WHERE a.\"landedCostId\" = ?"
                    + " GROUP BY 1, 2, 3 ORDER BY 2").param(id));
            for (Map<String, Object> it : items) {
                Map<String, Object> recv = jdbc.sql("SELECT COALESCE(SUM(il.\"quantityChange\"), 0) AS qty,"
                        + " COALESCE(SUM(il.\"totalValue\"), 0) AS value FROM \"InventoryLedger\" il WHERE"
                        + " il.\"organizationId\" = ? AND il.\"productId\" = ? AND il.\"referenceType\" = 'goods_receipt'"
                        + " AND il.\"quantityChange\" > 0 AND il.\"referenceId\" IN (SELECT \"goodsReceiptId\" FROM"
                        + " \"LandedCostReceipt\" WHERE \"landedCostId\" = ? UNION SELECT \"goodsReceiptId\" FROM"
                        + " \"LandedCost\" WHERE id = ?)").params(org(), it.get("productId"), id, id).query().singleRow();
                BigDecimal qty = Money.dec(recv.get("qty"));
                BigDecimal value = Money.dec(recv.get("value"));
                BigDecimal allocated = Money.dec(it.get("allocated"));
                it.put("quantity", JsNumbers.dec(qty));
                it.put("purchaseValue", JsNumbers.dec(value));
                it.put("allocated", JsNumbers.dec(allocated));
                it.put("capitalized", dec(it.get("capitalized")));
                it.put("purchaseUnitCost", qty.signum() == 0 ? "0" : JsNumbers.dec(Money.div(value, qty)
                        .setScale(6, RoundingMode.HALF_UP)));
                it.put("landedUnitCost", qty.signum() == 0 ? "0" : JsNumbers.dec(Money.div(value.add(allocated), qty)
                        .setScale(6, RoundingMode.HALF_UP)));
            }
            BigDecimal capitalized = Money.dec(l.get("capitalizedAmount"));
            BigDecimal expensed = Money.dec(l.get("expensedAmount"));
            Map<String, Object> row = new LinkedHashMap<>(l);
            row.put("purchaseValue", JsNumbers.dec(purchase));
            row.put("totalAmount", dec(l.get("totalAmount")));
            row.put("capitalizedAmount", JsNumbers.dec(capitalized));
            row.put("expensedAmount", JsNumbers.dec(expensed));
            row.put("taxAmount", dec(l.get("taxAmount")));
            row.put("landedValue", JsNumbers.dec(purchase.add(capitalized).add(expensed)));
            row.put("components", comps);
            row.put("items", items);
            out.add(row);
        }
        return out;
    }

    /** Per product over the period: landed cost added, capitalised vs to COGS. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> byItem(LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.\"productId\", p.name, p.sku,"
                + " count(DISTINCT l.id) AS vouchers, SUM(a.\"allocatedAmount\") AS allocated,"
                + " SUM(a.\"capitalizedAmount\") AS capitalized, SUM(a.\"expensedAmount\") AS expensed"
                + " FROM \"LandedCostAllocation\" a JOIN \"LandedCost\" l ON l.id = a.\"landedCostId\""
                + " JOIN \"Product\" p ON p.id = a.\"productId\" WHERE" + POSTED + " GROUP BY 1, 2, 3 ORDER BY 5 DESC")
                .params(org(), from, to.plusDays(1)));
        for (Map<String, Object> r : rows) {
            for (String k : List.of("allocated", "capitalized", "expensed")) {
                r.put(k, dec(r.get(k)));
            }
        }
        return rows;
    }

    /** Per supplier of landed charges: charge count, base amount and recoverable tax. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> bySupplier(LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT c.\"supplierId\", COALESCE(p.name, '(none)') AS"
                + " name, count(*) AS charges, SUM(COALESCE(c.\"baseAmount\", c.amount)) AS amount,"
                + " SUM(c.\"taxAmount\" * c.\"exchangeRate\") AS tax FROM \"LandedCostCharge\" c"
                + " JOIN \"LandedCost\" l ON l.id = c.\"landedCostId\" LEFT JOIN \"Partner\" p ON p.id = c.\"supplierId\""
                + " WHERE" + POSTED + " GROUP BY 1, 2 ORDER BY 4 DESC").params(org(), from, to.plusDays(1)));
        for (Map<String, Object> r : rows) {
            r.put("amount", dec(r.get("amount")));
            r.put("tax", dec(r.get("tax")));
        }
        return rows;
    }

    /**
     * Landed-cost clearing: what vouchers credited (charges without their own credit account), what was debited
     * (vendor bills booked against it), and the open balance with the charges behind it.
     */
    @Transactional
    public Map<String, Object> clearing() {
        String account = accounts.ensureByCode("2155", "Landed Cost Clearing", "current_liability", "landed_cost_clearing");
        Map<String, Object> gl = jdbc.sql("SELECT COALESCE(SUM(l.\"baseCredit\"), 0) AS credit,"
                + " COALESCE(SUM(l.\"baseDebit\"), 0) AS debit FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                + " ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ?"
                + " AND e.status IN ('posted', 'reversed')").params(org(), account).query().singleRow();
        List<Map<String, Object>> charges = SqlRows.list(jdbc.sql("SELECT l.code, l.date, c.\"supplierId\","
                + " p.name AS \"supplierName\", c.\"supplierInvoiceRef\", c.kind, c.description,"
                + " COALESCE(c.\"baseAmount\", c.amount) + c.\"taxAmount\" * c.\"exchangeRate\" AS amount"
                + " FROM \"LandedCostCharge\" c JOIN \"LandedCost\" l ON l.id = c.\"landedCostId\""
                + " LEFT JOIN \"Partner\" p ON p.id = c.\"supplierId\" WHERE l.\"organizationId\" = ?"
                + " AND l.status = 'posted' AND l.\"voucherType\" = 'voucher' AND c.\"creditAccountId\" IS NULL"
                + " ORDER BY l.date DESC LIMIT 500").param(org()));
        charges.forEach(c -> c.put("amount", dec(c.get("amount"))));
        BigDecimal credit = Money.dec(gl.get("credit"));
        BigDecimal debit = Money.dec(gl.get("debit"));
        return Obj.of("accountId", account, "credited", JsNumbers.dec(credit), "debited", JsNumbers.dec(debit),
                "openBalance", JsNumbers.dec(credit.subtract(debit)), "charges", charges);
    }

    /**
     * Product profitability: invoiced revenue (net of discount, excl. tax), COGS from stock issues (which carry the
     * landed cost already in the layers) plus late landed cost expensed to COGS, gross profit and margin.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> productProfitability(LocalDate from, LocalDate to) {
        Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT i.\"productId\", p.name, p.sku, SUM(i.quantity) AS qty,"
                + " SUM(i.subtotal) AS revenue FROM \"InvoiceItem\" i JOIN \"Invoice\" v ON v.id = i.\"invoiceId\""
                + " JOIN \"Product\" p ON p.id = i.\"productId\" WHERE v.\"organizationId\" = ? AND v.status IN ('posted',"
                + " 'paid') AND v.\"issueDate\" >= ? AND v.\"issueDate\" < ? GROUP BY 1, 2, 3")
                .params(org(), from, to.plusDays(1)))) {
            byProduct.put((String) r.get("productId"), r);
        }
        Map<String, BigDecimal> cogs = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"productId\", -SUM(\"totalValue\") AS cogs FROM"
                + " \"InventoryLedger\" WHERE \"organizationId\" = ? AND type = 'issue' AND \"createdAt\" >= ?"
                + " AND \"createdAt\" < ? GROUP BY 1").params(org(), from, to.plusDays(1)))) {
            cogs.put((String) r.get("productId"), Money.dec(r.get("cogs")).abs());
        }
        Map<String, BigDecimal> landed = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT a.\"productId\", SUM(a.\"expensedAmount\") AS v FROM"
                + " \"LandedCostAllocation\" a JOIN \"LandedCost\" l ON l.id = a.\"landedCostId\" WHERE" + POSTED
                + " GROUP BY 1").params(org(), from, to.plusDays(1)))) {
            landed.put((String) r.get("productId"), Money.dec(r.get("v")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : byProduct.values()) {
            String id = (String) r.get("productId");
            BigDecimal revenue = Money.dec(r.get("revenue"));
            BigDecimal c = cogs.getOrDefault(id, Money.ZERO).add(landed.getOrDefault(id, Money.ZERO));
            BigDecimal gp = revenue.subtract(c);
            out.add(Obj.of("productId", id, "name", r.get("name"), "sku", r.get("sku"), "quantity", dec(r.get("qty")),
                    "revenue", JsNumbers.dec(revenue), "cogs", JsNumbers.dec(c), "landedCostInCogs",
                    JsNumbers.dec(landed.getOrDefault(id, Money.ZERO)), "grossProfit", JsNumbers.dec(gp),
                    "marginPct", revenue.signum() == 0 ? "0" : JsNumbers.dec(gp.multiply(BigDecimal.valueOf(100))
                            .divide(revenue, 2, RoundingMode.HALF_UP))));
        }
        out.sort((a, b) -> new BigDecimal((String) b.get("grossProfit")).compareTo(new BigDecimal((String) a.get(
                "grossProfit"))));
        return out;
    }
}
