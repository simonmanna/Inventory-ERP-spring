package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales targets beyond monthly revenue (SG-12): gross profit, collections, new customers and orders, over any period, with
 * alerts that fire once. Revenue comes from {@link SalesRevenueQuery}, so this and the report center agree. Also the customer
 * KPIs: average order value, lifetime value, churn, bad debt.
 */
@Service
public class SalesTargetService {

    private static final Set<String> METRICS = Set.of("revenue", "gross_profit", "collection", "new_customers", "order_count", "order_value");
    private static final Set<String> PERIODS = Set.of("week", "month", "quarter", "year", "custom");

    private final SalesDb db;
    private final SalesRevenueQuery revenue;
    private final SalesAlerts alerts;
    private final SalesAccess access;

    public SalesTargetService(SalesDb db, SalesRevenueQuery revenue, SalesAlerts alerts, SalesAccess access) {
        this.db = db;
        this.revenue = revenue;
        this.alerts = alerts;
        this.access = access;
    }

    static void validate(Map<String, Object> d) {
        if (d.get("metric") != null && !METRICS.contains(d.get("metric"))) {
            throw ApiException.badRequest("metric must be one of: " + String.join(", ", new java.util.TreeSet<>(METRICS)));
        }
        if (d.get("periodType") != null && !PERIODS.contains(d.get("periodType"))) {
            throw ApiException.badRequest("periodType must be one of: " + String.join(", ", new java.util.TreeSet<>(PERIODS)));
        }
        if (d.get("periodEnd") instanceof LocalDate e && d.get("month") instanceof LocalDate s && e.isBefore(s)) {
            throw ApiException.badRequest("The period end cannot be before its start");
        }
    }

    private static LocalDate start(Map<String, Object> t) {
        return LocalDate.parse(String.valueOf(t.get("month")).substring(0, 10));
    }

    private static LocalDate end(Map<String, Object> t) {
        if (t.get("periodEnd") != null) {
            return LocalDate.parse(String.valueOf(t.get("periodEnd")).substring(0, 10));
        }
        LocalDate s = start(t);
        return switch (str(t.get("periodType"))) {
            case "week" -> s.plusWeeks(1).minusDays(1);
            case "quarter" -> s.plusMonths(3).minusDays(1);
            case "year" -> s.plusYears(1).minusDays(1);
            default -> s.plusMonths(1).minusDays(1);
        };
    }

    private static boolean match(Object want, Object have) {
        return want == null || want.equals(have);
    }

    /** Targets overlapping {@code [from, to]}, each with its own period's actual and achievement %. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> achievement(LocalDate from, LocalDate to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : db.rows("SELECT t.*, trim(u.\"firstName\" || ' ' || COALESCE(u.\"lastName\", '')) AS \"salespersonName\", tm.name AS \"teamName\", b.name AS"
                + " \"branchName\", c.name AS \"productCategoryName\" FROM \"SalesTarget\" t LEFT JOIN \"User\" u ON u.id = t.\"salespersonId\" LEFT JOIN \"CrmTeam\" tm ON tm.id ="
                + " t.\"teamId\" LEFT JOIN \"Branch\" b ON b.id = t.\"branchId\" LEFT JOIN \"ProductCategory\" c ON c.id = t.\"productCategoryId\" WHERE t.\"organizationId\" = ? AND"
                + " t.month <= ? AND COALESCE(t.\"periodEnd\", (t.month + CASE t.\"periodType\" WHEN 'week' THEN interval '7 days' WHEN 'quarter' THEN interval '3 months' WHEN 'year'"
                + " THEN interval '1 year' ELSE interval '1 month' END - interval '1 day')::date) >= ? ORDER BY t.month DESC", org(), to, from)) {
            fill(t);
            out.add(t);
        }
        return out;
    }

    private void fill(Map<String, Object> t) {
        LocalDate from = start(t);
        LocalDate to = end(t);
        BigDecimal[] actual = actual(t, from, to);
        BigDecimal target = dec(t.get("amount"));
        t.put("periodEndDate", to);
        t.put("actualAmount", JsNumbers.js(actual[0].setScale(2, RoundingMode.HALF_UP)));
        t.put("actualQuantity", JsNumbers.js(actual[1]));
        t.put("achievementPercent", target.signum() == 0 ? null : JsNumbers.js(actual[0].multiply(BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP)));
    }

    /** {value in the target's measure, quantity}. */
    private BigDecimal[] actual(Map<String, Object> t, LocalDate from, LocalDate to) {
        String metric = str(t.get("metric"));
        return switch (metric == null ? "revenue" : metric) {
            case "collection" -> new BigDecimal[] {collected(t, from, to), BigDecimal.ZERO};
            case "new_customers" -> new BigDecimal[] {newCustomers(t, from, to), BigDecimal.ZERO};
            case "order_count", "order_value" -> orders(t, from, to, "order_count".equals(metric));
            default -> {
                BigDecimal amount = BigDecimal.ZERO;
                BigDecimal qty = BigDecimal.ZERO;
                for (Map<String, Object> l : revenue.invoiced(from, to)) {
                    if (match(t.get("salespersonId"), l.get("salespersonId")) && match(t.get("teamId"), l.get("teamId"))
                            && match(t.get("branchId"), l.get("branchId")) && match(t.get("productCategoryId"), l.get("productCategoryId"))) {
                        amount = amount.add(dec(l.get("revenue")));
                        if ("gross_profit".equals(metric)) {
                            amount = amount.subtract(dec(l.get("cost")));
                        }
                        qty = qty.add(dec(l.get("quantity")));
                    }
                }
                yield new BigDecimal[] {amount, qty};
            }
        };
    }

    private BigDecimal collected(Map<String, Object> t, LocalDate from, LocalDate to) {
        List<Object> params = new ArrayList<>(List.of(org(), from, to.plusDays(1)));
        String extra = filters(t, params, "d");
        return dec(db.row("SELECT COALESCE(sum((a.amount - COALESCE(a.\"refundedAmount\", 0)) * d.\"exchangeRate\"), 0) AS v FROM \"PaymentAllocation\" a JOIN \"Document\" d ON d.id ="
                + " COALESCE(a.\"documentId\", a.\"invoiceId\") LEFT JOIN \"Partner\" p ON p.id = d.\"partnerId\" LEFT JOIN \"SalesOrder\" so ON d.\"sourceType\" = 'sales_order' AND so.id ="
                + " d.\"sourceId\" WHERE d.\"organizationId\" = ? AND a.\"createdAt\" >= ? AND a.\"createdAt\" < ?" + extra, params.toArray()).get("v"));
    }

    private static String filters(Map<String, Object> t, List<Object> params, String doc) {
        String sql = "";
        if (t.get("salespersonId") != null) {
            sql += " AND COALESCE(" + doc + ".\"salespersonId\", so.\"ownerId\", p.\"salespersonId\") = ?";
            params.add(t.get("salespersonId"));
        }
        if (t.get("branchId") != null) {
            sql += " AND " + doc + ".\"branchId\" = ?";
            params.add(t.get("branchId"));
        }
        return sql;
    }

    private BigDecimal newCustomers(Map<String, Object> t, LocalDate from, LocalDate to) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String extra = "";
        if (t.get("salespersonId") != null) {
            extra = " AND p.\"salespersonId\" = ?";
            params.add(t.get("salespersonId"));
        }
        params.add(from);
        params.add(to.plusDays(1));
        return dec(db.row("SELECT count(*) AS v FROM (SELECT d.\"partnerId\", min(d.\"issueDate\") AS first FROM \"Document\" d JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE"
                + " d.\"organizationId\" = ? AND d.\"documentType\"::text = 'sales_invoice' AND d.status::text IN ('posted', 'paid')" + extra + " GROUP BY 1) f WHERE f.first >= ? AND f.first < ?",
                params.toArray()).get("v"));
    }

    private BigDecimal[] orders(Map<String, Object> t, LocalDate from, LocalDate to, boolean count) {
        List<Object> params = new ArrayList<>(List.of(org(), from, to.plusDays(1)));
        String extra = "";
        if (t.get("salespersonId") != null) {
            extra += " AND \"ownerId\" = ?";
            params.add(t.get("salespersonId"));
        }
        if (t.get("branchId") != null) {
            extra += " AND \"branchId\" = ?";
            params.add(t.get("branchId"));
        }
        Map<String, Object> r = db.row("SELECT count(*) AS c, COALESCE(sum(total * \"exchangeRate\"), 0) AS v FROM \"SalesOrder\" WHERE \"organizationId\" = ? AND \"confirmedAt\" >= ? AND"
                + " \"confirmedAt\" < ? AND status <> 'cancelled' AND \"deletedAt\" IS NULL" + extra, params.toArray());
        return new BigDecimal[] {count ? dec(r.get("c")) : dec(r.get("v")), BigDecimal.ZERO};
    }

    /**
     * Job body: each target tells its owner once per threshold reached, and once when its period ends short. The unique key on
     * target and kind is what makes "once" true, also under two runs at the same time.
     */
    @Transactional
    public int runAlerts(LocalDate today) {
        int fired = 0;
        for (Map<String, Object> t : db.rows("SELECT * FROM \"SalesTarget\" WHERE \"organizationId\" = ? AND month <= ? AND amount > 0", org(), today)) {
            LocalDate to = end(t);
            if (to.isBefore(today.minusDays(31))) {
                continue;
            }
            fill(t);
            BigDecimal pct = dec(t.get("achievementPercent"));
            List<Integer> levels = new ArrayList<>();
            if (t.get("alertPercents") instanceof java.sql.Array a) {
                try {
                    for (Object o : (Object[]) a.getArray()) {
                        levels.add(((Number) o).intValue());
                    }
                } catch (java.sql.SQLException e) {
                    levels = List.of(80, 100);
                }
            } else if (t.get("alertPercents") instanceof List<?> l) {
                for (Object o : l) {
                    levels.add(((Number) o).intValue());
                }
            } else {
                levels = List.of(80, 100);
            }
            for (int level : levels) {
                if (pct.compareTo(BigDecimal.valueOf(level)) >= 0) {
                    fired += fire(t, "reached_" + level, "Target " + level + "% reached", pct);
                }
            }
            if (to.isBefore(today) && pct.compareTo(BigDecimal.valueOf(100)) < 0) {
                fired += fire(t, "missed", "Target period ended at " + pct.stripTrailingZeros().toPlainString() + "%", pct);
            }
        }
        return fired;
    }

    private int fire(Map<String, Object> t, String kind, String title, BigDecimal pct) {
        int n = db.update("INSERT INTO \"SalesTargetAlert\" (id, \"organizationId\", \"targetId\", kind, actual) VALUES (?, ?, ?, ?, ?) ON CONFLICT (\"targetId\", kind) DO NOTHING",
                SalesDb.newId(), org(), t.get("id"), kind, pct);
        if (n > 0) {
            alerts.send(str(t.get("salespersonId")), "sales_target_alert", title, str(t.get("metric")) + " target for the period from " + t.get("month"), Obj.of("entityType", "sales_target",
                    "entityId", t.get("id")));
        }
        return n;
    }

    /** Sales KPIs of {@code [from, to]}, on the one revenue logic. Organization-wide. */
    @Transactional(readOnly = true)
    public Map<String, Object> kpis(LocalDate from, LocalDate to) {
        access.assertAllBranches();
        BigDecimal rev = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        Set<String> invoices = new java.util.HashSet<>();
        Set<String> customers = new java.util.HashSet<>();
        for (Map<String, Object> l : revenue.invoiced(from, to)) {
            rev = rev.add(dec(l.get("revenue")));
            cost = cost.add(dec(l.get("cost")));
            if ("sales_invoice".equals(l.get("type"))) {
                invoices.add(str(l.get("documentId")));
                customers.add(str(l.get("partnerId")));
            }
        }
        long span = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        Set<String> earlier = new java.util.HashSet<>();
        for (Map<String, Object> l : revenue.invoiced(from.minusDays(span), from.minusDays(1))) {
            if ("sales_invoice".equals(l.get("type"))) {
                earlier.add(str(l.get("partnerId")));
            }
        }
        long lost = earlier.stream().filter(c -> !customers.contains(c)).count();
        Map<String, Object> life = db.row("SELECT COALESCE(sum(d.\"subtotal\" * d.\"exchangeRate\"), 0) AS v, count(DISTINCT d.\"partnerId\") AS c FROM \"Document\" d WHERE d.\"organizationId\" = ? AND"
                + " d.\"documentType\"::text = 'sales_invoice' AND d.status::text IN ('posted', 'paid')", org());
        BigDecimal badDebt = dec(db.row("SELECT COALESCE(sum(\"totalAmount\" * \"exchangeRate\"), 0) AS v FROM \"Document\" WHERE \"organizationId\" = ? AND \"documentType\"::text = 'credit_note' AND"
                + " status::text IN ('posted', 'paid') AND data->>'creditType' = 'write_off' AND \"issueDate\" >= ? AND \"issueDate\" < ?", org(), from, to.plusDays(1)).get("v"));
        return Obj.of("from", from, "to", to, "revenue", money(rev), "grossProfit", money(rev.subtract(cost)), "invoices", invoices.size(), "averageOrderValue", ratio(rev, invoices.size()),
                "activeCustomers", customers.size(), "newCustomers", newCustomers(Map.of(), from, to).intValue(), "lifetimeValue", ratio(dec(life.get("v")), dec(life.get("c")).longValue()),
                "lostCustomers", lost, "churnPercent", earlier.isEmpty() ? null : JsNumbers.js(BigDecimal.valueOf(lost * 100).divide(BigDecimal.valueOf(earlier.size()), 1, RoundingMode.HALF_UP)),
                "badDebt", money(badDebt));
    }

    private static Object money(BigDecimal v) {
        return JsNumbers.js(v.setScale(2, RoundingMode.HALF_UP));
    }

    private static Object ratio(BigDecimal v, long n) {
        return n == 0 ? JsNumbers.js(BigDecimal.ZERO) : money(v.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP));
    }
}
