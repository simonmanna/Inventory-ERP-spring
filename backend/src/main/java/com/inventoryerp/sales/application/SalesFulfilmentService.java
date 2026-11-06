package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.fleet.application.DispatchCommands.FromSourceInput;
import com.inventoryerp.fleet.application.FleetDeliveryOrderService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fulfilment orchestration of sales orders (ADR-S011 phase S5, sales.md §4.1, §10).
 *
 * <p>Fleet's delivery order is the only delivery document: {@link #createDelivery} asks fleet to deliver the order's
 * open quantities. The physical quantities on the order lines are projections <em>recomputed absolutely</em> from
 * fleet's records (loaded, dispatched − returned, received per POD, accepted = fleet billable after approved
 * discrepancies) — so duplicate or out-of-order fleet events are harmless and {@link #rebuild} is always safe.
 *
 * <p>The billable quantity follows the product's billing basis ({@code weighed} = net dispatched, {@code accepted},
 * {@code ordered} = dispatched capped at the ordered quantity); every change is an immutable
 * {@code SalesFulfilmentDecision}. A manual decision (permission {@code sales:billing:override}) sticks until replaced.
 */
@Service
public class SalesFulfilmentService {

    private final SalesDb db;
    private final SalesOrderService orders;
    private final SalesAtpService atp;
    private final FleetDeliveryOrderService fleet;
    private final AuditService audit;

    public SalesFulfilmentService(SalesDb db, SalesOrderService orders, SalesAtpService atp,
            FleetDeliveryOrderService fleet, AuditService audit) {
        this.db = db;
        this.orders = orders;
        this.atp = atp;
        this.fleet = fleet;
        this.audit = audit;
    }

    /** Create the fleet delivery order for the open quantities of a confirmed, released order. */
    @Transactional
    public Map<String, Object> createDelivery(String orderId, Map<String, Object> in) {
        orders.require(orderId);
        LocalDate date = in.get("requestedDate") == null ? null : SalesDb.date(str(in.get("requestedDate")));
        Map<String, Object> d = fleet.fromSalesOrder(new FromSourceInput(orderId, blank(str(in.get("siteId"))) ? null
                : str(in.get("siteId")), blank(str(in.get("locationId"))) ? null : str(in.get("locationId")), date, null,
                null, blank(str(in.get("priority"))) ? null : str(in.get("priority")), null, null, null,
                str(in.get("notes"))));
        audit.record(AuditInput.of("SalesOrder", orderId, "update", null, Obj.of("deliveryOrder", d.get("code"))));
        return d;
    }

    /**
     * Recompute the physical quantity projections and billable decisions of an order from fleet's records, then
     * refresh the order status and its reservations. Returns the lines whose projection changed.
     */
    @Transactional
    public List<Map<String, Object>> rebuild(String orderId) {
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", orderId,
                org());
        if (o == null) {
            throw ApiException.notFound("Sales order not found");
        }
        // Lock first, then read: a rebuild from an earlier event must not write back a quantity older than the lock holder's.
        db.rows("SELECT id FROM \"SalesOrderLine\" WHERE \"orderId\" = ? FOR UPDATE", orderId);
        Map<String, Map<String, Object>> fromFleet = fleetQuantities(orderId);
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> l : db.rows("SELECT l.*, p.\"billingBasis\" FROM \"SalesOrderLine\" l LEFT JOIN"
                + " \"Product\" p ON p.id = l.\"productId\" WHERE l.\"orderId\" = ? FOR UPDATE OF l", orderId)) {
            String lineId = str(l.get("id"));
            Map<String, Object> f = fromFleet.getOrDefault(lineId, Map.of());
            BigDecimal loaded = dec(f.get("loaded"));
            BigDecimal dispatched = dec(f.get("dispatched")).max(BigDecimal.ZERO);
            BigDecimal received = dec(f.get("received"));
            BigDecimal accepted = dec(f.get("accepted"));
            String basis = l.get("billingBasis") == null ? "accepted" : str(l.get("billingBasis"));
            BigDecimal auto = switch (basis) {
                case "weighed" -> dispatched;
                case "ordered" -> dispatched.min(dec(l.get("quantity")).subtract(dec(l.get("qtyCancelled"))));
                default -> accepted;
            };
            Map<String, Object> last = db.row("SELECT basis, quantity FROM \"SalesFulfilmentDecision\" WHERE"
                    + " \"orderLineId\" = ? ORDER BY \"createdAt\" DESC LIMIT 1", lineId);
            BigDecimal billable = last != null && "manual".equals(last.get("basis")) ? dec(last.get("quantity")) : auto;
            BigDecimal previous = dec(l.get("qtyBillable"));
            if (billable.compareTo(previous) != 0 && !(last != null && "manual".equals(last.get("basis")))) {
                decide(orderId, lineId, basis, billable, previous, "Recomputed from fleet deliveries");
            }
            boolean diff = loaded.compareTo(dec(l.get("qtyLoaded"))) != 0
                    || dispatched.compareTo(dec(l.get("qtyDispatched"))) != 0
                    || received.compareTo(dec(l.get("qtyReceived"))) != 0
                    || accepted.compareTo(dec(l.get("qtyAccepted"))) != 0 || billable.compareTo(previous) != 0;
            if (diff) {
                db.update("UPDATE \"SalesOrderLine\" SET \"qtyLoaded\" = ?, \"qtyDispatched\" = ?, \"qtyReceived\" = ?,"
                        + " \"qtyAccepted\" = ?, \"qtyDelivered\" = ?, \"qtyBillable\" = ? WHERE id = ?", loaded, dispatched,
                        received, accepted, dispatched, billable, lineId);
                changed.add(Obj.of("lineId", lineId, "dispatched", JsNumbers.js(dispatched), "accepted",
                        JsNumbers.js(accepted), "billable", JsNumbers.js(billable)));
            }
        }
        if (!changed.isEmpty()) {
            orders.refreshStatus(orderId);
            atp.reserveOrder(orderId);
        }
        return changed;
    }

    /** Per order line: quantities from the fleet delivery orders of this sales order (not cancelled). */
    private Map<String, Map<String, Object>> fleetQuantities(String orderId) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : db.rows("""
                SELECT dl."sourceLineId" AS line,
                       COALESCE(sum(ll."loadedQty") FILTER (WHERE t.status <> 'cancelled'), 0) AS loaded,
                       COALESCE(sum(COALESCE(ll."dispatchedQty", 0) - COALESCE(ll."returnedQty", 0)) FILTER (WHERE t.status
                           IN ('dispatched', 'in_transit', 'delivered', 'closed', 'returned')), 0) AS dispatched,
                       COALESCE(sum(ll."deliveredQty") FILTER (WHERE t.status IN ('delivered', 'closed')), 0) AS received,
                       COALESCE(sum(COALESCE(ll."billableQty", ll."deliveredQty")) FILTER (WHERE t.status IN ('delivered',
                           'closed')), 0) AS accepted
                  FROM "FleetLoadLine" ll
                  JOIN "FleetLoad" t ON t.id = ll."loadId"
                  JOIN "FleetDeliveryOrderLine" dl ON dl.id = ll."deliveryOrderLineId"
                  JOIN "FleetDeliveryOrder" d ON d.id = dl."deliveryOrderId"
                 WHERE d."organizationId" = ? AND d."sourceType" = 'sales_order' AND d."sourceId" = ?
                   AND d.status <> 'cancelled' AND dl."sourceLineId" IS NOT NULL
                 GROUP BY dl."sourceLineId\"""", org(), orderId)) {
            out.put(str(r.get("line")), r);
        }
        return out;
    }

    private void decide(String orderId, String lineId, String basis, BigDecimal qty, BigDecimal previous, String reason) {
        db.update("INSERT INTO \"SalesFulfilmentDecision\" (id, \"organizationId\", \"orderId\", \"orderLineId\", basis,"
                + " quantity, \"previousQty\", reason, \"decidedById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", SalesDb.newId(),
                org(), orderId, lineId, basis, qty, previous, reason, user());
    }

    /** Manual billable quantity (permission {@code sales:billing:override}); recorded, sticks until replaced. */
    @Transactional
    public Map<String, Object> overrideBillable(String orderId, String lineId, Map<String, Object> in) {
        Map<String, Object> o = orders.lock(orderId);
        if (!SalesOrderService.OPEN.contains(o.get("status")) && !"done".equals(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed orders have billable quantities");
        }
        Map<String, Object> l = db.row("SELECT * FROM \"SalesOrderLine\" WHERE id = ? AND \"orderId\" = ? FOR UPDATE",
                lineId, orderId);
        if (l == null) {
            throw ApiException.notFound("Order line not found");
        }
        String reason = str(in.get("reason"));
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        BigDecimal qty = dec(in.get("quantity"));
        if (qty.signum() < 0) {
            throw ApiException.badRequest("quantity must not be less than 0");
        }
        BigDecimal net = dec(l.get("qtyInvoiced")).subtract(dec(l.get("qtyCredited")));
        if (qty.compareTo(net) < 0) {
            throw ApiException.badRequest("Billable quantity cannot go below the net invoiced quantity ("
                    + net.stripTrailingZeros().toPlainString() + "); issue a credit note first");
        }
        decide(orderId, lineId, "manual", qty, dec(l.get("qtyBillable")), reason);
        db.update("UPDATE \"SalesOrderLine\" SET \"qtyBillable\" = ? WHERE id = ?", qty, lineId);
        audit.record(AuditInput.of("SalesOrder", orderId, "update", null, Obj.of("lineId", lineId, "billable", qty,
                "reason", reason)));
        orders.refreshStatus(orderId);
        return fulfilment(orderId);
    }

    /** Quantities per line, billable decisions and fleet deliveries of an order. */
    @Transactional(readOnly = true)
    public Map<String, Object> fulfilment(String orderId) {
        orders.require(orderId);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : db.rows("SELECT l.id, l.description, l.quantity, l.\"qtyCancelled\", l.\"qtyLoaded\","
                + " l.\"qtyDispatched\", l.\"qtyReceived\", l.\"qtyAccepted\", l.\"qtyBillable\", l.\"qtyInvoiced\","
                + " l.\"qtyCredited\", p.\"billingBasis\" FROM \"SalesOrderLine\" l LEFT JOIN \"Product\" p ON p.id ="
                + " l.\"productId\" WHERE l.\"orderId\" = ? ORDER BY l.\"sortOrder\"", orderId)) {
            l.put("decisions", db.rows("SELECT basis, quantity, \"previousQty\", reason, \"decidedById\", \"createdAt\" FROM"
                    + " \"SalesFulfilmentDecision\" WHERE \"orderLineId\" = ? ORDER BY \"createdAt\" DESC", l.get("id")));
            lines.add(l);
        }
        List<Map<String, Object>> deliveries = db.rows("SELECT d.id, d.code, d.status, d.\"requestedDate\", (SELECT"
                + " count(*) FROM \"FleetLoad\" t WHERE t.\"deliveryOrderId\" = d.id AND t.status NOT IN ('cancelled'))"
                + " AS trips FROM \"FleetDeliveryOrder\" d WHERE d.\"organizationId\" = ? AND d.\"sourceType\" ="
                + " 'sales_order' AND d.\"sourceId\" = ? ORDER BY d.\"createdAt\"", org(), orderId);
        return Obj.of("orderId", orderId, "lines", lines, "deliveries", deliveries);
    }

    /** Record an applied fulfilment event in the inbox. */
    @Transactional
    public void logEvent(String eventName, String sourceType, String sourceId, String orderId, String payload,
            String status, String error) {
        db.update("INSERT INTO \"SalesProcessedEvent\" (id, \"organizationId\", \"eventName\", \"sourceType\", \"sourceId\","
                + " \"orderId\", payload, status, error) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)", SalesDb.newId(), org(),
                eventName, sourceType, sourceId, orderId, payload, status, error);
    }

    /** Sales order fed by a fleet delivery order, or null. */
    @Transactional(readOnly = true)
    public String orderOfDelivery(String deliveryOrderId) {
        Map<String, Object> d = db.row("SELECT \"sourceType\", \"sourceId\" FROM \"FleetDeliveryOrder\" WHERE id = ? AND"
                + " \"organizationId\" = ?", deliveryOrderId, org());
        if (d == null || !"sales_order".equals(d.get("sourceType"))) {
            return null;
        }
        return db.exists("SalesOrder", str(d.get("sourceId"))) ? str(d.get("sourceId")) : null;
    }

    /**
     * Nightly reconciliation body: rebuild every open order with fleet deliveries, record how many lines drifted
     * (a drift means an event was missed; the rebuild repaired it).
     */
    @Transactional
    public Map<String, Object> reconcile() {
        List<Map<String, Object>> drift = new ArrayList<>();
        List<Map<String, Object>> open = db.rows("SELECT DISTINCT o.id FROM \"SalesOrder\" o JOIN \"FleetDeliveryOrder\" d"
                + " ON d.\"sourceType\" = 'sales_order' AND d.\"sourceId\" = o.id WHERE o.\"organizationId\" = ? AND"
                + " o.status IN ('confirmed', 'in_fulfilment')", org());
        for (Map<String, Object> o : open) {
            for (Map<String, Object> c : rebuild(str(o.get("id")))) {
                c.put("orderId", o.get("id"));
                drift.add(c);
            }
        }
        db.update("INSERT INTO \"SalesReconciliation\" (id, \"organizationId\", \"ordersChecked\", \"linesDrifted\","
                + " details) VALUES (?, ?, ?, ?, ?::jsonb)", SalesDb.newId(), org(), open.size(), drift.size(),
                SalesDb.toJson(drift));
        return Obj.of("ordersChecked", open.size(), "linesDrifted", drift.size(), "details", drift);
    }
}
