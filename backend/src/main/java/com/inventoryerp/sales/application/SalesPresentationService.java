package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stakeholder sales presentation: one read-only snapshot of {@code [from, to]} compared with the period of equal length
 * just before it, from which the web client renders a slide deck (PPTX). Revenue and margin use the same net invoiced
 * basis as {@link SalesAnalyticsService}; the commercial funnel counts quotations issued, orders confirmed and orders
 * cancelled in the period; the pipeline is the CRM opportunity book; field activity is the completed CRM activities
 * of the period and the follow-up plan its open activities plus the sales advisor's advisories.
 */
@Service
public class SalesPresentationService {

    static final int MAX_DAYS = 366;
    private static final int TREND_BUCKETS = 6;
    private static final int TOP_CATEGORIES = 6;
    private static final int TOP_PRODUCTS = 8;

    private final SalesDb db;
    private final SalesRevenueQuery revenue;
    private final SalesAnalyticsService analytics;
    private final SalesAdvisorService advisor;

    public SalesPresentationService(SalesDb db, SalesRevenueQuery revenue, SalesAnalyticsService analytics,
            SalesAdvisorService advisor) {
        this.db = db;
        this.revenue = revenue;
        this.analytics = analytics;
        this.advisor = advisor;
    }

    private static Object money(BigDecimal v) {
        return JsNumbers.js(v.setScale(2, RoundingMode.HALF_UP));
    }

    private static Object num(BigDecimal v) {
        return JsNumbers.js(v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    private static Object pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? null
                : JsNumbers.js(part.multiply(SalesPricingEngine.HUNDRED).divide(whole, 1, RoundingMode.HALF_UP));
    }

    private static Object change(BigDecimal current, BigDecimal previous) {
        return previous.signum() == 0 ? null : pct(current.subtract(previous), previous.abs());
    }

    private static BigDecimal sum(Collection<Map<String, Object>> lines, String key) {
        return lines.stream().map(l -> dec(l.get(key))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, Object> compare(BigDecimal current, BigDecimal previous) {
        return Obj.of("current", money(current), "previous", money(previous), "changePercent", change(current,
                previous));
    }

    private static List<Object> params(Object first, Collection<?> rest) {
        List<Object> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    /** The full deck data set of {@code [from, to]} (default: the last seven days). */
    @Transactional(readOnly = true)
    public Map<String, Object> snapshot(String fromRaw, String toRaw) {
        LocalDate to = blank(toRaw) ? LocalDate.now() : SalesDb.date(toRaw);
        LocalDate from = blank(fromRaw) ? to.minusDays(6) : SalesDb.date(fromRaw);
        if (from.isAfter(to)) {
            throw ApiException.badRequest("from must not be after to");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw ApiException.badRequest("The presentation period cannot exceed " + MAX_DAYS + " days");
        }
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);

        List<Map<String, Object>> lines = revenue.invoiced(from, to);
        List<Map<String, Object>> prevLines = revenue.invoiced(prevFrom, prevTo);

        Map<String, Object> organization = db.row("SELECT name, \"currencyCode\" FROM \"Organization\" WHERE id = ?",
                org());
        Map<String, Object> out = Obj.of("organization", organization, "period", Obj.of("from", from, "to", to, "days",
                days), "previousPeriod", Obj.of("from", prevFrom, "to", prevTo), "generatedAt", Instant.now());
        out.put("kpis", kpis(lines, prevLines, from, to, prevFrom, prevTo));
        out.put("categories", categories(lines, prevLines));
        out.put("trend", trend(from, to, days));
        out.put("topCustomers", topCustomers(lines, prevLines));
        out.put("salespeople", salespeople(lines));
        out.put("pipeline", pipeline(from, to));
        out.put("lostBusiness", lostBusiness(from, to));
        out.put("receivables", receivables());
        out.put("activities", activities(from, to));
        out.put("forecast", analytics.forecast(3));
        out.put("advisories", advisor.advisories().stream().limit(8).toList());
        return out;
    }

    // ------------------------------------------------------------------ KPIs

    private Map<String, Object> kpis(List<Map<String, Object>> lines, List<Map<String, Object>> prevLines,
            LocalDate from, LocalDate to, LocalDate prevFrom, LocalDate prevTo) {
        BigDecimal rev = sum(lines, "revenue");
        BigDecimal prevRev = sum(prevLines, "revenue");
        BigDecimal margin = rev.subtract(sum(lines, "cost"));
        BigDecimal prevMargin = prevRev.subtract(sum(prevLines, "cost"));
        long invoices = lines.stream().filter(l -> "sales_invoice".equals(l.get("type"))).map(l -> l.get("documentId"))
                .distinct().count();
        long prevInvoices = prevLines.stream().filter(l -> "sales_invoice".equals(l.get("type")))
                .map(l -> l.get("documentId")).distinct().count();
        long customers = lines.stream().map(l -> l.get("partnerId")).distinct().count();
        long prevCustomers = prevLines.stream().map(l -> l.get("partnerId")).distinct().count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revenue", compare(rev, prevRev));
        out.put("margin", compare(margin, prevMargin));
        out.put("marginPercent", Obj.of("current", pct(margin, rev), "previous", pct(prevMargin, prevRev)));
        out.put("invoices", Obj.of("current", invoices, "previous", prevInvoices));
        out.put("activeCustomers", Obj.of("current", customers, "previous", prevCustomers));
        out.put("averageInvoice", compare(invoices == 0 ? BigDecimal.ZERO
                : rev.divide(BigDecimal.valueOf(invoices), 2, RoundingMode.HALF_UP), prevInvoices == 0 ? BigDecimal.ZERO
                : prevRev.divide(BigDecimal.valueOf(prevInvoices), 2, RoundingMode.HALF_UP)));
        out.put("quantity", Obj.of("current", num(sum(lines, "quantity")), "previous", num(sum(prevLines, "quantity"))));

        String quotes = "SELECT count(*) AS count, COALESCE(sum(total), 0) AS value,"
                + " count(*) FILTER (WHERE status = 'converted') AS converted FROM \"SalesQuotation\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status NOT IN ('draft', 'cancelled')"
                + " AND \"issueDate\" >= ? AND \"issueDate\" <= ?";
        Map<String, Object> q = db.row(quotes, org(), from, to);
        Map<String, Object> pq = db.row(quotes, org(), prevFrom, prevTo);
        long qCount = ((Number) q.get("count")).longValue();
        out.put("quotationsIssued", Obj.of("count", qCount, "value", money(dec(q.get("value"))), "previousCount",
                pq.get("count"), "converted", q.get("converted"), "conversionPercent", qCount == 0 ? null
                        : pct(BigDecimal.valueOf(((Number) q.get("converted")).longValue()), BigDecimal.valueOf(qCount))));

        String confirmed = "SELECT count(*) AS count, COALESCE(sum(total), 0) AS value FROM \"SalesOrder\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status IN ('confirmed', 'in_fulfilment',"
                + " 'done') AND COALESCE(\"confirmedAt\", \"createdAt\") >= ? AND COALESCE(\"confirmedAt\","
                + " \"createdAt\") < ?";
        Map<String, Object> o = db.row(confirmed, org(), from, to.plusDays(1));
        Map<String, Object> po = db.row(confirmed, org(), prevFrom, prevTo.plusDays(1));
        out.put("ordersConfirmed", Obj.of("count", o.get("count"), "value", money(dec(o.get("value"))), "previousCount",
                po.get("count"), "previousValue", money(dec(po.get("value")))));

        Map<String, Object> lost = db.row("SELECT count(*) AS count, COALESCE(sum(total), 0) AS value FROM"
                + " \"SalesOrder\" WHERE \"organizationId\" = ? AND status = 'cancelled' AND \"cancelledAt\" >= ? AND"
                + " \"cancelledAt\" < ?", org(), from, to.plusDays(1));
        Map<String, Object> lostQuotes = db.row("SELECT count(*) AS count, COALESCE(sum(total), 0) AS value FROM"
                + " \"SalesQuotation\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status IN ('rejected',"
                + " 'expired') AND \"updatedAt\" >= ? AND \"updatedAt\" < ?", org(), from, to.plusDays(1));
        out.put("ordersLost", Obj.of("count", ((Number) lost.get("count")).longValue()
                + ((Number) lostQuotes.get("count")).longValue(), "value", money(dec(lost.get("value"))
                        .add(dec(lostQuotes.get("value")))), "cancelledOrders", lost.get("count"), "lostQuotations",
                lostQuotes.get("count")));

        String collected = "SELECT COALESCE(sum(amount - \"refundedAmount\"), 0) AS amount FROM \"Payment\""
                + " WHERE \"organizationId\" = ? AND direction = 'inbound' AND status IN ('posted', 'paid')"
                + " AND \"voidedAt\" IS NULL AND \"paymentDate\" >= ? AND \"paymentDate\" < ?";
        out.put("collections", compare(dec(db.row(collected, org(), from, to.plusDays(1)).get("amount")),
                dec(db.row(collected, org(), prevFrom, prevTo.plusDays(1)).get("amount"))));

        out.put("newCustomers", db.row("SELECT count(*) AS count FROM (SELECT \"partnerId\" FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"documentType\" = 'sales_invoice' AND status IN ('posted', 'paid')"
                + " GROUP BY \"partnerId\" HAVING min(\"issueDate\") >= ? AND min(\"issueDate\") < ?) f", org(), from,
                to.plusDays(1)).get("count"));
        return out;
    }

    // ------------------------------------------------------------------ categories / products

    private List<Map<String, Object>> categories(List<Map<String, Object>> lines, List<Map<String, Object>> prevLines) {
        BigDecimal total = sum(lines, "revenue");
        Map<String, List<Map<String, Object>>> byCat = group(lines, l -> str(l.get("productCategoryId")));
        Map<String, BigDecimal> prevByCat = new LinkedHashMap<>();
        prevLines.forEach(l -> prevByCat.merge(String.valueOf(str(l.get("productCategoryId"))), dec(l.get("revenue")),
                BigDecimal::add));
        Set<String> productIds = new HashSet<>();
        lines.forEach(l -> productIds.add(str(l.get("productId"))));
        Map<String, String> catNames = names("SELECT id, name FROM \"ProductCategory\"", byCat.keySet());
        Map<String, Map<String, Object>> products = productInfo(productIds);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> c : byCat.entrySet()) {
            BigDecimal rev = sum(c.getValue(), "revenue");
            BigDecimal cost = sum(c.getValue(), "cost");
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Integer> unitVotes = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> p : group(c.getValue(), l -> str(l.get("productId")))
                    .entrySet()) {
                BigDecimal pRev = sum(p.getValue(), "revenue");
                BigDecimal pCost = sum(p.getValue(), "cost");
                Map<String, Object> info = products.getOrDefault(p.getKey(), Map.of());
                String unit = str(info.get("unit"));
                if (unit != null) {
                    unitVotes.merge(unit, 1, Integer::sum);
                }
                Map<String, Object> r = Obj.of("key", p.getKey(), "label", info.getOrDefault("name", "—"), "unit", unit,
                        "quantity", num(sum(p.getValue(), "quantity")), "revenue", money(pRev), "margin",
                        money(pRev.subtract(pCost)));
                r.put("marginPercent", pct(pRev.subtract(pCost), pRev));
                r.put("sharePercent", pct(pRev, rev));
                rows.add(r);
            }
            rows.sort(Comparator.comparing((Map<String, Object> r) -> dec(r.get("revenue"))).reversed());
            String unit = unitVotes.size() == 1 ? unitVotes.keySet().iterator().next() : null;
            Map<String, Object> cat = Obj.of("key", c.getKey(), "label", c.getKey() == null ? "Uncategorised"
                    : catNames.getOrDefault(c.getKey(), c.getKey()), "unit", unit, "quantity", unit == null ? null
                            : num(sum(c.getValue(), "quantity")), "revenue", money(rev), "previousRevenue",
                    money(prevByCat.getOrDefault(String.valueOf(c.getKey()), BigDecimal.ZERO)));
            cat.put("changePercent", change(rev, prevByCat.getOrDefault(String.valueOf(c.getKey()), BigDecimal.ZERO)));
            cat.put("margin", money(rev.subtract(cost)));
            cat.put("marginPercent", pct(rev.subtract(cost), rev));
            cat.put("sharePercent", pct(rev, total));
            cat.put("productCount", rows.size());
            cat.put("products", rows.stream().limit(TOP_PRODUCTS).toList());
            out.add(cat);
        }
        out.sort(Comparator.comparing((Map<String, Object> r) -> dec(r.get("revenue"))).reversed());
        if (out.size() <= TOP_CATEGORIES) {
            return out;
        }
        List<Map<String, Object>> top = new ArrayList<>(out.subList(0, TOP_CATEGORIES - 1));
        List<Map<String, Object>> rest = out.subList(TOP_CATEGORIES - 1, out.size());
        BigDecimal restRev = rest.stream().map(r -> dec(r.get("revenue"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal restMargin = rest.stream().map(r -> dec(r.get("margin"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal restPrev = rest.stream().map(r -> dec(r.get("previousRevenue"))).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        Map<String, Object> other = Obj.of("key", "__other", "label", "Other categories", "unit", null, "quantity", null,
                "revenue", money(restRev), "previousRevenue", money(restPrev));
        other.put("changePercent", change(restRev, restPrev));
        other.put("margin", money(restMargin));
        other.put("marginPercent", pct(restMargin, restRev));
        other.put("sharePercent", pct(restRev, total));
        other.put("productCount", rest.stream().mapToInt(r -> ((Number) r.get("productCount")).intValue()).sum());
        other.put("products", List.of());
        top.add(other);
        return top;
    }

    private static Map<String, List<Map<String, Object>>> group(List<Map<String, Object>> lines,
            Function<Map<String, Object>, String> key) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        lines.forEach(l -> out.computeIfAbsent(key.apply(l), k -> new ArrayList<>()).add(l));
        return out;
    }

    private Map<String, String> names(String select, Collection<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> ids = keys.stream().filter(k -> k != null).distinct().toList();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : db.rows(select + " WHERE \"organizationId\" = ? AND id IN ("
                + SalesDb.marks(ids.size()) + ")", params(org(), ids))) {
            out.put(str(r.get("id")), str(r.get("name")));
        }
        return out;
    }

    private Map<String, Map<String, Object>> productInfo(Collection<String> keys) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        List<String> ids = keys.stream().filter(k -> k != null).distinct().toList();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : db.rows("SELECT p.id, p.name, COALESCE(u.symbol, u.name) AS unit FROM \"Product\" p"
                + " LEFT JOIN \"UnitOfMeasure\" u ON u.id = COALESCE(p.\"salesUomId\", p.\"uomId\") WHERE"
                + " p.\"organizationId\" = ? AND p.id IN (" + SalesDb.marks(ids.size()) + ")", params(org(), ids))) {
            out.put(str(r.get("id")), r);
        }
        return out;
    }

    // ------------------------------------------------------------------ trend

    /**
     * Revenue and margin per bucket: periods of up to 45 days are compared with the {@value #TREND_BUCKETS} periods
     * of equal length ending with it (e.g. the last six weeks); longer periods are split into calendar months.
     */
    private Map<String, Object> trend(LocalDate from, LocalDate to, long days) {
        List<LocalDate[]> buckets = new ArrayList<>();
        String granularity;
        if (days <= 45) {
            granularity = "period";
            for (int i = TREND_BUCKETS - 1; i >= 0; i--) {
                LocalDate bTo = to.minusDays(days * i);
                buckets.add(new LocalDate[] {bTo.minusDays(days - 1), bTo});
            }
        } else {
            granularity = "month";
            for (LocalDate m = from.withDayOfMonth(1); !m.isAfter(to); m = m.plusMonths(1)) {
                LocalDate start = m.isBefore(from) ? from : m;
                LocalDate end = m.plusMonths(1).minusDays(1);
                buckets.add(new LocalDate[] {start, end.isAfter(to) ? to : end});
            }
        }
        List<Map<String, Object>> lines = revenue.invoiced(buckets.getFirst()[0], buckets.getLast()[1]);
        Map<String, BigDecimal> catTotals = new LinkedHashMap<>();
        lines.forEach(l -> catTotals.merge(String.valueOf(str(l.get("productCategoryId"))), dec(l.get("revenue")),
                BigDecimal::add));
        List<String> topCats = catTotals.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()).limit(4).map(Map.Entry::getKey)
                .toList();
        Map<String, String> catNames = names("SELECT id, name FROM \"ProductCategory\"", topCats);
        List<Map<String, Object>> series = new ArrayList<>();
        for (String c : topCats) {
            series.add(Obj.of("key", c, "label", "null".equals(c) ? "Uncategorised" : catNames.getOrDefault(c, c)));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate[] b : buckets) {
            String start = b[0].toString();
            String end = b[1].toString();
            List<Map<String, Object>> in = lines.stream().filter(l -> {
                String d = String.valueOf(l.get("date"));
                return d.compareTo(start) >= 0 && d.compareTo(end) <= 0;
            }).toList();
            BigDecimal rev = sum(in, "revenue");
            Map<String, Object> byCat = new LinkedHashMap<>();
            for (String c : topCats) {
                byCat.put(c, money(sum(in.stream().filter(l -> c.equals(String.valueOf(str(l.get(
                        "productCategoryId"))))).toList(), "revenue")));
            }
            rows.add(Obj.of("from", b[0], "to", b[1], "revenue", money(rev), "margin", money(rev.subtract(sum(in,
                    "cost"))), "byCategory", byCat));
        }
        return Obj.of("granularity", granularity, "series", series, "buckets", rows);
    }

    // ------------------------------------------------------------------ customers / salespeople

    private List<Map<String, Object>> topCustomers(List<Map<String, Object>> lines,
            List<Map<String, Object>> prevLines) {
        BigDecimal total = sum(lines, "revenue");
        Map<String, List<Map<String, Object>>> byCustomer = group(lines, l -> str(l.get("partnerId")));
        Map<String, BigDecimal> prev = new LinkedHashMap<>();
        prevLines.forEach(l -> prev.merge(String.valueOf(str(l.get("partnerId"))), dec(l.get("revenue")),
                BigDecimal::add));
        Map<String, String> names = names("SELECT id, name FROM \"Partner\"", byCustomer.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byCustomer.entrySet()) {
            BigDecimal rev = sum(e.getValue(), "revenue");
            BigDecimal p = prev.getOrDefault(String.valueOf(e.getKey()), BigDecimal.ZERO);
            Map<String, Object> r = Obj.of("partnerId", e.getKey(), "name", names.getOrDefault(e.getKey(), "—"),
                    "revenue", money(rev), "previousRevenue", money(p), "changePercent", change(rev, p));
            r.put("sharePercent", pct(rev, total));
            r.put("invoices", e.getValue().stream().map(l -> l.get("documentId")).distinct().count());
            rows.add(r);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> dec(r.get("revenue"))).reversed());
        return rows.stream().limit(10).toList();
    }

    private List<Map<String, Object>> salespeople(List<Map<String, Object>> lines) {
        Map<String, List<Map<String, Object>>> by = group(lines, l -> str(l.get("salespersonId")));
        Map<String, String> names = names("SELECT id, trim(\"firstName\" || ' ' || COALESCE(\"lastName\", '')) AS name"
                + " FROM \"User\"", by.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : by.entrySet()) {
            BigDecimal rev = sum(e.getValue(), "revenue");
            BigDecimal margin = rev.subtract(sum(e.getValue(), "cost"));
            Map<String, Object> r = Obj.of("userId", e.getKey(), "name", e.getKey() == null ? "Unassigned"
                    : names.getOrDefault(e.getKey(), "—"), "revenue", money(rev), "margin", money(margin),
                    "marginPercent", pct(margin, rev));
            r.put("customers", e.getValue().stream().map(l -> l.get("partnerId")).distinct().count());
            r.put("invoices", e.getValue().stream().map(l -> l.get("documentId")).distinct().count());
            rows.add(r);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> dec(r.get("revenue"))).reversed());
        return rows.stream().limit(10).toList();
    }

    // ------------------------------------------------------------------ pipeline / lost business

    private Map<String, Object> pipeline(LocalDate from, LocalDate to) {
        String effective = "COALESCE(d.probability, s.probability, 0)";
        Map<String, Object> open = db.row("SELECT count(*) AS count, COALESCE(sum(d.amount), 0) AS value,"
                + " COALESCE(sum(d.amount * " + effective + " / 100), 0) AS weighted FROM \"Deal\" d LEFT JOIN"
                + " \"CrmStage\" s ON s.id = d.\"stageId\" WHERE d.\"organizationId\" = ? AND d.\"deletedAt\" IS NULL"
                + " AND d.kind = 'opportunity' AND d.status = 'open'", org());
        Map<String, Object> won = db.row("SELECT count(*) AS count, COALESCE(sum(amount), 0) AS value FROM \"Deal\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status = 'won' AND \"wonAt\" >= ? AND"
                + " \"wonAt\" < ?", org(), from, to.plusDays(1));
        Map<String, Object> lost = db.row("SELECT count(*) AS count, COALESCE(sum(amount), 0) AS value FROM \"Deal\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status = 'lost' AND \"lostAt\" >= ? AND"
                + " \"lostAt\" < ?", org(), from, to.plusDays(1));
        Map<String, Object> created = db.row("SELECT count(*) FILTER (WHERE kind = 'lead') AS leads,"
                + " count(*) FILTER (WHERE kind = 'opportunity') AS opportunities FROM \"Deal\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"createdAt\" >= ? AND \"createdAt\" < ?", org(),
                from, to.plusDays(1));
        List<Map<String, Object>> stages = db.rows("SELECT COALESCE(s.name, initcap(d.stage::text)) AS stage,"
                + " min(COALESCE(s.sequence, 999)) AS seq, count(*) AS count, COALESCE(sum(d.amount), 0) AS value FROM"
                + " \"Deal\" d LEFT JOIN \"CrmStage\" s ON s.id = d.\"stageId\" WHERE d.\"organizationId\" = ? AND"
                + " d.\"deletedAt\" IS NULL AND d.kind = 'opportunity' AND d.status = 'open' GROUP BY 1 ORDER BY 2, 1",
                org()).stream().map(r -> Obj.of("stage", r.get("stage"), "count", r.get("count"), "value",
                        money(dec(r.get("value"))))).toList();
        List<Map<String, Object>> reasons = db.rows("SELECT COALESCE(r.name, 'Not specified') AS reason, count(*) AS"
                + " count, COALESCE(sum(d.amount), 0) AS value FROM \"Deal\" d LEFT JOIN \"CrmLostReason\" r ON r.id ="
                + " d.\"lostReasonId\" WHERE d.\"organizationId\" = ? AND d.\"deletedAt\" IS NULL AND d.status = 'lost'"
                + " AND d.\"lostAt\" >= ? AND d.\"lostAt\" < ? GROUP BY 1 ORDER BY 2 DESC", org(), from, to.plusDays(1))
                .stream().map(r -> Obj.of("reason", r.get("reason"), "count", r.get("count"), "value",
                        money(dec(r.get("value"))))).toList();
        List<Map<String, Object>> top = db.rows("SELECT d.id, d.name, COALESCE(p.name, d.\"companyName\","
                + " d.\"contactName\") AS customer, COALESCE(s.name, initcap(d.stage::text)) AS stage, d.amount, "
                + effective + " AS probability, d.\"expectedClose\"::date AS \"expectedClose\", trim(u.\"firstName\" ||"
                + " ' ' || COALESCE(u.\"lastName\", '')) AS owner FROM \"Deal\" d LEFT JOIN \"Partner\" p ON p.id ="
                + " d.\"partnerId\" LEFT JOIN \"CrmStage\" s ON s.id = d.\"stageId\" LEFT JOIN \"User\" u ON u.id ="
                + " d.\"ownerId\" WHERE d.\"organizationId\" = ? AND d.\"deletedAt\" IS NULL AND d.kind = 'opportunity'"
                + " AND d.status = 'open' ORDER BY d.amount DESC, d.\"createdAt\" LIMIT 8", org()).stream().map(r -> {
                    Map<String, Object> m = Obj.of("id", r.get("id"), "name", r.get("name"), "customer",
                            r.get("customer"), "stage", r.get("stage"), "amount", money(dec(r.get("amount"))),
                            "probability", JsNumbers.js(dec(r.get("probability"))));
                    m.put("expectedClose", r.get("expectedClose"));
                    m.put("owner", blank(str(r.get("owner"))) ? null : r.get("owner"));
                    return m;
                }).toList();
        Map<String, Object> out = Obj.of("open", Obj.of("count", open.get("count"), "value", money(dec(open.get(
                "value"))), "weighted", money(dec(open.get("weighted")))), "won", Obj.of("count", won.get("count"),
                        "value", money(dec(won.get("value")))), "lost", Obj.of("count", lost.get("count"), "value",
                                money(dec(lost.get("value")))), "newLeads", created.get("leads"), "newOpportunities",
                created.get("opportunities"));
        long closed = ((Number) won.get("count")).longValue() + ((Number) lost.get("count")).longValue();
        out.put("winRatePercent", closed == 0 ? null : pct(BigDecimal.valueOf(((Number) won.get("count")).longValue()),
                BigDecimal.valueOf(closed)));
        out.put("byStage", stages);
        out.put("lostReasons", reasons);
        out.put("topOpen", top);
        return out;
    }

    private List<Map<String, Object>> lostBusiness(LocalDate from, LocalDate to) {
        return db.rows("""
                SELECT * FROM (
                    SELECT 'order' AS kind, o.number, p.name AS customer, o.total AS value,
                           o."cancelReason" AS reason, o."cancelledAt"::date AS date
                      FROM "SalesOrder" o LEFT JOIN "Partner" p ON p.id = o."partnerId"
                     WHERE o."organizationId" = ? AND o.status = 'cancelled' AND o."cancelledAt" >= ? AND o."cancelledAt" < ?
                    UNION ALL
                    SELECT 'quotation', q.number, p.name, q.total,
                           COALESCE(q."rejectionReason", CASE q.status WHEN 'expired' THEN 'Quotation expired' END),
                           q."updatedAt"::date
                      FROM "SalesQuotation" q LEFT JOIN "Partner" p ON p.id = q."partnerId"
                     WHERE q."organizationId" = ? AND q."deletedAt" IS NULL AND q.status IN ('rejected', 'expired')
                       AND q."updatedAt" >= ? AND q."updatedAt" < ?
                ) x ORDER BY value DESC LIMIT 10""", org(), from, to.plusDays(1), org(), from, to.plusDays(1)).stream()
                .map(r -> {
                    Map<String, Object> m = Obj.of("kind", r.get("kind"), "number", r.get("number"), "customer",
                            r.get("customer"), "value", money(dec(r.get("value"))), "reason", r.get("reason"));
                    m.put("date", r.get("date"));
                    return m;
                }).toList();
    }

    // ------------------------------------------------------------------ receivables / activities

    private Map<String, Object> receivables() {
        Map<String, Object> r = db.row("""
                SELECT COALESCE(sum("amountResidual"), 0) AS total,
                       COALESCE(sum("amountResidual") FILTER (WHERE "dueDate" IS NULL OR "dueDate" >= CURRENT_DATE), 0) AS current,
                       COALESCE(sum("amountResidual") FILTER (WHERE "dueDate" < CURRENT_DATE AND "dueDate" >= CURRENT_DATE - 30), 0) AS d30,
                       COALESCE(sum("amountResidual") FILTER (WHERE "dueDate" < CURRENT_DATE - 30 AND "dueDate" >= CURRENT_DATE - 60), 0) AS d60,
                       COALESCE(sum("amountResidual") FILTER (WHERE "dueDate" < CURRENT_DATE - 60 AND "dueDate" >= CURRENT_DATE - 90), 0) AS d90,
                       COALESCE(sum("amountResidual") FILTER (WHERE "dueDate" < CURRENT_DATE - 90), 0) AS older,
                       count(DISTINCT "partnerId") FILTER (WHERE "dueDate" < CURRENT_DATE) AS "overdueCustomers"
                  FROM "Document"
                 WHERE "organizationId" = ? AND "documentType" = 'sales_invoice' AND status = 'posted'
                   AND "amountResidual" > 0""", org());
        BigDecimal overdue = dec(r.get("d30")).add(dec(r.get("d60"))).add(dec(r.get("d90"))).add(dec(r.get("older")));
        return Obj.of("total", money(dec(r.get("total"))), "overdue", money(overdue), "overdueCustomers",
                r.get("overdueCustomers"), "aging", List.of(
                        Obj.of("bucket", "Current", "amount", money(dec(r.get("current")))),
                        Obj.of("bucket", "1-30 days", "amount", money(dec(r.get("d30")))),
                        Obj.of("bucket", "31-60 days", "amount", money(dec(r.get("d60")))),
                        Obj.of("bucket", "61-90 days", "amount", money(dec(r.get("d90")))),
                        Obj.of("bucket", "90+ days", "amount", money(dec(r.get("older"))))));
    }

    private Map<String, Object> activities(LocalDate from, LocalDate to) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows("SELECT type::text AS type, count(*) AS count FROM \"Activity\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND type IN ('call', 'email', 'meeting', 'task')"
                + " AND COALESCE(\"completedAt\", \"occurredAt\") >= ? AND COALESCE(\"completedAt\", \"occurredAt\") < ?"
                + " AND (completed OR type IN ('call', 'email', 'meeting')) GROUP BY 1", org(), from, to.plusDays(1))) {
            counts.put(str(r.get("type")), r.get("count"));
        }
        String cols = "SELECT a.type::text AS type, a.title, COALESCE(p.name, d.name) AS subject, ";
        String joins = " FROM \"Activity\" a LEFT JOIN \"Partner\" p ON p.id = a.\"partnerId\" LEFT JOIN \"Deal\" d ON"
                + " d.id = a.\"dealId\" WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL";
        List<Map<String, Object>> highlights = db.rows(cols + "COALESCE(a.\"completedAt\", a.\"occurredAt\")::date AS"
                + " date" + joins + " AND a.type IN ('meeting', 'call') AND COALESCE(a.\"completedAt\", a.\"occurredAt\")"
                + " >= ? AND COALESCE(a.\"completedAt\", a.\"occurredAt\") < ? ORDER BY (a.type = 'meeting') DESC, 4 DESC"
                + " LIMIT 8", org(), from, to.plusDays(1));
        List<Map<String, Object>> upcoming = db.rows(cols + "a.\"dueAt\"::date AS date" + joins + " AND NOT a.completed"
                + " AND a.status NOT IN ('done', 'cancelled') AND a.\"dueAt\" IS NOT NULL AND a.\"dueAt\" < ? ORDER BY"
                + " a.\"dueAt\" LIMIT 10", org(), to.plusDays(15));
        return Obj.of("counts", counts, "highlights", highlights, "upcoming", upcoming);
    }
}
