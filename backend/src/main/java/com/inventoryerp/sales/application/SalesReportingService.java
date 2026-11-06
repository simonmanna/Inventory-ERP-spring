package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesReportKit.money;
import static com.inventoryerp.sales.application.SalesReportKit.pct;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.sales.application.SalesReportFacts.Dim;
import com.inventoryerp.sales.application.SalesReportKit.Bucket;
import com.inventoryerp.sales.application.SalesReportKit.Column;
import com.inventoryerp.sales.application.SalesReportKit.Def;
import com.inventoryerp.sales.application.SalesReportKit.Filter;
import com.inventoryerp.sales.application.SalesReportKit.Report;
import com.inventoryerp.shared.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sales report center (ADR-S057): the catalog of every sales report, running one with the shared filter,
 * exporting it (Excel / CSV; PDF and print are rendered by the web viewer), the executive dashboard and the filter
 * lookups. All reports are read-only and tenant-scoped through {@link SalesDb}.
 */
@Service
public class SalesReportingService {

    /** Report categories in menu order: key, title, description. */
    static final String[][] CATEGORIES = {
        {"performance", "Sales Performance", "Summary, period sales, comparisons, targets, trends, branches,"
            + " salespeople and channels."},
        {"customers", "Customer Sales", "Customer sales, purchase history, top and inactive customers, growth,"
            + " frequency and preferences."},
        {"products", "Product & Item Sales", "Product and category sales, top, slow and non-moving items, trends"
            + " and quantities by unit."},
        {"orders", "Sales Orders", "Order register, open, pending, overdue and cancelled orders, backorders,"
            + " fulfilment and cycle time."},
        {"quotations", "Quotations & Pipeline", "Quotation register, conversion, follow-up, lost opportunities and"
            + " the CRM pipeline."},
        {"deliveries", "Delivery & Fulfilment", "Delivery register, pending and late deliveries, delivery notes,"
            + " vehicles, drivers and routes."},
        {"invoices", "Invoices & Billing", "Invoice register, paid / unpaid / overdue, aging, credit and cash sales,"
            + " tax and billing."},
        {"collections", "Collections & Payments", "Receipts, payment methods, balances, receivables, deposits,"
            + " allocations and refunds."},
        {"discounts", "Discounts & Returns", "Discounts by customer, product and salesperson, approvals, returns and"
            + " net sales."},
        {"team", "Salesperson & Team", "Performance, targets, ranking, acquisition, retention, quotations,"
            + " collections, commission and activity."},
        {"profitability", "Profitability", "Gross profit by product, customer, branch and salesperson, margins,"
            + " cost, discount and return impact."},
        {"forecasting", "Forecasting & Planning", "Forecast, projection, targets, growth, demand, seasonality and"
            + " budget vs actual."}};

    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final SalesReportFacts facts;
    private final SalesDb db;

    public SalesReportingService(List<SalesReportSet> sets, SalesReportFacts facts, SalesDb db) {
        this.facts = facts;
        this.db = db;
        for (SalesReportSet s : sets) {
            for (Def d : s.definitions()) {
                if (defs.put(d.key(), d) != null) {
                    throw new IllegalStateException("Duplicate sales report key " + d.key());
                }
            }
        }
    }

    /** Every report the caller may run, grouped by category. */
    @Transactional(readOnly = true)
    public Map<String, Object> catalog() {
        List<Map<String, Object>> cats = new ArrayList<>();
        int count = 0;
        for (String[] c : CATEGORIES) {
            List<Map<String, Object>> reports = defs.values().stream()
                    .filter(d -> d.category().equals(c[0]) && allowed(d)).map(Def::describe).toList();
            count += reports.size();
            cats.add(Obj.of("key", c[0], "title", c[1], "description", c[2], "reports", reports));
        }
        return Obj.of("categories", cats, "count", count);
    }

    private SalesAccess access;

    @org.springframework.beans.factory.annotation.Autowired
    void setAccess(SalesAccess access) {
        this.access = access;
    }

    private boolean allowed(Def d) {
        if (SalesRevenueReports.PROFIT.equals(d.category()) && !access.canSeeCost()) {
            return false;
        }
        if (!d.filters().contains("branch") && access.scope().restricted()) {
            return false;
        }
        return Permissions.SALES__REPORT_READ.equals(d.permission()) || TenantContext.has(d.permission());
    }

    /** The filter narrowed to the caller's branches. */
    private Filter scoped(Map<String, String> params) {
        Filter f = Filter.parse(params);
        if ("margin".equals(f.measure())) {
            access.assertCost();
        }
        var s = access.scope();
        return s.restricted() ? f.inScope(s.branchIds()) : f;
    }

    private Report guarded(Report r) {
        return access.canSeeCost() ? r : r.stripCost();
    }

    Def def(String key) {
        Def d = defs.get(key);
        if (d == null) {
            throw ApiException.notFound("Sales report not found: " + key);
        }
        if (!allowed(d)) {
            throw ApiException.forbidden("You do not have permission to run this report");
        }
        return d;
    }

    /** Runs report {@code key} with the shared filter in {@code params}. */
    @Transactional(readOnly = true)
    public Map<String, Object> run(String key, Map<String, String> params) {
        Def d = def(key);
        Filter f = scoped(params);
        return guarded(d.run().apply(f)).toMap(d, f);
    }

    /** Report keys (tests and tooling). */
    public List<String> keys() {
        return List.copyOf(defs.keySet());
    }

    // ------------------------------------------------------------------ export

    /** Rendered export. */
    public record Export(String filename, String contentType, byte[] content) {}

    @Transactional(readOnly = true)
    public Export export(String key, Map<String, String> params) {
        Map<String, String> p = new LinkedHashMap<>(params == null ? Map.of() : params);
        String format = p.remove("format");
        format = format == null ? "xlsx" : format;
        Def d = def(key);
        Filter f = scoped(p);
        Report r = guarded(d.run().apply(f));
        String base = key + "_" + f.from() + "_" + f.to();
        return switch (format) {
            case "csv" -> new Export(base + ".csv", "text/csv", csv(d, f, r));
            case "xlsx" -> new Export(base + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(d, f, r));
            default -> throw ApiException.badRequest("format must be one of: xlsx, csv");
        };
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }

    static byte[] csv(Def d, Filter f, Report r) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(field(d.title())).append('\n').append(field("Period " + f.from() + " – " + f.to())).append("\n\n");
        List<Column> cols = r.columns;
        sb.append(String.join(",", cols.stream().map(c -> field(c.label())).toList())).append('\n');
        for (Map<String, Object> row : r.rows) {
            sb.append(String.join(",", cols.stream().map(c -> field(text(row.get(c.key())))).toList())).append('\n');
        }
        if (!r.totals.isEmpty()) {
            sb.append(String.join(",", cols.stream().map(c -> field(text(r.totals.get(c.key())))).toList()))
                    .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String field(String s) {
        String v = s == null ? "" : s;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9.]+")) {
            v = "'" + v;
        }
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    static byte[] xlsx(Def d, Filter f, Report r) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet(d.title().length() > 31 ? d.title().substring(0, 31) : d.title());
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            CellStyle moneyStyle = wb.createCellStyle();
            moneyStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle qtyStyle = wb.createCellStyle();
            qtyStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0.###"));
            CellStyle totalMoney = wb.createCellStyle();
            totalMoney.cloneStyleFrom(moneyStyle);
            totalMoney.setFont(bold);
            int rn = 0;
            Row t = sh.createRow(rn++);
            t.createCell(0).setCellValue(d.title());
            t.getCell(0).setCellStyle(head);
            sh.createRow(rn++).createCell(0).setCellValue("Period " + f.from() + " – " + f.to());
            for (SalesReportKit.Kpi k : r.kpis) {
                Row kr = sh.createRow(rn++);
                kr.createCell(0).setCellValue(k.label());
                put(kr.createCell(1), k.value(), k.type(), moneyStyle, qtyStyle);
            }
            rn++;
            Row h = sh.createRow(rn++);
            for (int i = 0; i < r.columns.size(); i++) {
                Cell c = h.createCell(i);
                c.setCellValue(r.columns.get(i).label());
                c.setCellStyle(head);
            }
            for (Map<String, Object> row : r.rows) {
                Row x = sh.createRow(rn++);
                for (int i = 0; i < r.columns.size(); i++) {
                    Column c = r.columns.get(i);
                    put(x.createCell(i), row.get(c.key()), "auto".equals(c.type()) ? text(row.get("type")) : c.type(),
                            moneyStyle, qtyStyle);
                }
            }
            if (!r.totals.isEmpty()) {
                Row x = sh.createRow(rn++);
                for (int i = 0; i < r.columns.size(); i++) {
                    Column c = r.columns.get(i);
                    Object v = r.totals.get(c.key());
                    put(x.createCell(i), v, c.type(), totalMoney, totalMoney);
                    if (!(v instanceof Number)) {
                        x.getCell(i).setCellStyle(head);
                    }
                }
            }
            for (String n : r.notes) {
                sh.createRow(++rn).createCell(0).setCellValue(n);
            }
            for (int i = 0; i < Math.min(r.columns.size(), 30); i++) {
                sh.setColumnWidth(i, Math.min(60, Math.max(10, r.columns.get(i).label().length() + 4)) * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(Cell cell, Object v, String type, CellStyle money, CellStyle qty) {
        if (v == null) {
            cell.setBlank();
        } else if (v instanceof Number n && !"text".equals(type)) {
            cell.setCellValue(n.doubleValue());
            if ("money".equals(type)) {
                cell.setCellStyle(money);
            } else if ("qty".equals(type) || "number".equals(type)) {
                cell.setCellStyle(qty);
            }
        } else {
            cell.setCellValue(v.toString());
        }
    }

    // ------------------------------------------------------------------ dashboard

    /** Executive sales overview for the filter period (default: year to date). */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(Map<String, String> params) {
        Filter f = scoped(params);
        List<Object> arParams = new ArrayList<>(List.of(org()));
        String arScope = access.branchClause("\"branchId\"", arParams);
        List<Object> orderParams = new ArrayList<>(List.of(f.from(), f.to().plusDays(1), org()));
        String orderScope = access.branchClause("\"branchId\"", orderParams);
        List<Object> quoteParams = new ArrayList<>(List.of(org()));
        String quoteScope = access.branchClause("\"branchId\"", quoteParams);
        Map<String, Object> cur = facts.totals(f);
        Map<String, Object> prev = facts.totals(f.previous());
        BigDecimal rev = dec(cur.get("revenue"));
        BigDecimal prevRev = dec(prev.get("revenue"));
        Map<String, Object> ar = db.row("SELECT COALESCE(sum(\"amountResidual\" * \"exchangeRate\"), 0) AS outstanding,"
                + " COALESCE(sum(\"amountResidual\" * \"exchangeRate\") FILTER (WHERE \"dueDate\" < CURRENT_DATE), 0) AS overdue,"
                + " count(*) FILTER (WHERE \"dueDate\" < CURRENT_DATE) AS \"overdueCount\" FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"documentType\" = 'sales_invoice' AND status = 'posted' AND"
                + " \"amountResidual\" > 0" + arScope, arParams.toArray());
        Map<String, Object> orders = db.row("SELECT count(*) FILTER (WHERE COALESCE(\"confirmedAt\", \"createdAt\") >= ?"
                + " AND COALESCE(\"confirmedAt\", \"createdAt\") < ? AND status <> 'cancelled') AS period,"
                + " count(*) FILTER (WHERE status IN ('draft', 'pending_approval')) AS pending,"
                + " count(*) FILTER (WHERE status IN ('confirmed', 'in_fulfilment') AND \"deliveryStatus\" <> 'full')"
                + " AS \"toDeliver\", COALESCE(sum(total) FILTER (WHERE status IN ('confirmed', 'in_fulfilment') AND"
                + " \"deliveryStatus\" <> 'full'), 0) AS \"toDeliverValue\" FROM \"SalesOrder\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL" + orderScope, orderParams.toArray());
        Map<String, Object> quotes = db.row("SELECT count(*) AS count, COALESCE(sum(subtotal), 0) AS value FROM"
                + " \"SalesQuotation\" WHERE \"organizationId\" = ? AND status IN ('pending_approval', 'approved', 'sent')"
                + " AND \"deletedAt\" IS NULL" + quoteScope, quoteParams.toArray());
        Map<String, Object> target = db.row("SELECT COALESCE(sum(amount), 0) AS amount FROM \"SalesTarget\" WHERE"
                + " \"organizationId\" = ? AND month >= ? AND month <= ? AND \"salespersonId\" IS NULL AND \"teamId\" IS"
                + " NULL AND \"branchId\" IS NULL AND \"productCategoryId\" IS NULL", org(), f.from().withDayOfMonth(1),
                f.to());
        BigDecimal tg = dec(target.get("amount"));
        List<Map<String, Object>> kpis = new ArrayList<>(List.of(
                kpi("revenue", "Net sales", cur.get("revenue"), "money", pct(rev.subtract(prevRev), prevRev.abs()),
                        "monthly-sales"),
                kpi("margin", "Gross profit", cur.get("margin"), "money", cur.get("marginPercent"), "gross-profit"),
                kpi("outstanding", "Outstanding", ar.get("outstanding"), "money", null, "outstanding-balances"),
                kpi("overdue", "Overdue invoices", ar.get("overdue"), "money", ar.get("overdueCount"),
                        "overdue-invoices"),
                kpi("orders", "Sales orders", orders.get("period"), "count", null, "order-register"),
                kpi("aov", "Average order value", cur.get("aov"), "money", null, "sales-summary"),
                kpi("quotations", "Pending quotations", quotes.get("count"), "count", quotes.get("value"),
                        "pending-quotations"),
                kpi("toDeliver", "Awaiting delivery", orders.get("toDeliver"), "count", orders.get("toDeliverValue"),
                        "pending-deliveries"),
                kpi("pendingOrders", "Pending orders", orders.get("pending"), "count", null, "pending-orders"),
                kpi("target", "Target achievement", tg.signum() == 0 ? null : pct(rev, tg), "percent", money(tg),
                        "target-vs-actual")));
        if (!access.canSeeCost()) {
            kpis.removeIf(k -> SalesAccess.isCostField(String.valueOf(k.get("key"))));
        }
        LocalDate trendFrom = f.to().withDayOfMonth(1).minusMonths(11);
        List<Map<String, Object>> trend = SalesReportFacts.fill(facts.aggregate(f.withPeriod(trendFrom, f.to()),
                Dim.MONTH), Bucket.MONTH, trendFrom, f.to());
        return Obj.of("from", f.from(), "to", f.to(), "kpis", kpis, "trend", trend.stream().map(r -> Obj.of("label",
                r.get("label"), "revenue", r.get("revenue"), "margin", r.get("margin"), "discount", r.get("discount")))
                .toList(), "topCustomers", top(f, Dim.CUSTOMER, 5), "topProducts", top(f, Dim.PRODUCT, 5),
                "topSalespeople", top(f, Dim.SALESPERSON, 5), "byBranch", top(f, Dim.BRANCH, 10), "byCategory",
                top(f, Dim.CATEGORY, 8), "byChannel", top(f, Dim.CHANNEL, 6));
    }

    private static Map<String, Object> kpi(String key, String label, Object value, String type, Object sub,
            String report) {
        return Obj.of("key", key, "label", label, "value", value, "type", type, "sub", sub, "report", report);
    }

    private List<Map<String, Object>> top(Filter f, Dim dim, int n) {
        List<Map<String, Object>> rows = facts.aggregate(f, dim);
        rows.sort(SalesReportKit.byDesc("revenue"));
        return rows.stream().limit(n).map(r -> {
            Map<String, Object> m = Obj.of("id", r.get("k0"), "label", r.get("label"), "revenue", r.get("revenue"),
                    "margin", r.get("margin"), "quantity", r.get("baseQty"));
            SalesReportFacts.drillFor(m, dim, r.get("k0") == null ? null : r.get("k0").toString());
            return m;
        }).toList();
    }

    // ------------------------------------------------------------------ lookups

    private String branchLookupScope() {
        return access.scope().restricted() ? " AND id = ANY(?::text[])" : "";
    }

    private Object[] branchLookupParams(String o) {
        var s = access.scope();
        return s.restricted() ? new Object[] {o, s.branchArray()} : new Object[] {o};
    }

    /** Options for the filter bar. */
    @Transactional(readOnly = true)
    public Map<String, Object> lookups() {
        String o = org();
        return Obj.of(
                "branches", db.rows("SELECT id, name FROM \"Branch\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                        + branchLookupScope() + " ORDER BY name", branchLookupParams(o)),
                "salespeople", db.rows("SELECT id, trim(\"firstName\" || ' ' || COALESCE(\"lastName\", '')) AS name FROM"
                        + " \"User\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\" ORDER BY 2", o),
                "customers", db.rows("SELECT id, name FROM \"Partner\" WHERE \"organizationId\" = ? AND \"isCustomer\" AND"
                        + " \"deletedAt\" IS NULL ORDER BY name LIMIT 3000", o),
                "products", db.rows("SELECT id, COALESCE(NULLIF(code, '') || ' · ', '') || name AS name FROM \"Product\""
                        + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY name LIMIT 3000", o),
                "categories", db.rows("SELECT id, name FROM \"ProductCategory\" WHERE \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL ORDER BY name", o),
                "customerCategories", db.rows("SELECT id, name FROM \"PartnerCategory\" WHERE \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL ORDER BY name", o),
                "channels", SalesReportFacts.CHANNELS.entrySet().stream().map(e -> Obj.of("id", e.getKey(), "name",
                        e.getValue())).toList());
    }
}
