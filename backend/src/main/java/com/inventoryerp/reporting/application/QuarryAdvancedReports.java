package com.inventoryerp.reporting.application;

import static com.inventoryerp.reporting.application.Tables.dec;
import static com.inventoryerp.reporting.application.Tables.money;
import static com.inventoryerp.reporting.application.Tables.number;
import static com.inventoryerp.reporting.application.Tables.pct;
import static com.inventoryerp.reporting.application.Tables.text;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.reporting.application.LedgerAggregator.AccountInfo;
import com.inventoryerp.reporting.application.LedgerAggregator.Segment;
import com.inventoryerp.reporting.application.OpenItems.Item;
import com.inventoryerp.reporting.application.StatementLayoutService.Layout;
import com.inventoryerp.reporting.application.StatementLayoutService.Line;
import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.OptionSpec;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportBody.Column;
import com.inventoryerp.reporting.spi.ReportCategory;
import com.inventoryerp.reporting.spi.ReportContext;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportPeriod;
import com.inventoryerp.reporting.spi.ReportProvider;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Expected credit loss (FR-8, IFRS 9 simplified approach: a provision matrix of loss rates over the AR aging, compared
 * with the allowance accounts) and the quarry and construction-materials pack (FR-9): profit or loss per quarry site,
 * extraction and crushing cost, cost per tonne and per cubic metre by product, production against sales, yield and
 * losses, plant capacity against actual, and margin per tonne by material.
 */
@Component
public class QuarryAdvancedReports implements ReportProvider {

    static final String ACC = Permissions.REPORT__ACCOUNTING;
    static final List<FilterKey> PERIOD = List.of(FilterKey.PERIOD);

    private final JdbcClient jdbc;
    private final LedgerAggregator ledger;
    private final OpenItems open;
    private final StatementLayoutService layouts;
    private final LedgerReports statements;
    private final ManagementReports management;

    public QuarryAdvancedReports(JdbcClient jdbc, LedgerAggregator ledger, OpenItems open,
            StatementLayoutService layouts, LedgerReports statements, ManagementReports management) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.open = open;
        this.layouts = layouts;
        this.statements = statements;
        this.management = management;
    }

    @Override
    public List<ReportDefinition> definitions() {
        ReportCategory q = ReportCategory.QUARRY;
        String qr = Permissions.QUARRY__REPORT;
        String pr = Permissions.PRODUCTION__REPORT;
        return List.of(
                ReportDefinition.of("adv.ecl", ReportCategory.ADVANCED, "Expected Credit Loss (IFRS 9)", "Provision"
                        + " matrix over the AR aging with loss rates per bucket, against the allowance accounts.",
                        "FINANCIAL", Permissions.REPORT__AR, List.of(FilterKey.AS_OF, FilterKey.BRANCH),
                        OptionSpec.text("rates", "Loss rates % (current, 1–30, 31–60, 61–90, 90+)", "1,3,10,25,50")),
                ReportDefinition.of("quarry.site-pl", q, "Quarry Profitability", "Profit or loss per quarry site"
                        + " (through each site's cost center) with the company total.", "MANAGEMENT", ACC,
                        List.of(FilterKey.PERIOD, FilterKey.POSTING_STATUS, FilterKey.LAYOUT)),
                ReportDefinition.of("quarry.extraction-cost", q, "Extraction Cost Analysis", "Tonnes extracted and"
                        + " cost by component (drilling and blasting, fuel, labour, equipment, royalties) per site"
                        + " and material, with cost per tonne.", "MANAGEMENT", qr, PERIOD),
                ReportDefinition.of("quarry.crusher-cost", q, "Crusher Cost Analysis", "Feed, output, yield,"
                        + " operating and downtime hours, conversion cost and cost per tonne per crushing plant.",
                        "MANAGEMENT", pr, PERIOD),
                ReportDefinition.of("quarry.cost-per-unit", q, "Cost per Ton and per Cubic Metre", "Production"
                        + " cost per tonne and per m³ by product (aggregate grade), from crushing output and"
                        + " extraction.", "MANAGEMENT", pr, PERIOD, OptionSpec.number("density",
                                "Default density t/m³", "1.6")),
                ReportDefinition.of("quarry.production-vs-sales", q, "Production vs Sales Reconciliation", "Tonnes"
                        + " produced against tonnes sold and stock on hand, by product.", "MANAGEMENT", pr,
                        List.of(FilterKey.PERIOD, FilterKey.BRANCH)),
                ReportDefinition.of("quarry.yield-loss", q, "Production Yield and Loss", "Extraction losses and"
                        + " crushing yield against expected, with the value lost.", "MANAGEMENT", pr, PERIOD),
                ReportDefinition.of("quarry.capacity", q, "Production Capacity vs Actual", "Design capacity over"
                        + " operating hours against actual output per plant.", "MANAGEMENT", pr, PERIOD),
                ReportDefinition.of("quarry.material-margin", q, "Material Margin Analysis", "Average selling price"
                        + " against unit production or purchase cost per product.", "MANAGEMENT", ACC,
                        List.of(FilterKey.PERIOD, FilterKey.BRANCH)));
    }

    @Override
    public ReportBody run(ReportContext ctx) {
        return switch (ctx.definition().id()) {
            case "adv.ecl" -> ecl(ctx);
            case "quarry.site-pl" -> sitePl(ctx);
            case "quarry.extraction-cost" -> extraction(ctx);
            case "quarry.crusher-cost" -> crushers(ctx, false);
            case "quarry.capacity" -> crushers(ctx, true);
            case "quarry.cost-per-unit" -> costPerUnit(ctx);
            case "quarry.production-vs-sales" -> productionVsSales(ctx);
            case "quarry.yield-loss" -> yieldLoss(ctx);
            case "quarry.material-margin" -> materialMargin(ctx);
            default -> throw ApiException.notFound("Report " + ctx.definition().id() + " not found");
        };
    }

    private Timestamp from(ReportContext ctx) {
        return SqlRows.ts(ctx.start(ctx.current().from()));
    }

    private Timestamp to(ReportContext ctx) {
        return SqlRows.ts(ctx.endExclusive(ctx.current().to()));
    }

    // ---------------------------------------------------------------- expected credit loss

    ReportBody ecl(ReportContext ctx) {
        BigDecimal[] rates = new BigDecimal[5];
        String[] parts = (ctx.request().option("rates") == null ? "1,3,10,25,50" : ctx.request().option("rates"))
                .split(",");
        if (parts.length != 5) {
            throw ApiException.badRequest("options.rates must be five percentages");
        }
        for (int i = 0; i < 5; i++) {
            try {
                rates[i] = new BigDecimal(parts[i].trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("options.rates must be five percentages");
            }
        }
        int[] lim = {30, 60, 90};
        String[] keys = {"current", "b30", "b60", "b90", "over"};
        String[] labels = {"Current", "1–30 days", "31–60 days", "61–90 days", "Over 90 days"};
        BigDecimal[] exposure = new BigDecimal[5];
        java.util.Arrays.fill(exposure, BigDecimal.ZERO);
        for (Item i : open.at(ctx, "AR", ctx.asOf())) {
            if (i.residual().signum() <= 0) {
                continue;
            }
            String b = ReceivablesPayablesReports.bucket(ReceivablesPayablesReports.days(i, ctx.asOf(), "DUE_DATE"),
                    lim);
            int idx = java.util.Arrays.asList(keys).indexOf(b);
            exposure[idx] = exposure[idx].add(i.residual());
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("bucket", "Aging bucket"), money("exposure",
                "Gross receivables"), pct("rate", "Loss rate %"), money("ecl", "Expected credit loss"));
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < 5; i++) {
            BigDecimal loss = exposure[i].multiply(rates[i]).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            total = total.add(loss);
            t.line(Obj.of("_id", keys[i], "bucket", labels[i], "exposure", exposure[i], "rate", rates[i], "ecl", loss),
                    Map.of("exposure", ReportBody.Drill.report("ar.aging-detail", Obj.of("asOf", ctx.asOf()
                            .toString()))));
        }
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        BigDecimal allowance = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : ledger.balances(ctx, new ReportPeriod("C", "", null, ctx.asOf()))
                .entrySet()) {
            AccountInfo a = accounts.get(e.getKey());
            if (a != null && !"operating_expense".equals(a.section()) && GlReports.nameHas(a, "doubtful",
                    "allowance", "expected credit", "impairment of receivable")) {
                allowance = allowance.add(e.getValue()[1]).subtract(e.getValue()[0]);
            }
        }
        t.add("kpi", 0, false, Obj.of("_id", "ALLOWANCE", "bucket", "Allowance in the ledger", "ecl", allowance),
                Map.of(), false);
        t.add("kpi", 0, true, Obj.of("_id", "TOPUP", "bucket", "Required adjustment (charge / (release))", "ecl",
                total.subtract(allowance)), Map.of(), false);
        t.meta("note", "Simplified approach (IFRS 9 §5.5.15): lifetime expected loss on trade receivables by aging.");
        return t.build();
    }

    // ---------------------------------------------------------------- quarry

    ReportBody sitePl(ReportContext ctx) {
        String code = ctx.request().layoutCode() == null || ctx.request().layoutCode().isBlank()
                ? StatementLayoutService.DEFAULT_PL : ctx.request().layoutCode();
        Layout layout = layouts.get(code);
        if (!"PL".equals(layout.kind())) {
            throw ApiException.badRequest("Layout " + code + " is a " + layout.kind() + " layout");
        }
        Map<String, String> siteByCc = new LinkedHashMap<>();
        for (Map<String, Object> s : SqlRows.list(jdbc.sql("SELECT name, \"costCenterId\" FROM \"QuarrySite\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"costCenterId\" IS NOT NULL ORDER BY name")
                .param(ctx.organizationId()))) {
            siteByCc.put((String) s.get("costCenterId"), (String) s.get("name"));
        }
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, Map<String, BigDecimal[]>> byCc = ledger.balancesBy(ctx, ctx.current(), Segment.COST_CENTER);
        Map<String, BigDecimal[]> other = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, BigDecimal[]>> e : byCc.entrySet()) {
            if (!siteByCc.containsKey(e.getKey())) {
                e.getValue().forEach((a, v) -> other.merge(a, v, (x, y) -> new BigDecimal[] {x[0].add(y[0]),
                    x[1].add(y[1])}));
            }
        }
        List<Column> cols = new ArrayList<>(List.of(text("label", "Description")));
        Map<String, LedgerReports.Column> evaluated = new LinkedHashMap<>();
        for (Map.Entry<String, String> s : siteByCc.entrySet()) {
            evaluated.put("S_" + s.getKey(), statements.evaluate(layout, accounts, byCc.getOrDefault(s.getKey(),
                    Map.of())));
            cols.add(money("S_" + s.getKey(), s.getValue()));
        }
        evaluated.put("OTHER", statements.evaluate(layout, accounts, other));
        cols.add(money("OTHER", "Other / head office"));
        LedgerReports.Column total = statements.evaluate(layout, accounts, ledger.balances(ctx, ctx.current()));
        cols.add(money("TOTAL", "Total"));
        Tables.Builder t = new Tables.Builder(ctx.zone(), cols);
        for (Line line : layout.lines()) {
            if ("HEADER".equals(line.lineType())) {
                t.add("header", line.indent(), true, Obj.of("_id", line.code(), "label", line.label()), Map.of(),
                        false);
                continue;
            }
            Map<String, Object> v = new LinkedHashMap<>(Obj.of("_id", line.code(), "label", line.label()));
            evaluated.forEach((k, c) -> v.put(k, c.lines().get(line.code())));
            v.put("TOTAL", total.lines().get(line.code()));
            if (line.percent()) {
                v.put("_format", "percent");
            }
            t.add(line.kpi() ? "kpi" : "FORMULA".equals(line.lineType()) ? "total" : "line", line.indent(),
                    line.bold(), v, Map.of(), false);
        }
        t.check("SITES_CONFIGURED", "Quarry sites have cost centers", !siteByCc.isEmpty(), "Give each quarry site a"
                + " cost center to see its profit");
        return t.build(false);
    }

    ReportBody extraction(ReportContext ctx) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT s.name AS site, coalesce(m.name, 'Unspecified')"
                + " AS material, count(*) AS logs, sum(l.\"grossTonnes\") AS gross, sum(l.\"lossTonnes\") AS loss,"
                + " sum(l.\"netTonnes\") AS net, sum(coalesce(l.\"blastingCost\", 0)) AS blasting,"
                + " sum(coalesce(l.\"fuelCost\", 0)) AS fuel, sum(coalesce(l.\"labourCost\", 0)) AS labour,"
                + " sum(coalesce(l.\"equipmentCost\", 0)) AS equipment, sum(coalesce(l.\"royaltyCost\", 0)) AS royalty,"
                + " sum(coalesce(l.\"otherCost\", 0)) AS other, sum(coalesce(l.\"totalCost\", 0)) AS total FROM"
                + " \"QuarryExtractionLog\" l JOIN \"QuarrySite\" s ON s.id = l.\"siteId\" LEFT JOIN \"QuarryMaterial\" m ON"
                + " m.id = l.\"materialId\" WHERE l.\"organizationId\" = ? AND l.status = 'posted' AND l.\"deletedAt\" IS"
                + " NULL AND l.\"extractedOn\" >= ? AND l.\"extractedOn\" <= ? GROUP BY s.name, m.name ORDER BY s.name,"
                + " m.name").params(ctx.organizationId(), ctx.current().from(), ctx.current().to()));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("material", "Material"), text("site", "Site"),
                number("logs", "Logs"), number("gross", "Gross t"), number("loss", "Loss t"), number("net", "Net t"),
                money("blasting", "Drilling & blasting"), money("fuel", "Fuel"), money("labour", "Labour"),
                money("equipment", "Equipment"), money("royalty", "Royalties"), money("other", "Other"),
                money("total", "Total cost"), money("perTonne", "Cost per net t"));
        for (Map<String, Object> r : rows) {
            r.put("perTonne", Tables.div(dec(r.get("total")), dec(r.get("net")), 2));
        }
        t.grouped(rows, "site", "site", r -> Map.of());
        return t.build();
    }

    ReportBody crushers(ReportContext ctx, boolean capacity) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT p.id, p.name AS plant, p.\"designCapacityTph\""
                + " AS capacity, count(r.id) AS runs, sum(r.\"feedTonnes\") AS feed, sum(r.\"outputTonnes\") AS output,"
                + " sum(r.\"lossTonnes\") AS loss, sum(r.\"operatingHours\") AS hours, sum(r.\"downtimeHours\") AS downtime,"
                + " sum(coalesce(r.\"feedCost\", 0)) AS \"feedCost\", sum(coalesce(r.\"conversionCost\", 0)) AS conversion,"
                + " sum(coalesce(r.\"abnormalLossValue\", 0)) AS \"abnormalLoss\", sum(coalesce(r.\"totalCost\", 0)) AS"
                + " total, avg(r.\"expectedYieldPct\") AS \"expectedYield\" FROM \"CrushingPlant\" p JOIN \"CrushingRun\" r"
                + " ON r.\"plantId\" = p.id AND r.status = 'posted' AND r.\"deletedAt\" IS NULL AND r.\"runDate\" >= ? AND"
                + " r.\"runDate\" <= ? WHERE p.\"organizationId\" = ? GROUP BY p.id ORDER BY p.name").params(
                ctx.current().from(), ctx.current().to(), ctx.organizationId()));
        if (capacity) {
            Tables.Builder t = new Tables.Builder(ctx.zone(), text("plant", "Plant"), number("capacity",
                    "Design t/h"), number("hours", "Operating h"), number("downtime", "Downtime h"), number(
                    "possible", "Capacity t (design × hours)"), number("output", "Actual output t"), pct(
                    "utilisation", "Utilisation %"), pct("availability", "Availability %"), number("tph",
                    "Actual t/h"));
            for (Map<String, Object> r : rows) {
                BigDecimal possible = dec(r.get("capacity")).multiply(dec(r.get("hours")));
                r.put("possible", possible);
                r.put("utilisation", Tables.pctOf(dec(r.get("output")), possible));
                r.put("availability", Tables.pctOf(dec(r.get("hours")), dec(r.get("hours")).add(dec(r.get(
                        "downtime")))));
                r.put("tph", Tables.div(dec(r.get("output")), dec(r.get("hours")), 2));
                t.line(r);
            }
            return t.build(false);
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("plant", "Plant"), number("runs", "Runs"),
                number("feed", "Feed t"), number("output", "Output t"), pct("yield", "Yield %"), pct(
                        "expectedYield", "Expected %"), number("hours", "Operating h"), money("feedCost",
                        "Feed cost"), money("conversion", "Conversion cost"), money("abnormalLoss", "Abnormal loss"),
                money("total", "Total cost"), money("perTonne", "Cost per output t"), money("conversionPerTonne",
                        "Conversion per t"));
        for (Map<String, Object> r : rows) {
            r.put("yield", Tables.pctOf(dec(r.get("output")), dec(r.get("feed"))));
            r.put("perTonne", Tables.div(dec(r.get("total")), dec(r.get("output")), 2));
            r.put("conversionPerTonne", Tables.div(dec(r.get("conversion")), dec(r.get("output")), 2));
            t.line(r);
        }
        return t.build();
    }

    ReportBody costPerUnit(ReportContext ctx) {
        BigDecimal defaultDensity = ctx.request().decimalOption("density");
        Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT o.\"productId\", pr.name AS product,"
                + " sum(o.\"actualTonnes\") AS tonnes, sum(coalesce(o.value, 0)) AS cost, max(m.\"densityTPerM3\") AS"
                + " density FROM \"CrushingRunOutput\" o JOIN \"CrushingRun\" r ON r.id = o.\"runId\" JOIN \"Product\" pr"
                + " ON pr.id = o.\"productId\" LEFT JOIN \"QuarryMaterial\" m ON m.\"productId\" = o.\"productId\" WHERE"
                + " r.\"organizationId\" = ? AND r.status = 'posted' AND r.\"deletedAt\" IS NULL AND r.\"runDate\" >= ? AND"
                + " r.\"runDate\" <= ? GROUP BY o.\"productId\", pr.name").params(ctx.organizationId(), ctx.current()
                .from(), ctx.current().to()))) {
            r.put("source", "crushing");
            agg.put("C:" + r.get("productId"), r);
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT m.\"productId\", coalesce(pr.name, m.name) AS"
                + " product, sum(l.\"netTonnes\") AS tonnes, sum(coalesce(l.\"capitalisedCost\", l.\"totalCost\", 0)) AS"
                + " cost, max(m.\"densityTPerM3\") AS density FROM \"QuarryExtractionLog\" l JOIN \"QuarryMaterial\" m ON"
                + " m.id = l.\"materialId\" LEFT JOIN \"Product\" pr ON pr.id = m.\"productId\" WHERE"
                + " l.\"organizationId\" = ? AND l.status = 'posted' AND l.\"deletedAt\" IS NULL AND l.\"extractedOn\" >= ?"
                + " AND l.\"extractedOn\" <= ? GROUP BY m.\"productId\", pr.name, m.name").params(ctx.organizationId(),
                ctx.current().from(), ctx.current().to()))) {
            r.put("source", "extraction (run of mine)");
            agg.put("E:" + r.get("product"), r);
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("product", "Product"), text("source", "Stage"),
                number("tonnes", "Tonnes"), money("cost", "Production cost"), money("perTonne", "Cost per t"),
                number("density", "Density t/m³"), number("cubic", "Volume m³"), money("perCubic", "Cost per m³"));
        for (Map<String, Object> r : agg.values()) {
            BigDecimal density = r.get("density") == null || dec(r.get("density")).signum() == 0 ? defaultDensity
                    : dec(r.get("density"));
            BigDecimal cubic = Tables.div(dec(r.get("tonnes")), density, 2);
            r.put("density", density);
            r.put("cubic", cubic);
            r.put("perTonne", Tables.div(dec(r.get("cost")), dec(r.get("tonnes")), 2));
            r.put("perCubic", cubic == null ? null : Tables.div(dec(r.get("cost")), cubic, 2));
            r.put("_id", r.get("source") + ":" + r.get("product"));
            t.line(r);
        }
        return t.build();
    }

    ReportBody productionVsSales(ReportContext ctx) {
        Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"productId\", pr.name AS product,"
                + " sum(l.\"quantityChange\") AS produced FROM \"InventoryLedger\" l JOIN \"Product\" pr ON pr.id ="
                + " l.\"productId\" WHERE l.\"organizationId\" = ? AND l.type::text = 'production_output' AND"
                + " l.\"createdAt\" >= ? AND l.\"createdAt\" < ? GROUP BY 1, 2").params(ctx.organizationId(), from(ctx),
                to(ctx)))) {
            agg.put((String) r.get("productId"), new LinkedHashMap<>(Obj.of("_id", r.get("productId"), "product",
                    r.get("product"), "produced", r.get("produced"), "sold", BigDecimal.ZERO)));
        }
        for (Map<String, Object> l : management.salesLines(ctx)) {
            if (l.get("productId") == null) {
                continue;
            }
            Map<String, Object> a = agg.computeIfAbsent((String) l.get("productId"), k -> new LinkedHashMap<>(Obj.of(
                    "_id", k, "product", l.get("product"), "produced", BigDecimal.ZERO, "sold", BigDecimal.ZERO)));
            a.put("sold", dec(a.get("sold")).add(dec(l.get("qty"))));
        }
        Map<String, BigDecimal> stock = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"productId\", sum(quantity) AS q FROM \"StockItem\""
                + " WHERE \"organizationId\" = ? GROUP BY 1").param(ctx.organizationId()))) {
            stock.put((String) r.get("productId"), dec(r.get("q")));
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("product", "Product"), number("produced",
                "Produced"), number("sold", "Sold"), number("difference", "Produced − sold"), pct("sellThrough",
                "Sold / produced %"), number("stock", "Stock on hand"));
        for (Map<String, Object> a : agg.values()) {
            if (dec(a.get("produced")).signum() == 0) {
                continue;
            }
            a.put("difference", dec(a.get("produced")).subtract(dec(a.get("sold"))));
            a.put("sellThrough", Tables.pctOf(dec(a.get("sold")), dec(a.get("produced"))));
            a.put("stock", stock.get((String) a.get("_id")));
            t.line(a);
        }
        return t.build();
    }

    ReportBody yieldLoss(ReportContext ctx) {
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("stage", "Stage"), text("name", "Site / plant"),
                number("input", "Input t"), number("output", "Usable output t"), number("loss", "Loss t"),
                pct("yield", "Yield %"), pct("expected", "Expected %"), money("lossValue", "Value lost"));
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT s.name, sum(l.\"grossTonnes\") AS input,"
                + " sum(l.\"netTonnes\") AS output, sum(l.\"lossTonnes\") AS loss, sum(coalesce(l.\"lossCost\", 0)) AS"
                + " \"lossValue\" FROM \"QuarryExtractionLog\" l JOIN \"QuarrySite\" s ON s.id = l.\"siteId\" WHERE"
                + " l.\"organizationId\" = ? AND l.status = 'posted' AND l.\"deletedAt\" IS NULL AND l.\"extractedOn\" >= ?"
                + " AND l.\"extractedOn\" <= ? GROUP BY s.name ORDER BY s.name").params(ctx.organizationId(),
                ctx.current().from(), ctx.current().to()))) {
            r.put("stage", "Extraction");
            r.put("yield", Tables.pctOf(dec(r.get("output")), dec(r.get("input"))));
            r.put("_id", "E:" + r.get("name"));
            t.line(r);
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT p.name, sum(r.\"feedTonnes\") AS input,"
                + " sum(r.\"outputTonnes\") AS output, sum(r.\"lossTonnes\") AS loss, sum(coalesce(r.\"abnormalLossValue\","
                + " 0)) AS \"lossValue\", avg(r.\"expectedYieldPct\") AS expected FROM \"CrushingRun\" r JOIN"
                + " \"CrushingPlant\" p ON p.id = r.\"plantId\" WHERE r.\"organizationId\" = ? AND r.status = 'posted' AND"
                + " r.\"deletedAt\" IS NULL AND r.\"runDate\" >= ? AND r.\"runDate\" <= ? GROUP BY p.name ORDER BY p.name")
                .params(ctx.organizationId(), ctx.current().from(), ctx.current().to()))) {
            r.put("stage", "Crushing");
            r.put("yield", Tables.pctOf(dec(r.get("output")), dec(r.get("input"))));
            r.put("_id", "C:" + r.get("name"));
            t.line(r);
        }
        return t.build();
    }

    ReportBody materialMargin(ReportContext ctx) {
        Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
        for (Map<String, Object> l : management.salesLines(ctx)) {
            if (l.get("productId") == null) {
                continue;
            }
            Map<String, Object> a = agg.computeIfAbsent((String) l.get("productId"), k -> new LinkedHashMap<>(Obj.of(
                    "_id", k, "product", l.get("product"), "qty", BigDecimal.ZERO, "net", BigDecimal.ZERO)));
            a.put("qty", dec(a.get("qty")).add(dec(l.get("qty"))));
            a.put("net", dec(a.get("net")).add(dec(l.get("net"))));
        }
        Map<String, BigDecimal> unitCost = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT o.\"productId\", sum(coalesce(o.value, 0)) /"
                + " nullif(sum(o.\"actualTonnes\"), 0) AS cost FROM \"CrushingRunOutput\" o JOIN \"CrushingRun\" r ON r.id"
                + " = o.\"runId\" WHERE r.\"organizationId\" = ? AND r.status = 'posted' AND r.\"runDate\" >= ? AND"
                + " r.\"runDate\" <= ? GROUP BY 1").params(ctx.organizationId(), ctx.current().from(),
                ctx.current().to()))) {
            if (r.get("cost") != null) {
                unitCost.put((String) r.get("productId"), dec(r.get("cost")));
            }
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"productId\", sum(quantity *"
                + " coalesce(\"runningAverageCost\", 0)) / nullif(sum(quantity), 0) AS cost FROM \"StockItem\" WHERE"
                + " \"organizationId\" = ? AND quantity > 0 GROUP BY 1").param(ctx.organizationId()))) {
            if (r.get("cost") != null) {
                unitCost.putIfAbsent((String) r.get("productId"), dec(r.get("cost")));
            }
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("product", "Product"), number("qty", "Quantity sold"),
                money("net", "Net sales"), money("price", "Average price"), money("unitCost", "Unit cost"),
                money("unitMargin", "Margin per unit"), pct("marginPct", "Margin %"), money("margin", "Total margin"));
        for (Map<String, Object> a : agg.values()) {
            BigDecimal price = Tables.div(dec(a.get("net")), dec(a.get("qty")), 2);
            BigDecimal cost = unitCost.get((String) a.get("_id"));
            a.put("price", price);
            a.put("unitCost", cost);
            if (price != null && cost != null) {
                BigDecimal um = price.subtract(cost);
                a.put("unitMargin", um);
                a.put("marginPct", Tables.pctOf(um, price));
                a.put("margin", um.multiply(dec(a.get("qty"))));
            }
            t.line(a);
        }
        t.meta("note", "Unit cost is this period's crushing cost per tonne, else today's average stock cost.");
        return t.build();
    }
}
