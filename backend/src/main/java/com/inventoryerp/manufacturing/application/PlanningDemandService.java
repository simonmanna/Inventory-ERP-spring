package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.application.DemandNetting.Allocation;
import com.inventoryerp.manufacturing.application.DemandNetting.Demand;
import com.inventoryerp.manufacturing.application.MfgCommands.CreatePlan;
import com.inventoryerp.manufacturing.application.PlanningCommands.PlanFromDemand;
import com.inventoryerp.manufacturing.domain.ProductionDemandPeg;
import com.inventoryerp.manufacturing.domain.ProductionPlanLine;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demand-driven planning (Track C 2.5, ADR-S017): the requirement of a horizon from the fleet delivery schedule,
 * confirmed sales orders, approved proformas, a sales-history forecast and stock policy, netted against stock and open
 * production in priority order. What stays uncovered is the shortfall; a draft production plan can be made from it,
 * with every line pegged to the demand it serves. Other modules' tables are read, never written.
 */
@Service
public class PlanningDemandService {

    private static final int DEFAULT_DAYS = 30;
    private static final int DEFAULT_WEEKS = 4;

    private final MfgDb db;
    private final BomService boms;
    private final MrpPlanningService mrp;
    private final ProductionPlanService plans;
    private final ProductionService production;
    private final PlanningPegService pegs;

    public PlanningDemandService(MfgDb db, BomService boms, MrpPlanningService mrp, ProductionPlanService plans,
            ProductionService production, PlanningPegService pegs) {
        this.db = db;
        this.boms = boms;
        this.mrp = mrp;
        this.plans = plans;
        this.production = production;
        this.pegs = pegs;
    }

    private record Horizon(LocalDate from, LocalDate to) {
        Instant end() {
            return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        long days() {
            return ChronoUnit.DAYS.between(from, to) + 1;
        }
    }

    private static Horizon horizon(String from, String to) {
        LocalDate f = parse(from) != null ? parse(from) : LocalDate.now(BusinessZone.get());
        LocalDate t = parse(to) != null ? parse(to) : f.plusDays(DEFAULT_DAYS);
        if (t.isBefore(f)) {
            throw ApiException.badRequest("to must not be before from");
        }
        return new Horizon(f, t);
    }

    private static LocalDate parse(String d) {
        if (d == null || d.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(d.length() > 10 ? d.substring(0, 10) : d);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("from / to must be ISO dates (YYYY-MM-DD)");
        }
    }

    // ================================================================== demand

    /** Every requirement due by the end of the horizon (overdue ones included). */
    List<Demand> collect(LocalDate from, LocalDate to, boolean forecast, int weeks, boolean stockPolicy) {
        Horizon h = new Horizon(from, to);
        List<Demand> out = new ArrayList<>();
        Set<String> scheduledOrders = new HashSet<>();
        Instant now = Instant.now();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT l.id AS \"lineId\", l.\"productId\","
                + " (l.\"orderedQty\" - l.\"dispatchedQty\") * l.\"uomFactor\" AS qty, d.id, d.code, d.\"partnerId\","
                + " d.\"siteId\", d.\"requestedDate\", d.priority, d.\"sourceType\", d.\"sourceId\""
                + " FROM \"FleetDeliveryOrderLine\" l JOIN \"FleetDeliveryOrder\" d ON d.id = l.\"deliveryOrderId\""
                + " WHERE d.\"organizationId\" = ? AND d.status IN ('open', 'planned', 'in_progress')"
                + " AND d.\"requestedDate\" <= ?").params(MfgDb.org(), java.sql.Date.valueOf(to)))) {
            if ("sales_order".equals(r.get("sourceType")) && r.get("sourceId") != null) {
                scheduledOrders.add(str(r, "sourceId"));
            }
            if (dec(r, "qty").signum() > 0) {
                Instant due = ((LocalDate) r.get("requestedDate")).atStartOfDay(ZoneOffset.UTC).toInstant();
                out.add(new Demand("delivery_order", str(r, "id"), str(r, "lineId"), str(r, "code"), str(r,
                        "productId"),
                        str(r, "partnerId"), str(r, "siteId"), due, DemandNetting.priority(str(r, "priority")),
                        dec(r, "qty")));
            }
        }
        // Confirmed back-office orders with a customer; an order already on the delivery schedule counts once.
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT i.id AS \"lineId\", i.\"productId\","
                + " i.quantity,"
                + " o.id, o.\"orderNumber\", o.\"partnerId\", o.\"openedAt\" FROM \"OrderItem\" i JOIN \"Order\" o ON"
                + " o.id = i.\"orderId\" WHERE o.\"organizationId\" = ? AND o.status = 'confirmed'"
                + " AND o.\"partnerId\" IS NOT NULL AND i.\"productId\" IS NOT NULL AND i.cancelled = false")
                .param(MfgDb.org()))) {
            if (!scheduledOrders.contains(str(r, "id"))) {
                out.add(new Demand("sales_order", str(r, "id"), str(r, "lineId"), str(r, "orderNumber"),
                        str(r, "productId"), str(r, "partnerId"), null, later(now, (Instant) r.get("openedAt")),
                        DemandNetting.NORMAL, dec(r, "quantity")));
            }
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT l.id AS \"lineId\", l.\"productId\","
                + " l.quantity,"
                + " d.id, d.\"documentNumber\", d.\"partnerId\", d.\"dueDate\" FROM \"DocumentLine\" l JOIN"
                + " \"Document\" d"
                + " ON d.id = l.\"documentId\" WHERE d.\"organizationId\" = ? AND d.\"documentType\" ="
                + " 'proforma_invoice'"
                + " AND d.status IN ('approved', 'confirmed', 'issued', 'active') AND l.\"productId\" IS NOT NULL"
                + " AND (d.\"dueDate\" IS NULL OR d.\"dueDate\" < ?)").params(MfgDb.org(), MfgDb.param(h.end())))) {
            out.add(new Demand("proforma", str(r, "id"), str(r, "lineId"), str(r, "documentNumber"), str(r,
                    "productId"),
                    str(r, "partnerId"), null, later(now, (Instant) r.get("dueDate")), DemandNetting.NORMAL,
                    dec(r, "quantity")));
        }
        if (forecast) {
            out.addAll(forecast(h, weeks, out));
        }
        if (stockPolicy) {
            for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT \"productId\", greatest(\"safetyStock\","
                    + " \"reorderPoint\") AS qty FROM \"ProductPlanning\" WHERE \"organizationId\" = ?"
                    + " AND \"isActive\" = true AND greatest(\"safetyStock\", \"reorderPoint\") > 0")
                    .param(MfgDb.org()))) {
                out.add(new Demand("stock_policy", null, null, "Stock policy", str(r, "productId"), null, null,
                        h.end(), DemandNetting.LOW, dec(r, "qty")));
            }
        }
        return out;
    }

    /**
     * Sales run-rate of the last {@code weeks} × the horizon days, for products with an active BOM, less the firm
     * demand already known (a forecast is consumed by real orders).
     */
    private List<Demand> forecast(Horizon h, int weeks, List<Demand> firm) {
        Map<String, BigDecimal> known = new HashMap<>();
        for (Demand d : firm) {
            known.merge(d.productId(), d.qty(), BigDecimal::add);
        }
        Instant since = Instant.now().minus(weeks * 7L, ChronoUnit.DAYS);
        List<Demand> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT i.\"productId\", sum(i.quantity) AS qty FROM"
                + " \"InvoiceItem\" i JOIN \"Invoice\" v ON v.id = i.\"invoiceId\" WHERE i.\"organizationId\" = ?"
                + " AND i.\"productId\" IS NOT NULL AND v.\"issueDate\" >= ? AND v.status NOT IN ('cancelled', 'draft')"
                + " GROUP BY i.\"productId\"").params(MfgDb.org(), MfgDb.param(since)))) {
            String productId = str(r, "productId");
            if (boms.findActive(productId, "") == null) {
                continue;
            }
            BigDecimal perDay = dec(r, "qty").divide(BigDecimal.valueOf(weeks * 7L), 6, RoundingMode.HALF_UP);
            BigDecimal net = perDay.multiply(BigDecimal.valueOf(h.days()))
                    .subtract(known.getOrDefault(productId, BigDecimal.ZERO)).setScale(3, RoundingMode.HALF_UP);
            if (net.signum() > 0) {
                out.add(new Demand("forecast", null, null, "Forecast", productId, null, null, h.end(),
                        DemandNetting.NORMAL, net));
            }
        }
        return out;
    }

    private static Instant later(Instant now, Instant date) {
        return date == null || date.isBefore(now) ? now : date;
    }

    private BigDecimal supply(String productId) {
        return mrp.onHand(productId, null).subtract(mrp.reservedByOthers(productId, null))
                .add(mrp.openProductionOutput(productId)).add(mrp.openCoProductOutput(productId));
    }

    /** The netted demand of a horizon: a row per requirement plus a line per product. */
    @Transactional(readOnly = true)
    public Map<String, Object> demand(String from, String to, Boolean includeForecast, Integer weeks,
            Boolean includeStockPolicy) {
        Horizon h = horizon(from, to);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> products = new ArrayList<>();
        netted(h, Boolean.TRUE.equals(includeForecast), weeks == null ? DEFAULT_WEEKS : weeks,
                !Boolean.FALSE.equals(includeStockPolicy), rows, products);
        return Obj.of("from", h.from().toString(), "to", h.to().toString(), "rows", rows, "products", products);
    }

    /** The uncovered part of the demand only, worst first. */
    @Transactional(readOnly = true)
    public Map<String, Object> shortfall(String from, String to, Boolean includeForecast) {
        Horizon h = horizon(from, to);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> products = new ArrayList<>();
        netted(h, Boolean.TRUE.equals(includeForecast), DEFAULT_WEEKS, true, rows, products);
        rows.removeIf(r -> dec(r, "uncoveredQty").signum() <= 0);
        products.removeIf(p -> dec(p, "shortfallQty").signum() <= 0);
        products.sort(Comparator.comparing((Map<String, Object> p) -> dec(p, "shortfallQty")).reversed());
        return Obj.of("from", h.from().toString(), "to", h.to().toString(), "rows", rows, "products", products);
    }

    private void netted(Horizon h, boolean forecast, int weeks, boolean stockPolicy, List<Map<String, Object>> rows,
            List<Map<String, Object>> products) {
        Map<String, List<Demand>> byProduct = group(collect(h.from(), h.to(), forecast, weeks, stockPolicy));
        Map<String, Map<String, Object>> names = names(byProduct.keySet());
        Map<String, String> partners = lookup("Partner", byProduct.values(), Demand::partnerId);
        Map<String, String> sites = lookup("FleetCustomerSite", byProduct.values(), Demand::siteId);
        for (Map.Entry<String, List<Demand>> e : byProduct.entrySet()) {
            String productId = e.getKey();
            BigDecimal onHand = mrp.onHand(productId, null);
            BigDecimal supply = supply(productId);
            BigDecimal demand = BigDecimal.ZERO;
            BigDecimal uncovered = BigDecimal.ZERO;
            Map<String, Object> product = names.get(productId);
            for (Allocation a : DemandNetting.allocate(supply, e.getValue())) {
                Demand d = a.demand();
                demand = demand.add(d.qty());
                uncovered = uncovered.add(a.uncovered());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("demandType", d.type());
                row.put("demandId", d.id());
                row.put("demandLineId", d.lineId());
                row.put("ref", d.ref());
                row.put("productId", productId);
                row.put("productCode", product == null ? null : product.get("code"));
                row.put("productName", product == null ? null : product.get("name"));
                row.put("partnerId", d.partnerId());
                row.put("partnerName", partners.get(d.partnerId()));
                row.put("siteId", d.siteId());
                row.put("siteName", sites.get(d.siteId()));
                row.put("dueAt", d.dueAt());
                row.put("priority", d.priority());
                row.put("quantity", d.qty());
                row.put("coveredQty", a.covered());
                row.put("uncoveredQty", a.uncovered());
                rows.add(row);
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("productId", productId);
            line.put("productCode", product == null ? null : product.get("code"));
            line.put("productName", product == null ? null : product.get("name"));
            line.put("demandQty", demand);
            line.put("onHandQty", onHand);
            line.put("supplyQty", supply);
            line.put("shortfallQty", uncovered);
            line.put("makeable", boms.findActive(productId, "") != null);
            products.add(line);
        }
    }

    private static Map<String, List<Demand>> group(List<Demand> demand) {
        Map<String, List<Demand>> byProduct = new LinkedHashMap<>();
        for (Demand d : demand) {
            byProduct.computeIfAbsent(d.productId(), k -> new ArrayList<>()).add(d);
        }
        return byProduct;
    }

    private Map<String, Map<String, Object>> names(Set<String> productIds) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        for (Map<String, Object> p : SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"Product\" WHERE"
                + " \"organizationId\" = :org AND id IN (:ids)").param("org", MfgDb.org()).param("ids",
                        List.copyOf(productIds)))) {
            out.put(str(p, "id"), p);
        }
        return out;
    }

    private Map<String, String> lookup(String table, java.util.Collection<List<Demand>> groups,
            java.util.function.Function<Demand, String> key) {
        Set<String> ids = new HashSet<>();
        for (List<Demand> g : groups) {
            for (Demand d : g) {
                if (key.apply(d) != null) {
                    ids.add(key.apply(d));
                }
            }
        }
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT id, name FROM \"" + table + "\" WHERE"
                + " \"organizationId\" = :org AND id IN (:ids)").param("org", MfgDb.org()).param("ids",
                        List.copyOf(ids)))) {
            out.put(str(r, "id"), str(r, "name"));
        }
        return out;
    }

    // ================================================================== plan from demand

    private record Pending(String productId, Map<String, Object> bom, BigDecimal qty, Instant scheduled, Demand top,
            List<Allocation> uncovered, String uomId) {}

    /**
     * A draft production plan for what stock and open orders cannot cover: one line per makeable product, lot-sized,
     * grossed up for yield, due at its earliest uncovered demand and scheduled early enough for lead time and curing,
     * with a peg per uncovered requirement. Products without an active BOM are reported, not planned.
     */
    @Transactional
    public Map<String, Object> planFromDemand(PlanFromDemand dto) {
        production.requireLocation(dto.locationId());
        Horizon h = horizon(dto.dateFrom(), dto.dateTo());
        boolean forecast = Boolean.TRUE.equals(dto.includeForecast());
        int weeks = dto.forecastWeeks() == null ? DEFAULT_WEEKS : dto.forecastWeeks();
        boolean stockPolicy = !Boolean.FALSE.equals(dto.includeStockPolicy());
        Map<String, List<Demand>> byProduct = group(collect(h.from(), h.to(), forecast, weeks, stockPolicy));
        Instant now = Instant.now();
        List<Pending> pending = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Map.Entry<String, List<Demand>> e : byProduct.entrySet()) {
            String productId = e.getKey();
            if (dto.productIds() != null && !dto.productIds().contains(productId)) {
                continue;
            }
            List<Allocation> open = new ArrayList<>();
            BigDecimal net = BigDecimal.ZERO;
            for (Allocation a : DemandNetting.allocate(supply(productId), e.getValue())) {
                if (a.uncovered().signum() > 0) {
                    open.add(a);
                    net = net.add(a.uncovered());
                }
            }
            if (net.signum() <= 0) {
                continue;
            }
            Map<String, Object> bom = boms.findActive(productId, "");
            if (bom == null) {
                skipped.add(Obj.of("productId", productId, "shortfallQty", net, "reason", "No active BOM"));
                continue;
            }
            MrpPlanningService.Params prm = mrp.params(productId);
            BigDecimal qty = MrpPlanningService.lotSize(MrpPlanningService.grossForYield(net,
                    dec(bom, "expectedYieldPct")), prm);
            Instant due = open.stream().map(a -> a.demand().dueAt()).min(Comparator.naturalOrder()).orElse(now);
            Instant scheduled = due.minus(prm.leadTimeDays() + mrp.curingDays(bom), ChronoUnit.DAYS);
            pending.add(new Pending(productId, bom, qty, scheduled.isBefore(now) ? now : scheduled,
                    open.getFirst().demand(), open, str(production.getProduct(productId), "uomId")));
        }
        if (pending.isEmpty()) {
            throw ApiException.badRequest("Nothing to plan: stock and open production cover the demand.");
        }
        pending.sort(Comparator.comparing(Pending::top, DemandNetting.RANK));
        String name = dto.name() != null && !dto.name().isBlank() ? dto.name()
                : "Demand " + h.from() + " to " + h.to();
        Map<String, Object> plan = plans.create(new CreatePlan(name, h.from().toString(), h.to().toString(),
                dto.locationId(), dto.notes(), null));
        String planId = str(plan, "id");
        int number = 0;
        for (Pending p : pending) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("planId", planId);
            line.put("productId", p.productId());
            line.put("bomId", p.bom().get("id"));
            line.put("targetQty", p.qty());
            line.put("scheduledDate", p.scheduled());
            line.put("uomId", p.uomId());
            line.put("notes", p.top().ref());
            line.put("lineNumber", number++);
            Map<String, Object> saved = db.insert(ProductionPlanLine.class, line);
            for (Allocation a : p.uncovered()) {
                Demand d = a.demand();
                Map<String, Object> peg = new LinkedHashMap<>();
                peg.put("planId", planId);
                peg.put("planLineId", saved.get("id"));
                peg.put("productId", p.productId());
                peg.put("demandType", d.type());
                peg.put("demandId", d.id());
                peg.put("demandLineId", d.lineId());
                peg.put("demandRef", d.ref());
                peg.put("partnerId", d.partnerId());
                peg.put("siteId", d.siteId());
                peg.put("dueAt", d.dueAt());
                peg.put("priority", d.priority());
                peg.put("quantity", a.uncovered());
                db.insert(ProductionDemandPeg.class, peg);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(plans.get(planId));
        out.put("pegs", pegs.pegs(null, planId, null, null));
        out.put("skipped", skipped);
        return out;
    }
}
