package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.inventory.application.StockReservationService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Available-to-promise and firm reservations for sales orders (ADR-S011 phase S4, sales.md §9).
 *
 * <p>ATP per location = on hand − active reservations (reservations already net the stock, so committed demand is
 * only the unreserved remainder). Expected supply (open purchase lines; production once connected) counts until it is
 * received and only for the part not yet pegged to other order lines — so nothing is counted twice.
 *
 * <p>Reservations: on confirmation (when {@code SalesSettings.reservationTrigger = confirm}) each stock-tracked line
 * reserves its open quantity at its source location (else the order location, else the location with the most free
 * stock) inside the {@code StockItem} row lock — two concurrent confirmations cannot both take the same stock. What
 * cannot be reserved is a backorder, pegged softly to open purchase supply by date; a peg is never a reservation. The
 * backorder job retries reservations by order priority, then confirmation time.
 */
@Service
public class SalesAtpService {

    public static final String SOURCE = "sales_order_line";

    private final SalesDb db;
    private final StockReservationService reservations;
    private final UomConversionService uoms;
    private final List<SupplyProvider> suppliers;

    private SalesAccess access;

    @org.springframework.beans.factory.annotation.Autowired
    void setAccess(SalesAccess access) {
        this.access = access;
    }

    public SalesAtpService(SalesDb db, StockReservationService reservations, UomConversionService uoms,
            List<SupplyProvider> suppliers) {
        this.db = db;
        this.reservations = reservations;
        this.uoms = uoms;
        this.suppliers = suppliers;
    }

    // ------------------------------------------------------------------ ATP

    /** {@code POST /sales/atp}: {@code lines [{productId, quantity, uomId}]}, optional {@code locationId}. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> check(Map<String, Object> in) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (in.get("lines") instanceof List<?> lines) {
            for (Object o : lines) {
                Map<String, Object> l = (Map<String, Object>) o;
                BigDecimal qty = l.get("quantity") == null ? BigDecimal.ONE : dec(l.get("quantity"));
                out.add(atp(str(l.get("productId")), str(l.get("variantId")), qty, str(l.get("uomId")),
                        str(in.get("locationId")), null));
            }
        }
        return Obj.of("lines", out, "production", Obj.of("connected", suppliers.stream().anyMatch(s -> "production"
                .equals(s.type()))));
    }

    /** ATP of every open line of an order. */
    @Transactional(readOnly = true)
    public Map<String, Object> forOrder(String orderId) {
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", orderId,
                org());
        if (o == null) {
            throw ApiException.notFound("Sales order not found");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : db.rows("SELECT l.*, p.\"trackInventory\" FROM \"SalesOrderLine\" l LEFT JOIN"
                + " \"Product\" p ON p.id = l.\"productId\" WHERE l.\"orderId\" = ? ORDER BY l.\"sortOrder\"", orderId)) {
            if (l.get("productId") == null || !Boolean.TRUE.equals(l.get("trackInventory"))) {
                continue;
            }
            BigDecimal open = open(l);
            Map<String, Object> a = atp(str(l.get("productId")), str(l.get("variantId")), open, str(l.get("uomId")),
                    location(l, o), str(l.get("id")));
            a.put("lineId", l.get("id"));
            a.put("reserved", JsNumbers.js(dec(l.get("qtyReserved"))));
            a.put("promisedDate", l.get("promisedDate"));
            a.put("promiseAtRisk", l.get("promiseAtRisk"));
            a.put("pegs", db.rows("SELECT \"supplyType\", \"supplyNumber\", quantity, \"expectedDate\" FROM"
                    + " \"SalesSupplyPeg\" WHERE \"orderLineId\" = ? ORDER BY \"expectedDate\" NULLS LAST", l.get("id")));
            out.add(a);
        }
        return Obj.of("orderId", orderId, "lines", out, "production", Obj.of("connected", suppliers.stream()
                .anyMatch(s -> "production".equals(s.type()))));
    }

    /**
     * ATP of one product: per-location on hand / reserved / free, free supply by date, earliest full-fill date, best
     * location and a split proposal. Quantities in the line unit ({@code uomId}); {@code ownLine} excludes that line's
     * own reservation and pegs (re-planning an existing line).
     */
    Map<String, Object> atp(String productId, String variantId, BigDecimal qty, String uomId, String locationId,
            String ownLine) {
        String variantKey = variantId == null ? "" : variantId;
        BigDecimal factor = blank(uomId) ? BigDecimal.ONE : uoms.factorToBase(productId, uomId);
        BigDecimal need = qty.multiply(factor);
        List<Map<String, Object>> locs = new ArrayList<>();
        BigDecimal totalFree = BigDecimal.ZERO;
        Map<String, Object> best = null;
        for (Map<String, Object> r : db.rows("""
                SELECT s."locationId", l.name, sum(s.quantity) AS onhand,
                       COALESCE((SELECT sum(r.quantity) FROM "StockReservation" r WHERE r."organizationId" = s."organizationId"
                           AND r."productId" = s."productId" AND r."locationId" = s."locationId" AND r.status = 'active'
                           AND (? = '' OR r."variantKey" = ?)
                           AND NOT (r."sourceType" = ? AND r."sourceId" = ?)), 0) AS reserved
                  FROM "StockItem" s JOIN "InventoryLocation" l ON l.id = s."locationId"
                 WHERE s."organizationId" = ? AND s."productId" = ? AND (? = '' OR s."variantKey" = ?)
                   AND l."deletedAt" IS NULL AND l."isActive"
                   AND l.type::text NOT IN ('virtual', 'production', 'transit')
                 GROUP BY s."locationId", l.name, s."organizationId", s."productId"
                 ORDER BY sum(s.quantity) DESC""", variantKey, variantKey, SOURCE, ownLine == null ? "" : ownLine, org(),
                productId, variantKey, variantKey)) {
            BigDecimal free = dec(r.get("onhand")).subtract(dec(r.get("reserved"))).max(BigDecimal.ZERO);
            Map<String, Object> m = Obj.of("locationId", r.get("locationId"), "name", r.get("name"), "onHand",
                    unit(dec(r.get("onhand")), factor), "reserved", unit(dec(r.get("reserved")), factor), "available",
                    unit(free, factor));
            m.put("free", free);
            if (locationId == null || locationId.equals(r.get("locationId"))) {
                totalFree = totalFree.add(free);
            }
            if (best == null || free.compareTo((BigDecimal) best.get("free")) > 0) {
                best = m;
            }
            locs.add(m);
        }
        // free supply: open supply minus quantities pegged to other lines
        List<Map<String, Object>> supply = new ArrayList<>();
        BigDecimal cumulative = totalFree;
        LocalDate earliest = cumulative.compareTo(need) >= 0 ? LocalDate.now() : null;
        for (SupplyProvider p : suppliers) {
            for (SupplyProvider.Supply s : p.supply(productId)) {
                BigDecimal pegged = dec(db.row("SELECT COALESCE(sum(quantity), 0) AS q FROM \"SalesSupplyPeg\" WHERE"
                        + " \"organizationId\" = ? AND \"supplyType\" = ? AND \"supplyId\" = ? AND \"orderLineId\" <> ?",
                        org(), s.type(), s.id(), ownLine == null ? "" : ownLine).get("q"));
                BigDecimal free = s.quantity().subtract(pegged).max(BigDecimal.ZERO);
                if (free.signum() == 0) {
                    continue;
                }
                cumulative = cumulative.add(free);
                if (earliest == null && cumulative.compareTo(need) >= 0) {
                    earliest = s.expectedDate();
                }
                supply.add(Obj.of("type", s.type(), "id", s.id(), "number", s.number(), "expectedDate", s.expectedDate(),
                        "available", unit(free, factor)));
            }
        }
        // split proposal: greedy over locations by free stock
        List<Map<String, Object>> split = new ArrayList<>();
        BigDecimal left = need;
        for (Map<String, Object> l : locs) {
            BigDecimal free = (BigDecimal) l.get("free");
            if (left.signum() <= 0 || free.signum() <= 0) {
                continue;
            }
            BigDecimal take = free.min(left);
            split.add(Obj.of("locationId", l.get("locationId"), "name", l.get("name"), "quantity", unit(take, factor)));
            left = left.subtract(take);
        }
        locs.forEach(l -> l.remove("free"));
        boolean today = totalFree.compareTo(need) >= 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("productId", productId);
        out.put("quantity", JsNumbers.js(qty));
        out.put("uomId", uomId);
        out.put("availableNow", unit(totalFree, factor));
        out.put("canPromiseToday", today);
        out.put("fullFillLocation", locs.stream().filter(l -> dec(l.get("available")).compareTo(qty) >= 0).map(l -> l.get(
                "locationId")).findFirst().orElse(null));
        out.put("bestLocation", best == null ? null : best.get("locationId"));
        out.put("earliestDate", earliest);
        out.put("shortage", unit(need.subtract(totalFree).max(BigDecimal.ZERO), factor));
        out.put("locations", locs);
        out.put("supply", supply);
        out.put("split", split);
        return out;
    }

    private static Object unit(BigDecimal base, BigDecimal factor) {
        return JsNumbers.js(base.divide(factor, 6, RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------ reservations

    /** Reserve / re-plan every open stock line of an order (idempotent; called on confirm, change, close short). */
    @Transactional
    public Map<String, Object> reserveOrder(String orderId) {
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", orderId,
                org());
        if (o == null) {
            throw ApiException.notFound("Sales order not found");
        }
        boolean open = SalesOrderService.OPEN.contains(o.get("status"));
        BigDecimal reservedTotal = BigDecimal.ZERO;
        BigDecimal shortTotal = BigDecimal.ZERO;
        for (Map<String, Object> l : db.rows("SELECT l.*, p.\"trackInventory\" FROM \"SalesOrderLine\" l LEFT JOIN"
                + " \"Product\" p ON p.id = l.\"productId\" WHERE l.\"orderId\" = ? ORDER BY l.\"sortOrder\"", orderId)) {
            if (!open || l.get("productId") == null || !Boolean.TRUE.equals(l.get("trackInventory"))) {
                releaseLine(str(l.get("id")));
                continue;
            }
            BigDecimal[] r = reserveLine(o, l);
            reservedTotal = reservedTotal.add(r[0]);
            shortTotal = shortTotal.add(r[1]);
        }
        return Obj.of("orderId", orderId, "reserved", JsNumbers.js(reservedTotal), "backorder", JsNumbers.js(shortTotal));
    }

    /** Returns {reserved, shortage} in line units. */
    private BigDecimal[] reserveLine(Map<String, Object> o, Map<String, Object> l) {
        String lineId = str(l.get("id"));
        String productId = str(l.get("productId"));
        String variantId = str(l.get("variantId"));
        String variantKey = variantId == null ? "" : variantId;
        BigDecimal factor = dec(l.get("uomFactor")).signum() > 0 ? dec(l.get("uomFactor")) : BigDecimal.ONE;
        BigDecimal desired = open(l).multiply(factor);
        String location = str(l.get("reservedLocationId"));
        if (location == null) {
            location = location(l, o);
        }
        if (location == null) {
            Object best = atp(productId, variantId, BigDecimal.ONE, null, null, lineId).get("bestLocation");
            location = best == null ? null : str(best);
        }
        BigDecimal reservable = BigDecimal.ZERO;
        if (location != null && desired.signum() > 0) {
            // lock the stock cell, then measure what is free (other sources' active reservations) inside the lock
            db.rows("SELECT id FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ?"
                    + " AND \"locationId\" = ? FOR UPDATE", org(), productId, variantKey, location);
            Map<String, Object> cell = db.row("""
                    SELECT COALESCE((SELECT sum(quantity) FROM "StockItem" WHERE "organizationId" = ? AND "productId" = ?
                               AND "locationId" = ? AND (? = '' OR "variantKey" = ?)), 0) AS onhand,
                           COALESCE((SELECT sum(quantity) FROM "StockReservation" WHERE "organizationId" = ? AND "productId" = ?
                               AND "locationId" = ? AND (? = '' OR "variantKey" = ?) AND status = 'active'
                               AND NOT ("sourceType" = ? AND "sourceId" = ?)), 0)
                               AS reserved""", org(), productId, location, variantKey, variantKey, org(), productId,
                    location, variantKey, variantKey, SOURCE, lineId);
            BigDecimal free = dec(cell.get("onhand")).subtract(dec(cell.get("reserved"))).max(BigDecimal.ZERO);
            reservable = desired.min(free);
        }
        if (reservable.signum() > 0) {
            reservations.reserve(new StockReservationService.Reserve(productId, variantId, location, reservable, SOURCE, lineId,
                    "Sales order " + o.get("number")));
        } else {
            reservations.release(SOURCE, lineId);
        }
        BigDecimal shortage = desired.subtract(reservable);
        db.update("DELETE FROM \"SalesSupplyPeg\" WHERE \"orderLineId\" = ?", lineId);
        LocalDate lastPeg = null;
        BigDecimal unpegged = shortage;
        if (shortage.signum() > 0) {
            for (SupplyProvider p : suppliers) {
                for (SupplyProvider.Supply s : p.supply(productId)) {
                    if (unpegged.signum() <= 0) {
                        break;
                    }
                    BigDecimal pegged = dec(db.row("SELECT COALESCE(sum(quantity), 0) AS q FROM \"SalesSupplyPeg\" WHERE"
                            + " \"organizationId\" = ? AND \"supplyType\" = ? AND \"supplyId\" = ?", org(), s.type(),
                            s.id()).get("q"));
                    BigDecimal take = s.quantity().subtract(pegged).max(BigDecimal.ZERO).min(unpegged);
                    if (take.signum() <= 0) {
                        continue;
                    }
                    db.update("INSERT INTO \"SalesSupplyPeg\" (id, \"organizationId\", \"orderId\", \"orderLineId\","
                            + " \"productId\", \"supplyType\", \"supplyId\", \"supplyNumber\", quantity, \"expectedDate\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", SalesDb.newId(), org(), o.get("id"), lineId,
                            productId, s.type(), s.id(), s.number(), take, s.expectedDate());
                    unpegged = unpegged.subtract(take);
                    lastPeg = s.expectedDate();
                }
            }
        }
        LocalDate promised = l.get("promisedDate") instanceof LocalDate d ? d : null;
        boolean atRisk = shortage.signum() > 0 && (unpegged.signum() > 0 || lastPeg == null
                || promised != null && lastPeg.isAfter(promised));
        db.update("UPDATE \"SalesOrderLine\" SET \"qtyReserved\" = ?, \"reservedLocationId\" = ?, \"promiseAtRisk\" = ?"
                + " WHERE id = ?", reservable.divide(factor, 6, RoundingMode.HALF_UP), reservable.signum() > 0 ? location
                        : null, atRisk, lineId);
        return new BigDecimal[] {reservable.divide(factor, 6, RoundingMode.HALF_UP), shortage.divide(factor, 6,
                RoundingMode.HALF_UP)};
    }

    /** Lines of a draft or open order that stock cannot cover today, with the shortage in line units. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> shortages(String orderId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : castLines(forOrder(orderId).get("lines"))) {
            if (dec(a.get("shortage")).signum() > 0) {
                out.add(Obj.of("lineId", a.get("lineId"), "productId", a.get("productId"), "shortage", a.get("shortage"),
                        "available", a.get("availableNow"), "fullFillLocation", a.get("fullFillLocation"),
                        "earliestDate", a.get("earliestDate")));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castLines(Object v) {
        return (List<Map<String, Object>>) v;
    }

    /** Move a line to another warehouse and re-plan its reservation; refused when that warehouse cannot cover it. */
    @Transactional
    public Map<String, Object> pickLocation(String orderId, String lineId, String locationId) {
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE",
                orderId, org());
        if (o == null) {
            throw ApiException.notFound("Sales order not found");
        }
        if (Set.of("cancelled", "done").contains(o.get("status"))) {
            throw ApiException.badRequest("This order is closed");
        }
        if (!db.exists("InventoryLocation", locationId)) {
            throw ApiException.notFound("Location not found");
        }
        if (!access.scope().allowsLocation(locationId)) {
            throw ApiException.forbidden("This warehouse is outside your data access");
        }
        Map<String, Object> l = db.row("SELECT * FROM \"SalesOrderLine\" WHERE id = ? AND \"orderId\" = ? AND"
                + " \"organizationId\" = ? FOR UPDATE", lineId, orderId, org());
        if (l == null) {
            throw ApiException.notFound("Order line not found");
        }
        if (dec(l.get("qtyDelivered")).signum() > 0) {
            throw ApiException.badRequest("This line has deliveries; its warehouse can no longer change");
        }
        Map<String, Object> a = atp(str(l.get("productId")), str(l.get("variantId")), open(l), str(l.get("uomId")),
                locationId, lineId);
        if (dec(a.get("shortage")).signum() > 0) {
            throw ApiException.badRequest("This warehouse has only " + a.get("availableNow") + " available");
        }
        releaseLine(lineId);
        db.update("UPDATE \"SalesOrderLine\" SET \"sourceLocationId\" = ?, \"reservedLocationId\" = NULL WHERE id = ?",
                locationId, lineId);
        return SalesOrderService.OPEN.contains(o.get("status")) ? reserveOrder(orderId) : Obj.of("orderId", orderId);
    }

    /** Release a line's reservation and pegs. */
    @Transactional
    public void releaseLine(String lineId) {
        reservations.release(SOURCE, lineId);
        db.update("DELETE FROM \"SalesSupplyPeg\" WHERE \"orderLineId\" = ?", lineId);
        db.update("UPDATE \"SalesOrderLine\" SET \"qtyReserved\" = 0, \"reservedLocationId\" = NULL, \"promiseAtRisk\" ="
                + " false WHERE id = ?", lineId);
    }

    /** Release every line of an order (cancel / done). */
    @Transactional
    public void releaseOrder(String orderId) {
        for (Map<String, Object> l : db.rows("SELECT id FROM \"SalesOrderLine\" WHERE \"orderId\" = ?", orderId)) {
            releaseLine(str(l.get("id")));
        }
    }

    /** Active reservations and pegs of an order. */
    @Transactional(readOnly = true)
    public Map<String, Object> reservationsOf(String orderId) {
        List<Map<String, Object>> rows = db.rows("SELECT r.*, l.name AS \"locationName\", ol.description FROM"
                + " \"StockReservation\" r JOIN \"SalesOrderLine\" ol ON ol.id = r.\"sourceId\" LEFT JOIN"
                + " \"InventoryLocation\" l ON l.id = r.\"locationId\" WHERE r.\"organizationId\" = ? AND r.\"sourceType\""
                + " = ? AND ol.\"orderId\" = ? ORDER BY r.\"createdAt\"", org(), SOURCE, orderId);
        List<Map<String, Object>> pegs = db.rows("SELECT p.*, ol.description FROM \"SalesSupplyPeg\" p JOIN"
                + " \"SalesOrderLine\" ol ON ol.id = p.\"orderLineId\" WHERE p.\"orderId\" = ? AND p.\"organizationId\" = ?"
                + " ORDER BY p.\"expectedDate\" NULLS LAST", orderId, org());
        return Obj.of("reservations", rows, "pegs", pegs);
    }

    /** Backorder report: open confirmed lines not fully reserved, by priority and confirmation. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> backorders() {
        access.assertAllBranches();
        return db.rows("""
                SELECT o.id AS "orderId", o.number, o.priority, p.name AS "partnerName", l.id AS "lineId", l.description,
                       l."productId", l.quantity - l."qtyCancelled" - l."qtyDelivered" AS open, l."qtyReserved" AS reserved,
                       l.quantity - l."qtyCancelled" - l."qtyDelivered" - l."qtyReserved" AS backorder, l."promisedDate",
                       l."promiseAtRisk",
                       (SELECT min("expectedDate") FROM "SalesSupplyPeg" g WHERE g."orderLineId" = l.id) AS "nextSupply"
                  FROM "SalesOrderLine" l JOIN "SalesOrder" o ON o.id = l."orderId" JOIN "Product" pr ON pr.id = l."productId"
                  LEFT JOIN "Partner" p ON p.id = o."partnerId"
                 WHERE o."organizationId" = ? AND o.status IN ('confirmed', 'in_fulfilment') AND o."deletedAt" IS NULL
                   AND pr."trackInventory" AND l.quantity - l."qtyCancelled" - l."qtyDelivered" - l."qtyReserved" > 0
                 ORDER BY o.priority DESC, o."confirmedAt\"""", org());
    }

    /** Backorder job body: retry reservations of orders with unreserved stock lines. Returns orders re-planned. */
    @Transactional
    public int retryBackorders() {
        List<Map<String, Object>> orders = db.rows("""
                SELECT DISTINCT o.id, o.priority, o."confirmedAt" FROM "SalesOrder" o JOIN "SalesOrderLine" l ON l."orderId" = o.id
                  JOIN "Product" pr ON pr.id = l."productId"
                 WHERE o."organizationId" = ? AND o.status IN ('confirmed', 'in_fulfilment') AND o."deletedAt" IS NULL
                   AND pr."trackInventory" AND l.quantity - l."qtyCancelled" - l."qtyDelivered" - l."qtyReserved" > 0
                 ORDER BY o.priority DESC, o."confirmedAt\"""", org());
        for (Map<String, Object> o : orders) {
            reserveOrder(str(o.get("id")));
        }
        return orders.size();
    }

    // ------------------------------------------------------------------ helpers

    /** Open quantity of a line in line units: ordered − closed short − delivered. */
    static BigDecimal open(Map<String, Object> l) {
        return dec(l.get("quantity")).subtract(dec(l.get("qtyCancelled"))).subtract(dec(l.get("qtyDelivered")))
                .max(BigDecimal.ZERO);
    }

    private static String location(Map<String, Object> l, Map<String, Object> o) {
        return l.get("sourceLocationId") != null ? str(l.get("sourceLocationId"))
                : o.get("locationId") != null ? str(o.get("locationId")) : null;
    }
}
