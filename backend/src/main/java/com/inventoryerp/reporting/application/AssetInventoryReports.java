package com.inventoryerp.reporting.application;

import static com.inventoryerp.reporting.application.Tables.date;
import static com.inventoryerp.reporting.application.Tables.dec;
import static com.inventoryerp.reporting.application.Tables.money;
import static com.inventoryerp.reporting.application.Tables.number;
import static com.inventoryerp.reporting.application.Tables.text;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.reporting.application.LedgerAggregator.AccountInfo;
import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.OptionSpec;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportBody.Drill;
import com.inventoryerp.reporting.spi.ReportCategory;
import com.inventoryerp.reporting.spi.ReportContext;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportPeriod;
import com.inventoryerp.reporting.spi.ReportProvider;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Inventory and fixed asset accounting (FR-4). Inventory: valuation by location, branch or product, GL reconciliation,
 * movement valuation by type, cost of goods sold by product, adjustments, write-offs, stock in transit, aging,
 * slow-moving and obsolete stock (from the stock items at running average cost and the inventory ledger). Fixed
 * assets: register with net book value at a date, depreciation schedule, acquisitions, disposals with gain / loss,
 * transfers, maintenance cost, revaluations, impairments and register-to-GL reconciliation.
 */
@Component
public class AssetInventoryReports implements ReportProvider {

    static final String ACC = Permissions.REPORT__ACCOUNTING;
    static final List<FilterKey> AS_OF_BRANCH = List.of(FilterKey.AS_OF, FilterKey.BRANCH);
    static final List<FilterKey> PERIOD_BRANCH = List.of(FilterKey.PERIOD, FilterKey.BRANCH);
    static final List<String> WRITE_OFF_TYPES = List.of("waste", "expiry_write_off", "internal_use", "promo_sample");

    private final JdbcClient jdbc;
    private final LedgerAggregator ledger;

    public AssetInventoryReports(JdbcClient jdbc, LedgerAggregator ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @Override
    public List<ReportDefinition> definitions() {
        ReportCategory inv = ReportCategory.INVENTORY;
        ReportCategory fa = ReportCategory.FIXED_ASSETS;
        return List.of(
                ReportDefinition.of("inv.valuation", inv, "Inventory Valuation by Location", "Quantity and value at"
                        + " running average cost by location, branch or product category.", "FINANCIAL", ACC,
                        List.of(FilterKey.BRANCH), OptionSpec.select("groupBy", "Group by", "location", "location",
                                "branch", "category")),
                ReportDefinition.of("inv.gl-reconciliation", inv, "Inventory to GL Reconciliation", "Stock value by"
                        + " location against the inventory control accounts.", "FINANCIAL", ACC,
                        List.of(FilterKey.BRANCH)),
                ReportDefinition.of("inv.movement-valuation", inv, "Stock Movement Valuation", "Quantity and value"
                        + " in and out of stock by movement type.", "FINANCIAL", ACC, PERIOD_BRANCH),
                ReportDefinition.of("inv.cogs", inv, "Cost of Goods Sold by Product", "Issued quantity and cost per"
                        + " product, checked against the cost of sales accounts.", "FINANCIAL", ACC, PERIOD_BRANCH),
                ReportDefinition.of("inv.adjustments", inv, "Stock Adjustment Report", "Stock gains and losses from"
                        + " counts and adjustments with who made them.", "OPERATIONAL", ACC, PERIOD_BRANCH),
                ReportDefinition.of("inv.write-offs", inv, "Stock Write-off Report", "Waste, expired, internal use"
                        + " and samples written off.", "OPERATIONAL", ACC, PERIOD_BRANCH),
                ReportDefinition.of("inv.in-transit", inv, "Stock in Transit Valuation", "Goods dispatched between"
                        + " locations and not yet received.", "FINANCIAL", ACC, List.of(FilterKey.BRANCH)),
                ReportDefinition.of("inv.aging", inv, "Inventory Aging", "Stock value by age since last receipt.",
                        "MANAGEMENT", ACC, List.of(FilterKey.BRANCH)),
                ReportDefinition.of("inv.slow-moving", inv, "Slow-moving Inventory", "Items in stock with no issue"
                        + " in the window.", "MANAGEMENT", ACC, List.of(FilterKey.BRANCH),
                        OptionSpec.number("days", "No issue for (days)", "90")),
                ReportDefinition.of("inv.obsolete", inv, "Obsolete Inventory", "Items in stock with no movement at"
                        + " all in the window.", "MANAGEMENT", ACC, List.of(FilterKey.BRANCH),
                        OptionSpec.number("days", "No movement for (days)", "365")),
                ReportDefinition.of("fa.register", fa, "Fixed Asset Register (NBV)", "Cost, accumulated depreciation"
                        + " and net book value per asset at a date.", "FINANCIAL", Permissions.FIXED_ASSET__READ,
                        AS_OF_BRANCH, OptionSpec.select("includeDisposed", "Disposed assets", "no", "no", "yes")),
                ReportDefinition.of("fa.depreciation-schedule", fa, "Depreciation Schedule", "Depreciation by asset"
                        + " and month with accumulated depreciation and book value.", "FINANCIAL",
                        Permissions.FIXED_ASSET__READ, PERIOD_BRANCH),
                ReportDefinition.of("fa.acquisitions", fa, "Asset Acquisition Report", "Assets bought and"
                        + " capitalised in the period.", "FINANCIAL", Permissions.FIXED_ASSET__READ, PERIOD_BRANCH),
                ReportDefinition.of("fa.disposals", fa, "Asset Disposal Report", "Sales, write-offs and disposal"
                        + " gains / losses.", "FINANCIAL", Permissions.FIXED_ASSET__READ, PERIOD_BRANCH),
                ReportDefinition.of("fa.transfers", fa, "Asset Transfer Report", "Movement between branches,"
                        + " locations and departments.", "OPERATIONAL", Permissions.FIXED_ASSET__READ, PERIOD_BRANCH),
                ReportDefinition.of("fa.maintenance-cost", fa, "Asset Maintenance Cost", "Maintenance and repair"
                        + " cost by asset.", "MANAGEMENT", Permissions.FIXED_ASSET__READ, PERIOD_BRANCH),
                ReportDefinition.of("fa.revaluations", fa, "Asset Revaluation Report", "Revalued asset values and"
                        + " the change.", "FINANCIAL", Permissions.FIXED_ASSET__READ, PERIOD_BRANCH),
                ReportDefinition.of("fa.impairment", fa, "Asset Impairment Report", "Downward revaluations"
                        + " (impairment losses).", "FINANCIAL", Permissions.FIXED_ASSET__READ, PERIOD_BRANCH),
                ReportDefinition.of("fa.reconciliation", fa, "Fixed Asset Reconciliation", "Register cost and"
                        + " accumulated depreciation against the fixed asset accounts.", "FINANCIAL", ACC,
                        List.of(FilterKey.AS_OF)));
    }

    @Override
    public ReportBody run(ReportContext ctx) {
        return switch (ctx.definition().id()) {
            case "inv.valuation" -> valuation(ctx);
            case "inv.gl-reconciliation" -> inventoryReconciliation(ctx);
            case "inv.movement-valuation" -> movementValuation(ctx);
            case "inv.cogs" -> cogs(ctx);
            case "inv.adjustments" -> ledgerMoves(ctx, List.of("adjustment_in", "adjustment_out", "cost_adjustment"));
            case "inv.write-offs" -> ledgerMoves(ctx, WRITE_OFF_TYPES);
            case "inv.in-transit" -> inTransit(ctx);
            case "inv.aging" -> aging(ctx);
            case "inv.slow-moving" -> slow(ctx, true);
            case "inv.obsolete" -> slow(ctx, false);
            case "fa.register" -> register(ctx);
            case "fa.depreciation-schedule" -> depreciation(ctx);
            case "fa.acquisitions" -> acquisitions(ctx);
            case "fa.disposals" -> disposals(ctx);
            case "fa.transfers" -> transfers(ctx);
            case "fa.maintenance-cost" -> maintenance(ctx);
            case "fa.revaluations" -> revaluations(ctx, false);
            case "fa.impairment" -> revaluations(ctx, true);
            case "fa.reconciliation" -> assetReconciliation(ctx);
            default -> throw ApiException.notFound("Report " + ctx.definition().id() + " not found");
        };
    }

    private Timestamp from(ReportContext ctx) {
        return SqlRows.ts(ctx.start(ctx.current().from()));
    }

    private Timestamp to(ReportContext ctx) {
        return SqlRows.ts(ctx.endExclusive(ctx.current().to()));
    }

    /** {@code AND <col> = ANY(branches)} with the parameter, when branches are filtered. */
    private static String branch(ReportContext ctx, String col, List<Object> p) {
        if (ctx.request().branchIds().isEmpty()) {
            return "";
        }
        p.add(ctx.request().branchIds().toArray(String[]::new));
        return " AND " + col + " = ANY(?::text[])";
    }

    // ---------------------------------------------------------------- inventory

    private List<Map<String, Object>> stock(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId()));
        String b = branch(ctx, "loc.\"branchId\"", p);
        return SqlRows.list(jdbc.sql("SELECT s.id, s.\"productId\", s.\"locationId\", pr.name AS product, pr.code,"
                + " coalesce(pc.name, 'Uncategorised') AS category, loc.name AS location, loc.type::text AS"
                + " \"locationType\", coalesce(br.name, 'Unassigned') AS branch, s.quantity, s.\"runningAverageCost\" AS"
                + " \"unitCost\", s.quantity * coalesce(s.\"runningAverageCost\", 0) AS value FROM \"StockItem\" s JOIN"
                + " \"Product\" pr ON pr.id = s.\"productId\" LEFT JOIN \"ProductCategory\" pc ON pc.id = pr.\"categoryId\""
                + " LEFT JOIN \"InventoryLocation\" loc ON loc.id = s.\"locationId\" LEFT JOIN \"Branch\" br ON br.id ="
                + " loc.\"branchId\" WHERE s.\"organizationId\" = ? AND s.quantity <> 0" + b
                + " ORDER BY loc.name, pr.name").params(p));
    }

    BigDecimal inventoryGl(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> b : ledger.balances(ctx, new ReportPeriod("C", "", null, ctx.today()))
                .entrySet()) {
            AccountInfo a = accounts.get(b.getKey());
            if (a != null && ("inventory".equals(a.category()) || "work_in_progress".equals(a.category()))) {
                sum = sum.add(b.getValue()[0]).subtract(b.getValue()[1]);
            }
        }
        return sum;
    }

    ReportBody valuation(ReportContext ctx) {
        String by = ctx.request().option("groupBy");
        String key = "branch".equals(by) ? "branch" : "category".equals(by) ? "category" : "location";
        List<Map<String, Object>> rows = stock(ctx);
        rows.sort((a, b) -> (a.get(key) + "" + a.get("product")).compareTo(b.get(key) + "" + b.get("product")));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("product", "Product"), text("code", "Code"),
                text("category", "Category"), text("location", "Location"), text("branch", "Branch"),
                number("quantity", "Quantity"), money("unitCost", "Average cost"), money("value", "Value"));
        t.grouped(rows, key, key, r -> Map.of());
        if (ctx.request().branchIds().isEmpty()) {
            t.tie("TIES_TO_GL", "Stock value equals the inventory accounts", t.total("value"), inventoryGl(ctx));
        }
        t.meta("note", "Valued at running average cost; FIFO and standard layers are on the inventory ledger.");
        return t.build();
    }

    ReportBody inventoryReconciliation(ReportContext ctx) {
        Map<String, BigDecimal> byLocation = new LinkedHashMap<>();
        for (Map<String, Object> r : stock(ctx)) {
            byLocation.merge(r.get("location") + " (" + r.get("branch") + ")", dec(r.get("value")), BigDecimal::add);
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("label", "Location / account"), money("subledger",
                "Stock subledger"), money("gl", "General ledger"));
        byLocation.forEach((k, v) -> t.line(Obj.of("_id", "L:" + k, "label", k, "subledger", v)));
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, BigDecimal[]> bal = ledger.balances(ctx, new ReportPeriod("C", "", null, ctx.today()));
        for (AccountInfo a : accounts.values()) {
            if ("inventory".equals(a.category()) || "work_in_progress".equals(a.category())) {
                BigDecimal[] b = GlReports.get(bal, a.id());
                if (b[0].compareTo(b[1]) != 0) {
                    t.line(Obj.of("_id", a.id(), "label", a.code() + " " + a.name(), "gl", b[0].subtract(b[1])),
                            Map.of("gl", GlReports.lines(ctx, null, ctx.today(), List.of(a.id()))));
                }
            }
        }
        if (ctx.request().branchIds().isEmpty()) {
            t.tie("RECONCILED", "Stock subledger equals the inventory accounts", t.total("subledger"), t.total("gl"));
        }
        return t.build();
    }

    ReportBody movementValuation(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String b = branch(ctx, "loc.\"branchId\"", p);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.type::text AS type, count(*) AS moves,"
                + " sum(CASE WHEN l.\"quantityChange\" > 0 THEN l.\"quantityChange\" ELSE 0 END) AS \"qtyIn\","
                + " sum(CASE WHEN l.\"quantityChange\" < 0 THEN -l.\"quantityChange\" ELSE 0 END) AS \"qtyOut\","
                + " sum(CASE WHEN l.\"quantityChange\" > 0 THEN abs(coalesce(l.\"totalValue\", 0)) ELSE 0 END) AS"
                + " \"valueIn\", sum(CASE WHEN l.\"quantityChange\" < 0 THEN abs(coalesce(l.\"totalValue\", 0)) ELSE 0 END)"
                + " AS \"valueOut\" FROM \"InventoryLedger\" l LEFT JOIN \"InventoryLocation\" loc ON loc.id ="
                + " l.\"locationId\" WHERE l.\"organizationId\" = ? AND l.\"createdAt\" >= ? AND l.\"createdAt\" < ?" + b
                + " GROUP BY 1 ORDER BY 1").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("type", "Movement type"), number("moves", "Moves"),
                number("qtyIn", "Quantity in"), number("qtyOut", "Quantity out"), money("valueIn", "Value in"),
                money("valueOut", "Value out"), money("net", "Net value"));
        for (Map<String, Object> r : rows) {
            r.put("_id", r.get("type"));
            r.put("net", dec(r.get("valueIn")).subtract(dec(r.get("valueOut"))));
            t.line(r);
        }
        return t.build();
    }

    ReportBody cogs(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String b = branch(ctx, "loc.\"branchId\"", p);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT pr.id, pr.name AS product, pr.code,"
                + " coalesce(pc.name, 'Uncategorised') AS category, sum(-l.\"quantityChange\") AS quantity,"
                + " sum(abs(coalesce(l.\"totalValue\", 0))) AS cost FROM \"InventoryLedger\" l JOIN \"Product\" pr ON pr.id"
                + " = l.\"productId\" LEFT JOIN \"ProductCategory\" pc ON pc.id = pr.\"categoryId\" LEFT JOIN"
                + " \"InventoryLocation\" loc ON loc.id = l.\"locationId\" WHERE l.\"organizationId\" = ? AND"
                + " l.\"createdAt\" >= ? AND l.\"createdAt\" < ? AND l.type::text = 'issue'" + b + " GROUP BY pr.id,"
                + " pc.name ORDER BY cost DESC").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("product", "Product"), text("code", "Code"),
                text("category", "Category"), number("quantity", "Quantity sold"), money("cost", "Cost of sales"),
                money("unitCost", "Unit cost"));
        for (Map<String, Object> r : rows) {
            r.put("unitCost", Tables.div(dec(r.get("cost")), dec(r.get("quantity")), 4));
            t.line(r);
        }
        if (ctx.request().branchIds().isEmpty()) {
            Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
            BigDecimal gl = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal[]> e : ledger.balances(ctx, ctx.current()).entrySet()) {
                AccountInfo a = accounts.get(e.getKey());
                if (a != null && "cost_of_goods_sold".equals(a.category())) {
                    gl = gl.add(e.getValue()[0]).subtract(e.getValue()[1]);
                }
            }
            t.tie("TIES_TO_GL", "Cost of stock issued equals the cost of sales accounts", t.total("cost"), gl);
        }
        return t.build();
    }

    ReportBody ledgerMoves(ReportContext ctx, List<String> types) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx), types.toArray(
                String[]::new)));
        String b = branch(ctx, "loc.\"branchId\"", p);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.id, l.\"createdAt\", l.\"ledgerCode\","
                + " l.type::text AS type, pr.name AS product, loc.name AS location, l.\"quantityChange\" AS quantity,"
                + " l.\"unitCost\", l.\"totalValue\" AS value, l.\"referenceType\", l.notes, coalesce(nullif(concat_ws(' ',"
                + " u.\"firstName\", u.\"lastName\"), ''), l.\"performedBy\") AS \"performedBy\" FROM \"InventoryLedger\" l"
                + " JOIN \"Product\" pr ON pr.id = l.\"productId\" LEFT JOIN \"InventoryLocation\" loc ON loc.id ="
                + " l.\"locationId\" LEFT JOIN \"User\" u ON u.id = l.\"performedBy\" WHERE l.\"organizationId\" = ? AND"
                + " l.\"createdAt\" >= ? AND l.\"createdAt\" < ? AND l.type::text = ANY(?::text[])" + b
                + " ORDER BY l.type, l.\"createdAt\"").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("createdAt", "Date"), text("ledgerCode",
                "Reference"), text("product", "Product"), text("location", "Location"), number("quantity",
                "Quantity"), money("unitCost", "Unit cost"), money("value", "Value"), text("notes", "Reason"),
                text("performedBy", "By"));
        t.grouped(rows, "type", "type", r -> Map.of("_row", Drill.route("/inventory/ledger/" + r.get("id"))));
        return t.build();
    }

    ReportBody inTransit(ReportContext ctx) {
        List<Map<String, Object>> rows = stock(ctx).stream().filter(r -> "transit".equals(r.get("locationType")))
                .toList();
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("location", "Transit location"), text("product",
                "Product"), number("quantity", "Quantity"), money("unitCost", "Average cost"), money("value",
                "Value"));
        rows.forEach(t::line);
        return t.build();
    }

    private Map<String, Object> lastMove(ReportContext ctx, String condition) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"productId\" || '|' || coalesce(\"locationId\", '')"
                + " AS k, max(\"createdAt\") AS at FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND " + condition
                + " GROUP BY 1").param(ctx.organizationId()))) {
            out.put((String) r.get("k"), r.get("at"));
        }
        return out;
    }

    ReportBody aging(ReportContext ctx) {
        Map<String, Object> received = lastMove(ctx, "\"quantityChange\" > 0");
        String[][] buckets = {{"d30", "0–30 days"}, {"d90", "31–90 days"}, {"d180", "91–180 days"}, {"d365",
            "181–365 days"}, {"older", "Over 365 days"}};
        List<ReportBody.Column> cols = new ArrayList<>(List.of(text("product", "Product"), text("location",
                "Location"), number("quantity", "Quantity"), date("lastReceipt", "Last receipt"), number("age",
                "Age (days)")));
        for (String[] b : buckets) {
            cols.add(money(b[0], b[1]));
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), cols);
        for (Map<String, Object> r : stock(ctx)) {
            if (dec(r.get("quantity")).signum() <= 0) {
                continue;
            }
            Object at = received.get(r.get("productId") + "|" + (r.get("locationId") == null ? "" : r.get(
                    "locationId")));
            long age = at == null ? 9999 : ChronoUnit.DAYS.between(LocalDate.parse(Tables.day(at, ctx.zone())),
                    ctx.today());
            String b = age <= 30 ? "d30" : age <= 90 ? "d90" : age <= 180 ? "d180" : age <= 365 ? "d365" : "older";
            Map<String, Object> v = new LinkedHashMap<>(r);
            v.put("lastReceipt", at);
            v.put("age", at == null ? null : age);
            v.put(b, r.get("value"));
            t.line(v);
        }
        return t.build();
    }

    ReportBody slow(ReportContext ctx, boolean issuesOnly) {
        int days = Math.max(1, ctx.request().intOption("days"));
        Map<String, Object> last = lastMove(ctx, issuesOnly ? "\"quantityChange\" < 0" : "true");
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("product", "Product"), text("location", "Location"),
                number("quantity", "Quantity"), money("value", "Value"), date("lastMove", issuesOnly ? "Last issue"
                        : "Last movement"), number("idle", "Idle (days)"));
        for (Map<String, Object> r : stock(ctx)) {
            if (dec(r.get("quantity")).signum() <= 0) {
                continue;
            }
            Object at = last.get(r.get("productId") + "|" + (r.get("locationId") == null ? "" : r.get(
                    "locationId")));
            long idle = at == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(LocalDate.parse(Tables.day(at,
                    ctx.zone())), ctx.today());
            if (idle < days) {
                continue;
            }
            Map<String, Object> v = new LinkedHashMap<>(r);
            v.put("lastMove", at);
            v.put("idle", at == null ? null : idle);
            t.line(v);
        }
        return t.build();
    }

    // ---------------------------------------------------------------- fixed assets

    ReportBody register(ReportContext ctx) {
        LocalDate asOf = ctx.asOf();
        boolean disposed = "yes".equals(ctx.request().option("includeDisposed"));
        List<Object> p = new ArrayList<>(List.of(BudgetFigures.YM.format(asOf), SqlRows.ts(ctx.endExclusive(asOf)),
                ctx.organizationId(), SqlRows.ts(ctx.endExclusive(asOf))));
        String b = branch(ctx, "a.\"branchId\"", p);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.id, a.\"assetCode\", a.name,"
                + " coalesce(c.name, 'Uncategorised') AS category, coalesce(br.name, '—') AS branch, a.location,"
                + " a.\"purchaseDate\", a.status::text AS status, coalesce(a.\"capitalizedCost\", a.\"purchaseCost\", 0) AS"
                + " cost, coalesce((SELECT sum(d.\"depreciationAmount\") FROM \"AssetDepreciation\" d WHERE d.\"assetId\" ="
                + " a.id AND d.\"isPosted\" AND d.period <= ?), 0) AS accumulated, (SELECT min(x.\"disposalDate\") FROM"
                + " \"AssetDisposal\" x WHERE x.\"assetId\" = a.id AND x.\"disposalDate\" < ?) AS \"disposedOn\""
                + " FROM \"Asset\" a LEFT JOIN \"AssetCategory\" c ON c.id = a.\"categoryId\" LEFT JOIN \"Branch\" br ON"
                + " br.id = a.\"branchId\" WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND"
                + " (a.\"purchaseDate\" IS NULL OR a.\"purchaseDate\" < ?)" + b + " ORDER BY c.name, a.\"assetCode\"")
                .params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("assetCode", "Code"), text("name", "Asset"),
                text("category", "Category"), text("branch", "Branch"), date("purchaseDate", "Acquired"),
                text("status", "Status"), money("cost", "Cost"), money("accumulated", "Accumulated depreciation"),
                money("nbv", "Net book value"), date("disposedOn", "Disposed"));
        List<Map<String, Object>> shown = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r.get("disposedOn") != null && !disposed) {
                continue;
            }
            r.put("nbv", dec(r.get("cost")).subtract(dec(r.get("accumulated"))));
            shown.add(r);
        }
        t.grouped(shown, "category", "category", r -> Map.of("_row", Drill.route("/fixed-assets/" + r.get("id"))));
        return t.build();
    }

    ReportBody depreciation(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), BudgetFigures.YM.format(ctx.current().from()),
                BudgetFigures.YM.format(ctx.current().to())));
        String b = branch(ctx, "a.\"branchId\"", p);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT d.id, a.id AS \"assetId\", a.\"assetCode\","
                + " a.name AS asset, coalesce(c.name, 'Uncategorised') AS category, d.period, d.method::text AS method,"
                + " d.\"assetCost\", d.\"depreciationAmount\" AS amount, d.\"accumulatedDepr\" AS accumulated,"
                + " d.\"bookValue\", CASE WHEN d.\"isPosted\" THEN 'posted' ELSE 'not posted' END AS posted FROM"
                + " \"AssetDepreciation\" d JOIN \"Asset\" a ON a.id = d.\"assetId\" LEFT JOIN \"AssetCategory\" c ON c.id"
                + " = a.\"categoryId\" WHERE d.\"organizationId\" = ? AND d.period >= ? AND d.period <= ?" + b
                + " ORDER BY c.name, a.\"assetCode\", d.period").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("assetCode", "Code"), text("asset", "Asset"),
                text("category", "Category"), text("period", "Month"), text("method", "Method"), money("assetCost",
                        "Cost"), money("amount", "Depreciation"), money("accumulated", "Accumulated"), money(
                        "bookValue", "Book value"), text("posted", "Posted"));
        t.grouped(rows, "category", "category", r -> Map.of("_row", Drill.route("/fixed-assets/" + r.get(
                "assetId"))));
        return t.build();
    }

    ReportBody acquisitions(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String b = branch(ctx, "a.\"branchId\"", p);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.id, a.\"assetCode\", a.name,"
                + " coalesce(c.name, 'Uncategorised') AS category, a.\"purchaseDate\", s.name AS supplier,"
                + " a.\"invoiceNumber\", a.\"acquisitionMethod\"::text AS method, a.\"purchaseCost\","
                + " a.\"capitalizedCost\" FROM \"Asset\" a LEFT JOIN \"AssetCategory\" c ON c.id = a.\"categoryId\""
                + " LEFT JOIN \"Partner\" s ON s.id = a.\"supplierId\" WHERE a.\"organizationId\" = ? AND"
                + " a.\"deletedAt\" IS NULL AND a.\"purchaseDate\" >= ? AND a.\"purchaseDate\" < ?" + b
                + " ORDER BY a.\"purchaseDate\"").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("purchaseDate", "Date"), text("assetCode", "Code"),
                text("name", "Asset"), text("category", "Category"), text("supplier", "Supplier"),
                text("invoiceNumber", "Invoice"), text("method", "Method"), money("purchaseCost", "Purchase cost"),
                money("capitalizedCost", "Capitalised cost"));
        rows.forEach(r -> t.line(r, Map.of("_row", Drill.route("/fixed-assets/" + r.get("id")))));
        return t.build();
    }

    ReportBody disposals(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String b = branch(ctx, "a.\"branchId\"", p);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT x.id, a.id AS \"assetId\", a.\"assetCode\","
                + " a.name AS asset, x.\"disposalDate\", x.\"disposalMethod\"::text AS method, x.\"disposalValue\","
                + " x.\"bookValueAtDisposal\", x.\"gainLoss\", x.reason FROM \"AssetDisposal\" x JOIN \"Asset\" a ON a.id ="
                + " x.\"assetId\" WHERE x.\"organizationId\" = ? AND x.\"disposalDate\" >= ? AND x.\"disposalDate\" < ?" + b
                + " ORDER BY x.\"disposalDate\"").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("disposalDate", "Date"), text("assetCode", "Code"),
                text("asset", "Asset"), text("method", "Method"), money("disposalValue", "Proceeds"), money(
                        "bookValueAtDisposal", "Book value"), money("gainLoss", "Gain / (loss)"), text("reason",
                        "Reason"));
        rows.forEach(r -> t.line(r, Map.of("_row", Drill.route("/fixed-assets/" + r.get("assetId")))));
        return t.build();
    }

    ReportBody transfers(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String b = ctx.request().branchIds().isEmpty() ? "" : " AND (x.\"fromBranchId\" = ANY(?::text[]) OR"
                + " x.\"toBranchId\" = ANY(?::text[]))";
        if (!b.isEmpty()) {
            p.add(ctx.request().branchIds().toArray(String[]::new));
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT x.id, a.id AS \"assetId\", a.\"assetCode\","
                + " a.name AS asset, x.\"transferDate\", fb.name AS \"fromBranch\", tb.name AS \"toBranch\","
                + " x.\"fromLocation\", x.\"toLocation\", x.\"fromDepartment\", x.\"toDepartment\", x.reason FROM"
                + " \"AssetTransfer\" x JOIN \"Asset\" a ON a.id = x.\"assetId\" LEFT JOIN \"Branch\" fb ON fb.id ="
                + " x.\"fromBranchId\" LEFT JOIN \"Branch\" tb ON tb.id = x.\"toBranchId\" WHERE x.\"organizationId\" = ?"
                + " AND x.\"transferDate\" >= ? AND x.\"transferDate\" < ?" + b + " ORDER BY x.\"transferDate\"")
                .params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("transferDate", "Date"), text("assetCode", "Code"),
                text("asset", "Asset"), text("fromBranch", "From branch"), text("toBranch", "To branch"),
                text("fromLocation", "From location"), text("toLocation", "To location"), text("fromDepartment",
                        "From department"), text("toDepartment", "To department"), text("reason", "Reason"));
        rows.forEach(r -> t.line(r, Map.of("_row", Drill.route("/fixed-assets/" + r.get("assetId")))));
        return t.build(false);
    }

    ReportBody maintenance(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String b = branch(ctx, "a.\"branchId\"", p);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.id, a.\"assetCode\", a.name AS asset,"
                + " count(m.id) AS jobs, sum(coalesce(m.cost, 0)) AS cost, max(coalesce(m.\"completedDate\","
                + " m.\"scheduledDate\")) AS \"lastJob\", coalesce(a.\"capitalizedCost\", a.\"purchaseCost\") AS"
                + " \"assetCost\" FROM \"AssetMaintenance\" m JOIN \"Asset\" a ON a.id = m.\"assetId\" WHERE"
                + " m.\"organizationId\" = ? AND coalesce(m.\"completedDate\", m.\"scheduledDate\") >= ? AND"
                + " coalesce(m.\"completedDate\", m.\"scheduledDate\") < ?" + b + " GROUP BY a.id ORDER BY cost DESC")
                .params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("assetCode", "Code"), text("asset", "Asset"),
                number("jobs", "Jobs"), money("cost", "Maintenance cost"), money("assetCost", "Asset cost"),
                Tables.pct("ratio", "Cost / asset cost %"), date("lastJob", "Last job"));
        for (Map<String, Object> r : rows) {
            r.put("ratio", Tables.pctOf(dec(r.get("cost")), dec(r.get("assetCost"))));
            t.line(r, Map.of("_row", Drill.route("/fixed-assets/" + r.get("id"))));
        }
        return t.build();
    }

    ReportBody revaluations(ReportContext ctx, boolean impairmentOnly) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String b = branch(ctx, "a.\"branchId\"", p);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT r.id, a.id AS \"assetId\", a.\"assetCode\","
                + " a.name AS asset, r.\"revaluationDate\", r.\"previousValue\", r.\"newValue\", r.\"newValue\" -"
                + " r.\"previousValue\" AS change, r.reason FROM \"AssetRevaluation\" r JOIN \"Asset\" a ON a.id ="
                + " r.\"assetId\" WHERE r.\"organizationId\" = ? AND r.\"revaluationDate\" >= ? AND r.\"revaluationDate\""
                + " < ?" + b + (impairmentOnly ? " AND r.\"newValue\" < r.\"previousValue\"" : "")
                + " ORDER BY r.\"revaluationDate\"").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("revaluationDate", "Date"), text("assetCode",
                "Code"), text("asset", "Asset"), money("previousValue", "Previous value"), money("newValue",
                "New value"), money("change", impairmentOnly ? "Impairment" : "Change"), text("reason", "Reason"));
        rows.forEach(r -> t.line(r, Map.of("_row", Drill.route("/fixed-assets/" + r.get("assetId")))));
        return t.build();
    }

    ReportBody assetReconciliation(ReportContext ctx) {
        LocalDate asOf = ctx.asOf();
        Map<String, Object> reg = SqlRows.one(jdbc.sql("SELECT coalesce(sum(coalesce(a.\"capitalizedCost\","
                + " a.\"purchaseCost\", 0)), 0) AS cost, coalesce(sum((SELECT sum(d.\"depreciationAmount\") FROM"
                + " \"AssetDepreciation\" d WHERE d.\"assetId\" = a.id AND d.\"isPosted\" AND d.period <= ?)), 0) AS acc"
                + " FROM \"Asset\" a WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND (a.\"purchaseDate\" IS"
                + " NULL OR a.\"purchaseDate\" < ?) AND NOT EXISTS (SELECT 1 FROM \"AssetDisposal\" x WHERE x.\"assetId\" ="
                + " a.id AND x.\"disposalDate\" < ?)").params(BudgetFigures.YM.format(asOf), ctx.organizationId(),
                SqlRows.ts(ctx.endExclusive(asOf)), SqlRows.ts(ctx.endExclusive(asOf))));
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        BigDecimal glCost = BigDecimal.ZERO;
        BigDecimal glAcc = BigDecimal.ZERO;
        List<String> costIds = new ArrayList<>();
        List<String> accIds = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : ledger.balances(ctx, new ReportPeriod("C", "", null, asOf))
                .entrySet()) {
            AccountInfo a = accounts.get(e.getKey());
            if (a == null) {
                continue;
            }
            if ("non_current_asset".equals(a.category())) {
                glCost = glCost.add(e.getValue()[0]).subtract(e.getValue()[1]);
                costIds.add(a.id());
            } else if (List.of("contra_asset", "accumulated_depreciation").contains(a.category())) {
                glAcc = glAcc.add(e.getValue()[1]).subtract(e.getValue()[0]);
                accIds.add(a.id());
            }
        }
        BigDecimal regCost = dec(reg == null ? null : reg.get("cost"));
        BigDecimal regAcc = dec(reg == null ? null : reg.get("acc"));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("label", "Item"), money("register", "Asset register"),
                money("gl", "General ledger"), money("difference", "Difference"));
        t.line(Obj.of("_id", "COST", "label", "Cost", "register", regCost, "gl", glCost, "difference",
                regCost.subtract(glCost)), Map.of("gl", GlReports.lines(ctx, null, asOf, costIds)));
        t.line(Obj.of("_id", "ACC", "label", "Accumulated depreciation", "register", regAcc, "gl", glAcc,
                "difference", regAcc.subtract(glAcc)), Map.of("gl", GlReports.lines(ctx, null, asOf, accIds)));
        t.line(Obj.of("_id", "NBV", "label", "Net book value", "register", regCost.subtract(regAcc), "gl", glCost
                .subtract(glAcc), "difference", regCost.subtract(regAcc).subtract(glCost.subtract(glAcc))));
        t.tie("COST_RECONCILES", "Register cost equals the fixed asset cost accounts", regCost, glCost);
        t.tie("DEPRECIATION_RECONCILES", "Register depreciation equals the accumulated depreciation accounts", regAcc,
                glAcc);
        return t.build(false);
    }
}
