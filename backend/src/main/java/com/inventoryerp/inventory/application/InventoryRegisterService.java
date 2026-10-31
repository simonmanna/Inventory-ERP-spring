package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryLookups.str;
import static com.inventoryerp.inventory.application.InventoryQueryService.notBlank;
import static com.inventoryerp.inventory.application.InventoryReportsService.localIso;
import static com.inventoryerp.inventory.application.InventoryReportsService.parseLocalDay;
import static com.inventoryerp.kernel.common.JsNumbers.js;
import static com.inventoryerp.kernel.common.JsNumbers.round;

import com.inventoryerp.kernel.common.BusinessZone;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Movement registers with analytics (port of {@code inventory-register.service.ts}): stock_in, stock_out,
 * damages, adjustments, transfers and all — ledger lines enriched from their source documents, with summary,
 * previous-period comparison, trend and breakdowns.
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

    /** One register line. */
    static final class Line {
        String id;
        Instant date;
        String ledgerCode;
        String docRef;
        String source;
        String sourceLabel;
        String type;
        String typeLabel;
        String productId;
        String code;
        String name;
        String categoryId;
        String category;
        String uom;
        String locationId;
        String location;
        String toLocationId;
        String toLocation;
        double qtyBefore;
        double balanceAfter;
        String batchNumber;
        double qty;
        double unitCost;
        double value;
        String reason;
        String responsibleId;
        String responsible;
        String approvedBy;
        String performedBy;
        String notes;

        Map<String, Object> render() {
            return Obj.of("id", id, "date", date, "ledgerCode", ledgerCode, "docRef", docRef, "source", source,
                    "sourceLabel", sourceLabel, "type", type, "typeLabel", typeLabel, "productId", productId, "code", code,
                    "name", name, "categoryId", categoryId, "category", category, "uom", uom, "locationId", locationId,
                    "location", location, "toLocationId", toLocationId, "toLocation", toLocation, "qtyBefore",
                    js(qtyBefore), "balanceAfter", js(balanceAfter), "batchNumber", batchNumber, "qty", js(round(qty, 6)),
                    "unitCost", js(round(unitCost, 2)), "value", js(round(value, 2)), "reason", reason, "responsibleId",
                    responsibleId, "responsible", responsible, "approvedBy", approvedBy, "performedBy", performedBy,
                    "notes", notes);
        }
    }

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
        List<Line> current = loadLines(kind, q, start, end, false);
        List<Line> previous = loadLines(kind, q, prevStart, prevEnd, true);

        List<Map<String, Object>> sources = new ArrayList<>();
        new LinkedHashSet<>(current.stream().map(l -> l.source).toList()).forEach(s -> sources.add(
                Obj.of("value", s, "label", SOURCE_LABELS.getOrDefault(s, humanize(s)))));
        List<String> reasons = new ArrayList<>(new TreeSet<>(current.stream().map(l -> l.reason).toList()));
        Map<String, String> staff = new LinkedHashMap<>();
        for (Line l : current) {
            if (l.responsibleId != null) {
                staff.put(l.responsibleId, l.responsible != null ? l.responsible : l.responsibleId);
            }
        }
        List<Map<String, Object>> staffFacet = new ArrayList<>();
        staff.forEach((k, v) -> staffFacet.add(Obj.of("value", k, "label", v)));

        List<Line> rows = applyFilters(current, q);
        List<Line> prevRows = applyFilters(previous, q);
        long days = (long) Math.ceil(spanMs / 86_400_000.0);
        List<Map<String, Object>> byWeekday = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            int dow = i;
            List<Line> hit = rows.stream().filter(l -> local(l.date).getDayOfWeek().getValue() % 7 == dow).toList();
            byWeekday.add(Obj.of("key", WEEKDAYS[i], "lines", hit.size(), "qty",
                    js(round(hit.stream().mapToDouble(l -> Math.abs(l.qty)).sum(), 6)), "value",
                    js(round(hit.stream().mapToDouble(l -> Math.abs(l.value)).sum(), 2))));
        }
        List<Map<String, Object>> byHour = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            int hour = h;
            List<Line> hit = rows.stream().filter(l -> local(l.date).getHour() == hour).toList();
            byHour.add(Obj.of("key", String.format("%02d", h), "lines", hit.size(), "value",
                    js(round(hit.stream().mapToDouble(l -> Math.abs(l.value)).sum(), 2))));
        }
        List<Line> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> b.date.compareTo(a.date));
        List<Map<String, Object>> rendered = sorted.stream().limit(5000).map(Line::render).toList();
        return Obj.of("kind", kind,
                "range", Obj.of("start", localIso(start), "end", localIso(end), "days", days),
                "previousRange", Obj.of("start", localIso(prevStart), "end", localIso(prevEnd)),
                "summary", summary(kind, rows, prevRows, days),
                "trend", trend(kind, rows, start, end),
                "byProduct", group(rows, l -> l.productId,
                        l -> Obj.of("code", l.code, "name", l.name, "uom", l.uom, "category", l.category), true),
                "byCategory", group(rows, l -> l.category, null, false),
                "byLocation", group(rows, l -> "transfers".equals(kind)
                        ? l.location + " → " + (l.toLocation != null ? l.toLocation : "?") : l.location, null, false),
                "bySource", group(rows, l -> l.sourceLabel, null, false),
                "byReason", group(rows, l -> l.reason, null, false),
                "byType", group(rows, l -> l.typeLabel, null, false),
                "byStaff", group(rows, l -> l.responsible != null ? l.responsible
                        : l.performedBy != null ? l.performedBy : "Unassigned", null, false),
                "byWeekday", byWeekday, "byHour", byHour,
                "facets", Obj.of("sources", sources, "reasons", reasons, "staff", staffFacet),
                "rows", rendered, "truncated", rows.size() > 5000);
    }

    private static List<Line> applyFilters(List<Line> lines, Map<String, String> q) {
        return lines.stream().filter(l -> (!notBlank(q.get("source")) || q.get("source").equals(l.source))
                && (!notBlank(q.get("reason")) || q.get("reason").equals(l.reason))
                && (!notBlank(q.get("staffId")) || q.get("staffId").equals(l.responsibleId))
                && (!notBlank(q.get("direction")) || ("gain".equals(q.get("direction")) ? l.qty > 0 : l.qty < 0)))
                .toList();
    }

    private List<Line> loadLines(String kind, Map<String, String> q, Instant start, Instant end, boolean light) {
        String org = org();
        List<String> productIds = null;
        String search = q.get("search") == null ? "" : q.get("search").trim();
        if (notBlank(q.get("productId")) || notBlank(q.get("categoryId")) || !search.isEmpty()) {
            Map<String, Object> p = new HashMap<>();
            StringBuilder w = new StringBuilder("\"organizationId\" = :org AND \"deletedAt\" IS NULL");
            p.put("org", org);
            if (notBlank(q.get("productId"))) {
                w.append(" AND id = :pid");
                p.put("pid", q.get("productId"));
            }
            if (notBlank(q.get("categoryId"))) {
                w.append(" AND \"categoryId\" IN (:cats)");
                p.put("cats", categoryTree(q.get("categoryId")));
            }
            if (!search.isEmpty()) {
                w.append(" AND (code ILIKE :s OR name ILIKE :s OR sku ILIKE :s OR barcode ILIKE :s)");
                p.put("s", "%" + BaseCrudService.escapeLike(search) + "%");
            }
            productIds = jdbc.sql("SELECT id FROM \"Product\" WHERE " + w).params(p).query(String.class).list();
            if (productIds.isEmpty()) {
                return List.of();
            }
        }
        Map<String, Object> p = new HashMap<>();
        StringBuilder w = new StringBuilder("l.\"organizationId\" = :org AND l.\"createdAt\" >= :start AND l.\"createdAt\" <= :end");
        p.put("org", org);
        p.put("start", SqlRows.ts(start));
        p.put("end", SqlRows.ts(end));
        if (productIds != null) {
            w.append(" AND l.\"productId\" IN (:pids)");
            p.put("pids", productIds);
        }
        if (!KIND_TYPES.get(kind).isEmpty()) {
            w.append(" AND l.type::text IN (:types)");
            p.put("types", KIND_TYPES.get(kind));
        }
        if (notBlank(q.get("locationId")) && !"transfers".equals(kind)) {
            w.append(" AND l.\"locationId\" = :loc");
            p.put("loc", q.get("locationId"));
        }
        List<Map<String, Object>> ledger = SqlRows.list(jdbc.sql("SELECT l.id, l.\"createdAt\", l.\"ledgerCode\","
                + " l.type::text AS type, l.\"productId\", l.\"locationId\", l.\"qtyBefore\", l.\"quantityChange\","
                + " l.\"balanceAfter\", l.\"unitCost\", l.\"totalValue\", l.\"referenceType\", l.\"referenceId\", l.\"batchId\","
                + " b.\"batchNumber\", l.notes, l.\"performedBy\", l.\"responsibleById\", l.\"approvedById\""
                + " FROM \"InventoryLedger\" l LEFT JOIN \"InventoryBatch\" b ON b.id = l.\"batchId\" WHERE " + w
                + " ORDER BY l.\"createdAt\" ASC").params(p));
        if (ledger.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> wasteBy = docs("WasteRecord", "wasteCode",
                "category::text AS category, \"responsibleById\", \"approvedById\", notes", refs(ledger, "waste"));
        Map<String, Map<String, Object>> outBy = docs("StockOut", "outCode",
                "category::text AS category, reason, \"responsibleById\", \"approvedById\"", refs(ledger, "stock_out"));
        Set<String> adjCodes = new LinkedHashSet<>();
        for (Map<String, Object> l : ledger) {
            Matcher m = Pattern.compile("^(ADJ-[\\w-]+)").matcher(l.get("notes") == null ? "" : str(l, "notes"));
            if (m.find()) {
                adjCodes.add(m.group(1));
            }
        }
        Map<String, Map<String, Object>> adjBy = docs("StockAdjustment", "adjCode",
                "reason::text AS reason, \"responsibleById\", \"approvedById\"", adjCodes);
        Map<String, Map<String, Object>> trfBy = docs("StockTransfer", "transferCode",
                "\"fromLocId\", \"toLocId\", \"responsibleById\", \"approvedById\"", refs(ledger, "stock_transfer"));

        Set<String> userIds = new HashSet<>();
        for (Map<String, Object> l : ledger) {
            for (String k : List.of("performedBy", "responsibleById", "approvedById")) {
                if (l.get(k) != null) {
                    userIds.add(str(l, k));
                }
            }
        }
        for (Map<String, Map<String, Object>> docs : List.of(wasteBy, outBy, adjBy, trfBy)) {
            for (Map<String, Object> d : docs.values()) {
                for (String k : List.of("responsibleById", "approvedById")) {
                    if (d.get(k) != null) {
                        userIds.add(str(d, k));
                    }
                }
            }
        }
        Set<String> locIds = new HashSet<>();
        ledger.forEach(l -> locIds.add(str(l, "locationId")));
        trfBy.values().forEach(t -> {
            locIds.add(str(t, "fromLocId"));
            locIds.add(str(t, "toLocId"));
        });
        Map<String, Map<String, Object>> prodBy = new HashMap<>();
        for (Map<String, Object> pr : jdbc.sql("SELECT p.id, p.code, p.name, u.code AS uom, c.id AS cid, c.name AS cname"
                        + " FROM \"Product\" p LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\""
                        + " LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\" WHERE p.id IN (:ids)"
                        + " AND p.\"organizationId\" = :org AND p.\"deletedAt\" IS NULL")
                .param("ids", ledger.stream().map(l -> str(l, "productId")).distinct().toList()).param("org", org)
                .query().listOfRows()) {
            prodBy.put((String) pr.get("id"), pr);
        }
        Map<String, String> locBy = new HashMap<>();
        for (Map<String, Object> lr : jdbc.sql("SELECT id, name FROM \"InventoryLocation\" WHERE id IN (:ids)"
                        + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL")
                .param("ids", List.copyOf(locIds)).param("org", org).query().listOfRows()) {
            locBy.put((String) lr.get("id"), (String) lr.get("name"));
        }
        Map<String, String> userBy = new HashMap<>();
        if (!light && !userIds.isEmpty()) {
            for (Map<String, Object> u : jdbc.sql("SELECT id, \"firstName\", \"lastName\" FROM \"User\" WHERE id IN (:ids)"
                            + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL")
                    .param("ids", List.copyOf(userIds)).param("org", org).query().listOfRows()) {
                List<String> parts = new ArrayList<>();
                for (String k : List.of("firstName", "lastName")) {
                    if (u.get(k) != null && !str(u, k).isEmpty()) {
                        parts.add(str(u, k));
                    }
                }
                userBy.put((String) u.get("id"), String.join(" ", parts));
            }
        }
        Map<String, String> inLeg = new HashMap<>();
        for (Map<String, Object> l : ledger) {
            if ("transfer_in".equals(l.get("type"))) {
                inLeg.put((l.get("referenceId") != null ? str(l, "referenceId") : str(l, "ledgerCode")) + "|"
                        + l.get("productId"), str(l, "locationId"));
            }
        }
        List<Line> lines = new ArrayList<>();
        for (Map<String, Object> l : ledger) {
            String type = str(l, "type");
            if ("transfers".equals(kind) && !"transfer_out".equals(type)) {
                continue;
            }
            Map<String, Object> pr = prodBy.get(str(l, "productId"));
            double signed = ((BigDecimal) l.get("quantityChange")).doubleValue();
            double absValue = ((BigDecimal) l.get("totalValue")).doubleValue();
            String notes = str(l, "notes");
            String refType = str(l, "referenceType");
            String refId = str(l, "referenceId");
            String source = refType != null ? refType : notes != null && notes.startsWith("ADJ-") ? "stock_adjust"
                    : "opening_balance".equals(type) ? "opening_balance" : "manual";
            String reason = TYPE_LABELS.getOrDefault(type, humanize(type));
            String docRef = refId;
            String responsibleId = str(l, "responsibleById");
            String approvedId = str(l, "approvedById");
            String toLocationId = null;
            boolean signedKind = "adjustments".equals(kind) || "all".equals(kind);
            Matcher adj = ADJ.matcher(notes == null ? "" : notes);
            boolean adjMatch = adj.find();
            if ("waste".equals(refType) && refId != null && wasteBy.containsKey(refId)) {
                Map<String, Object> wd = wasteBy.get(refId);
                reason = "expiry_write_off".equals(type) ? "Expired" : humanize(str(wd, "category"));
                responsibleId = responsibleId != null ? responsibleId : str(wd, "responsibleById");
                approvedId = approvedId != null ? approvedId : str(wd, "approvedById");
            } else if ("stock_out".equals(refType) && refId != null && outBy.containsKey(refId)) {
                Map<String, Object> o = outBy.get(refId);
                reason = humanize(str(o, "category"));
                responsibleId = responsibleId != null ? responsibleId : str(o, "responsibleById");
                approvedId = approvedId != null ? approvedId : str(o, "approvedById");
            } else if (adjMatch) {
                docRef = adj.group(1);
                Map<String, Object> a = adjBy.get(adj.group(1));
                String r = a != null && a.get("reason") != null ? str(a, "reason")
                        : adj.group(2) != null ? adj.group(2) : "other";
                reason = humanize(r);
                responsibleId = responsibleId != null ? responsibleId : a == null ? null : str(a, "responsibleById");
                approvedId = approvedId != null ? approvedId : a == null ? null : str(a, "approvedById");
            } else if ("stock_transfer".equals(refType) && refId != null && trfBy.containsKey(refId)) {
                Map<String, Object> t = trfBy.get(refId);
                toLocationId = str(t, "toLocId");
                responsibleId = responsibleId != null ? responsibleId : str(t, "responsibleById");
                approvedId = approvedId != null ? approvedId : str(t, "approvedById");
                reason = "Transfer";
            } else if ("waste".equals(type)) {
                reason = "Waste";
            }
            if ("transfers".equals(kind)) {
                if (toLocationId == null) {
                    toLocationId = inLeg.get((refId != null ? refId : str(l, "ledgerCode")) + "|" + l.get("productId"));
                }
                String loc = q.get("locationId");
                if (notBlank(loc) && !loc.equals(l.get("locationId")) && !loc.equals(toLocationId)) {
                    continue;
                }
            }
            boolean isDamage = "waste".equals(type) || "expiry_write_off".equals(type)
                    || DAMAGE_REASONS.contains(reason.toLowerCase());
            if ("damages".equals(kind) && !isDamage) {
                continue;
            }
            if ("stock_out".equals(kind) && isDamage) {
                continue;
            }
            Line line = new Line();
            line.id = str(l, "id");
            line.date = (Instant) l.get("createdAt");
            line.ledgerCode = str(l, "ledgerCode");
            line.docRef = docRef;
            line.source = source;
            line.sourceLabel = SOURCE_LABELS.getOrDefault(source, humanize(source));
            line.type = type;
            line.typeLabel = TYPE_LABELS.getOrDefault(type, humanize(type));
            line.productId = str(l, "productId");
            line.code = pr != null && pr.get("code") != null ? str(pr, "code") : "";
            line.name = pr != null ? str(pr, "name") : str(l, "productId");
            line.categoryId = pr == null ? null : str(pr, "cid");
            line.category = pr != null && pr.get("cname") != null ? str(pr, "cname") : "Uncategorised";
            line.uom = pr == null ? null : str(pr, "uom");
            line.locationId = str(l, "locationId");
            line.location = locBy.getOrDefault(str(l, "locationId"), "—");
            line.toLocationId = toLocationId;
            line.toLocation = toLocationId != null ? locBy.getOrDefault(toLocationId, "—") : null;
            line.qtyBefore = ((BigDecimal) l.get("qtyBefore")).doubleValue();
            line.balanceAfter = ((BigDecimal) l.get("balanceAfter")).doubleValue();
            line.batchNumber = str(l, "batchNumber");
            line.qty = signedKind ? signed : Math.abs(signed);
            line.unitCost = ((BigDecimal) l.get("unitCost")).doubleValue();
            line.value = signedKind ? Math.signum(signed) * absValue : absValue;
            line.reason = reason;
            line.responsibleId = responsibleId;
            line.responsible = responsibleId != null ? userBy.get(responsibleId) : null;
            line.approvedBy = approvedId != null ? userBy.get(approvedId) : null;
            line.performedBy = l.get("performedBy") != null ? userBy.get(str(l, "performedBy")) : null;
            line.notes = notes;
            lines.add(line);
        }
        return lines;
    }

    private static Set<String> refs(List<Map<String, Object>> ledger, String type) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> l : ledger) {
            if (type.equals(l.get("referenceType")) && l.get("referenceId") != null) {
                out.add(str(l, "referenceId"));
            }
        }
        return out;
    }

    private Map<String, Map<String, Object>> docs(String table, String codeField, String cols, Set<String> codes) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (codes.isEmpty()) {
            return out;
        }
        for (Map<String, Object> d : jdbc.sql("SELECT \"" + codeField + "\" AS code, " + cols + " FROM \"" + table
                        + "\" WHERE \"organizationId\" = :org AND \"" + codeField + "\" IN (:codes)")
                .param("org", org()).param("codes", List.copyOf(codes)).query().listOfRows()) {
            out.put((String) d.get("code"), d);
        }
        return out;
    }

    private Map<String, Object> summary(String kind, List<Line> rows, List<Line> prev, long days) {
        boolean signed = "adjustments".equals(kind) || "all".equals(kind);
        Map<String, Object> cur = totals(rows, signed);
        Map<String, Object> pv = totals(prev, signed);
        Set<String> docs = new HashSet<>();
        rows.forEach(l -> docs.add(l.docRef != null ? l.docRef : l.ledgerCode));
        Map<String, Double> productValues = new LinkedHashMap<>();
        rows.forEach(l -> productValues.merge(l.name, Math.abs(l.value), Double::sum));
        Map.Entry<String, Double> top = null;
        for (Map.Entry<String, Double> e : productValues.entrySet()) {
            if (top == null || e.getValue() > top.getValue()) {
                top = e;
            }
        }
        double gross = rows.stream().mapToDouble(l -> Math.abs(l.value)).sum();
        List<Double> sortedVals = new ArrayList<>(productValues.values());
        sortedVals.sort((a, b) -> Double.compare(b, a));
        double acc = 0;
        int pareto = 0;
        for (double v : sortedVals) {
            if (acc >= gross * 0.8) {
                break;
            }
            acc += v;
            pareto++;
        }
        Map<String, Object> out = new LinkedHashMap<>(cur);
        out.put("documents", docs.size());
        out.put("items", rows.stream().map(l -> l.productId).distinct().count());
        out.put("locations", rows.stream().map(l -> l.locationId).distinct().count());
        out.put("grossValue", js(round(gross, 2)));
        out.put("avgValuePerDay", js(round(gross / Math.max(1, days), 2)));
        out.put("avgValuePerLine", rows.isEmpty() ? 0 : js(round(gross / rows.size(), 2)));
        out.put("previous", pv);
        out.put("changePct", Obj.of("lines", pct(((Number) cur.get("lines")).doubleValue(),
                        ((Number) pv.get("lines")).doubleValue()),
                "qty", pct(((Number) cur.get("qty")).doubleValue(), ((Number) pv.get("qty")).doubleValue()),
                "value", pct(((Number) cur.get("value")).doubleValue(), ((Number) pv.get("value")).doubleValue())));
        out.put("topItem", top == null ? null : Obj.of("name", top.getKey(), "value", js(round(top.getValue(), 2)),
                "sharePct", gross != 0 ? js(round(top.getValue() / gross * 100, 2)) : 0));
        out.put("paretoItems", pareto);
        out.put("unassignedLines", rows.stream().filter(l -> l.responsibleId == null).count());
        out.put("unapprovedLines", rows.stream().filter(l -> l.approvedBy == null).count());
        if (signed) {
            out.put("gainQty", js(round(rows.stream().filter(l -> l.qty > 0).mapToDouble(l -> l.qty).sum(), 6)));
            out.put("gainValue", js(round(rows.stream().filter(l -> l.qty > 0).mapToDouble(l -> l.value).sum(), 2)));
            out.put("lossQty", js(round(rows.stream().filter(l -> l.qty < 0).mapToDouble(l -> -l.qty).sum(), 6)));
            out.put("lossValue", js(round(rows.stream().filter(l -> l.qty < 0).mapToDouble(l -> -l.value).sum(), 2)));
        }
        return out;
    }

    private static Map<String, Object> totals(List<Line> ls, boolean signed) {
        double qty = 0;
        double value = 0;
        for (Line l : ls) {
            qty += signed ? l.qty : Math.abs(l.qty);
            value += signed ? l.value : Math.abs(l.value);
        }
        return Obj.of("lines", ls.size(), "qty", js(round(qty, 6)), "value", js(round(value, 2)));
    }

    private static Number pct(double a, double b) {
        if (b == 0) {
            return a == 0 ? 0 : null;
        }
        return js(round((a - b) / Math.abs(b) * 100, 2));
    }

    private Map<String, Object> trend(String kind, List<Line> rows, Instant start, Instant end) {
        long days = (long) Math.ceil((end.toEpochMilli() - start.toEpochMilli()) / 86_400_000.0);
        String bucket = days > 400 ? "month" : days > 92 ? "week" : "day";
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
        Map<String, double[]> map = new LinkedHashMap<>();
        ZoneId zone = BusinessZone.get();
        for (LocalDate d = local(start).toLocalDate(); !d.atStartOfDay(zone).toInstant().isAfter(end); d = d.plusDays(1)) {
            map.putIfAbsent(keyOf.apply(d.atStartOfDay(zone).toInstant()), new double[5]);
        }
        boolean signed = "adjustments".equals(kind) || "all".equals(kind);
        for (Line l : rows) {
            double[] b = map.get(keyOf.apply(l.date));
            if (b == null) {
                continue;
            }
            b[0] += 1;
            b[1] += Math.abs(l.qty);
            b[2] += Math.abs(l.value);
            if (signed) {
                if (l.value >= 0) {
                    b[3] += l.value;
                } else {
                    b[4] += -l.value;
                }
            }
        }
        List<Map<String, Object>> points = new ArrayList<>();
        map.forEach((k, b) -> points.add(Obj.of("key", k, "lines", (long) b[0], "qty", js(round(b[1], 6)), "value",
                js(round(b[2], 2)), "gain", js(round(b[3], 2)), "loss", js(round(b[4], 2)))));
        return Obj.of("bucket", bucket, "points", points);
    }

    private static List<Map<String, Object>> group(List<Line> rows, Function<Line, String> keyFn,
            Function<Line, Map<String, Object>> extra, boolean withAvgCost) {
        double total = rows.stream().mapToDouble(l -> Math.abs(l.value)).sum();
        Map<String, Object[]> map = new LinkedHashMap<>();
        for (Line l : rows) {
            String k = keyFn.apply(l);
            Object[] g = map.computeIfAbsent(k, x -> new Object[] {new double[4], new HashSet<String>(),
                    extra == null ? null : extra.apply(l)});
            double[] d = (double[]) g[0];
            d[0] += 1;
            d[1] += l.qty;
            d[2] += Math.abs(l.value);
            d[3] += l.value;
            @SuppressWarnings("unchecked")
            Set<String> docs = (Set<String>) g[1];
            docs.add(l.docRef != null ? l.docRef : l.ledgerCode);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        map.forEach((k, g) -> {
            double[] d = (double[]) g[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k);
            if (g[2] != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ex = (Map<String, Object>) g[2];
                m.putAll(ex);
            }
            m.put("lines", (long) d[0]);
            m.put("documents", ((Set<?>) g[1]).size());
            m.put("qty", js(round(d[1], 6)));
            m.put("value", js(round(d[2], 2)));
            m.put("netValue", js(round(d[3], 2)));
            m.put("sharePct", total != 0 ? js(round(d[2] / total * 100, 2)) : 0);
            if (withAvgCost) {
                m.put("avgUnitCost", d[1] != 0 ? js(round(Math.abs(d[3] / d[1]), 2)) : 0);
            }
            out.add(m);
        });
        out.sort((a, b) -> Double.compare(((Number) b.get("value")).doubleValue(), ((Number) a.get("value")).doubleValue()));
        return out;
    }

    /** Category plus descendants (breadth-grow, like the Nest register). */
    private List<String> categoryTree(String categoryId) {
        return reports.categoryTree(categoryId);
    }
}
