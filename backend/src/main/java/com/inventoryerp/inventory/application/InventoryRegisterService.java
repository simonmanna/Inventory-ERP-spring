package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryQueryService.notBlank;
import static com.inventoryerp.inventory.application.InventoryReportsService.localIso;
import static com.inventoryerp.inventory.application.InventoryReportsService.parseLocalDay;
import static com.inventoryerp.kernel.common.JsNumbers.js;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Movement registers with analytics (port of {@code inventory-register.service.ts}): stock_in, stock_out,
 * damages, adjustments, transfers and all — ledger lines enriched from their source documents, with summary,
 * previous-period comparison, trend and breakdowns. Classification, filters, breakdowns, sorting and the row cap run in
 * SQL (one statement for every breakdown, one for the capped rows, one for the previous-period totals), so a two-year
 * window never loads the ledger into memory.
 */
@Service
@Transactional(readOnly = true)
public class InventoryRegisterService {

    static final List<String> KINDS = List.of("stock_in", "stock_out", "damages", "adjustments", "transfers", "all");

    private static final Map<String, List<String>> KIND_TYPES = Map.of(
            "stock_in", List.of("receipt", "return_in", "production_output", "opening_balance"),
            "stock_out", List.of("issue", "production_consume", "internal_use", "promo_sample", "return_to_supplier"),
            "damages", List.of("waste", "expiry_write_off", "adjustment_out", "internal_use", "promo_sample", "issue"),
            "adjustments", List.of("adjustment_in", "adjustment_out"),
            "transfers", List.of("transfer_out", "transfer_in"),
            "all", List.of());

    private static final Set<String> DAMAGE_REASONS = Set.of("damaged", "expired", "theft");

    private static final Map<String, String> SOURCE_LABELS = Map.ofEntries(
            Map.entry("pos_invoice", "POS sale"), Map.entry("pos_refund", "POS refund"),
            Map.entry("menu_recipe", "Recipe consumption"), Map.entry("goods_receipt", "Purchase receipt (GRN)"),
            Map.entry("direct_stock_in", "Direct stock in"), Map.entry("direct_stock_out", "Direct stock out"),
            Map.entry("stock_out", "Stock-out document"), Map.entry("waste", "Waste / damage record"),
            Map.entry("stock_adjust", "Stock adjustment"), Map.entry("stock_adjustment", "Stock adjustment"),
            Map.entry("stock_transfer", "Stock transfer"), Map.entry("repair_part_issue", "Repair part issue"),
            Map.entry("production", "Production"), Map.entry("opening_backfill", "Opening balance (backfill)"),
            Map.entry("manual", "Manual / unreferenced"), Map.entry("opening_balance", "Opening balance"));

    private static final Map<String, String> TYPE_LABELS = Map.ofEntries(
            Map.entry("receipt", "Receipt"), Map.entry("return_in", "Customer return"),
            Map.entry("production_output", "Production output"), Map.entry("opening_balance", "Opening balance"),
            Map.entry("issue", "Issue / sale"), Map.entry("production_consume", "Production consume"),
            Map.entry("internal_use", "Internal use"), Map.entry("promo_sample", "Promo / sample"),
            Map.entry("return_to_supplier", "Return to supplier"), Map.entry("waste", "Waste"),
            Map.entry("expiry_write_off", "Expiry write-off"), Map.entry("adjustment_in", "Adjustment in"),
            Map.entry("adjustment_out", "Adjustment out"), Map.entry("transfer_in", "Transfer in"),
            Map.entry("transfer_out", "Transfer out"));

    private static final String[] WEEKDAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final Pattern ADJ = Pattern.compile("^(ADJ-[\\w-]+)(?:\\s*·\\s*(\\w+))?");

    /** Rows returned by {@link #register(String, Map)}; summary, trend and breakdowns always cover every line. */
    public static final int ROW_CAP = 5000;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal PARETO_SHARE = new BigDecimal("0.8");

    /** Aggregates of one group (every branch of the breakdown query carries the same columns). */
    private static final String AGG = "count(*) AS n, count(DISTINCT doc_key) AS docs,"
            + " COALESCE(sum(lq), 0) AS qty, COALESCE(sum(abs(lq)), 0) AS aqty,"
            + " COALESCE(sum(lv), 0) AS val, COALESCE(sum(abs(lv)), 0) AS aval, min(seq) AS seq,"
            + " COALESCE(sum(lv) FILTER (WHERE lv >= 0), 0) AS gain, COALESCE(sum(-lv) FILTER (WHERE lv < 0), 0) AS loss,"
            + " count(DISTINCT product_id) AS items, count(DISTINCT location_id) AS locs,"
            + " count(*) FILTER (WHERE responsible_id IS NULL) AS unassigned,"
            + " count(*) FILTER (WHERE approved_by IS NULL) AS unapproved,"
            + " COALESCE(sum(lq) FILTER (WHERE lq > 0), 0) AS gqty, COALESCE(sum(lv) FILTER (WHERE lq > 0), 0) AS gval,"
            + " COALESCE(sum(-lq) FILTER (WHERE lq < 0), 0) AS lqty, COALESCE(sum(-lv) FILTER (WHERE lq < 0), 0) AS lval";

    private static final String NO_EXTRAS = "NULL::text AS x1, NULL::text AS x2, NULL::text AS x3, NULL::text AS x4";

    private final JdbcClient jdbc;
    private final InventoryReportsService reports;
    private final LocationScope scope;

    public InventoryRegisterService(JdbcClient jdbc, InventoryReportsService reports, LocationScope scope) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.scope = scope;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String humanize(String s) {
        String t = s.replace('_', ' ');
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static ZonedDateTime local(Instant i) {
        return i.atZone(BusinessZone.get());
    }

    /** Register of one kind over local days [start, end] (default: from the first movement, max 2 years). */
    public Map<String, Object> register(String kind, Map<String, String> q) {
        return register(kind, q, ROW_CAP);
    }

    /**
     * Register of one kind with at most {@code rowCap} rows (newest first, {@code truncated} when more lines match).
     * Classification, filters, breakdowns, sorting and the cap all run in SQL: no ledger window is loaded into memory.
     */
    public Map<String, Object> register(String kind, Map<String, String> q, int rowCap) {
        if (!KINDS.contains(kind)) {
            throw ApiException.badRequest("Unknown report: " + kind);
        }
        scope.assertUnrestricted();
        Instant end = parseLocalDay(q.get("end"), false);
        if (end == null) {
            end = parseLocalDay(localIso(Instant.now()), false);
        }
        Instant start = parseLocalDay(q.get("start"), true);
        if (start == null) {
            Instant first = jdbc.sql("SELECT \"createdAt\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                            + " ORDER BY \"createdAt\" ASC LIMIT 1").param(org())
                    .query((rs, n) -> SqlRows.value(rs.getTimestamp(1))).optional().map(o -> (Instant) o).orElse(null);
            LocalDate endDay = local(end).toLocalDate();
            Instant floor = endDay.minusDays(730).atStartOfDay(BusinessZone.get()).toInstant();
            start = parseLocalDay(localIso(first != null && first.isAfter(floor) ? first : floor), true);
        }
        if (start.isAfter(end)) {
            Instant s = parseLocalDay(localIso(end), true);
            Instant e = parseLocalDay(localIso(start), false);
            start = s;
            end = e;
        }
        long spanMs = end.toEpochMilli() - start.toEpochMilli();
        if (spanMs > 732L * 86_400_000L) {
            throw ApiException.badRequest("Registers are limited to a 2-year window");
        }
        Instant prevEnd = start.minusMillis(1);
        Instant prevStart = prevEnd.minusMillis(spanMs);
        long days = (long) Math.ceil(spanMs / 86_400_000.0);
        boolean signed = "adjustments".equals(kind) || "all".equals(kind);
        String bucket = days > 400 ? "month" : days > 92 ? "week" : "day";

        Query cur = lines(kind, q, start, end, false);
        Map<String, List<Map<String, Object>>> agg = breakdowns(cur, bucket);
        Map<String, Object> total = agg.getOrDefault("total", List.of()).stream().findFirst().orElse(Map.of());
        Query prev = lines(kind, q, prevStart, prevEnd, true);
        Map<String, Object> prevTotal = jdbc.sql(prev.with() + " SELECT " + AGG + " FROM f").params(prev.params())
                .query().singleRow();

        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> s : agg.getOrDefault("fsource", List.of())) {
            String v = (String) s.get("k");
            sources.add(Obj.of("value", v, "label", SOURCE_LABELS.getOrDefault(v, humanize(v))));
        }
        List<String> reasons = new ArrayList<>(new TreeSet<>(agg.getOrDefault("freason", List.of()).stream()
                .map(r -> (String) r.get("k")).toList()));
        List<Map<String, Object>> staffFacet = new ArrayList<>();
        for (Map<String, Object> s : agg.getOrDefault("fstaff", List.of())) {
            staffFacet.add(Obj.of("value", s.get("k"), "label", s.get("x1") != null ? s.get("x1") : s.get("k")));
        }

        Map<String, Map<String, Object>> weekdays = keyed(agg.get("weekday"));
        List<Map<String, Object>> byWeekday = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Map<String, Object> g = weekdays.get(String.valueOf(i));
            byWeekday.add(Obj.of("key", WEEKDAYS[i], "lines", g == null ? 0 : count(g, "n"), "qty",
                    js(dec(g, "aqty"), 6), "value", js(dec(g, "aval"), 2)));
        }
        Map<String, Map<String, Object>> hours = keyed(agg.get("hour"));
        List<Map<String, Object>> byHour = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> g = hours.get(String.valueOf(h));
            byHour.add(Obj.of("key", String.format("%02d", h), "lines", g == null ? 0 : count(g, "n"), "value",
                    js(dec(g, "aval"), 2)));
        }

        long lines = count(total, "n");
        BigDecimal gross = dec(total, "aval");
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(cur.with() + " SELECT id, created_at, ledger_code,"
                        + " doc_ref, source, source_label, type, type_label, product_id, code, name, category_id, category,"
                        + " uom, location_id, location, to_location_id, to_location, qty_before, balance_after,"
                        + " batch_number, lq, unit_cost, lv, reason, responsible_id, responsible, approved_by, performed_by,"
                        + " notes FROM f ORDER BY created_at DESC, seq ASC LIMIT :cap")
                .params(cur.params()).param("cap", Math.max(0, rowCap)));
        List<Map<String, Object>> rendered = rows.stream().map(InventoryRegisterService::renderRow).toList();
        return Obj.of("kind", kind,
                "range", Obj.of("start", localIso(start), "end", localIso(end), "days", days),
                "previousRange", Obj.of("start", localIso(prevStart), "end", localIso(prevEnd)),
                "summary", summary(signed, total, prevTotal, agg.getOrDefault("name", List.of()), gross, days),
                "trend", trend(signed, agg.get("trend"), bucket, start, end),
                "byProduct", group(agg.get("product"), gross, true),
                "byCategory", group(agg.get("category"), gross, false),
                "byLocation", group(agg.get("location"), gross, false),
                "bySource", group(agg.get("source"), gross, false),
                "byReason", group(agg.get("reason"), gross, false),
                "byType", group(agg.get("type"), gross, false),
                "byStaff", group(agg.get("staff"), gross, false),
                "byWeekday", byWeekday, "byHour", byHour,
                "facets", Obj.of("sources", sources, "reasons", reasons, "staff", staffFacet),
                "rows", rendered, "truncated", lines > rowCap);
    }

    private static Map<String, Object> renderRow(Map<String, Object> r) {
        return Obj.of("id", r.get("id"), "date", r.get("created_at"), "ledgerCode", r.get("ledger_code"), "docRef",
                r.get("doc_ref"), "source", r.get("source"), "sourceLabel", r.get("source_label"), "type", r.get("type"),
                "typeLabel", r.get("type_label"), "productId", r.get("product_id"), "code", r.get("code"), "name",
                r.get("name"), "categoryId", r.get("category_id"), "category", r.get("category"), "uom", r.get("uom"),
                "locationId", r.get("location_id"), "location", r.get("location"), "toLocationId",
                r.get("to_location_id"), "toLocation", r.get("to_location"), "qtyBefore",
                js((BigDecimal) r.get("qty_before")), "balanceAfter", js((BigDecimal) r.get("balance_after")),
                "batchNumber", r.get("batch_number"), "qty", js(dec(r, "lq"), 6), "unitCost",
                js(dec(r, "unit_cost"), 2), "value", js(dec(r, "lv"), 2), "reason", r.get("reason"), "responsibleId",
                r.get("responsible_id"), "responsible", r.get("responsible"), "approvedBy", r.get("approved_by"),
                "performedBy", r.get("performed_by"), "notes", r.get("notes"));
    }

    /** The generated {@code WITH base, c, d, e, lines, f} prefix of a register window and its parameters. */
    private record Query(String with, Map<String, Object> params) {}

    /**
     * SQL for the register lines of a window: {@code lines} = every line of the kind (facets), {@code f} = the lines
     * passing the source / reason / staff / direction filters. Columns: {@code lq} / {@code lv} = signed or absolute
     * quantity / value by kind, {@code seq} = encounter order (createdAt, id). {@code light}: no display names (the
     * previous-period totals need none).
     */
    private Query lines(String kind, Map<String, String> q, Instant start, Instant end, boolean light) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", org());
        p.put("start", SqlRows.ts(start));
        p.put("end", SqlRows.ts(end));
        p.put("zone", BusinessZone.get().getId());
        p.put("adjre", ADJ.pattern());
        StringBuilder w = new StringBuilder("l.\"organizationId\" = :org AND l.\"createdAt\" >= :start"
                + " AND l.\"createdAt\" <= :end");
        String search = q.get("search") == null ? "" : q.get("search").trim();
        if (notBlank(q.get("productId")) || notBlank(q.get("categoryId")) || !search.isEmpty()) {
            StringBuilder pw = new StringBuilder("SELECT id FROM \"Product\" WHERE \"organizationId\" = :org"
                    + " AND \"deletedAt\" IS NULL");
            if (notBlank(q.get("productId"))) {
                pw.append(" AND id = :pid");
                p.put("pid", q.get("productId"));
            }
            if (notBlank(q.get("categoryId"))) {
                pw.append(" AND \"categoryId\" IN (:cats)");
                p.put("cats", categoryTree(q.get("categoryId")));
            }
            if (!search.isEmpty()) {
                pw.append(" AND (code ILIKE :s OR name ILIKE :s OR sku ILIKE :s OR barcode ILIKE :s)");
                p.put("s", "%" + BaseCrudService.escapeLike(search) + "%");
            }
            w.append(" AND l.\"productId\" IN (").append(pw).append(")");
        }
        if (!KIND_TYPES.get(kind).isEmpty()) {
            w.append(" AND l.type::text IN (:types)");
            p.put("types", KIND_TYPES.get(kind));
        }
        boolean transfers = "transfers".equals(kind);
        String loc = notBlank(q.get("locationId")) ? q.get("locationId") : null;
        if (loc != null) {
            p.put("loc", loc);
            if (!transfers) {
                w.append(" AND l.\"locationId\" = :loc");
            }
        }
        String base = "base AS (SELECT l.id, l.\"createdAt\" AS created_at, l.\"ledgerCode\" AS ledger_code,"
                + " l.type::text AS type, l.\"productId\" AS product_id, l.\"locationId\" AS location_id,"
                + " l.\"qtyBefore\" AS qty_before, l.\"quantityChange\" AS qty_change, l.\"balanceAfter\" AS balance_after,"
                + " l.\"unitCost\" AS unit_cost, l.\"totalValue\" AS total_value, l.\"referenceType\" AS ref_type,"
                + " l.\"referenceId\" AS ref_id, b.\"batchNumber\" AS batch_number, l.notes,"
                + " l.\"performedBy\" AS performed_by_id, l.\"responsibleById\" AS l_resp, l.\"approvedById\" AS l_appr,"
                + " regexp_match(COALESCE(l.notes, ''), CAST(:adjre AS text)) AS adj,"
                + " (l.\"createdAt\" AT TIME ZONE 'UTC') AT TIME ZONE CAST(:zone AS text) AS local_ts,"
                + " row_number() OVER (ORDER BY l.\"createdAt\", l.id) AS seq"
                + " FROM \"InventoryLedger\" l LEFT JOIN \"InventoryBatch\" b ON b.id = l.\"batchId\" WHERE " + w + ")";
        // Source documents, looked up by code like the Nest register (first match only).
        String c = "c AS (SELECT base.*, wd.hit AS w_hit, wd.category AS w_cat, wd.resp AS w_resp, wd.appr AS w_appr,"
                + " od.hit AS o_hit, od.category AS o_cat, od.resp AS o_resp, od.appr AS o_appr,"
                + " ad.reason AS a_reason, ad.resp AS a_resp, ad.appr AS a_appr,"
                + " td.hit AS t_hit, td.to_loc AS t_to, td.resp AS t_resp, td.appr AS t_appr,"
                + (transfers ? " il.location_id AS in_leg" : " NULL::text AS in_leg") + " FROM base"
                + " LEFT JOIN LATERAL (SELECT 1 AS hit, w.category::text AS category, w.\"responsibleById\" AS resp,"
                + " w.\"approvedById\" AS appr FROM \"WasteRecord\" w WHERE base.ref_type = 'waste'"
                + " AND w.\"organizationId\" = :org AND w.\"wasteCode\" = base.ref_id LIMIT 1) wd ON true"
                + " LEFT JOIN LATERAL (SELECT 1 AS hit, o.category::text AS category, o.\"responsibleById\" AS resp,"
                + " o.\"approvedById\" AS appr FROM \"StockOut\" o WHERE base.ref_type = 'stock_out'"
                + " AND o.\"organizationId\" = :org AND o.\"outCode\" = base.ref_id LIMIT 1) od ON true"
                + " LEFT JOIN LATERAL (SELECT a.reason::text AS reason, a.\"responsibleById\" AS resp,"
                + " a.\"approvedById\" AS appr FROM \"StockAdjustment\" a WHERE base.adj IS NOT NULL"
                + " AND a.\"organizationId\" = :org AND a.\"adjCode\" = base.adj[1] LIMIT 1) ad ON true"
                + " LEFT JOIN LATERAL (SELECT 1 AS hit, t.\"toLocId\" AS to_loc, t.\"responsibleById\" AS resp,"
                + " t.\"approvedById\" AS appr FROM \"StockTransfer\" t WHERE base.ref_type = 'stock_transfer'"
                + " AND t.\"organizationId\" = :org AND t.\"transferCode\" = base.ref_id LIMIT 1) td ON true"
                + (transfers
                        // The receiving leg of the same transfer in the window (the latest one wins, as in Nest).
                        ? " LEFT JOIN LATERAL (SELECT i.\"locationId\" AS location_id FROM \"InventoryLedger\" i"
                                + " WHERE i.\"organizationId\" = :org AND i.type::text = 'transfer_in'"
                                + " AND i.\"productId\" = base.product_id AND i.\"createdAt\" >= :start"
                                + " AND i.\"createdAt\" <= :end"
                                + " AND COALESCE(i.\"referenceId\", i.\"ledgerCode\") = COALESCE(base.ref_id, base.ledger_code)"
                                + " ORDER BY i.\"createdAt\" DESC, i.id DESC LIMIT 1) il ON true"
                        : "")
                + ")";
        String d = "d AS (SELECT c.*, CASE WHEN ref_type = 'waste' AND w_hit IS NOT NULL THEN 'w'"
                + " WHEN ref_type = 'stock_out' AND o_hit IS NOT NULL THEN 'o' WHEN adj IS NOT NULL THEN 'a'"
                + " WHEN ref_type = 'stock_transfer' AND t_hit IS NOT NULL THEN 't' WHEN type = 'waste' THEN 'x'"
                + " ELSE '' END AS br FROM c)";
        String e = "e AS (SELECT d.*, CASE br WHEN 'w' THEN CASE WHEN type = 'expiry_write_off' THEN 'Expired' ELSE "
                + humanizeSql("w_cat") + " END WHEN 'o' THEN " + humanizeSql("o_cat")
                + " WHEN 'a' THEN " + humanizeSql("COALESCE(a_reason, adj[2], 'other')")
                + " WHEN 't' THEN 'Transfer' WHEN 'x' THEN 'Waste' ELSE " + labelSql("type", TYPE_LABELS)
                + " END AS reason, CASE WHEN br = 'a' THEN adj[1] ELSE ref_id END AS doc_ref,"
                + " COALESCE(l_resp, CASE br WHEN 'w' THEN w_resp WHEN 'o' THEN o_resp WHEN 'a' THEN a_resp"
                + " WHEN 't' THEN t_resp END) AS responsible_id,"
                + " COALESCE(l_appr, CASE br WHEN 'w' THEN w_appr WHEN 'o' THEN o_appr WHEN 'a' THEN a_appr"
                + " WHEN 't' THEN t_appr END) AS approved_id,"
                + " COALESCE(CASE WHEN br = 't' THEN t_to END, in_leg) AS to_location_id,"
                + " COALESCE(ref_type, CASE WHEN notes LIKE 'ADJ-%' THEN 'stock_adjust'"
                + " WHEN type = 'opening_balance' THEN 'opening_balance' ELSE 'manual' END) AS source FROM d)";
        boolean signedKind = "adjustments".equals(kind) || "all".equals(kind);
        String damage = "(e.type IN ('waste', 'expiry_write_off') OR lower(e.reason) IN ('damaged', 'expired', 'theft'))";
        List<String> kindWhere = new ArrayList<>();
        if (transfers) {
            kindWhere.add("e.type = 'transfer_out'");
            if (loc != null) {
                kindWhere.add("(e.location_id = :loc OR e.to_location_id = :loc)");
            }
        }
        if ("damages".equals(kind)) {
            kindWhere.add(damage);
        }
        if ("stock_out".equals(kind)) {
            kindWhere.add("NOT " + damage);
        }
        String names = light
                ? " NULL::text AS code, NULL::text AS name, NULL::text AS category_id, NULL::text AS category,"
                        + " NULL::text AS uom, NULL::text AS location, NULL::text AS to_location, NULL::text AS loc_key,"
                        + " NULL::text AS responsible, NULL::text AS approved_by, NULL::text AS performed_by FROM e"
                : " COALESCE(p.code, '') AS code, CASE WHEN p.id IS NULL THEN e.product_id ELSE p.name END AS name,"
                        + " pc.id AS category_id, COALESCE(pc.name, 'Uncategorised') AS category, u.code AS uom,"
                        + " COALESCE(lo.name, '—') AS location,"
                        + " CASE WHEN e.to_location_id IS NULL THEN NULL ELSE COALESCE(lt.name, '—') END AS to_location,"
                        + (transfers
                                ? " COALESCE(lo.name, '—') || ' → ' || COALESCE(CASE WHEN e.to_location_id IS NULL"
                                        + " THEN NULL ELSE COALESCE(lt.name, '—') END, '?') AS loc_key,"
                                : " COALESCE(lo.name, '—') AS loc_key,")
                        + userName("ur") + " AS responsible, " + userName("ua") + " AS approved_by, "
                        + userName("up") + " AS performed_by FROM e"
                        + " LEFT JOIN \"Product\" p ON p.id = e.product_id AND p.\"organizationId\" = :org"
                        + " AND p.\"deletedAt\" IS NULL"
                        + " LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\""
                        + " LEFT JOIN \"ProductCategory\" pc ON pc.id = p.\"categoryId\""
                        + " LEFT JOIN \"InventoryLocation\" lo ON lo.id = e.location_id AND lo.\"organizationId\" = :org"
                        + " AND lo.\"deletedAt\" IS NULL"
                        + " LEFT JOIN \"InventoryLocation\" lt ON lt.id = e.to_location_id AND lt.\"organizationId\" = :org"
                        + " AND lt.\"deletedAt\" IS NULL"
                        + userJoin("ur", "e.responsible_id") + userJoin("ua", "e.approved_id")
                        + userJoin("up", "e.performed_by_id");
        String linesCte = "lines AS (SELECT e.*,"
                + (signedKind ? " e.qty_change AS lq, sign(e.qty_change) * e.total_value AS lv,"
                        : " abs(e.qty_change) AS lq, e.total_value AS lv,")
                + " " + labelSql("e.type", TYPE_LABELS) + " AS type_label, " + labelSql("e.source", SOURCE_LABELS)
                + " AS source_label, COALESCE(e.doc_ref, e.ledger_code) AS doc_key," + names
                + (kindWhere.isEmpty() ? "" : " WHERE " + String.join(" AND ", kindWhere)) + ")";
        List<String> filters = new ArrayList<>();
        if (notBlank(q.get("source"))) {
            filters.add("source = :fsource");
            p.put("fsource", q.get("source"));
        }
        if (notBlank(q.get("reason"))) {
            filters.add("reason = :freason");
            p.put("freason", q.get("reason"));
        }
        if (notBlank(q.get("staffId"))) {
            filters.add("responsible_id = :fstaff");
            p.put("fstaff", q.get("staffId"));
        }
        if (notBlank(q.get("direction"))) {
            filters.add("gain".equals(q.get("direction")) ? "lq > 0" : "lq < 0");
        }
        String f = "f AS (SELECT * FROM lines" + (filters.isEmpty() ? "" : " WHERE " + String.join(" AND ", filters))
                + ")";
        return new Query("WITH " + String.join(", ", base, c, d, e, linesCte, f), p);
    }

    /**
     * Every breakdown of a window in one statement, keyed by dimension: {@code total}, {@code product},
     * {@code category}, {@code location}, {@code source}, {@code reason}, {@code type}, {@code staff}, {@code name},
     * {@code weekday}, {@code hour}, {@code trend} (filtered lines) and the facets {@code fsource}, {@code freason},
     * {@code fstaff} (all lines), each list in encounter order.
     */
    private Map<String, List<Map<String, Object>>> breakdowns(Query query, String bucket) {
        String trendKey = switch (bucket) {
            case "month" -> "to_char(local_ts, 'YYYY-MM')";
            case "week" -> "to_char(local_ts::date - (extract(isodow FROM local_ts)::int - 1), 'YYYY-MM-DD')";
            default -> "to_char(local_ts, 'YYYY-MM-DD')";
        };
        String sql = String.join(" UNION ALL ",
                branch("total", "NULL::text", "f", null, null),
                branch("product", "product_id", "f", "min(code) AS x1, min(name) AS x2, min(uom) AS x3,"
                        + " min(category) AS x4", null),
                branch("category", "category", "f", null, null),
                branch("location", "loc_key", "f", null, null),
                branch("source", "source_label", "f", null, null),
                branch("reason", "reason", "f", null, null),
                branch("type", "type_label", "f", null, null),
                branch("staff", "COALESCE(responsible, performed_by, 'Unassigned')", "f", null, null),
                branch("name", "name", "f", null, null),
                branch("weekday", "extract(dow FROM local_ts)::int::text", "f", null, null),
                branch("hour", "extract(hour FROM local_ts)::int::text", "f", null, null),
                branch("trend", trendKey, "f", null, null),
                branch("fsource", "source", "lines", null, null),
                branch("freason", "reason", "lines", null, null),
                branch("fstaff", "responsible_id", "lines", "max(responsible) AS x1, NULL::text AS x2,"
                        + " NULL::text AS x3, NULL::text AS x4", "responsible_id IS NOT NULL"));
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.sql(query.with() + " " + sql + " ORDER BY dim, seq NULLS FIRST")
                .params(query.params()).query().listOfRows()) {
            out.computeIfAbsent((String) r.get("dim"), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    private static String branch(String dim, String key, String from, String extras, String where) {
        boolean grouped = !"NULL::text".equals(key);
        return "(SELECT '" + dim + "'::text AS dim, (" + key + ")::text AS k, " + (extras == null ? NO_EXTRAS : extras)
                + ", " + AGG + " FROM " + from + (where == null ? "" : " WHERE " + where)
                + (grouped ? " GROUP BY 2" : "") + ")";
    }

    private static String humanizeSql(String x) {
        return "(upper(left(replace(" + x + ", '_', ' '), 1)) || substr(replace(" + x + ", '_', ' '), 2))";
    }

    private static String labelSql(String col, Map<String, String> labels) {
        StringBuilder sb = new StringBuilder("(CASE " + col);
        new TreeMap<>(labels).forEach((k, v) -> sb.append(" WHEN '").append(k.replace("'", "''")).append("' THEN '")
                .append(v.replace("'", "''")).append("'"));
        return sb.append(" ELSE ").append(humanizeSql(col)).append(" END)").toString();
    }

    private static String userName(String alias) {
        return " CASE WHEN " + alias + ".id IS NULL THEN NULL ELSE concat_ws(' ', NULLIF(" + alias
                + ".\"firstName\", ''), NULLIF(" + alias + ".\"lastName\", '')) END";
    }

    private static String userJoin(String alias, String col) {
        return " LEFT JOIN \"User\" " + alias + " ON " + alias + ".id = " + col + " AND " + alias
                + ".\"organizationId\" = :org AND " + alias + ".\"deletedAt\" IS NULL";
    }

    private static Map<String, Map<String, Object>> keyed(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (rows != null) {
            rows.forEach(r -> out.put((String) r.get("k"), r));
        }
        return out;
    }

    private static BigDecimal dec(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? BigDecimal.ZERO : v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    private static long count(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static BigDecimal dec(Number n) {
        return n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
    }

    private static BigDecimal div(BigDecimal a, BigDecimal b) {
        return a.divide(b, Money.DECIMAL_JS);
    }

    private static Map<String, Object> summary(boolean signed, Map<String, Object> cur, Map<String, Object> prev,
            List<Map<String, Object>> byName, BigDecimal gross, long days) {
        Map<String, Object> totals = totals(signed, cur);
        Map<String, Object> pv = totals(signed, prev);
        Map<String, Object> top = null;
        for (Map<String, Object> g : byName) {
            if (top == null || dec(g, "aval").compareTo(dec(top, "aval")) > 0) {
                top = g;
            }
        }
        List<BigDecimal> sortedVals = new ArrayList<>(byName.stream().map(g -> dec(g, "aval")).toList());
        sortedVals.sort((a, b) -> b.compareTo(a));
        BigDecimal threshold = gross.multiply(PARETO_SHARE);
        BigDecimal acc = BigDecimal.ZERO;
        int pareto = 0;
        for (BigDecimal v : sortedVals) {
            if (acc.compareTo(threshold) >= 0) {
                break;
            }
            acc = acc.add(v);
            pareto++;
        }
        long lines = count(cur, "n");
        Map<String, Object> out = new LinkedHashMap<>(totals);
        out.put("documents", count(cur, "docs"));
        out.put("items", count(cur, "items"));
        out.put("locations", count(cur, "locs"));
        out.put("grossValue", js(gross, 2));
        out.put("avgValuePerDay", js(div(gross, BigDecimal.valueOf(Math.max(1, days))), 2));
        out.put("avgValuePerLine", lines == 0 ? 0 : js(div(gross, BigDecimal.valueOf(lines)), 2));
        out.put("previous", pv);
        out.put("changePct", Obj.of("lines", pct((Number) totals.get("lines"), (Number) pv.get("lines")),
                "qty", pct((Number) totals.get("qty"), (Number) pv.get("qty")),
                "value", pct((Number) totals.get("value"), (Number) pv.get("value"))));
        out.put("topItem", top == null ? null : Obj.of("name", top.get("k"), "value", js(dec(top, "aval"), 2),
                "sharePct", gross.signum() != 0 ? js(div(dec(top, "aval").multiply(HUNDRED), gross), 2) : 0));
        out.put("paretoItems", pareto);
        out.put("unassignedLines", count(cur, "unassigned"));
        out.put("unapprovedLines", count(cur, "unapproved"));
        if (signed) {
            out.put("gainQty", js(dec(cur, "gqty"), 6));
            out.put("gainValue", js(dec(cur, "gval"), 2));
            out.put("lossQty", js(dec(cur, "lqty"), 6));
            out.put("lossValue", js(dec(cur, "lval"), 2));
        }
        return out;
    }

    /** {@code lines}, {@code qty}, {@code value}: signed kinds net, the others by magnitude. */
    private static Map<String, Object> totals(boolean signed, Map<String, Object> agg) {
        return Obj.of("lines", count(agg, "n"), "qty", js(dec(agg, signed ? "qty" : "aqty"), 6), "value",
                js(dec(agg, signed ? "val" : "aval"), 2));
    }

    private static Number pct(Number a, Number b) {
        BigDecimal x = dec(a);
        BigDecimal y = dec(b);
        if (y.signum() == 0) {
            return x.signum() == 0 ? 0 : null;
        }
        return js(div(x.subtract(y).multiply(HUNDRED), y.abs()), 2);
    }

    private Map<String, Object> trend(boolean signed, List<Map<String, Object>> rows, String bucket, Instant start,
            Instant end) {
        Function<Instant, String> keyOf = i -> {
            LocalDate d = local(i).toLocalDate();
            if ("month".equals(bucket)) {
                return String.format("%d-%02d", d.getYear(), d.getMonthValue());
            }
            if ("week".equals(bucket)) {
                return d.minusDays(d.getDayOfWeek().getValue() - 1).toString();
            }
            return d.toString();
        };
        Map<String, Map<String, Object>> byKey = keyed(rows);
        Set<String> keys = new LinkedHashSet<>();
        ZoneId zone = BusinessZone.get();
        for (LocalDate d = local(start).toLocalDate(); !d.atStartOfDay(zone).toInstant().isAfter(end); d = d.plusDays(1)) {
            keys.add(keyOf.apply(d.atStartOfDay(zone).toInstant()));
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (String k : keys) {
            Map<String, Object> g = byKey.get(k);
            points.add(Obj.of("key", k, "lines", count(g, "n"), "qty", js(dec(g, "aqty"), 6), "value",
                    js(dec(g, "aval"), 2), "gain", js(signed ? dec(g, "gain") : BigDecimal.ZERO, 2), "loss",
                    js(signed ? dec(g, "loss") : BigDecimal.ZERO, 2)));
        }
        return Obj.of("bucket", bucket, "points", points);
    }

    /**
     * One breakdown in the register's shape, rows arriving in encounter order and then stably sorted by value
     * (largest first). {@code withProduct}: the product columns and the average unit cost.
     */
    private static List<Map<String, Object>> group(List<Map<String, Object>> rows, BigDecimal total,
            boolean withProduct) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> g : rows) {
            BigDecimal qty = dec(g, "qty");
            BigDecimal abs = dec(g, "aval");
            BigDecimal net = dec(g, "val");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", g.get("k"));
            if (withProduct) {
                m.put("code", g.get("x1"));
                m.put("name", g.get("x2"));
                m.put("uom", g.get("x3"));
                m.put("category", g.get("x4"));
            }
            m.put("lines", count(g, "n"));
            m.put("documents", count(g, "docs"));
            m.put("qty", js(qty, 6));
            m.put("value", js(abs, 2));
            m.put("netValue", js(net, 2));
            m.put("sharePct", total.signum() != 0 ? js(div(abs.multiply(HUNDRED), total), 2) : 0);
            if (withProduct) {
                m.put("avgUnitCost", qty.signum() != 0 ? js(div(net, qty).abs(), 2) : 0);
            }
            out.add(m);
        }
        out.sort((a, b) -> Double.compare(((Number) b.get("value")).doubleValue(),
                ((Number) a.get("value")).doubleValue()));
        return out;
    }

    /** Category plus descendants (breadth-grow, like the Nest register). */
    private List<String> categoryTree(String categoryId) {
        return reports.categoryTree(categoryId);
    }
}
