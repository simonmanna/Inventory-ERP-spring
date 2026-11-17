package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Why does this item cost X?" (review 2026-11-17, ease of use; extension, no NestJS counterpart). One read that explains
 * an item's carrying cost in plain words: the costing method, the stock value and quantity per location (the inventory
 * sub-ledger, so it ties to the GL), the price differences and net-realisable-value allowance held beside it, every
 * value-only cost change over its life, and the stock movements with the running quantity, value and unit cost after
 * each one. Receipts and other inbound rows are traceable through {@code /inventory/cost-layers/{ledgerId}/trace}.
 *
 * <p>Values come from the stock ledger: an outbound row's {@code totalValue} is unsigned (its sign is the quantity's),
 * a value-only row's carries its own sign. The base component ({@code valuationComponent} null) is the stock value; the
 * {@code price_difference} and {@code nrv_allowance} components are the adjuncts (accounts 1396 and 1397). Variants are
 * added together.
 */
@Service
public class CostExplanationService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private static final String SIGNED = "CASE WHEN l.\"quantityChange\" >= 0 THEN l.\"totalValue\" ELSE"
            + " -l.\"totalValue\" END";

    /** The document a ledger row came from, where it has a number of its own; else the stock posting number. */
    private static final String DOCUMENT_NO = "coalesce(CASE l.\"referenceType\""
            + " WHEN 'goods_receipt' THEN (SELECT g.\"receiptNumber\" FROM \"GoodsReceiptNote\" g WHERE g.id ="
            + " l.\"referenceId\")"
            + " WHEN 'landed_cost_voucher' THEN (SELECT c.code FROM \"LandedCost\" c WHERE c.id = l.\"referenceId\")"
            + " WHEN 'landed_cost' THEN (SELECT c.code FROM \"LandedCost\" c WHERE c.id = l.\"referenceId\")"
            + " WHEN 'production_order' THEN (SELECT o.\"orderCode\" FROM \"ProductionOrder\" o WHERE o.id ="
            + " l.\"referenceId\")"
            + " WHEN 'production_reversal' THEN (SELECT o.\"orderCode\" FROM \"ProductionOrder\" o WHERE o.id ="
            + " l.\"referenceId\")"
            + " WHEN 'inventory_revaluation' THEN (SELECT r.number FROM \"InventoryRevaluation\" r WHERE r.id ="
            + " l.\"referenceId\")"
            + " WHEN 'cost_adjustment_run' THEN (SELECT r.number FROM \"CostAdjustmentRun\" r WHERE r.id ="
            + " l.\"referenceId\")"
            + " WHEN 'absorption_settlement' THEN (SELECT s.number FROM \"AbsorptionSettlement\" s WHERE s.id ="
            + " l.\"referenceId\")"
            + " WHEN 'landed_cost_accrual' THEN (SELECT a.number FROM \"LandedCostAccrual\" a WHERE a.id ="
            + " l.\"referenceId\")"
            + " WHEN 'sales_invoice' THEN (SELECT i.\"invoiceNumber\" FROM \"Invoice\" i WHERE i.id = l.\"referenceId\")"
            + " WHEN 'debit_note' THEN (SELECT d.\"noteNumber\" FROM \"DebitNote\" d WHERE d.id = l.\"referenceId\")"
            + " END, l.\"ledgerCode\")";

    private static final Map<String, String> TYPE_LABELS = Map.ofEntries(
            Map.entry("receipt", "Received"),
            Map.entry("issue", "Issued"),
            Map.entry("adjustment_in", "Count adjustment (gain)"),
            Map.entry("adjustment_out", "Count adjustment (loss)"),
            Map.entry("transfer_in", "Transferred in"),
            Map.entry("transfer_out", "Transferred out"),
            Map.entry("opening_balance", "Opening balance"),
            Map.entry("waste", "Wasted"),
            Map.entry("return_in", "Returned by a customer"),
            Map.entry("return_to_supplier", "Returned to the supplier"),
            Map.entry("expiry_write_off", "Written off (expired)"),
            Map.entry("internal_use", "Used internally"),
            Map.entry("promo_sample", "Given as a sample"),
            Map.entry("production_consume", "Used in production"),
            Map.entry("production_output", "Produced"),
            Map.entry("reversal_in", "Reversal (back into stock)"),
            Map.entry("reversal_out", "Reversal (back out of stock)"),
            Map.entry("cost_adjustment", "Cost adjusted (value only)"));

    private static final Map<String, String> SOURCE_LABELS = Map.ofEntries(
            Map.entry("goods_receipt", "goods receipt"),
            Map.entry("direct_stock_in", "direct stock entry"),
            Map.entry("direct_stock_out", "direct stock entry"),
            Map.entry("production_order", "production order"),
            Map.entry("production_stage", "production stage"),
            Map.entry("production_stage_reversal", "production stage reversal"),
            Map.entry("production_reversal", "production order reversal"),
            Map.entry("landed_cost_voucher", "landed cost (freight, duty, insurance)"),
            Map.entry("landed_cost", "landed cost (freight, duty, insurance)"),
            Map.entry("landed_cost_accrual", "landed cost accrued before the supplier's invoice"),
            Map.entry("landed_cost_accrual_clearing", "accrued landed cost trued up to the invoice"),
            Map.entry("reversal", "reversal of an earlier cost change"),
            Map.entry("cost_release", "price difference released with the stock"),
            Map.entry("cost_adjustment_run", "late cost carried into what was produced"),
            Map.entry("inventory_nrv", "write-down to net realisable value"),
            Map.entry("standard_cost", "change of standard cost"),
            Map.entry("inventory_revaluation", "revaluation"),
            Map.entry("absorption_settlement", "overhead under- or over-absorption settled"),
            Map.entry("lineage_exception", "late-cost exception resolved"),
            Map.entry("branch_transfer", "branch transfer pricing difference"),
            Map.entry("material_ledger_run", "periodic actual cost (material ledger) run"),
            Map.entry("quarry_rehab", "quarry rehabilitation cost"),
            Map.entry("quarry_stripping", "quarry stripping cost"),
            Map.entry("sales_invoice", "sales invoice"),
            Map.entry("pos_invoice", "sale"),
            Map.entry("cash_sale", "sale"),
            Map.entry("vendor_bill", "supplier bill"),
            Map.entry("debit_note", "debit note to the supplier"),
            Map.entry("opening_balance", "opening balance"),
            Map.entry("quarry_extraction", "quarry extraction"),
            Map.entry("quarry_explosive_issue", "explosives used"));

    private final JdbcClient jdbc;

    public CostExplanationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * Explains the carrying cost of a product, at one location or across all of them.
     *
     * @param productId the product
     * @param locationId one location, or null for all
     * @param limitRaw how many of the newest movements to return (default 100, at most 500)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> explain(String productId, String locationId, String limitRaw) {
        if (productId == null || productId.isBlank()) {
            throw ApiException.badRequest("productId should not be empty");
        }
        int limit = limit(limitRaw);
        Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT p.id, p.name, p.sku, p.\"costingMethod\"::text AS"
                + " \"costingMethod\", u.code AS \"uomCode\", (SELECT s.\"totalCost\" FROM \"ProductStandardCost\" s WHERE"
                + " s.\"productId\" = p.id AND s.\"organizationId\" = p.\"organizationId\" AND s.status = 'active' ORDER BY"
                + " s.\"effectiveFrom\" DESC NULLS LAST LIMIT 1) AS \"standardCost\" FROM \"Product\" p LEFT JOIN"
                + " \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE p.id = ? AND p.\"organizationId\" = ?")
                .params(productId, org()));
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        if (locationId != null && !locationId.isBlank() && jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE"
                + " id = ? AND \"organizationId\" = ?").params(locationId, org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Location not found");
        }
        String loc = locationId == null || locationId.isBlank() ? null : locationId;
        String method = (String) product.get("costingMethod");

        List<Map<String, Object>> locations = locations(productId, loc);
        Map<String, Object> totals = totals(locations);
        List<Map<String, Object>> adjustments = adjustments(productId, loc);
        List<Map<String, Object>> adjuncts = openAdjuncts(productId, loc);
        long total = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" l WHERE l.\"organizationId\" = ? AND"
                + " l.\"productId\" = ?" + (loc == null ? "" : " AND l.\"locationId\" = ?"))
                .params(loc == null ? List.of(org(), productId) : List.of(org(), productId, loc))
                .query(Long.class).single();
        List<Map<String, Object>> movements = movements(productId, loc, limit);
        return Obj.of("product", product, "method", method(method), "locations", locations, "totals", totals,
                "summary", summary(product, method, locations, totals, adjustments, productId, loc), "adjustments",
                adjustments, "openAdjuncts", adjuncts, "movements", movements, "movementsShown", movements.size(),
                "movementsTotal", total);
    }

    private static int limit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < 1) {
                throw ApiException.badRequest("limit must not be less than 1");
            }
            return Math.min(n, MAX_LIMIT);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("limit must be an integer number");
        }
    }

    // ---- per location ------------------------------------------------------------------------------------------------

    private List<Map<String, Object>> locations(String productId, String loc) {
        List<Object> params = new ArrayList<>(List.of(org(), productId, org(), productId));
        if (loc != null) {
            params.add(loc);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("WITH v AS (SELECT l.\"locationId\","
                + " coalesce(sum(" + SIGNED + ") FILTER (WHERE l.\"valuationComponent\" IS NULL), 0) AS value,"
                + " coalesce(sum(" + SIGNED + ") FILTER (WHERE l.\"valuationComponent\" = 'price_difference'), 0) AS pd,"
                + " coalesce(sum(" + SIGNED + ") FILTER (WHERE l.\"valuationComponent\" = 'nrv_allowance'), 0) AS nrv"
                + " FROM \"InventoryLedger\" l WHERE l.\"organizationId\" = ? AND l.\"productId\" = ? GROUP BY 1),"
                + " q AS (SELECT s.\"locationId\", sum(s.quantity) AS quantity, CASE WHEN count(*) = 1 THEN"
                + " max(s.\"runningAverageCost\") END AS avg FROM \"StockItem\" s WHERE s.\"organizationId\" = ? AND"
                + " s.\"productId\" = ? GROUP BY 1)"
                + " SELECT coalesce(v.\"locationId\", q.\"locationId\") AS \"locationId\", il.name AS \"locationName\","
                + " coalesce(q.quantity, 0) AS quantity, q.avg, coalesce(v.value, 0) AS value, coalesce(v.pd, 0) AS pd,"
                + " coalesce(v.nrv, 0) AS nrv FROM v FULL JOIN q ON q.\"locationId\" = v.\"locationId\" JOIN"
                + " \"InventoryLocation\" il ON il.id = coalesce(v.\"locationId\", q.\"locationId\")"
                + (loc == null ? "" : " WHERE coalesce(v.\"locationId\", q.\"locationId\") = ?")
                + " ORDER BY il.name").params(params));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal qty = SqlRows.dec(r, "quantity");
            BigDecimal value = SqlRows.dec(r, "value");
            BigDecimal pd = SqlRows.dec(r, "pd");
            BigDecimal nrv = SqlRows.dec(r, "nrv");
            if (qty.signum() == 0 && value.signum() == 0 && pd.signum() == 0 && nrv.signum() == 0) {
                continue; // a location the item only passed through
            }
            BigDecimal carrying = value.add(pd).add(nrv);
            BigDecimal unit = per(value, qty);
            if (unit == null && r.get("avg") != null) {
                unit = SqlRows.dec(r, "avg");
            }
            out.add(Obj.of("locationId", r.get("locationId"), "locationName", r.get("locationName"), "quantity",
                    JsNumbers.dec(qty), "unitCost", unit == null ? null : JsNumbers.dec(unit), "value",
                    JsNumbers.dec(value), "priceDifference", JsNumbers.dec(pd), "nrvAllowance", JsNumbers.dec(nrv),
                    "carryingValue", JsNumbers.dec(carrying), "carryingUnitCost", nullable(per(carrying, qty))));
        }
        return out;
    }

    private static Map<String, Object> totals(List<Map<String, Object>> locations) {
        BigDecimal qty = sum(locations, "quantity");
        BigDecimal value = sum(locations, "value");
        BigDecimal pd = sum(locations, "priceDifference");
        BigDecimal nrv = sum(locations, "nrvAllowance");
        BigDecimal carrying = value.add(pd).add(nrv);
        return Obj.of("quantity", JsNumbers.dec(qty), "value", JsNumbers.dec(value), "priceDifference",
                JsNumbers.dec(pd), "nrvAllowance", JsNumbers.dec(nrv), "carryingValue", JsNumbers.dec(carrying),
                "unitCost", nullable(per(value, qty)), "carryingUnitCost", nullable(per(carrying, qty)));
    }

    // ---- value-only cost changes and adjuncts ------------------------------------------------------------------------

    private List<Map<String, Object>> adjustments(String productId, String loc) {
        List<Object> params = new ArrayList<>(List.of(org(), productId));
        if (loc != null) {
            params.add(loc);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT coalesce(l.\"referenceType\", '') AS source,"
                + " sum(" + SIGNED + ") AS amount, count(*) AS n FROM \"InventoryLedger\" l WHERE l.\"organizationId\" = ?"
                + " AND l.\"productId\" = ? AND l.\"quantityChange\" = 0 AND l.\"valuationComponent\" IS NULL"
                + (loc == null ? "" : " AND l.\"locationId\" = ?") + " GROUP BY 1 ORDER BY abs(sum(" + SIGNED
                + ")) DESC").params(params));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String source = (String) r.get("source");
            out.add(Obj.of("referenceType", source.isEmpty() ? null : source, "label", capitalise(source(source)),
                    "amount", JsNumbers.dec(SqlRows.dec(r, "amount")), "count", ((Number) r.get("n")).longValue()));
        }
        return out;
    }

    private List<Map<String, Object>> openAdjuncts(String productId, String loc) {
        List<Object> params = new ArrayList<>(List.of(org(), productId));
        if (loc != null) {
            params.add(loc);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.\"locationId\", il.name AS \"locationName\","
                + " a.\"valuationComponent\" AS component, sum(a.\"openAmount\") AS open, count(*) AS n FROM"
                + " \"CostValueAdjustment\" a LEFT JOIN \"InventoryLocation\" il ON il.id = a.\"locationId\" WHERE"
                + " a.\"organizationId\" = ? AND a.\"productId\" = ? AND a.\"openAmount\" <> 0"
                + (loc == null ? "" : " AND a.\"locationId\" = ?")
                + " GROUP BY 1, 2, 3 ORDER BY 2, 3").params(params));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String component = (String) r.get("component");
            out.add(Obj.of("locationId", r.get("locationId"), "locationName", r.get("locationName"), "component",
                    component, "label", adjunctLabel(component), "openAmount", JsNumbers.dec(SqlRows.dec(r, "open")),
                    "adjustments", ((Number) r.get("n")).longValue()));
        }
        return out;
    }

    private static String adjunctLabel(String component) {
        if ("nrv_allowance".equals(component)) {
            return "Allowance writing the stock down to net realisable value (1397)";
        }
        if ("price_difference".equals(component)) {
            return "Price difference held beside the stock value (1396)";
        }
        return component == null ? "Stock value" : capitalise(component.replace('_', ' '));
    }

    // ---- movements -----------------------------------------------------------------------------------------------

    private List<Map<String, Object>> movements(String productId, String loc, int limit) {
        List<Object> params = new ArrayList<>(List.of(org(), productId));
        if (loc != null) {
            params.add(loc);
        }
        params.add(limit);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("WITH m AS (SELECT l.id, l.\"createdAt\", l.type::text"
                + " AS type, l.\"referenceType\", l.\"referenceId\", " + DOCUMENT_NO + " AS \"documentNo\","
                + " l.\"locationId\", il.name AS \"locationName\", l.\"quantityChange\", l.\"unitCost\", " + SIGNED
                + " AS value, l.\"valuationComponent\" AS component, l.\"ledgerCode\","
                + " sum(CASE WHEN l.\"valuationComponent\" IS NULL THEN l.\"quantityChange\" ELSE 0 END) OVER w AS rq,"
                + " sum(CASE WHEN l.\"valuationComponent\" IS NULL THEN " + SIGNED + " ELSE 0 END) OVER w AS rv"
                + " FROM \"InventoryLedger\" l LEFT JOIN \"InventoryLocation\" il ON il.id = l.\"locationId\" WHERE"
                + " l.\"organizationId\" = ? AND l.\"productId\" = ?" + (loc == null ? "" : " AND l.\"locationId\" = ?")
                + " WINDOW w AS (ORDER BY l.\"createdAt\", l.\"ledgerCode\", l.id ROWS UNBOUNDED PRECEDING))"
                + " SELECT * FROM m ORDER BY \"createdAt\" DESC, \"ledgerCode\" DESC, id DESC LIMIT ?").params(params));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String type = (String) r.get("type");
            String source = (String) r.get("referenceType");
            BigDecimal qty = SqlRows.dec(r, "quantityChange");
            BigDecimal rq = SqlRows.dec(r, "rq");
            BigDecimal rv = SqlRows.dec(r, "rv");
            out.add(Obj.of("ledgerId", r.get("id"), "createdAt", r.get("createdAt"), "type", type, "label",
                    label(type, source), "referenceType", source, "referenceId", r.get("referenceId"), "documentNo",
                    r.get("documentNo"), "locationId", r.get("locationId"), "locationName", r.get("locationName"),
                    "quantityChange", JsNumbers.dec(qty), "unitCost", r.get("unitCost") == null ? null
                            : JsNumbers.dec(SqlRows.dec(r, "unitCost")),
                    "value", JsNumbers.dec(SqlRows.dec(r, "value")), "component", r.get("component"),
                    "runningQuantity", JsNumbers.dec(rq), "runningValue", JsNumbers.dec(rv), "runningUnitCost",
                    nullable(per(rv, rq)), "traceable", qty.signum() > 0 && r.get("component") == null));
        }
        return out;
    }

    static String label(String type, String source) {
        String base = TYPE_LABELS.getOrDefault(type, type == null ? "" : capitalise(type.replace('_', ' ')));
        if ("cost_adjustment".equals(type)) {
            return source == null ? base : capitalise(source(source));
        }
        if (source == null || source.isBlank() || source.equals(type)) {
            return base;
        }
        return base + " — " + source(source);
    }

    private static String source(String source) {
        if (source == null || source.isBlank()) {
            return "other";
        }
        return SOURCE_LABELS.getOrDefault(source, source.replace('_', ' '));
    }

    // ---- method and summary ------------------------------------------------------------------------------------------

    static Map<String, Object> method(String code) {
        String c = code == null ? "AVCO" : code;
        return switch (c) {
            case "FIFO" -> Obj.of("code", c, "label", "First in, first out (FIFO)", "explanation", "Stock is issued"
                    + " oldest receipt first, so what is left on hand is valued at the most recent receipts.");
            case "STANDARD" -> Obj.of("code", c, "label", "Standard cost", "explanation", "Stock is carried at a"
                    + " fixed standard cost per unit; the difference between the actual cost of a purchase or a"
                    + " production run and the standard goes to variance accounts, not to the stock.");
            case "SPECIFIC" -> Obj.of("code", c, "label", "Specific identification", "explanation", "Each unit,"
                    + " batch or serial number carries the cost of its own receipt; an issue takes the cost of the"
                    + " exact unit that leaves.");
            default -> Obj.of("code", c, "label", "Weighted average cost", "explanation", "Every receipt is blended"
                    + " into one average cost per location; issues and sales leave at the average of that moment, so"
                    + " the unit cost changes only when stock comes in or its value is adjusted.");
        };
    }

    private List<String> summary(Map<String, Object> product, String method, List<Map<String, Object>> locations,
            Map<String, Object> totals, List<Map<String, Object>> adjustments, String productId, String loc) {
        List<String> lines = new ArrayList<>();
        BigDecimal qty = dec(totals.get("quantity"));
        String unit = product.get("uomCode") == null ? "" : " " + product.get("uomCode");
        if (qty.signum() > 0) {
            BigDecimal carrying = dec(totals.get("carryingValue"));
            lines.add(qty(qty) + unit + " on hand" + (locations.size() > 1 ? " at " + locations.size() + " locations"
                    : locations.size() == 1 ? " at " + locations.get(0).get("locationName") : "") + ", carried at "
                    + money(carrying) + " in total — " + money(carrying.divide(qty, 4, RoundingMode.HALF_UP))
                    + " a unit.");
        } else if (qty.signum() < 0) {
            lines.add("Stock is negative (" + qty(qty) + unit + "): more was issued than received. The shortfall left"
                    + " at the last known cost; the next receipt corrects that cost.");
        } else {
            lines.add("Nothing is on hand" + (loc == null ? "" : " at this location") + " now.");
        }
        lines.add(method(method).get("label") + ": " + method(method).get("explanation"));
        if ("STANDARD".equals(method) && product.get("standardCost") != null) {
            lines.add("The active standard is " + money(dec(product.get("standardCost"))) + " a unit.");
        }
        Map<String, Object> last = SqlRows.one(jdbc.sql("SELECT l.\"quantityChange\", l.\"unitCost\", l.\"createdAt\","
                + " " + DOCUMENT_NO + " AS doc FROM \"InventoryLedger\" l WHERE l.\"organizationId\" = ? AND"
                + " l.\"productId\" = ? AND l.\"valuationComponent\" IS NULL AND l.type::text IN ('receipt',"
                + " 'production_output', 'opening_balance')" + (loc == null ? "" : " AND l.\"locationId\" = ?")
                + " ORDER BY l.\"createdAt\" DESC, l.\"ledgerCode\" DESC LIMIT 1")
                .params(loc == null ? List.of(org(), productId) : List.of(org(), productId, loc)));
        if (last != null) {
            lines.add("Stock last came in on " + last.get("createdAt").toString().substring(0, 10) + ": "
                    + qty(SqlRows.dec(last, "quantityChange")) + unit + (last.get("unitCost") == null ? "" : " at "
                    + money(SqlRows.dec(last, "unitCost")) + " a unit") + " (" + last.get("doc") + ").");
        }
        BigDecimal pd = dec(totals.get("priceDifference"));
        if (pd.signum() != 0) {
            lines.add(money(pd) + " of price differences is held beside the stock value (account 1396). It follows"
                    + " the stock into cost of sales or production as the stock is used; the release run moves what"
                    + " was already used.");
        }
        BigDecimal nrv = dec(totals.get("nrvAllowance"));
        if (nrv.signum() != 0) {
            lines.add("An allowance of " + money(nrv.negate()) + " writes this stock down to what it can be sold for"
                    + " (net realisable value, account 1397).");
        }
        if (!adjustments.isEmpty()) {
            List<String> parts = new ArrayList<>();
            long count = 0;
            for (Map<String, Object> a : adjustments) {
                count += ((Number) a.get("count")).longValue();
                if (parts.size() < 3) {
                    BigDecimal amount = dec(a.get("amount"));
                    parts.add(((String) a.get("label")).toLowerCase(Locale.ROOT) + " " + (amount.signum() >= 0 ? "+"
                            : "") + money(amount));
                }
            }
            lines.add("The stock value was changed without a quantity movement " + count + (count == 1 ? " time" : " times")
                    + ", mainly: " + String.join("; ", parts) + ".");
        }
        return lines;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private static BigDecimal per(BigDecimal value, BigDecimal qty) {
        return qty.signum() > 0 ? value.divide(qty, 4, RoundingMode.HALF_UP) : null;
    }

    private static Object nullable(BigDecimal v) {
        return v == null ? null : JsNumbers.dec(v);
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        BigDecimal s = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            s = s.add(dec(r.get(key)));
        }
        return s;
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.2f", v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String qty(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
