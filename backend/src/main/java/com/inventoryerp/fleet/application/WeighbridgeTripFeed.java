package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.domain.FleetDeliveryOrder;
import com.inventoryerp.fleet.domain.FleetLoad;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.fleet.domain.WeighbridgeTicket;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weighbridge ↔ dispatch wiring (fleet 1.4b). An outbound ({@code sale_delivery}) ticket can reference a 1.3 trip:
 * registration pre-fills the truck, driver / transporter, customer, delivery order, product and planned quantity from
 * the trip; the trip must be open (planned / loading / weighed) and can be on one live ticket only.
 *
 * <p><b>Completion</b> (within tolerance, approved exception or reissue) feeds the trip through
 * {@link FleetDispatchService#recordWeighedQuantity} (source {@value FleetWeighbridgeRuleService#SOURCE} + ticket id,
 * idempotent) inside the ticket's transaction, so the weighed net becomes the trip's loaded quantity and therefore the
 * stock issued at dispatch. Single-product trip whose line unit is the ticket's net unit: the ticket's net quantity
 * (the weighbridge unit conversion); otherwise the net kg spread by planned tonnage (load factors). A trip that already
 * left, or a product without a weight factor, does not block the weighbridge: the ticket completes and records why the
 * trip was not fed ({@code tripFeedStatus} skipped / failed); {@code POST /tickets/{id}/feed-trip} retries. An
 * out-of-tolerance ticket feeds nothing until its exception is approved.
 *
 * <p><b>Void</b>: before dispatch the trip loses the ticket's quantity and goes back to {@code loading} (a reissued
 * ticket feeds it again); once the trip was dispatched with the ticket's quantity the void is refused (409) — the stock
 * is issued, corrections go through the delivery (proof of delivery, discrepancy, return).
 *
 * <p>Lock order: ticket → delivery order → trip (dispatch locks delivery order → trip and only reads tickets).
 */
@Service
public class WeighbridgeTripFeed {

    private final FleetDb db;
    private final FleetDispatchService dispatch;
    private final WeighbridgeUnits units;
    private final FleetWeighbridgeRuleService rules;

    public WeighbridgeTripFeed(FleetDb db, FleetDispatchService dispatch, WeighbridgeUnits units,
            FleetWeighbridgeRuleService rules) {
        this.db = db;
        this.dispatch = dispatch;
        this.units = units;
        this.rules = rules;
    }

    // ------------------------------------------------------------------ lookups / pre-fill

    /** Open trips (planned / loading / weighed) for the ticket form's trip picker, nearest planned date first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openTrips(String search, String vehicleId) {
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ? AND t.status IN ('planned', 'loading',"
                + " 'weighed')");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(search)) {
            String like = FleetIncidentService.like(search);
            where.append(" AND (t.code ILIKE ? OR o.code ILIKE ? OR p.name ILIKE ? OR v.\"registrationPlate\" ILIKE ?"
                    + " OR t.\"transporterPlate\" ILIKE ?)");
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }
        FleetIncidentService.eq(where, params, "t.\"vehicleId\"", vehicleId);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(FleetDeliveryOrderService.TRIP_SELECT + where
                + " ORDER BY t.\"plannedDate\", t.code LIMIT 50").params(params));
        for (Map<String, Object> r : rows) {
            r.put("liveTicket", rules.liveTicket((String) r.get("id")));
            r.put("products", SqlRows.list(db.jdbc().sql("SELECT l.\"productId\", p.name AS \"productName\","
                    + " l.\"plannedQty\", u.code AS \"uomCode\" FROM \"FleetLoadLine\" l JOIN \"Product\" p ON p.id ="
                    + " l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE l.\"organizationId\" ="
                    + " ? AND l.\"loadId\" = ? ORDER BY l.\"createdAt\", l.id").params(FleetDb.org(), r.get("id"))));
        }
        return rows;
    }

    /** The ticket header a trip pre-fills (the "Weigh" button of the trip screen), with display names. */
    @Transactional(readOnly = true)
    public Map<String, Object> prefill(String tripId) {
        Map<String, Object> trip = db.require(FleetLoad.class, tripId, false, "Trip not found");
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("tripId", tripId);
        applyTripDefaults(h);
        Map<String, Object> out = new LinkedHashMap<>(h);
        out.put("tripCode", trip.get("code"));
        out.put("tripStatus", trip.get("status"));
        out.put("plannedTonnes", trip.get("plannedTonnes"));
        out.put("deliveryOrderCode", db.jdbc().sql("SELECT code FROM \"FleetDeliveryOrder\" WHERE id = ?")
                .param(trip.get("deliveryOrderId")).query(String.class).optional().orElse(null));
        out.put("partnerName", name("Partner", h.get("partnerId")));
        out.put("transporterName", name("Partner", h.get("transporterPartnerId")));
        out.put("productName", name("Product", h.get("productId")));
        Map<String, Object> v = h.get("vehicleId") == null ? null : db.byId(FleetVehicle.class,
                (String) h.get("vehicleId"), false);
        out.put("vehicleCode", v == null ? null : v.get("code"));
        out.put("vehiclePlate", v == null ? null : v.get("registrationPlate"));
        out.put("driverDisplayName", h.get("driverId") == null ? h.get("driverName") : db.jdbc().sql("SELECT"
                + " trim(coalesce(e.\"firstName\", '') || ' ' || coalesce(e.\"lastName\", '')) FROM \"FleetDriver\" d"
                + " JOIN \"HrEmployee\" e ON e.id = d.\"employeeId\" WHERE d.id = ? AND d.\"organizationId\" = ?")
                .params(h.get("driverId"), FleetDb.org()).query(String.class).optional().orElse(null));
        out.put("orderedUomCode", h.get("orderedUomId") == null ? null : db.jdbc().sql("SELECT code FROM"
                + " \"UnitOfMeasure\" WHERE id = ?").param(h.get("orderedUomId")).query(String.class).optional()
                .orElse(null));
        out.put("open", TripStatusPolicy.isOpen((String) trip.get("status")));
        out.put("liveTicket", rules.liveTicket(tripId));
        out.put("weighbridge", rules.forTrip(trip).toMap());
        return out;
    }

    private String name(String table, Object id) {
        return id == null ? null : db.jdbc().sql("SELECT name FROM \"" + table + "\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, FleetDb.org()).query(String.class).optional().orElse(null);
    }

    /**
     * Fills the blank header fields of a new ticket that names a trip: subject, delivery order, truck (fleet vehicle or
     * the transporter's plate), trailer, driver, transporter, customer, sales order, reference, product + planned
     * quantity and the yard. 400 {@code Trip not found}.
     */
    void applyTripDefaults(Map<String, Object> h) {
        String tripId = str(h.get("tripId"));
        if (tripId == null) {
            return;
        }
        Map<String, Object> trip = db.byId(FleetLoad.class, tripId, false);
        if (trip == null) {
            throw ApiException.badRequest("Trip not found");
        }
        Map<String, Object> order = db.byId(FleetDeliveryOrder.class, (String) trip.get("deliveryOrderId"), false);
        putIfBlank(h, "subjectType", "sale_delivery");
        putIfBlank(h, "deliveryOrderId", trip.get("deliveryOrderId"));
        if (str(h.get("vehicleId")) == null && str(h.get("registrationPlate")) == null) {
            if (trip.get("vehicleId") != null) {
                h.put("vehicleId", trip.get("vehicleId"));
            } else {
                putIfBlank(h, "registrationPlate", trip.get("transporterPlate"));
            }
        }
        if (str(h.get("trailerPlate")) == null && trip.get("trailerId") != null) {
            Map<String, Object> tr = db.byId(FleetVehicle.class, (String) trip.get("trailerId"), false);
            if (tr != null) {
                h.put("trailerPlate", tr.get("registrationPlate") != null ? tr.get("registrationPlate")
                        : tr.get("code"));
            }
        }
        if (str(h.get("driverId")) == null && str(h.get("driverName")) == null) {
            if (trip.get("driverId") != null) {
                h.put("driverId", trip.get("driverId"));
            } else {
                putIfBlank(h, "driverName", trip.get("transporterDriverName"));
            }
        }
        putIfBlank(h, "transporterPartnerId", trip.get("transporterPartnerId"));
        if (order != null) {
            putIfBlank(h, "partnerId", order.get("partnerId"));
            if ("sales_order".equals(order.get("sourceType"))) {
                putIfBlank(h, "salesOrderId", order.get("sourceId"));
            }
            putIfBlank(h, "referenceNumber", order.get("code") + " / " + trip.get("code"));
        }
        putIfBlank(h, "locationId", trip.get("locationId"));
        if (str(h.get("productId")) == null && h.get("orderedQty") == null && str(h.get("orderedUomId")) == null) {
            plannedQuantity(trip, h);
        }
    }

    /**
     * Product + expected quantity of the trip: one product in a unit the weighbridge can convert to kg → its planned
     * quantity in that unit; otherwise the planned tonnage in TON (KG) — the product stays when there is only one.
     */
    private void plannedQuantity(Map<String, Object> trip, Map<String, Object> h) {
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("SELECT l.\"productId\", l.\"plannedQty\","
                + " COALESCE(l.\"uomId\", p.\"uomId\") AS uom FROM \"FleetLoadLine\" l JOIN \"Product\" p ON p.id ="
                + " l.\"productId\" WHERE l.\"organizationId\" = ? AND l.\"loadId\" = ? ORDER BY l.\"createdAt\","
                + " l.id").params(FleetDb.org(), trip.get("id")));
        if (lines.size() == 1) {
            Map<String, Object> l = lines.getFirst();
            h.put("productId", l.get("productId"));
            String uom = (String) l.get("uom");
            BigDecimal perUnit = null;
            if (uom != null) {
                perUnit = kgPerUnit((String) l.get("productId"), uom);
            }
            if (perUnit != null && perUnit.signum() > 0) {
                h.put("orderedQty", SqlRows.dec(l, "plannedQty"));
                h.put("orderedUomId", uom);
                return;
            }
        }
        BigDecimal tonnes = (BigDecimal) trip.get("plannedTonnes");
        if (tonnes == null || tonnes.signum() <= 0) {
            return;
        }
        String ton = db.jdbc().sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND upper(code) IN"
                + " ('TON', 'T', 'TONNE') AND \"deletedAt\" IS NULL ORDER BY code LIMIT 1").param(FleetDb.org())
                .query(String.class).optional().orElse(null);
        if (ton != null) {
            h.put("orderedQty", tonnes);
            h.put("orderedUomId", ton);
            return;
        }
        String kg = db.jdbc().sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND upper(code) = 'KG'"
                + " AND \"deletedAt\" IS NULL LIMIT 1").param(FleetDb.org()).query(String.class).optional().orElse(null);
        if (kg != null) {
            h.put("orderedQty", tonnes.multiply(BigDecimal.valueOf(1000)));
            h.put("orderedUomId", kg);
        }
    }

    /**
     * Validates the dispatch references of a resolved ticket header ({@code d}): delivery order of the organization,
     * the trip open and of that delivery order (its delivery order is filled in), not on another live ticket.
     */
    void validateLink(Map<String, Object> d, String ticketId) {
        String tripId = str(d.get("tripId"));
        String orderId = str(d.get("deliveryOrderId"));
        if (tripId == null && orderId == null) {
            return;
        }
        if (!"sale_delivery".equals(d.get("subjectType"))) {
            throw ApiException.badRequest("Only sale deliveries reference a sales order or delivery order");
        }
        Map<String, Object> order = orderId == null ? null : db.byId(FleetDeliveryOrder.class, orderId, false);
        if (orderId != null && order == null) {
            throw ApiException.badRequest("Delivery order not found");
        }
        if (tripId == null) {
            return;
        }
        Map<String, Object> trip = db.byId(FleetLoad.class, tripId, false);
        if (trip == null) {
            throw ApiException.badRequest("Trip not found");
        }
        if (order != null && !orderId.equals(trip.get("deliveryOrderId"))) {
            throw ApiException.badRequest("Trip " + trip.get("code") + " belongs to another delivery order than "
                    + order.get("code"));
        }
        d.put("deliveryOrderId", trip.get("deliveryOrderId"));
        if (!TripStatusPolicy.isOpen((String) trip.get("status"))) {
            throw ApiException.badRequest("Trip " + trip.get("code") + " is " + TripStatusPolicy.label((String)
                    trip.get("status")) + ": only planned, loading or weighed trips can be weighed");
        }
        Map<String, Object> live = rules.liveTicket(tripId);
        if (live != null && !live.get("id").equals(ticketId)) {
            throw ApiException.conflict("Trip " + trip.get("code") + " is already on live weighbridge ticket "
                    + live.get("ticketNumber"));
        }
    }

    // ------------------------------------------------------------------ completion / void

    private record Feed(String status, String message) {}

    private BigDecimal kgPerUnit(String productId, String uomId) {
        try {
            return units.kgPerUnit(productId, uomId);
        } catch (ApiException e) {
            return null;
        }
    }

    /** Completed ticket → its trip (when it has one); records the outcome on the ticket. Never throws for the trip. */
    Map<String, Object> onCompleted(Map<String, Object> ticket) {
        if (ticket.get("tripId") == null || !"sale_delivery".equals(ticket.get("subjectType"))) {
            return ticket;
        }
        return record(ticket, feed(ticket));
    }

    /** {@code POST /tickets/{id}/feed-trip}: feed (again) now; 400 with the reason when the trip cannot take it. */
    @Transactional
    public Map<String, Object> feedTrip(Map<String, Object> ticket) {
        if (!"completed".equals(ticket.get("status"))) {
            throw ApiException.badRequest("Ticket " + ticket.get("ticketNumber") + " is not completed (it is "
                    + String.valueOf(ticket.get("status")).replace('_', ' ') + ")");
        }
        if (ticket.get("tripId") == null) {
            throw ApiException.badRequest("Ticket " + ticket.get("ticketNumber") + " is not linked to a trip");
        }
        Feed f = feed(ticket);
        if (!"fed".equals(f.status())) {
            throw ApiException.badRequest(f.message());
        }
        return record(ticket, f);
    }

    private Feed feed(Map<String, Object> t) {
        String tripId = (String) t.get("tripId");
        Map<String, Object> trip = db.byId(FleetLoad.class, tripId, false);
        if (trip == null) {
            return new Feed("failed", "Trip not found");
        }
        String code = (String) trip.get("code");
        if (FleetWeighbridgeRuleService.SOURCE.equals(trip.get("weighSourceType")) && t.get("id").equals(
                trip.get("weighSourceId"))) {
            return new Feed("fed", "Trip " + code + " is loaded with the net weight of this ticket");
        }
        if (!TripStatusPolicy.isOpen((String) trip.get("status"))) {
            return new Feed("skipped", "Trip " + code + " is " + TripStatusPolicy.label((String) trip.get("status"))
                    + ": the ticket did not change its loaded quantity");
        }
        BigDecimal netKg = (BigDecimal) t.get("netKg");
        if (netKg == null) {
            return new Feed("failed", "Ticket " + t.get("ticketNumber") + " has no net weight");
        }
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("SELECT l.id, l.\"productId\", l.\"uomId\","
                + " l.\"tonnesPerUnit\","
                + " p.name AS \"productName\", p.\"uomId\" AS \"productUomId\" FROM \"FleetLoadLine\" l JOIN \"Product\" p"
                + " ON p.id = l.\"productId\" WHERE l.\"organizationId\" = ? AND l.\"loadId\" = ? ORDER BY"
                + " l.\"createdAt\", l.id").params(FleetDb.org(), tripId));
        if (lines.isEmpty()) {
            return new Feed("failed", "Trip " + code + " has no products");
        }
        if (lines.size() == 1) {
            Map<String, Object> l = lines.getFirst();
            String lineUom = l.get("uomId") != null ? (String) l.get("uomId") : (String) l.get("productUomId");
            if (t.get("netQty") != null && lineUom != null && lineUom.equals(t.get("netUomId"))) {
                dispatch.recordWeighedQuantity(tripId, (BigDecimal) t.get("netQty"), (String) l.get("uomId"),
                        FleetWeighbridgeRuleService.SOURCE, (String) t.get("id"));
                return new Feed("fed", "Trip " + code + " loaded quantity set from the net quantity "
                        + WeighbridgeTicketService.plain((BigDecimal) t.get("netQty")) + " of the ticket");
            }
            // The weighbridge's own conversion of the line unit (unit / product conversion / kg per unit setting).
            BigDecimal perUnit = lineUom == null ? null : kgPerUnit((String) l.get("productId"), lineUom);
            if (perUnit != null && perUnit.signum() > 0) {
                BigDecimal qty = WeighbridgeUnits.fromKg(netKg, perUnit);
                dispatch.recordWeighedQuantity(tripId, qty, (String) l.get("uomId"), FleetWeighbridgeRuleService.SOURCE,
                        (String) t.get("id"));
                return new Feed("fed", "Trip " + code + " loaded quantity set to " + WeighbridgeTicketService.plain(qty)
                        + " from the net weight " + WeighbridgeTicketService.plain(netKg) + " kg of the ticket");
            }
        }
        for (Map<String, Object> l : lines) {
            if (l.get("tonnesPerUnit") == null) {
                return new Feed("failed", "No weight conversion for " + l.get("productName") + " on trip " + code
                        + ": set its kg per unit in the weighbridge product settings, then feed the trip again from the"
                        + " ticket");
            }
        }
        dispatch.recordWeighedQuantity(tripId, netKg, "kg", FleetWeighbridgeRuleService.SOURCE, (String) t.get("id"));
        return new Feed("fed", "Trip " + code + " loaded quantity set from the net weight "
                + WeighbridgeTicketService.plain(netKg) + " kg of the ticket");
    }

    private Map<String, Object> record(Map<String, Object> ticket, Feed f) {
        return db.update(WeighbridgeTicket.class, (String) ticket.get("id"), Obj.of("tripFeedStatus", f.status(),
                "tripFeedMessage", f.message(), "tripFedAt", "fed".equals(f.status()) ? Instant.now() : null));
    }

    /**
     * Before a completed ticket is voided: if it gave its trip the loaded quantity, an open trip goes back to loading
     * (quantity cleared); a dispatched trip refuses the void (409).
     */
    void onVoiding(Map<String, Object> ticket, String reason) {
        String tripId = (String) ticket.get("tripId");
        if (tripId == null) {
            return;
        }
        Map<String, Object> trip = db.byId(FleetLoad.class, tripId, false);
        if (trip == null || !FleetWeighbridgeRuleService.SOURCE.equals(trip.get("weighSourceType"))
                || !ticket.get("id").equals(trip.get("weighSourceId"))) {
            return;
        }
        String status = (String) trip.get("status");
        if (TripStatusPolicy.isOpen(status)) {
            dispatch.clearWeighedQuantity(tripId, FleetWeighbridgeRuleService.SOURCE, (String) ticket.get("id"),
                    "Weighbridge ticket " + ticket.get("ticketNumber") + " voided: " + reason);
            db.update(WeighbridgeTicket.class, (String) ticket.get("id"), Obj.of("tripFeedStatus", "cleared",
                    "tripFeedMessage", "Voided: trip " + trip.get("code") + " is back to loading"));
            return;
        }
        if ("cancelled".equals(status)) {
            return;
        }
        throw ApiException.conflict("Ticket " + ticket.get("ticketNumber") + " gave the loaded quantity of trip "
                + trip.get("code") + ", which is " + TripStatusPolicy.label(status) + ": its stock is issued, so the"
                + " ticket cannot be voided. Correct the quantity through the delivery (proof of delivery,"
                + " discrepancy or return) instead");
    }

    private static void putIfBlank(Map<String, Object> h, String key, Object value) {
        if (value != null && str(h.get(key)) == null) {
            h.put(key, value);
        }
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }
}
