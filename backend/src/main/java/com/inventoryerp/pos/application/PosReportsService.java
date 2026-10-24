package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.accounting.application.SessionReconciliation;
import com.inventoryerp.accounting.domain.CashSession;
import com.inventoryerp.invoicing.domain.Invoice;
import com.inventoryerp.invoicing.domain.InvoiceItem;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.pos.domain.PosReportSnapshot;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.text.Collator;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * POS X / Z reports and sales analytics (port of {@code pos-reports.service.ts}). Sales come from the POS
 * {@code Invoice} pipeline ({@code orderId} set, status posted/paid/refunded), bucketed by trading date; money is
 * summed as decimals. "Local" dates use the server time zone (UTC in production), like the Nest host.
 */
@Service
public class PosReportsService {

    static final List<String> POS_SALE_STATUSES = List.of("posted", "paid", "refunded");
    static final List<String> ORDER_TYPES = List.of("dine_in", "takeaway", "delivery");
    static final List<String> PAYMENT_MODES = List.of("cash", "card", "mobile_money", "mixed", "credit");

    private static final Pattern DAY = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})");
    /** ICU puts U+202F (narrow no-break space) before AM/PM in Node 20+. */
    private static final String NNBSP = " ";
    private static final Collator COLLATOR = Collator.getInstance(Locale.ENGLISH);

    /** Filters shared by every invoice-backed report ({@code PosSaleFilters}). */
    public record Filters(String waiterId, String paymentMethod, String orderType, String search, String cashSessionId,
            String tableId) {

        public static Filters of(String waiterId, String paymentMethod, String orderType, String search) {
            return new Filters(waiterId, paymentMethod, orderType, search, null, null);
        }

        static final Filters NONE = new Filters(null, null, null, null, null, null);

        boolean any() {
            return waiterId != null || paymentMethod != null || orderType != null || search != null || tableId != null
                    || cashSessionId != null;
        }
    }

    record Range(Instant start, Instant end) {}

    /** A resolved sale category ({@code SaleCategory}). */
    record SaleCategory(String key, String id, String name, String source) {}

    private static final SaleCategory UNCATEGORISED = new SaleCategory(null, null, "Uncategorised", null);
    private static final SaleCategory LEGACY_UNRESOLVED = new SaleCategory(null, null, "Legacy unresolved", null);

    private final JdbcClient jdbc;
    private final SessionReconciliation reconciliation;
    private final TransactionTemplate tx;

    public PosReportsService(PosDb db, SessionReconciliation reconciliation, PlatformTransactionManager txm) {
        this.jdbc = db.jdbc();
        this.reconciliation = reconciliation;
        this.tx = new TransactionTemplate(txm);
    }

    // ───────────────────────────── date helpers ─────────────────────────────

    private static ZoneId zone() {
        return BusinessZone.get();
    }

    private static ZonedDateTime local(Instant i) {
        return i.atZone(zone());
    }

    /** Local {@code YYYY-MM-DD}. */
    static String localIso(Instant i) {
        return local(i).toLocalDate().toString();
    }

    /** JS {@code new Date(y, m, d)} with month/day overflow normalisation. */
    private static LocalDate jsDate(int y, int month0, int day) {
        return LocalDate.of(y, 1, 1).plusMonths(month0).plusDays(day - 1L);
    }

    private static Instant edge(LocalDate d, boolean start) {
        LocalDateTime t = start ? d.atStartOfDay() : d.atTime(LocalTime.of(23, 59, 59, 999_000_000));
        return t.atZone(zone()).toInstant();
    }

    private static Instant parseLocalDay(String value, boolean start) {
        String v = value == null ? "" : value.trim();
        Matcher m = DAY.matcher(v);
        if (!m.find()) {
            Instant loose = null;
            try {
                loose = value == null || value.isEmpty() ? null : PosSql.date(value);
            } catch (RuntimeException e) {
                loose = null;
            }
            if (loose == null) {
                throw ApiException.badRequest("Invalid fromDate/toDate");
            }
            return edge(local(loose).toLocalDate(), start);
        }
        LocalDate d = jsDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)));
        return edge(d, start);
    }

    /** {@code parseReportRange}: local-day bounds; a reversed range is normalised. */
    static Range parseRange(String fromDate, String toDate) {
        Instant start = parseLocalDay(fromDate, true);
        Instant end = parseLocalDay(toDate, false);
        if (start.isAfter(end)) {
            String f = localIso(end);
            String t = localIso(start);
            start = parseLocalDay(f, true);
            end = parseLocalDay(t, false);
        }
        return new Range(start, end);
    }

    /** {@code d.toLocaleTimeString()} (en-US): {@code 3:04:05 PM}. */
    static String timeString(Instant i) {
        ZonedDateTime z = local(i);
        int h = z.getHour() % 12 == 0 ? 12 : z.getHour() % 12;
        return h + ":" + two(z.getMinute()) + ":" + two(z.getSecond()) + NNBSP + (z.getHour() < 12 ? "AM" : "PM");
    }

    /** {@code toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} (en-US): {@code 03:04 PM}. */
    static String shortTime(Instant i) {
        ZonedDateTime z = local(i);
        int h = z.getHour() % 12 == 0 ? 12 : z.getHour() % 12;
        return two(h) + ":" + two(z.getMinute()) + NNBSP + (z.getHour() < 12 ? "AM" : "PM");
    }

    /** {@code toLocaleDateString()} (en-US): {@code 10/20/2026}. */
    static String dateString(Instant i) {
        ZonedDateTime z = local(i);
        return z.getMonthValue() + "/" + z.getDayOfMonth() + "/" + z.getYear();
    }

    private static String two(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static String iso(Object v) {
        return v == null ? null : JsonConfig.toJsIso(instant(v));
    }

    private static Instant instant(Object v) {
        return v instanceof Timestamp t ? t.toInstant() : (Instant) v;
    }

    static int localeCompare(String a, String b) {
        return Integer.signum(COLLATOR.compare(a == null ? "" : a, b == null ? "" : b));
    }

    // ───────────────────────────── money helpers ─────────────────────────────

    private static BigDecimal dec(Object v) {
        return PosSql.dec(v);
    }

    /** decimal.js {@code toFixed(2)} (ROUND_HALF_UP). */
    static String fx(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static Object displayName(Map<String, Object> u) {
        Object last = u.get("lastName");
        return u.get("firstName") + (PosSql.truthy(last) ? " " + last : "");
    }

    // ───────────────────────────── shared queries ─────────────────────────────

    /** {@code posSaleWhere} over alias {@code i} ({@code Invoice}); appends its params. */
    static String saleWhere(String orgId, Range r, Filters f, List<Object> params) {
        if (f.orderType() != null && !ORDER_TYPES.contains(f.orderType())) {
            throw ApiException.badRequest("Unknown orderType \"" + f.orderType() + "\"");
        }
        if (f.paymentMethod() != null && !PAYMENT_MODES.contains(f.paymentMethod())) {
            throw ApiException.badRequest("Unknown paymentMethod \"" + f.paymentMethod() + "\"");
        }
        String search = f.search() == null ? null : f.search().trim();
        StringBuilder w = new StringBuilder("i.\"organizationId\" = ? AND i.\"orderId\" IS NOT NULL"
                + " AND i.status::text IN ('posted', 'paid', 'refunded')"
                + " AND ((i.\"businessDate\" >= ? AND i.\"businessDate\" <= ?)"
                + " OR (i.\"businessDate\" IS NULL AND i.\"issueDate\" >= ? AND i.\"issueDate\" <= ?))");
        params.add(orgId);
        params.add(java.sql.Date.valueOf(localIso(r.start())));
        params.add(java.sql.Date.valueOf(localIso(r.end())));
        params.add(Timestamp.from(r.start()));
        params.add(Timestamp.from(r.end()));
        if (f.waiterId() != null) {
            w.append(" AND i.\"waiterId\" = ?");
            params.add(f.waiterId());
        }
        if (f.tableId() != null) {
            w.append(" AND i.\"tableId\" = ?");
            params.add(f.tableId());
        }
        if (f.cashSessionId() != null) {
            w.append(" AND i.\"cashSessionId\" = ?");
            params.add(f.cashSessionId());
        }
        if (f.paymentMethod() != null) {
            w.append(" AND i.\"paymentMode\" = ?");
            params.add(f.paymentMethod());
        }
        if (f.orderType() != null) {
            w.append(" AND EXISTS (SELECT 1 FROM \"Order\" so WHERE so.id = i.\"orderId\" AND so.\"orderType\" = ?)");
            params.add(f.orderType());
        }
        if (search != null && !search.isEmpty()) {
            w.append(" AND (i.\"invoiceNumber\" ILIKE ? OR EXISTS (SELECT 1 FROM \"Order\" sq WHERE sq.id = i.\"orderId\""
                    + " AND sq.\"orderNumber\" ILIKE ?))");
            params.add(PosSql.contains(search));
            params.add(PosSql.contains(search));
        }
        return w.toString();
    }

    private List<Map<String, Object>> invoices(Range r, Filters f, String orderBy) {
        List<Object> params = new ArrayList<>();
        String w = saleWhere(org(), r, f, params);
        return SqlRows.list(Invoice.class, jdbc.sql("SELECT i.* FROM \"Invoice\" i WHERE " + w
                + (orderBy == null ? "" : " ORDER BY " + orderBy)).params(params));
    }

    /** {@code include: { items: true }}. */
    private void attachItems(List<Map<String, Object>> invoices) {
        Map<String, List<Map<String, Object>>> byInvoice = new HashMap<>();
        for (Map<String, Object> inv : invoices) {
            List<Map<String, Object>> l = new ArrayList<>();
            inv.put("items", l);
            byInvoice.put((String) inv.get("id"), l);
        }
        for (List<Object> chunk : chunks(new ArrayList<>(byInvoice.keySet()))) {
            for (Map<String, Object> it : SqlRows.list(InvoiceItem.class, jdbc.sql("SELECT * FROM \"InvoiceItem\" WHERE"
                    + " \"invoiceId\" IN (" + PosSql.marks(chunk.size()) + ") ORDER BY \"lineNumber\" ASC, id ASC")
                    .params(chunk))) {
                byInvoice.get((String) it.get("invoiceId")).add(it);
            }
        }
    }

    /** {@code include: { order: { select: ... } }}: orderNumber, orderType, tableId of the invoice's order. */
    private Map<String, Map<String, Object>> orders(Collection<Map<String, Object>> invoices) {
        Set<Object> ids = new LinkedHashSet<>();
        invoices.forEach(i -> {
            if (i.get("orderId") != null) {
                ids.add(i.get("orderId"));
            }
        });
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (List<Object> chunk : chunks(new ArrayList<>(ids))) {
            for (Map<String, Object> o : jdbc.sql("SELECT id, \"orderNumber\", \"orderType\"::text AS \"orderType\","
                    + " \"tableId\" FROM \"Order\" WHERE id IN (" + PosSql.marks(chunk.size()) + ")").params(chunk).query()
                    .listOfRows()) {
                out.put((String) o.get("id"), o);
            }
        }
        return out;
    }

    private static List<List<Object>> chunks(List<Object> ids) {
        List<List<Object>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 5000) {
            out.add(new ArrayList<>(ids.subList(i, Math.min(ids.size(), i + 5000))));
        }
        return out;
    }

    /** {@code user.findMany({ where: { id: { in } } })} → display names. */
    private Map<String, String> userNames(Collection<String> ids, boolean trim) {
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<Object> p = new ArrayList<>(List.of(org()));
        p.addAll(ids);
        for (Map<String, Object> u : jdbc.sql("SELECT id, \"firstName\", \"lastName\" FROM \"User\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND id IN (" + PosSql.marks(ids.size()) + ")").params(p).query().listOfRows()) {
            String name = String.valueOf(displayName(u));
            out.put((String) u.get("id"), trim ? name.trim() : name);
        }
        return out;
    }

    private Map<String, Object> namesById(String table, Collection<String> ids, boolean softDelete) {
        Map<String, Object> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<Object> p = new ArrayList<>(List.of(org()));
        p.addAll(ids);
        for (Map<String, Object> r : jdbc.sql("SELECT id, name FROM \"" + table + "\" WHERE \"organizationId\" = ?"
                + (softDelete ? " AND \"deletedAt\" IS NULL" : "") + " AND id IN (" + PosSql.marks(ids.size()) + ")")
                .params(p).query().listOfRows()) {
            out.put((String) r.get("id"), r.get("name"));
        }
        return out;
    }

    private static Object saleDate(Map<String, Object> inv) {
        return inv.get("issueDate") != null ? inv.get("issueDate") : inv.get("createdAt");
    }

    // ───────────────────────────── category resolver ─────────────────────────────

    /** {@code saleCategoryResolver}: one category precedence for every item report. */
    final class Resolver {
        private final Map<String, String[]> products = new HashMap<>();
        private final Map<String, String[]> menuItems = new HashMap<>();
        private final Map<String, Object> bridge;

        Resolver(List<Map<String, Object>> lines) {
            Set<String> productIds = new LinkedHashSet<>();
            Set<String> menuItemIds = new LinkedHashSet<>();
            for (Map<String, Object> l : lines) {
                if (PosSql.truthy(l.get("productId"))) {
                    productIds.add((String) l.get("productId"));
                }
                if (PosSql.truthy(l.get("menuItemId"))) {
                    menuItemIds.add((String) l.get("menuItemId"));
                }
            }
            load(products, "Product", "ProductCategory", productIds);
            load(menuItems, "MenuItem", "MenuCategory", menuItemIds);
            List<String> uncategorised = new ArrayList<>();
            products.forEach((id, v) -> {
                if (v[1] == null) {
                    uncategorised.add(id);
                }
            });
            bridge = legacyBridge(uncategorised);
        }

        private void load(Map<String, String[]> target, String table, String categoryTable, Set<String> ids) {
            if (ids.isEmpty()) {
                return;
            }
            List<Object> p = new ArrayList<>(List.of(org()));
            p.addAll(ids);
            for (Map<String, Object> r : jdbc.sql("SELECT t.id, t.name, c.id AS cid, c.name AS cname FROM \"" + table + "\" t"
                    + " LEFT JOIN \"" + categoryTable + "\" c ON c.id = t.\"categoryId\" WHERE t.\"organizationId\" = ?"
                    + " AND t.\"deletedAt\" IS NULL AND t.id IN (" + PosSql.marks(ids.size()) + ")").params(p).query()
                    .listOfRows()) {
                target.put((String) r.get("id"), new String[] {(String) r.get("name"), (String) r.get("cid"),
                        (String) r.get("cname")});
            }
        }

        SaleCategory resolve(Map<String, Object> line) {
            String[] mi = line.get("menuItemId") == null ? null : menuItems.get((String) line.get("menuItemId"));
            if (mi != null && mi[1] != null) {
                return new SaleCategory("menu:" + mi[1], mi[1], mi[2] == null ? "Uncategorised" : mi[2], "menu");
            }
            String[] prod = line.get("productId") == null ? null : products.get((String) line.get("productId"));
            if (prod != null && prod[1] != null) {
                return new SaleCategory("product:" + prod[1], prod[1], prod[2] == null ? "Uncategorised" : prod[2],
                        "product");
            }
            if (PosSql.truthy(line.get("productId"))) {
                Object b = bridge.get((String) line.get("productId"));
                if ("ambiguous".equals(b)) {
                    return LEGACY_UNRESOLVED;
                }
                if (b instanceof String[] c) {
                    return new SaleCategory("menu:" + c[0], c[0], c[1], "legacy_bridge");
                }
            }
            return UNCATEGORISED;
        }

        String productName(String id) {
            String[] p = products.get(id);
            return p == null ? null : p[0];
        }

        String menuItemName(String id) {
            String[] m = menuItems.get(id);
            return m == null ? null : m[0];
        }
    }

    /** productId → the one MenuCategory of its recipes ({@code String[]{id, name}}) or {@code "ambiguous"}. */
    private Map<String, Object> legacyBridge(List<String> productIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        List<Object> p = new ArrayList<>(List.of(org()));
        p.addAll(productIds);
        Map<String, Map<String, String>> seen = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.sql("SELECT mp.\"productId\", c.id AS cid, c.name AS cname FROM \"MenuProduct\" mp"
                + " LEFT JOIN \"MenuItem\" mi ON mi.id = mp.\"menuItemId\" LEFT JOIN \"MenuCategory\" c ON c.id = mi.\"categoryId\""
                + " WHERE mp.\"organizationId\" = ? AND mp.\"productId\" IN (" + PosSql.marks(productIds.size()) + ")")
                .params(p).query().listOfRows()) {
            if (r.get("cid") == null) {
                continue;
            }
            seen.computeIfAbsent((String) r.get("productId"), k -> new LinkedHashMap<>())
                    .put((String) r.get("cid"), (String) r.get("cname"));
        }
        seen.forEach((productId, cats) -> {
            if (cats.size() != 1) {
                out.put(productId, "ambiguous");
            } else {
                Map.Entry<String, String> e = cats.entrySet().iterator().next();
                out.put(productId, new String[] {e.getKey(), e.getValue()});
            }
        });
        return out;
    }

    private List<String> bridgedProductIds(String menuCategoryId) {
        String orgId = org();
        List<String> candidates = new ArrayList<>(new LinkedHashSet<>(jdbc.sql("SELECT mp.\"productId\" FROM \"MenuProduct\" mp"
                + " JOIN \"MenuItem\" mi ON mi.id = mp.\"menuItemId\" WHERE mp.\"organizationId\" = ? AND mi.\"categoryId\" = ?")
                .params(orgId, menuCategoryId).query(String.class).list()));
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Object> p = new ArrayList<>(List.of(orgId));
        p.addAll(candidates);
        List<String> uncategorised = jdbc.sql("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND \"categoryId\" IS NULL AND id IN (" + PosSql.marks(candidates.size()) + ")").params(p)
                .query(String.class).list();
        List<String> out = new ArrayList<>();
        legacyBridge(uncategorised).forEach((productId, resolved) -> {
            if (resolved instanceof String[] c && c[0].equals(menuCategoryId)) {
                out.add(productId);
            }
        });
        return out;
    }

    private static boolean matchesCategory(SaleCategory c, String filter) {
        return filter.equals(c.key()) || filter.equals(c.id());
    }

    private static List<Map<String, Object>> allItems(List<Map<String, Object>> invoices) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> inv : invoices) {
            out.addAll(PosOrdersService.maps(inv.get("items")));
        }
        return out;
    }

    // ───────────────────────────── filter options ─────────────────────────────

    public Map<String, Object> filterOptions(String fromDate, String toDate) {
        String orgId = org();
        Range r = parseRange(fromDate, toDate);
        List<Map<String, Object>> invoices = invoices(r, Filters.NONE, null);
        attachItems(invoices);
        List<Map<String, Object>> registers = jdbc.sql("SELECT id, code, name FROM \"CashRegister\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL ORDER BY code ASC").param(orgId).query().listOfRows();
        Set<String> waiterIds = new LinkedHashSet<>();
        Set<String> paymentModes = new LinkedHashSet<>();
        for (Map<String, Object> inv : invoices) {
            if (PosSql.truthy(inv.get("waiterId"))) {
                waiterIds.add((String) inv.get("waiterId"));
            }
            if (PosSql.truthy(inv.get("paymentMode"))) {
                paymentModes.add((String) inv.get("paymentMode"));
            }
        }
        List<Map<String, Object>> lines = allItems(invoices);
        Map<String, String> names = userNames(waiterIds, false);
        Resolver cat = new Resolver(lines);
        Map<String, String> categories = new LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            SaleCategory c = cat.resolve(line);
            if (c.key() != null) {
                categories.put(c.key(), c.name());
            }
        }
        List<Map<String, Object>> waiters = new ArrayList<>();
        names.forEach((id, name) -> waiters.add(Obj.of("id", id, "name", name)));
        sortByName(waiters);
        List<Map<String, Object>> cats = new ArrayList<>();
        categories.forEach((id, name) -> cats.add(Obj.of("id", id, "name", name)));
        sortByName(cats);
        return Obj.of("fromDate", localIso(r.start()), "toDate", localIso(r.end()), "waiters", waiters,
                "categories", cats,
                "paymentMethods", PAYMENT_MODES.stream().map(m -> Obj.of("id", m, "name", m.replace('_', ' '), "seen",
                        paymentModes.contains(m))).toList(),
                "orderTypes", ORDER_TYPES.stream().map(t -> Obj.of("id", t, "name", t.replace('_', ' '))).toList(),
                "orderStatuses", OrderStatusUtil.CANONICAL.stream().map(s -> Obj.of("id", s, "name", s.replace('_', ' ')))
                        .toList(),
                "registers", registers.stream().map(g -> Obj.of("id", g.get("id"), "name", g.get("code") + " - "
                        + g.get("name"))).toList());
    }

    private static void sortByName(List<Map<String, Object>> rows) {
        rows.sort((a, b) -> localeCompare((String) a.get("name"), (String) b.get("name")));
    }

    // ───────────────────────────── X / Z ─────────────────────────────

    private Map<String, Object> resolveSession(String orgId, String cashSessionId) {
        if (cashSessionId != null) {
            return SqlRows.one(CashSession.class, jdbc.sql("SELECT * FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?"
                    + " LIMIT 1").params(cashSessionId, orgId));
        }
        return SqlRows.one(CashSession.class, jdbc.sql("SELECT * FROM \"CashSession\" WHERE \"organizationId\" = ?"
                + " AND status = 'open' ORDER BY \"openedAt\" DESC LIMIT 1").param(orgId));
    }

    /** Live mid-shift X-report for a cash session (or the open one). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> xReport(String cashSessionId) {
        String orgId = org();
        Map<String, Object> session = resolveSession(orgId, cashSessionId);
        if (session == null) {
            throw ApiException.notFound("No cash session found");
        }
        Map<String, Object> evidence = tx.execute(st -> reconciliation.reconcileSession(orgId, session));
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) evidence.get("report"));
        out.put("accounts", evidence.get("accounts"));
        out.put("settlements", evidence.get("settlements"));
        out.put("issues", evidence.get("issues"));
        return out;
    }

    /** Z-report: the frozen close snapshot of a closed session. */
    public Object zReport(String cashSessionId) {
        String orgId = org();
        Map<String, Object> session = resolveSession(orgId, cashSessionId);
        if (session == null || "open".equals(session.get("status"))) {
            throw ApiException.badRequest("Close the register before requesting its Z-report");
        }
        Map<String, Object> snapshot = SqlRows.one(PosReportSnapshot.class, jdbc.sql("SELECT * FROM \"PosReportSnapshot\""
                + " WHERE \"cashSessionId\" = ? AND \"organizationId\" = ? LIMIT 1").params(session.get("id"), orgId));
        if (snapshot == null) {
            throw ApiException.notFound("No frozen close report exists for this legacy shift");
        }
        return PosSql.java(snapshot.get("reportData"));
    }

    /** Frozen Z-report snapshot row (reprint). */
    public Map<String, Object> getZReportSnapshot(String cashSessionId) {
        Map<String, Object> snap = SqlRows.one(PosReportSnapshot.class, jdbc.sql("SELECT * FROM \"PosReportSnapshot\""
                + " WHERE \"organizationId\" = ? AND \"cashSessionId\" = ? LIMIT 1").params(org(), cashSessionId));
        if (snap == null) {
            throw ApiException.notFound("No Z-report snapshot for this session");
        }
        return snap;
    }

    // ───────────────────────────── sales by hour ─────────────────────────────

    public Map<String, Object> salesByHour(String fromDate, String toDate, String hours, Filters filters) {
        Range r = parseRange(fromDate, toDate);
        Set<Integer> hourFilter = null;
        if (hours != null) {
            hourFilter = new LinkedHashSet<>();
            for (String h : hours.split(",", -1)) {
                Matcher m = Pattern.compile("^\\s*([+-]?\\d+)").matcher(h);
                if (m.find()) {
                    int n = Integer.parseInt(m.group(1).replace("+", ""));
                    if (n >= 0 && n <= 23) {
                        hourFilter.add(n);
                    }
                }
            }
        }
        long[] counts = new long[24];
        BigDecimal[] totals = new BigDecimal[24];
        java.util.Arrays.fill(totals, BigDecimal.ZERO);
        for (Map<String, Object> d : invoices(r, filters, null)) {
            int hour = local(instant(saleDate(d))).getHour();
            if (hourFilter != null && !hourFilter.contains(hour)) {
                continue;
            }
            counts[hour] += 1;
            totals[hour] = totals[hour].add(dec(d.get("totalAmount")));
        }
        BigDecimal grand = BigDecimal.ZERO;
        long grandCount = 0;
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            grand = grand.add(totals[h]);
            grandCount += counts[h];
            buckets.add(Obj.of("hour", h, "count", counts[h], "total", fx(totals[h])));
        }
        return Obj.of("fromDate", localIso(r.start()), "toDate", localIso(r.end()),
                "totals", Obj.of("count", grandCount, "total", fx(grand)), "buckets", buckets);
    }

    // ───────────────────────────── sales summary ─────────────────────────────

    private static final class Bucket {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        long orders;
    }

    private static String periodKey(Instant i, String groupBy) {
        if ("day".equals(groupBy)) {
            return localIso(i);
        }
        LocalDate d = local(i).toLocalDate();
        if ("week".equals(groupBy)) {
            int dow = d.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : d.getDayOfWeek().getValue();
            return d.plusDays(-dow + (dow == 0 ? -6 : 1)).toString();
        }
        return d.toString().substring(0, 7);
    }

    public Map<String, Object> salesSummary(String fromDate, String toDate, String groupBy, Filters filters) {
        String orgId = org();
        Range r = parseRange(fromDate, toDate);
        if (groupBy == null || !List.of("day", "week", "month").contains(groupBy)) {
            throw ApiException.badRequest("Unknown groupBy \"" + (groupBy == null ? "undefined" : groupBy) + "\"");
        }
        List<Map<String, Object>> invoices = invoices(r, filters, "i.\"createdAt\" ASC");
        List<Map<String, Object>> refunds = loadRefundsForScope(orgId, r, filters, invoices);
        Map<String, Bucket> grouped = new LinkedHashMap<>();
        Bucket overall = new Bucket();
        for (Map<String, Object> row : invoices) {
            Bucket cur = grouped.computeIfAbsent(periodKey(instant(saleDate(row)), groupBy), k -> new Bucket());
            for (Bucket b : List.of(cur, overall)) {
                b.gross = b.gross.add(dec(row.get("totalAmount")));
                b.net = b.net.add(dec(row.get("subtotal")));
                b.tax = b.tax.add(dec(row.get("taxAmount")));
                b.discount = b.discount.add(dec(row.get("discountTotal")));
                b.orders += 1;
            }
        }
        for (Map<String, Object> refund : refunds) {
            Bucket cur = grouped.computeIfAbsent(periodKey(instant(refund.get("createdAt")), groupBy), k -> new Bucket());
            Object itemsRaw = PosSql.java(refund.get("items"));
            BigDecimal net = BigDecimal.ZERO;
            BigDecimal tax = BigDecimal.ZERO;
            if (itemsRaw instanceof List<?> items) {
                for (Object o : items) {
                    Map<String, Object> item = PosSql.map(o);
                    net = net.add(dec(item == null || item.get("subtotal") == null ? 0 : item.get("subtotal")));
                    tax = tax.add(dec(item == null || item.get("taxAmount") == null ? 0 : item.get("taxAmount")));
                }
            }
            BigDecimal amount = dec(refund.get("amount"));
            cur.refunds = cur.refunds.add(amount);
            overall.refunds = overall.refunds.add(amount);
            cur.net = cur.net.subtract(net);
            overall.net = overall.net.subtract(net);
            cur.tax = cur.tax.subtract(tax);
            overall.tax = overall.tax.subtract(tax);
        }

        Map<String, Object[]> byMethod = new LinkedHashMap<>();
        List<Object> ids = invoices.stream().map(i -> i.get("id")).toList();
        for (List<Object> chunk : chunks(new ArrayList<>(ids))) {
            List<Object> p = new ArrayList<>(List.of(orgId));
            p.addAll(chunk);
            for (Map<String, Object> a : jdbc.sql("SELECT a.amount, pm.\"paymentMethod\"::text AS method,"
                    + " pm.direction::text AS direction FROM \"PaymentAllocation\" a LEFT JOIN \"Payment\" pm ON pm.id ="
                    + " a.\"paymentId\" WHERE a.\"organizationId\" = ? AND a.\"invoiceId\" IN (" + PosSql.marks(chunk.size())
                    + ") ORDER BY a.\"createdAt\" ASC, a.id ASC").params(p).query().listOfRows()) {
                if (!"inbound".equals(a.get("direction"))) {
                    continue;
                }
                Object[] cur = byMethod.computeIfAbsent(String.valueOf(a.get("method")), k -> new Object[] {0L,
                        BigDecimal.ZERO});
                cur[0] = (long) cur[0] + 1;
                cur[1] = ((BigDecimal) cur[1]).add(dec(a.get("amount")));
            }
        }

        withEmptyPeriods(grouped, groupBy, r);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("revenue", fx(overall.net));
        totals.put("grossSales", fx(overall.gross));
        totals.put("netSales", fx(overall.gross.subtract(overall.refunds)));
        totals.put("refunds", fx(overall.refunds));
        totals.put("orders", overall.orders);
        totals.put("avgOrderValue", aov(overall.gross, overall.orders));
        totals.put("discounts", fx(overall.discount));
        totals.put("taxes", fx(overall.tax));
        List<Map<String, Object>> periods = new ArrayList<>();
        new TreeMap<>(grouped).forEach((key, p) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("periodKey", key);
            m.put("revenue", fx(p.net));
            m.put("grossSales", fx(p.gross));
            m.put("refunds", fx(p.refunds));
            m.put("orders", p.orders);
            m.put("avgOrderValue", aov(p.gross, p.orders));
            m.put("discounts", fx(p.discount));
            m.put("taxes", fx(p.tax));
            periods.add(m);
        });
        List<Map<String, Object>> methods = new ArrayList<>();
        byMethod.forEach((m, v) -> methods.add(Obj.of("method", "null".equals(m) ? null : m, "count", v[0],
                "total", fx((BigDecimal) v[1]))));
        return Obj.of("fromDate", localIso(r.start()), "toDate", localIso(r.end()), "groupBy", groupBy,
                "totals", totals, "periods", periods, "byMethod", methods);
    }

    private static String aov(BigDecimal gross, long orders) {
        return orders > 0 ? fx(Money.div(gross, BigDecimal.valueOf(orders))) : "0.00";
    }

    private static void withEmptyPeriods(Map<String, Bucket> grouped, String groupBy, Range r) {
        LocalDate cursor = local(r.start()).toLocalDate();
        LocalDate last = local(r.end()).toLocalDate();
        for (int guard = 0; !cursor.isAfter(last) && guard < 3660; guard++) {
            String key = "day".equals(groupBy) ? cursor.toString() : "month".equals(groupBy)
                    ? cursor.toString().substring(0, 7) : null;
            if (key != null && !grouped.containsKey(key)) {
                grouped.put(key, new Bucket());
            }
            cursor = cursor.plusDays(1);
        }
    }

    /** Refunds dated in the range, narrowed to parents that satisfy the active filters (same date window, as Nest). */
    private List<Map<String, Object>> loadRefundsForScope(String orgId, Range r, Filters filters,
            List<Map<String, Object>> inRange) {
        List<Map<String, Object>> refunds = SqlRows.list(jdbc.sql("SELECT * FROM \"PosRefund\" WHERE \"organizationId\" = ?"
                + " AND \"createdAt\" >= ? AND \"createdAt\" <= ?").params(orgId, Timestamp.from(r.start()),
                Timestamp.from(r.end())));
        if (!filters.any() || refunds.isEmpty()) {
            return refunds;
        }
        Set<Object> known = new LinkedHashSet<>();
        inRange.forEach(i -> known.add(i.get("id")));
        Set<Object> unknown = new LinkedHashSet<>();
        refunds.forEach(f -> {
            if (PosSql.truthy(f.get("invoiceId")) && !known.contains(f.get("invoiceId"))) {
                unknown.add(f.get("invoiceId"));
            }
        });
        Set<Object> matched = new LinkedHashSet<>(known);
        if (!unknown.isEmpty()) {
            List<Object> params = new ArrayList<>();
            String w = saleWhere(orgId, r, filters, params);
            params.addAll(unknown);
            matched.addAll(jdbc.sql("SELECT i.id FROM \"Invoice\" i WHERE " + w + " AND i.id IN ("
                    + PosSql.marks(unknown.size()) + ")").params(params).query(String.class).list());
        }
        return refunds.stream().filter(f -> matched.contains(f.get("invoiceId"))).toList();
    }

    // ───────────────────────────── top items ─────────────────────────────

    public List<Map<String, Object>> topItems(String fromDate, String toDate, double limit, String categoryId,
            Filters filters) {
        String orgId = org();
        Range r = parseRange(fromDate, toDate);
        double t = Double.isNaN(limit) ? 0 : (limit < 0 ? Math.ceil(limit) : Math.floor(limit));
        int cap = (int) Math.min(200, Math.max(1, t == 0 ? 20 : t));
        List<Object> params = new ArrayList<>(List.of(orgId));
        String w = saleWhere(orgId, r, filters, params);
        StringBuilder sql = new StringBuilder("SELECT ii.\"productId\", ii.\"menuItemId\", sum(ii.quantity) AS q,"
                + " sum(ii.total) AS t, sum(ii.\"refundedQty\") AS rq FROM \"InvoiceItem\" ii JOIN \"Invoice\" i ON i.id ="
                + " ii.\"invoiceId\" WHERE ii.\"organizationId\" = ? AND " + w);
        if (categoryId != null) {
            int colon = categoryId.indexOf(':');
            String tree = null;
            String id = categoryId;
            if (colon >= 0) {
                String tr = categoryId.substring(0, colon);
                tree = "menu".equals(tr) || "product".equals(tr) ? tr : null;
                id = categoryId.substring(colon + 1);
            }
            List<String> allowedProducts = new ArrayList<>("menu".equals(tree) ? List.of() : jdbc.sql("SELECT id FROM"
                    + " \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"categoryId\" = ?")
                    .params(orgId, id).query(String.class).list());
            List<String> allowedMenuItems = "product".equals(tree) ? List.of() : jdbc.sql("SELECT id FROM \"MenuItem\""
                    + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"categoryId\" = ?").params(orgId, id)
                    .query(String.class).list();
            if (!"product".equals(tree)) {
                allowedProducts.addAll(bridgedProductIds(id));
            }
            if (allowedProducts.isEmpty() && allowedMenuItems.isEmpty()) {
                return List.of();
            }
            List<String> ors = new ArrayList<>();
            if (!allowedProducts.isEmpty()) {
                ors.add("ii.\"productId\" IN (" + PosSql.marks(allowedProducts.size()) + ")");
                params.addAll(allowedProducts);
            }
            if (!allowedMenuItems.isEmpty()) {
                ors.add("ii.\"menuItemId\" IN (" + PosSql.marks(allowedMenuItems.size()) + ")");
                params.addAll(allowedMenuItems);
            }
            sql.append(" AND (").append(String.join(" OR ", ors)).append(")");
        }
        sql.append(" GROUP BY ii.\"productId\", ii.\"menuItemId\"");

        record Row(String key, String[] ids, BigDecimal[] qt) {}
        Map<String, Row> merged = new LinkedHashMap<>();
        for (Map<String, Object> g : jdbc.sql(sql.toString()).params(params).query().listOfRows()) {
            String productId = (String) g.get("productId");
            String menuItemId = (String) g.get("menuItemId");
            String key = menuItemId != null ? menuItemId : productId;
            if (key == null) {
                continue;
            }
            Row cur = merged.computeIfAbsent(key, k -> new Row(k, new String[] {productId, menuItemId},
                    new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO}));
            if (cur.ids()[0] == null) {
                cur.ids()[0] = productId;
            }
            if (cur.ids()[1] == null) {
                cur.ids()[1] = menuItemId;
            }
            BigDecimal q = dec(g.get("q"));
            BigDecimal tot = dec(g.get("t"));
            BigDecimal refunded = dec(g.get("rq"));
            cur.qt()[0] = cur.qt()[0].add(q).subtract(refunded);
            BigDecimal unitAvg = q.signum() > 0 ? Money.div(tot, q) : BigDecimal.ZERO;
            cur.qt()[1] = cur.qt()[1].add(tot).subtract(unitAvg.multiply(refunded));
        }
        Set<String> productIds = new LinkedHashSet<>();
        Set<String> menuItemIds = new LinkedHashSet<>();
        merged.values().forEach(row -> {
            if (row.ids()[0] != null) {
                productIds.add(row.ids()[0]);
            }
            if (row.ids()[1] != null) {
                menuItemIds.add(row.ids()[1]);
            }
        });
        Map<String, Map<String, Object>> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            List<Object> p = new ArrayList<>(List.of(orgId));
            p.addAll(productIds);
            jdbc.sql("SELECT id, name, sku FROM \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND id IN ("
                    + PosSql.marks(productIds.size()) + ")").params(p).query().listOfRows()
                    .forEach(m -> products.put((String) m.get("id"), m));
        }
        Map<String, Object> menuNames = namesById("MenuItem", menuItemIds, true);
        List<Row> rows = new ArrayList<>(merged.values());
        rows.sort((a, b) -> b.qt()[1].compareTo(a.qt()[1]));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row row : rows.subList(0, Math.min(cap, rows.size()))) {
            Map<String, Object> p = row.ids()[0] == null ? null : products.get(row.ids()[0]);
            Object mi = row.ids()[1] == null ? null : menuNames.get(row.ids()[1]);
            Object name = mi != null ? mi : p != null && p.get("name") != null ? p.get("name") : "(deleted item)";
            out.add(Obj.of("productId", row.key(), "name", name, "sku", p == null ? null : p.get("sku"),
                    "quantity", PosSql.js(row.qt()[0]), "total", fx(row.qt()[1])));
        }
        return out;
    }

    // ───────────────────────────── row reports ─────────────────────────────

    private Map<String, String> waiterNames(List<Map<String, Object>> invoices, boolean punchers) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> inv : invoices) {
            if (PosSql.truthy(inv.get("waiterId"))) {
                ids.add((String) inv.get("waiterId"));
            }
            if (punchers) {
                for (Map<String, Object> it : PosOrdersService.maps(inv.get("items"))) {
                    if (PosSql.truthy(it.get("punchedById"))) {
                        ids.add((String) it.get("punchedById"));
                    }
                }
            }
        }
        return userNames(ids, false);
    }

    private static Object name(Map<String, String> names, Object id) {
        return id == null ? null : names.get((String) id);
    }

    private static Object orderField(Map<String, Map<String, Object>> orders, Map<String, Object> inv, String field,
            Object fallback) {
        Map<String, Object> o = inv.get("orderId") == null ? null : orders.get((String) inv.get("orderId"));
        Object v = o == null ? null : o.get(field);
        return v == null ? fallback : v;
    }

    public List<Map<String, Object>> salesReport(String fromDate, String toDate, String waiterId, String search,
            String paymentMethod, String orderType) {
        Range r = parseRange(fromDate, toDate);
        List<Map<String, Object>> invoices = invoices(r, Filters.of(waiterId, paymentMethod, orderType, search),
                "i.\"createdAt\" ASC");
        Map<String, Map<String, Object>> orders = orders(invoices);
        Map<String, String> waiters = waiterNames(invoices, false);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> inv : invoices) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", inv.get("id"));
            m.put("orderNumber", orderField(orders, inv, "orderNumber", "—"));
            m.put("orderType", orderField(orders, inv, "orderType", null));
            m.put("invoiceNumber", inv.get("invoiceNumber"));
            m.put("saleDate", iso(saleDate(inv)));
            m.put("time", timeString(instant(saleDate(inv))));
            m.put("subtotal", fx(dec(inv.get("subtotal"))));
            m.put("discount", fx(dec(inv.get("discountTotal"))));
            m.put("discountType", inv.get("discountType") == null ? "percentage" : inv.get("discountType"));
            m.put("discountValue", fx(dec(inv.get("discountValue"))));
            m.put("discountReason", inv.get("discountReason"));
            m.put("tax", fx(dec(inv.get("taxAmount"))));
            m.put("totalAmount", fx(dec(inv.get("totalAmount"))));
            m.put("amountPaid", fx(dec(inv.get("amountPaid"))));
            m.put("amountRefunded", fx(dec(inv.get("amountRefunded"))));
            m.put("netAmount", fx(dec(inv.get("totalAmount")).subtract(dec(inv.get("amountRefunded")))));
            m.put("status", inv.get("status"));
            m.put("paymentMethod", inv.get("paymentMode"));
            m.put("waiterName", name(waiters, inv.get("waiterId")));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> cashierReport(String fromDate, String toDate, String waiterId, String search,
            String paymentMethod, String orderType) {
        Range r = parseRange(fromDate, toDate);
        List<Map<String, Object>> invoices = invoices(r, Filters.of(waiterId, paymentMethod, orderType, search),
                "i.\"createdAt\" DESC");
        Map<String, Map<String, Object>> orders = orders(invoices);
        Map<String, String> cashiers = waiterNames(invoices, false);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> inv : invoices) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cashierName", name(cashiers, inv.get("waiterId")));
            m.put("orderNumber", orderField(orders, inv, "orderNumber", "—"));
            m.put("orderType", orderField(orders, inv, "orderType", null));
            m.put("invoiceNumber", inv.get("invoiceNumber"));
            m.put("saleDate", iso(saleDate(inv)));
            m.put("time", timeString(instant(saleDate(inv))));
            m.put("status", inv.get("status"));
            m.put("amountRefunded", fx(dec(inv.get("amountRefunded"))));
            m.put("salesAmount", fx(dec(inv.get("totalAmount"))));
            m.put("discount", fx(dec(inv.get("discountTotal"))));
            m.put("discountType", inv.get("discountType") == null ? "percentage" : inv.get("discountType"));
            m.put("discountValue", fx(dec(inv.get("discountValue"))));
            m.put("discountReason", inv.get("discountReason"));
            m.put("paymentMethod", inv.get("paymentMode"));
            m.put("received", fx(dec(inv.get("amountPaid"))));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> cashierShiftSummary(String fromDate, String toDate, String cashierId,
            String registerId, String status) {
        String orgId = org();
        Range r = parseRange(fromDate, toDate);
        StringBuilder w = new StringBuilder("\"organizationId\" = ? AND \"openedAt\" >= ? AND \"openedAt\" <= ?");
        List<Object> params = new ArrayList<>(List.of(orgId, Timestamp.from(r.start()), Timestamp.from(r.end())));
        if (cashierId != null) {
            w.append(" AND \"userId\" = ?");
            params.add(cashierId);
        }
        if (registerId != null) {
            w.append(" AND \"cashRegisterId\" = ?");
            params.add(registerId);
        }
        if (status != null) {
            w.append(" AND status = ?");
            params.add(status);
        }
        List<Map<String, Object>> sessions = SqlRows.list(CashSession.class, jdbc.sql("SELECT * FROM \"CashSession\" WHERE "
                + w + " ORDER BY \"openedAt\" DESC").params(params));
        Map<String, Object[]> tender = new HashMap<>();
        Set<String> userIds = new LinkedHashSet<>();
        for (Map<String, Object> s : sessions) {
            if (PosSql.truthy(s.get("userId"))) {
                userIds.add((String) s.get("userId"));
            }
        }
        List<Object> sessionIds = sessions.stream().map(s -> s.get("id")).toList();
        Map<String, List<Map<String, Object>>> movements = new HashMap<>();
        Map<String, Map<String, Object>> registers = new HashMap<>();
        for (List<Object> chunk : chunks(new ArrayList<>(sessionIds))) {
            List<Object> p = new ArrayList<>(List.of(orgId));
            p.addAll(chunk);
            for (Map<String, Object> inv : jdbc.sql("SELECT \"cashSessionId\", \"totalAmount\" FROM \"Invoice\" WHERE"
                    + " \"organizationId\" = ? AND \"orderId\" IS NOT NULL AND status::text IN ('posted', 'paid', 'refunded')"
                    + " AND \"cashSessionId\" IN (" + PosSql.marks(chunk.size()) + ")").params(p).query().listOfRows()) {
                if (inv.get("cashSessionId") == null) {
                    continue;
                }
                Object[] cur = tender.computeIfAbsent((String) inv.get("cashSessionId"), k -> new Object[] {
                        BigDecimal.ZERO, 0L});
                cur[0] = ((BigDecimal) cur[0]).add(dec(inv.get("totalAmount")));
                cur[1] = (long) cur[1] + 1;
            }
            for (Map<String, Object> m : jdbc.sql("SELECT \"cashSessionId\", \"movementType\"::text AS \"movementType\", amount"
                    + " FROM \"CashMovement\" WHERE \"cashSessionId\" IN (" + PosSql.marks(chunk.size()) + ")")
                    .params(chunk).query().listOfRows()) {
                movements.computeIfAbsent((String) m.get("cashSessionId"), k -> new ArrayList<>()).add(m);
            }
        }
        Set<Object> registerIds = new LinkedHashSet<>();
        sessions.forEach(s -> registerIds.add(s.get("cashRegisterId")));
        for (List<Object> chunk : chunks(new ArrayList<>(registerIds))) {
            jdbc.sql("SELECT id, code, name FROM \"CashRegister\" WHERE id IN (" + PosSql.marks(chunk.size()) + ")")
                    .params(chunk).query().listOfRows().forEach(g -> registers.put((String) g.get("id"), g));
        }
        Map<String, String> users = userNames(userIds, false);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : sessions) {
            Map<String, Object> reg = registers.get((String) s.get("cashRegisterId"));
            Object registerLabel = reg != null ? reg.get("code") + " - " + reg.get("name") : s.get("cashRegisterId");
            Instant openedAt = s.get("openedAt") == null ? null : instant(s.get("openedAt"));
            String shift = registerLabel + " · " + (openedAt == null ? "Invalid Date" : dateString(openedAt)) + " "
                    + (openedAt == null ? "Invalid Date" : shortTime(openedAt));
            BigDecimal cash = BigDecimal.ZERO;
            BigDecimal refunds = BigDecimal.ZERO;
            BigDecimal payIns = BigDecimal.ZERO;
            BigDecimal payOuts = BigDecimal.ZERO;
            BigDecimal adjustments = BigDecimal.ZERO;
            for (Map<String, Object> m : movements.getOrDefault((String) s.get("id"), List.of())) {
                BigDecimal amt = dec(m.get("amount"));
                switch (String.valueOf(m.get("movementType"))) {
                    case "sale" -> cash = cash.add(amt);
                    case "refund" -> refunds = refunds.add(amt);
                    case "pay_in" -> payIns = payIns.add(amt);
                    case "pay_out", "supplier_payment" -> payOuts = payOuts.add(amt);
                    case "adjustment" -> adjustments = adjustments.add(amt);
                    default -> { }
                }
            }
            BigDecimal opening = dec(s.get("openingFloat"));
            BigDecimal expected = opening.add(cash).subtract(refunds).add(payIns).subtract(payOuts).add(adjustments);
            BigDecimal actual = s.get("closingCounted") == null ? null : dec(s.get("closingCounted"));
            BigDecimal diff = actual == null ? null : actual.subtract(expected);
            Object[] t = tender.get((String) s.get("id"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shift", shift);
            m.put("sessionId", s.get("id"));
            m.put("registerName", registerLabel);
            m.put("openedAt", iso(s.get("openedAt")));
            m.put("closedAt", iso(s.get("closedAt")));
            m.put("status", s.get("status"));
            m.put("cashierName", s.get("userId") == null ? null : users.get((String) s.get("userId")));
            m.put("openingCash", fx(opening));
            m.put("sales", fx(cash));
            m.put("cashSales", fx(cash));
            m.put("totalSales", fx(t == null ? BigDecimal.ZERO : (BigDecimal) t[0]));
            m.put("saleCount", t == null ? 0 : t[1]);
            m.put("cashRefunds", fx(refunds));
            m.put("payIns", fx(payIns));
            m.put("payOuts", fx(payOuts));
            m.put("adjustments", fx(adjustments));
            m.put("expectedCash", fx(expected));
            m.put("actualCash", actual == null ? null : fx(actual));
            m.put("difference", diff == null ? null : fx(diff));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> waiterReport(String fromDate, String toDate, String waiterId, String orderType,
            String search, String paymentMethod) {
        Range r = parseRange(fromDate, toDate);
        List<Map<String, Object>> invoices = invoices(r, Filters.of(waiterId, paymentMethod, orderType, search),
                "i.\"createdAt\" ASC");
        attachItems(invoices);
        Map<String, Map<String, Object>> orders = orders(invoices);
        Set<String> tableIds = new LinkedHashSet<>();
        for (Map<String, Object> inv : invoices) {
            Object t = orderField(orders, inv, "tableId", null);
            if (t != null) {
                tableIds.add((String) t);
            }
        }
        Map<String, String> waiters = waiterNames(invoices, false);
        Map<String, Object> tables = namesById("PosTable", tableIds, false);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> inv : invoices) {
            Object tableId = orderField(orders, inv, "tableId", null);
            String invoiceTime = timeString(instant(saleDate(inv)));
            for (Map<String, Object> it : PosOrdersService.maps(inv.get("items"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("waiterName", name(waiters, inv.get("waiterId")));
                m.put("orderNumber", orderField(orders, inv, "orderNumber", "—"));
                m.put("orderType", orderField(orders, inv, "orderType", null));
                m.put("tableName", tableId == null ? null : tables.get((String) tableId));
                m.put("item", it.get("description"));
                m.put("quantity", fx(dec(it.get("quantity"))));
                m.put("unitPrice", fx(dec(it.get("unitPrice"))));
                m.put("discountPercent", fx(dec(it.get("discountPercent"))));
                m.put("discountType", it.get("discountType") == null ? "percentage" : it.get("discountType"));
                m.put("discountAmount", fx(dec(it.get("discountAmount"))));
                m.put("discountReason", it.get("discountReason"));
                m.put("total", fx(dec(it.get("total"))));
                m.put("date", iso(saleDate(inv)));
                m.put("time", invoiceTime);
                rows.add(m);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> orderReport(String fromDate, String toDate, String orderType, String status,
            String waiterId, String search, boolean includeCancelled) {
        String orgId = org();
        Range r = parseRange(fromDate, toDate);
        if (orderType != null && !ORDER_TYPES.contains(orderType)) {
            throw ApiException.badRequest("Unknown orderType \"" + orderType + "\"");
        }
        String q = search == null ? null : search.trim();
        if (status != null && !OrderStatusUtil.ACCEPTED.contains(status)) {
            throw ApiException.badRequest("Unknown order status \"" + status + "\"");
        }
        String canonical = status == null ? null : OrderStatusUtil.toCanonical(status);
        StringBuilder w = new StringBuilder("\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (canonical != null) {
            w.append(" AND status = ?");
            params.add(canonical);
        } else if (!includeCancelled) {
            w.append(" AND status <> 'cancelled'");
        }
        if (orderType != null) {
            w.append(" AND \"orderType\" = ?");
            params.add(orderType);
        }
        if (waiterId != null) {
            w.append(" AND \"waiterId\" = ?");
            params.add(waiterId);
        }
        if (q != null && !q.isEmpty()) {
            w.append(" AND \"orderNumber\" ILIKE ?");
            params.add(PosSql.contains(q));
        }
        w.append(" AND \"createdAt\" >= ? AND \"createdAt\" <= ?");
        params.add(Timestamp.from(r.start()));
        params.add(Timestamp.from(r.end()));
        List<Map<String, Object>> orders = SqlRows.list(com.inventoryerp.pos.domain.Order.class, jdbc.sql(
                "SELECT * FROM \"Order\" WHERE " + w + " ORDER BY \"createdAt\" DESC").params(params));
        Set<String> tableIds = new LinkedHashSet<>();
        Set<String> waiterIds = new LinkedHashSet<>();
        Set<String> partnerIds = new LinkedHashSet<>();
        for (Map<String, Object> o : orders) {
            if (PosSql.truthy(o.get("tableId"))) {
                tableIds.add((String) o.get("tableId"));
            }
            if (PosSql.truthy(o.get("waiterId"))) {
                waiterIds.add((String) o.get("waiterId"));
            }
            if (PosSql.truthy(o.get("partnerId"))) {
                partnerIds.add((String) o.get("partnerId"));
            }
        }
        Map<String, Object> tables = namesById("PosTable", tableIds, false);
        Map<String, String> waiters = userNames(waiterIds, false);
        Map<String, Object> partners = namesById("Partner", partnerIds, true);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : orders) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNumber", o.get("orderNumber"));
            m.put("orderType", o.get("orderType"));
            m.put("time", timeString(instant(o.get("createdAt"))));
            m.put("date", iso(o.get("createdAt")));
            m.put("tableName", o.get("tableId") == null ? null : tables.get((String) o.get("tableId")));
            m.put("waiterName", o.get("waiterId") == null ? null : waiters.get((String) o.get("waiterId")));
            m.put("customerName", o.get("partnerId") == null ? null : partners.get((String) o.get("partnerId")));
            m.put("status", o.get("status"));
            m.put("totalAmount", fx(dec(o.get("totalAmount"))));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> soldItems(String fromDate, String toDate, String categoryId, String waiterId,
            String orderType, String search, String itemSearch, String paymentMethod) {
        Range r = parseRange(fromDate, toDate);
        List<Map<String, Object>> invoices = invoices(r, Filters.of(waiterId, paymentMethod, orderType, search),
                "i.\"createdAt\" ASC");
        attachItems(invoices);
        Map<String, Map<String, Object>> orders = orders(invoices);
        String needle = needle(itemSearch);
        Map<String, String> waiters = waiterNames(invoices, true);
        Resolver cat = new Resolver(allItems(invoices));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> inv : invoices) {
            String invoiceTime = timeString(instant(saleDate(inv)));
            for (Map<String, Object> it : PosOrdersService.maps(inv.get("items"))) {
                SaleCategory c = cat.resolve(it);
                if (categoryId != null && !matchesCategory(c, categoryId)) {
                    continue;
                }
                if (needle != null && !String.valueOf(it.get("description") == null ? "" : it.get("description"))
                        .toLowerCase().contains(needle)) {
                    continue;
                }
                Object servedBy = it.get("punchedByName");
                if (servedBy == null) {
                    servedBy = name(waiters, it.get("punchedById"));
                }
                if (servedBy == null) {
                    servedBy = name(waiters, inv.get("waiterId"));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderNumber", orderField(orders, inv, "orderNumber", "—"));
                m.put("orderType", orderField(orders, inv, "orderType", null));
                m.put("invoiceNumber", inv.get("invoiceNumber"));
                m.put("saleDate", iso(saleDate(inv)));
                m.put("time", invoiceTime);
                m.put("item", it.get("description"));
                m.put("unitPrice", fx(dec(it.get("unitPrice"))));
                m.put("discountPercent", fx(dec(it.get("discountPercent"))));
                m.put("discountType", it.get("discountType") == null ? "percentage" : it.get("discountType"));
                m.put("discountAmount", fx(dec(it.get("discountAmount"))));
                m.put("discountReason", it.get("discountReason"));
                m.put("quantity", fx(dec(it.get("quantity"))));
                m.put("totalAmount", fx(dec(it.get("total"))));
                m.put("waiterName", name(waiters, inv.get("waiterId")));
                m.put("servedBy", servedBy);
                m.put("categoryName", c.name());
                rows.add(m);
            }
        }
        return rows;
    }

    private static String needle(String s) {
        if (s == null) {
            return null;
        }
        String n = s.trim().toLowerCase();
        return n.isEmpty() ? null : n;
    }

    // ───────────────────────────── items by server ─────────────────────────────

    public Map<String, Object> itemsByServer(String fromDate, String toDate, String groupBy, Filters saleFilters,
            String categoryId, String itemSearch) {
        Range r = parseRange(fromDate, toDate);
        if (groupBy == null || !List.of("day", "shift", "none").contains(groupBy)) {
            throw ApiException.badRequest("Unknown groupBy \"" + (groupBy == null ? "undefined" : groupBy) + "\"");
        }
        List<Map<String, Object>> invoices = invoices(r, saleFilters, "i.\"issueDate\" ASC");
        attachItems(invoices);
        Set<String> staffIds = new LinkedHashSet<>();
        Set<String> sessionIds = new LinkedHashSet<>();
        for (Map<String, Object> inv : invoices) {
            if (PosSql.truthy(inv.get("waiterId"))) {
                staffIds.add((String) inv.get("waiterId"));
            }
            if (PosSql.truthy(inv.get("cashSessionId"))) {
                sessionIds.add((String) inv.get("cashSessionId"));
            }
            for (Map<String, Object> it : PosOrdersService.maps(inv.get("items"))) {
                if (PosSql.truthy(it.get("punchedById"))) {
                    staffIds.add((String) it.get("punchedById"));
                }
            }
        }
        Map<String, String> staffName = userNames(staffIds, true);
        Map<String, String> sessionLabel = new HashMap<>();
        if ("shift".equals(groupBy) && !sessionIds.isEmpty()) {
            List<Object> p = new ArrayList<>(List.of(org()));
            p.addAll(sessionIds);
            for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT id, \"openedAt\", \"closedAt\" FROM \"CashSession\""
                    + " WHERE \"organizationId\" = ? AND id IN (" + PosSql.marks(sessionIds.size()) + ")").params(p))) {
                Instant opened = instant(c.get("openedAt"));
                sessionLabel.put((String) c.get("id"), localIso(opened) + " " + timeString(opened)
                        + (c.get("closedAt") != null ? "" : " (open)"));
            }
        }
        Resolver cat = new Resolver(allItems(invoices));
        String needle = needle(itemSearch);

        final class Row {
            String periodKey;
            String periodLabel;
            Object serverId;
            Object serverName;
            Object item;
            String categoryName;
            BigDecimal quantity = BigDecimal.ZERO;
            BigDecimal gross = BigDecimal.ZERO;
            BigDecimal discount = BigDecimal.ZERO;
            BigDecimal net = BigDecimal.ZERO;
            Set<Object> orderIds = new LinkedHashSet<>();
        }
        final class Server {
            Object serverId;
            Object serverName;
            BigDecimal quantity = BigDecimal.ZERO;
            BigDecimal gross = BigDecimal.ZERO;
            Set<Object> items = new LinkedHashSet<>();
            Set<Object> orderIds = new LinkedHashSet<>();
        }
        Map<String, Row> rows = new LinkedHashMap<>();
        Map<String, Server> servers = new LinkedHashMap<>();
        for (Map<String, Object> inv : invoices) {
            Instant when = instant(saleDate(inv));
            Object sessionId = inv.get("cashSessionId");
            String periodKey = "none".equals(groupBy) ? "all" : "day".equals(groupBy) ? localIso(when)
                    : sessionId != null ? (String) sessionId : "no-shift";
            String periodLabel = "none".equals(groupBy) ? "All" : "day".equals(groupBy) ? localIso(when)
                    : sessionId != null ? sessionLabel.getOrDefault((String) sessionId, (String) sessionId) : "No shift";
            for (Map<String, Object> it : PosOrdersService.maps(inv.get("items"))) {
                SaleCategory c = cat.resolve(it);
                if (categoryId != null && !matchesCategory(c, categoryId)) {
                    continue;
                }
                if (needle != null && !String.valueOf(it.get("description") == null ? "" : it.get("description"))
                        .toLowerCase().contains(needle)) {
                    continue;
                }
                Object serverId = it.get("punchedById") != null ? it.get("punchedById") : inv.get("waiterId");
                Object serverName = it.get("punchedByName");
                if (serverName == null) {
                    serverName = name(staffName, serverId);
                }
                if (serverName == null) {
                    serverName = "Unattributed";
                }
                BigDecimal gross = dec(it.get("unitPrice")).multiply(dec(it.get("quantity")));
                BigDecimal discount = "fixed_amount".equals(it.get("discountType")) ? dec(it.get("discountAmount"))
                        : Money.div(gross.multiply(dec(it.get("discountPercent"))), BigDecimal.valueOf(100));
                String key = periodKey + "|" + (serverId == null ? "-" : serverId) + "|" + it.get("description");
                Object sn = serverName;
                Row cur = rows.computeIfAbsent(key, k -> {
                    Row n = new Row();
                    n.periodKey = periodKey;
                    n.periodLabel = periodLabel;
                    n.serverId = serverId;
                    n.serverName = sn;
                    n.item = it.get("description");
                    n.categoryName = c.name();
                    return n;
                });
                cur.quantity = cur.quantity.add(dec(it.get("quantity")));
                cur.gross = cur.gross.add(gross);
                cur.discount = cur.discount.add(discount);
                cur.net = cur.net.add(dec(it.get("total")));
                cur.orderIds.add(inv.get("id"));
                String sKey = serverId == null ? "-" : (String) serverId;
                Server s = servers.computeIfAbsent(sKey, k -> {
                    Server n = new Server();
                    n.serverId = serverId;
                    n.serverName = sn;
                    return n;
                });
                s.quantity = s.quantity.add(dec(it.get("quantity")));
                s.gross = s.gross.add(dec(it.get("total")));
                s.items.add(it.get("description"));
                s.orderIds.add(inv.get("id"));
            }
        }
        List<Row> sorted = new ArrayList<>(rows.values());
        sorted.sort((a, b) -> {
            int c = localeCompare(a.periodKey, b.periodKey);
            if (c != 0) {
                return c;
            }
            c = localeCompare(String.valueOf(a.serverName), String.valueOf(b.serverName));
            return c != 0 ? c : b.net.compareTo(a.net);
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row row : sorted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("periodKey", row.periodKey);
            m.put("periodLabel", row.periodLabel);
            m.put("serverId", row.serverId);
            m.put("serverName", row.serverName);
            m.put("item", row.item);
            m.put("categoryName", row.categoryName);
            m.put("quantity", fx(row.quantity));
            m.put("grossAmount", fx(row.gross));
            m.put("discountAmount", fx(row.discount));
            m.put("totalAmount", fx(row.net));
            m.put("orderCount", row.orderIds.size());
            out.add(m);
        }
        List<Server> ss = new ArrayList<>(servers.values());
        ss.sort((a, b) -> b.gross.compareTo(a.gross));
        List<Map<String, Object>> serverRows = new ArrayList<>();
        for (Server s : ss) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("serverId", s.serverId);
            m.put("serverName", s.serverName);
            m.put("quantity", fx(s.quantity));
            m.put("totalAmount", fx(s.gross));
            m.put("distinctItems", s.items.size());
            m.put("orderCount", s.orderIds.size());
            serverRows.add(m);
        }
        return Obj.of("groupBy", groupBy, "rows", out, "servers", serverRows);
    }

    // ───────────────────────────── items by group / item sales ─────────────────────────────

    public List<Map<String, Object>> itemsByGroup(String fromDate, String toDate, String orderType, String waiterId,
            String categoryId, String paymentMethod) {
        Range r = parseRange(fromDate, toDate);
        List<Map<String, Object>> invoices = invoices(r, Filters.of(waiterId, paymentMethod, orderType, null), null);
        attachItems(invoices);
        Resolver cat = new Resolver(allItems(invoices));
        Map<String, Object[]> groups = new LinkedHashMap<>();
        for (Map<String, Object> inv : invoices) {
            for (Map<String, Object> it : PosOrdersService.maps(inv.get("items"))) {
                SaleCategory c = cat.resolve(it);
                if (categoryId != null && !matchesCategory(c, categoryId)) {
                    continue;
                }
                String key = c.key() != null ? c.key() : c.name();
                Object[] b = groups.computeIfAbsent(key, k -> new Object[] {c.name(), c.key(), BigDecimal.ZERO,
                        BigDecimal.ZERO, 0L});
                b[2] = ((BigDecimal) b[2]).add(dec(it.get("quantity")));
                b[3] = ((BigDecimal) b[3]).add(dec(it.get("total")));
                b[4] = (long) b[4] + 1;
            }
        }
        List<Object[]> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> ((BigDecimal) b[3]).compareTo((BigDecimal) a[3]));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] g : sorted) {
            out.add(Obj.of("groupId", g[1], "groupName", g[0], "totalQuantity", fx((BigDecimal) g[2]),
                    "totalAmount", fx((BigDecimal) g[3]), "itemCount", g[4]));
        }
        return out;
    }

    public Map<String, Object> itemSales(String fromDate, String toDate, String itemKey, String categoryId,
            String waiterId, String orderType, String paymentMethod, String itemSearch) {
        Range r = parseRange(fromDate, toDate);
        List<Map<String, Object>> invoices = invoices(r, Filters.of(null, paymentMethod, orderType, null), null);
        attachItems(invoices);
        String needle = needle(itemSearch);
        Map<String, String> waiterMap = waiterNames(invoices, false);
        Resolver cat = new Resolver(allItems(invoices));
        Map<String, Object[]> buckets = new LinkedHashMap<>();
        Map<String, Object> itemOptions = new LinkedHashMap<>();
        Map<String, Object> waiterOptions = new LinkedHashMap<>();
        Map<String, Object> categoryOptions = new LinkedHashMap<>();
        for (Map<String, Object> inv : invoices) {
            String invWaiterId = (String) inv.get("waiterId");
            if (PosSql.truthy(invWaiterId)) {
                waiterOptions.put(invWaiterId, waiterMap.getOrDefault(invWaiterId, "—"));
            }
            for (Map<String, Object> it : PosOrdersService.maps(inv.get("items"))) {
                String key = it.get("menuItemId") != null ? (String) it.get("menuItemId")
                        : it.get("productId") != null ? (String) it.get("productId") : "desc:" + it.get("description");
                Object resolved = PosSql.truthy(it.get("menuItemId")) ? cat.menuItemName((String) it.get("menuItemId"))
                        : PosSql.truthy(it.get("productId")) ? cat.productName((String) it.get("productId")) : null;
                Object name = resolved != null ? resolved : it.get("description");
                SaleCategory c = cat.resolve(it);
                itemOptions.put(key, name);
                if (c.key() != null) {
                    categoryOptions.put(c.key(), c.name());
                }
                if (itemKey != null && !key.equals(itemKey)) {
                    continue;
                }
                if (categoryId != null && !matchesCategory(c, categoryId)) {
                    continue;
                }
                if (waiterId != null && !waiterId.equals(invWaiterId)) {
                    continue;
                }
                if (needle != null && !String.valueOf(name == null ? "" : name).toLowerCase().contains(needle)) {
                    continue;
                }
                Object[] b = buckets.computeIfAbsent(key, k -> new Object[] {k, name, c.key(), c.name(), BigDecimal.ZERO,
                        BigDecimal.ZERO});
                b[4] = ((BigDecimal) b[4]).add(dec(it.get("quantity")));
                b[5] = ((BigDecimal) b[5]).add(dec(it.get("total")));
            }
        }
        List<Object[]> sorted = new ArrayList<>(buckets.values());
        sorted.sort((a, b) -> ((BigDecimal) b[5]).compareTo((BigDecimal) a[5]));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] b : sorted) {
            BigDecimal q = (BigDecimal) b[4];
            BigDecimal t = (BigDecimal) b[5];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemKey", b[0]);
            m.put("item", b[1]);
            m.put("categoryId", b[2]);
            m.put("categoryName", b[3]);
            m.put("quantity", fx(q));
            m.put("unitPrice", q.signum() == 0 ? "0.00" : fx(Money.div(t, q)));
            m.put("totalAmount", fx(t));
            rows.add(m);
        }
        return Obj.of("rows", rows, "filters", Obj.of(
                "items", options(itemOptions, "key"),
                "waiters", options(waiterOptions, "id"),
                "categories", options(categoryOptions, "id")));
    }

    private static List<Map<String, Object>> options(Map<String, Object> source, String idKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        source.forEach((k, v) -> out.add(Obj.of(idKey, k, "name", v)));
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("name")), PosReportsService::localeCompare));
        return out;
    }
}
