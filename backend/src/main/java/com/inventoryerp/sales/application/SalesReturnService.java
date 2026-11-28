package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.invoicing.application.CreditNoteService;
import com.inventoryerp.invoicing.application.CreditNoteService.CreditNoteInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.PaymentService;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.events.DomainEvents;
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
 * Sales returns / RMA (ADR-S011 phase S7; sales-postings.md P12, P13):
 * {@code requested → approved → received → inspected → closed}, or {@code rejected} / {@code cancelled}.
 *
 * <ul>
 *   <li>Receive: returned goods enter the receiving location at their cost (Dr inventory / Cr COGS, inventory's
 *       return receipt); the order lines' returned quantity is updated. Claims without goods skip receive / inspect.</li>
 *   <li>Inspect: per line restock (stays usable), quarantine (moved to a quarantine location) or scrap (issued as waste:
 *       Dr waste / Cr inventory).</li>
 *   <li>Close: credit note against the order invoice for the invoiced part (never edits the invoice; gross invoiced is
 *       kept, credited grows), the not-yet-invoiced part lowers the billable quantity by a recorded decision; optional
 *       refund (Dr AR / Cr tender) or a replacement order.</li>
 * </ul>
 */
@Service
public class SalesReturnService {

    static final Set<String> REASONS = Set.of("wrong_grade", "damaged", "short_quantity", "excess_quantity",
            "quality_rejection", "other");

    private final SalesDb db;
    private final SalesOrderService orders;
    private final StockService stock;
    private final CreditNoteService creditNotes;
    private final PaymentService payments;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;
    private SalesAccess access;
    private SalesSettingsService settings;
    private SalesCreditFlowService creditFlow;

    private SalesFulfilmentService fulfilment;

    @org.springframework.beans.factory.annotation.Autowired
    void setCollaborators(SalesAccess access, SalesSettingsService settings,
            @org.springframework.context.annotation.Lazy SalesCreditFlowService creditFlow,
            @org.springframework.context.annotation.Lazy SalesFulfilmentService fulfilment) {
        this.access = access;
        this.settings = settings;
        this.creditFlow = creditFlow;
        this.fulfilment = fulfilment;
    }

    public SalesReturnService(SalesDb db, SalesOrderService orders, StockService stock, CreditNoteService creditNotes,
            PaymentService payments, SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.orders = orders;
        this.stock = stock;
        this.creditNotes = creditNotes;
        this.payments = payments;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Map<String, String> p) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ?");
        where.append(access.branchClause("o.\"branchId\"", params));
        for (String k : List.of("orderId", "partnerId", "status")) {
            if (!blank(p.get(k))) {
                where.append(" AND r.\"").append(k).append("\" = ?");
                params.add(p.get(k));
            }
        }
        return db.rows("SELECT r.*, o.number AS \"orderNumber\", pa.name AS \"partnerName\" FROM \"SalesReturn\" r JOIN"
                + " \"SalesOrder\" o ON o.id = r.\"orderId\" LEFT JOIN \"Partner\" pa ON pa.id = r.\"partnerId\"" + where
                + " ORDER BY r.\"createdAt\" DESC LIMIT 500", params);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = db.row("SELECT r.*, o.number AS \"orderNumber\", pa.name AS \"partnerName\" FROM"
                + " \"SalesReturn\" r JOIN \"SalesOrder\" o ON o.id = r.\"orderId\" LEFT JOIN \"Partner\" pa ON pa.id ="
                + " r.\"partnerId\" WHERE r.id = ? AND r.\"organizationId\" = ?", id, org());
        if (r == null) {
            throw ApiException.notFound("Return not found");
        }
        r.put("lines", db.rows("SELECT rl.*, p.\"serialTracking\", t.code AS \"loadCode\" FROM \"SalesReturnLine\" rl"
                + " LEFT JOIN \"Product\" p ON p.id = rl.\"productId\" LEFT JOIN \"FleetLoadLine\" ll ON ll.id ="
                + " rl.\"loadLineId\" LEFT JOIN \"FleetLoad\" t ON t.id = ll.\"loadId\" WHERE rl.\"returnId\" = ? ORDER BY"
                + " rl.description", id));
        return r;
    }

    /**
     * ADR-S094 §7.2: what each line of an order can still come back from, per delivery (load line): delivered (accepted)
     * on that delivery minus what other returns already claim, with the serial numbers it issued.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> returnable(String orderId) {
        orders.require(orderId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ol : db.rows("SELECT l.*, p.\"serialTracking\" FROM \"SalesOrderLine\" l LEFT JOIN"
                + " \"Product\" p ON p.id = l.\"productId\" WHERE l.\"orderId\" = ? AND l.\"organizationId\" = ? ORDER BY"
                + " l.\"sortOrder\", l.id", orderId, org())) {
            List<Map<String, Object>> deliveries = new ArrayList<>();
            for (Map<String, Object> d : deliveries(str(ol.get("id")))) {
                deliveries.add(Obj.of("loadLineId", d.get("loadLineId"), "loadCode", d.get("loadCode"), "deliveredAt",
                        d.get("deliveredAt"), "delivered", JsNumbers.js(dec(d.get("delivered"))), "returnable",
                        JsNumbers.js(dec(d.get("returnable"))), "serialNumbers", SalesDb.strings(d.get("serialNumbers"))));
            }
            out.add(Obj.of("orderLineId", ol.get("id"), "description", ol.get("description"), "productId",
                    ol.get("productId"), "serialTracking", Boolean.TRUE.equals(ol.get("serialTracking")), "returnable",
                    JsNumbers.js(returnable(ol)), "deliveries", deliveries));
        }
        return out;
    }

    /** The delivered load lines of an order line, oldest first, with what is left to return from each. */
    private List<Map<String, Object>> deliveries(String orderLineId) {
        return db.rows("""
                SELECT ll.id AS "loadLineId", t.code AS "loadCode", t."deliveredAt",
                       CASE WHEN jsonb_typeof(ll."serialNumbers") = 'array'
                            THEN ARRAY(SELECT jsonb_array_elements_text(ll."serialNumbers")) ELSE '{}'::text[] END
                           AS "serialNumbers",
                       ll."stockLedgerCode", ll."stockValue", ll."dispatchedQty", ll."uomFactor",
                       COALESCE(ll."billableQty", ll."deliveredQty", 0) AS delivered,
                       GREATEST(COALESCE(ll."billableQty", ll."deliveredQty", 0)
                         - COALESCE((SELECT sum(CASE WHEN r."physicalReturn" AND r.status IN ('received', 'inspected', 'closed')
                                                     THEN rl."receivedQty" - COALESCE(rl."rejectedQty", 0) ELSE rl.quantity END)
                                       FROM "SalesReturnLine" rl JOIN "SalesReturn" r ON r.id = rl."returnId"
                                      WHERE rl."loadLineId" = ll.id AND r.status NOT IN ('rejected', 'cancelled')), 0), 0)
                           AS returnable
                  FROM "FleetLoadLine" ll
                  JOIN "FleetLoad" t ON t.id = ll."loadId"
                  JOIN "FleetDeliveryOrderLine" dl ON dl.id = ll."deliveryOrderLineId"
                 WHERE dl."sourceLineId" = ? AND ll."organizationId" = ? AND t.status IN ('delivered', 'closed')
                 ORDER BY t."deliveredAt", t."createdAt", ll.id""", orderLineId, org());
    }

    // ------------------------------------------------------------------ lifecycle

    /** New return request against a confirmed order: lines {@code [{orderLineId, quantity}]}. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> in) {
        String orderId = str(in.get("orderId"));
        Map<String, Object> o = orders.lock(orderId);
        if (!Set.of("confirmed", "done").contains(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed orders can have returns");
        }
        String reason = str(in.get("reason"));
        if (reason == null || !REASONS.contains(reason)) {
            throw ApiException.badRequest("reason must be one of: " + String.join(", ", REASONS.stream().sorted().toList()));
        }
        boolean physical = !Boolean.FALSE.equals(in.get("physicalReturn"));
        if (!physical && !TenantContext.has(Permissions.SALES__RETURN_WRITE_OFF)) {
            // ADR-S094 AD-20: crediting goods the customer keeps or lost has its own permission (and an approval)
            throw ApiException.forbidden("Crediting goods without a return needs the write-off permission");
        }
        List<Map<String, Object>> lines = in.get("lines") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Add at least one line to return");
        }
        int year = LocalDate.now(BusinessZone.get()).getYear();
        String id = SalesDb.newId();
        String number = sequence.next("sales_return:" + year, "RMA-" + year + "-", 5);
        db.update("INSERT INTO \"SalesReturn\" (id, \"organizationId\", number, \"orderId\", \"partnerId\", reason,"
                + " \"physicalReturn\", \"receiveLocationId\", \"complaintId\", \"discrepancyId\", notes, \"requestedById\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, org(), number, orderId, o.get("partnerId"), reason,
                physical, blank(str(in.get("receiveLocationId"))) ? o.get("locationId") : str(in.get("receiveLocationId")),
                str(in.get("complaintId")), str(in.get("discrepancyId")), str(in.get("notes")), user());
        for (Map<String, Object> l : lines) {
            Map<String, Object> ol = db.row("SELECT * FROM \"SalesOrderLine\" WHERE id = ? AND \"orderId\" = ?",
                    str(l.get("orderLineId")), orderId);
            if (ol == null) {
                throw ApiException.notFound("Order line not found");
            }
            BigDecimal qty = dec(l.get("quantity"));
            BigDecimal open = returnable(ol);
            if (qty.signum() <= 0 || qty.compareTo(open) > 0) {
                throw ApiException.badRequest("Quantity of " + ol.get("description") + " must be between 0 and "
                        + open.stripTrailingZeros().toPlainString() + " (delivered and not yet returned)");
            }
            String loadLineId = physical ? deliveryOf(ol, str(l.get("loadLineId")), qty) : null;
            db.update("INSERT INTO \"SalesReturnLine\" (id, \"organizationId\", \"returnId\", \"orderLineId\", \"productId\","
                    + " \"variantId\", description, quantity, \"loadLineId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    SalesDb.newId(), org(), id, ol.get("id"), ol.get("productId"), ol.get("variantId"),
                    ol.get("description"), qty, loadLineId);
        }
        if (!blank(str(in.get("complaintId")))) {
            db.update("UPDATE \"CustomerComplaint\" SET \"returnId\" = ?, status = 'investigating', \"updatedAt\" = now()"
                    + " WHERE id = ? AND \"organizationId\" = ?", id, str(in.get("complaintId")), org());
        }
        audit.record(AuditInput.of("SalesReturn", id, "create", null, Obj.of("number", number, "orderId", orderId)));
        return get(id);
    }

    /**
     * ADR-S094 §7.2: the delivery (load line) a returned quantity comes back from — the one named, else the only delivery
     * with something left to return; refused above what is left on it. Null for goods delivered without a recorded
     * delivery (legacy, services).
     */
    private String deliveryOf(Map<String, Object> ol, String given, BigDecimal qty) {
        List<Map<String, Object>> deliveries = deliveries(str(ol.get("id")));
        Map<String, Object> d;
        if (!blank(given)) {
            d = deliveries.stream().filter(x -> given.equals(str(x.get("loadLineId")))).findFirst().orElseThrow(
                    () -> ApiException.badRequest(ol.get("description") + " was not delivered on that delivery"));
        } else {
            List<Map<String, Object>> open = deliveries.stream().filter(x -> dec(x.get("returnable")).signum() > 0)
                    .toList();
            if (open.size() > 1) {
                throw ApiException.badRequest("Choose the delivery " + ol.get("description") + " comes back from");
            }
            if (open.isEmpty()) {
                return null;
            }
            d = open.get(0);
        }
        if (qty.compareTo(dec(d.get("returnable"))) > 0) {
            throw ApiException.badRequest("Quantity of " + ol.get("description") + " must not exceed "
                    + dec(d.get("returnable")).stripTrailingZeros().toPlainString() + " (delivered on " + d.get("loadCode")
                    + " and not yet returned)");
        }
        return str(d.get("loadLineId"));
    }

    /**
     * What the customer actually holds, minus quantities on other open / done returns. Sales readiness DEL-10: for goods
     * that went out on deliveries that is the accepted quantity (not what is in transit, damaged, short or rejected);
     * otherwise what was delivered or invoiced (net of credits).
     */
    /** What a return line holds against what the customer has (alias {@code l} line, {@code r} return). */
    private static final String CLAIMED = "CASE WHEN r.\"physicalReturn\" AND r.status IN ('received', 'inspected', 'closed')"
            + " THEN l.\"receivedQty\" - COALESCE(l.\"rejectedQty\", 0) ELSE l.quantity END";

    private BigDecimal returnable(Map<String, Object> ol) {
        BigDecimal delivered = dec(ol.get("qtyDispatched")).signum() > 0 ? dec(ol.get("qtyAccepted"))
                : dec(ol.get("qtyDelivered")).max(dec(ol.get("qtyInvoiced")).subtract(dec(ol.get("qtyCredited"))));
        // once goods are received, what came back and was kept counts (not what was asked for): the rest is still with
        // the customer and can come back on another return
        BigDecimal claimed = dec(db.row("SELECT COALESCE(sum(" + CLAIMED + "), 0) AS q FROM \"SalesReturnLine\" l JOIN"
                + " \"SalesReturn\" r ON r.id = l.\"returnId\" WHERE l.\"orderLineId\" = ? AND r.status NOT IN ('rejected',"
                + " 'cancelled')", ol.get("id")).get("q"));
        return delivered.subtract(claimed).max(BigDecimal.ZERO);
    }

    @Transactional
    public Map<String, Object> approve(String id) {
        Map<String, Object> r = lock(id, "requested");
        if (!Boolean.TRUE.equals(r.get("physicalReturn")) && user() != null && user().equals(r.get("requestedById"))) {
            // ADR-S094 AD-20 / OD-8: a write-off is approved by someone else, unless the organization allows a sales
            // administrator to approve their own (audited)
            Map<String, Object> rules = settings.stored();
            if (!(rules != null && Boolean.TRUE.equals(rules.get("allowSelfApproval"))
                    && TenantContext.has(Permissions.SALES__CONFIGURE))) {
                throw ApiException.forbidden("You cannot approve your own claim");
            }
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'approved', \"approvedById\" = ?, \"approvedAt\" = now(),"
                + " \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?", user(), id, org());
        audit.record(AuditInput.of("SalesReturn", id, "approve", null, Obj.of("number", r.get("number"))));
        return get(id);
    }

    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> r = lock(id, null);
        if (!Set.of("requested", "approved").contains(r.get("status"))) {
            throw ApiException.badRequest("Only a return not yet received can be rejected");
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'rejected', \"rejectionReason\" = ?, \"updatedAt\" = now() WHERE"
                + " id = ?", blank(reason) ? "Rejected" : reason, id);
        audit.record(AuditInput.of("SalesReturn", id, "reject", null, Obj.of("reason", reason)));
        orders.refreshStatus(str(r.get("orderId"))); // ADR-S094 §4.5: no longer holds the order open
        return get(id);
    }

    /**
     * Receive the goods ({@code lines [{lineId, receivedQty}]}, default the requested quantity) into the receiving
     * location: Dr inventory / Cr COGS at the current cost.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> receive(String id, Map<String, Object> in) {
        Map<String, Object> r = lock(id, "approved");
        if (!Boolean.TRUE.equals(r.get("physicalReturn"))) {
            throw ApiException.badRequest("This return is a claim without goods; close it directly");
        }
        String location = blank(str(in.get("locationId"))) ? str(r.get("receiveLocationId")) : str(in.get("locationId"));
        if (location == null) {
            throw ApiException.badRequest("Choose the location the goods are received into");
        }
        Map<String, BigDecimal> given = new LinkedHashMap<>();
        Map<String, List<String>> serialsGiven = new LinkedHashMap<>();
        if (in.get("lines") instanceof List<?> l) {
            for (Map<String, Object> x : (List<Map<String, Object>>) l) {
                if (x.get("receivedQty") != null) {
                    given.put(str(x.get("lineId")), dec(x.get("receivedQty")));
                }
                serialsGiven.put(str(x.get("lineId")), SalesDb.strings(x.get("serialNumbers")).stream()
                        .map(String::trim).filter(v -> !v.isEmpty()).toList());
            }
        }
        for (Map<String, Object> l : db.rows("SELECT rl.*, ol.\"uomId\", ol.\"uomFactor\", p.\"trackInventory\","
                + " p.\"serialTracking\" FROM \"SalesReturnLine\" rl JOIN \"SalesOrderLine\" ol ON ol.id = rl.\"orderLineId\""
                + " LEFT JOIN \"Product\" p ON p.id = rl.\"productId\" WHERE rl.\"returnId\" = ?", id)) {
            BigDecimal qty = given.getOrDefault(str(l.get("id")), dec(l.get("quantity")));
            if (qty.signum() < 0 || qty.compareTo(dec(l.get("quantity"))) > 0) {
                throw ApiException.badRequest("Received quantity of " + l.get("description") + " is out of range");
            }
            String loadLineId = str(l.get("loadLineId"));
            List<String> serials = serialsGiven.getOrDefault(str(l.get("id")), List.of());
            if (qty.signum() > 0 && Boolean.TRUE.equals(l.get("trackInventory"))
                    && Boolean.TRUE.equals(l.get("serialTracking"))) {
                checkSerials(l, qty, serials, loadLineId);
            }
            BigDecimal issueCost = loadLineId != null ? loadLineUnitCost(loadLineId) : issueUnitCost(str(l.get(
                    "orderLineId")));
            if (qty.signum() > 0 && Boolean.TRUE.equals(l.get("trackInventory"))) {
                // ADR-S094 §7.2: the goods come back against the delivery they left on, when the return names it
                List<String> issueCodes = loadLineId != null ? loadLineCodes(loadLineId)
                        : issueLedgerCodes(str(l.get("orderLineId")));
                if (!issueCodes.isEmpty()) {
                    // R1: the goods come back on the lot, expiry and serial the delivery issued them from, at that
                    // cost, into the receiving location; the delivery's rows are the authority.
                    stockReturns.returnFromSource(new StockReversalService.ReturnFromSource(
                            StockReversalService.IssueSource.ledgerCodes(issueCodes),
                            List.of(new StockReversalService.ReturnLine(str(l.get("productId")),
                                    str(l.get("variantId")), qty, str(l.get("uomId")), dec(l.get("uomFactor")), null,
                                    serials.isEmpty() ? null : serials, str(l.get("id")))),
                            "sales_return", id, str(r.get("number")), null, null, location, true, null));
                } else {
                    // Goods not delivered through a recorded stock issue (legacy): restock at the delivery cost if
                    // known, else the current cost, without lot identity (as before R1).
                    StockMove m = StockMove.of(str(l.get("productId")), location, qty).variant(str(l.get("variantId")))
                            .uom(str(l.get("uomId")), dec(l.get("uomFactor"))).reference(str(r.get("number")))
                            .source("sales_return", id).sourceLine(str(l.get("id")));
                    stock.receiveReturn(issueCost == null ? m : m.unitCost(issueCost));
                }
            }
            db.update("UPDATE \"SalesReturnLine\" SET \"receivedQty\" = ?, \"issueUnitCost\" = ?, \"serialNumbers\" = ?"
                    + " WHERE id = ? AND \"organizationId\" = ?", qty, issueCost, serials, l.get("id"), org());
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'received', \"receiveLocationId\" = ?, \"receivedAt\" = now(),"
                + " \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?", location, id, org());
        returnedProjection(str(r.get("orderId")));
        events.publishOnCommit(DomainEvents.SALES_RETURN_RECEIVED, Obj.of("v", 1, "organizationId", org(), "returnId", id,
                "number", r.get("number"), "orderId", r.get("orderId")));
        audit.record(AuditInput.of("SalesReturn", id, "update", null, Obj.of("status", "received", "location", location)));
        return get(id);
    }

    /** R1: the stock moves that issued an order line's goods (the dispatch of its delivery-order load lines). */
    private List<String> issueLedgerCodes(String orderLineId) {
        return db.rows("SELECT DISTINCT ll.\"stockLedgerCode\" AS code FROM \"FleetLoadLine\" ll JOIN"
                + " \"FleetDeliveryOrderLine\" dl ON dl.id = ll.\"deliveryOrderLineId\" WHERE dl.\"sourceLineId\" = ?"
                + " AND"
                + " ll.\"organizationId\" = ? AND ll.\"stockLedgerCode\" IS NOT NULL", orderLineId, org()).stream()
                .map(x -> str(x.get("code"))).toList();
    }

    /** ADR-S094 §7.2: the stock move that issued one delivery (load line). */
    private List<String> loadLineCodes(String loadLineId) {
        return db.rows("SELECT \"stockLedgerCode\" AS code FROM \"FleetLoadLine\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"stockLedgerCode\" IS NOT NULL", loadLineId, org()).stream().map(x -> str(x.get("code"))).toList();
    }

    /** What one delivery's goods cost when they left, per base unit (null when not recorded). */
    private BigDecimal loadLineUnitCost(String loadLineId) {
        Map<String, Object> c = db.row("SELECT \"stockValue\" AS v, COALESCE(\"dispatchedQty\", 0) * \"uomFactor\" AS q FROM"
                + " \"FleetLoadLine\" WHERE id = ? AND \"organizationId\" = ? AND \"stockValue\" IS NOT NULL", loadLineId, org());
        if (c == null || c.get("v") == null || dec(c.get("q")).signum() <= 0) {
            return null;
        }
        return dec(c.get("v")).divide(dec(c.get("q")), 6, RoundingMode.HALF_UP);
    }

    /**
     * ADR-S094 §7.2 RT-3: a serial-tracked product comes back by name — one serial per unit, each issued on the delivery
     * the return names and not returned before.
     */
    private void checkSerials(Map<String, Object> l, BigDecimal qty, List<String> serials, String loadLineId) {
        BigDecimal units = qty.multiply(l.get("uomFactor") == null ? BigDecimal.ONE : dec(l.get("uomFactor")));
        if (new BigDecimal(serials.size()).compareTo(units) != 0 || serials.stream().distinct().count() != serials.size()) {
            throw ApiException.badRequest(units.compareTo(BigDecimal.ONE) == 0 ? "Name the serial number of "
                    + l.get("description") + " that came back" : "Name the " + units.stripTrailingZeros().toPlainString()
                    + " serial numbers of " + l.get("description") + " that came back");
        }
        if (loadLineId != null) {
            for (String serial : serials) {
                if (db.count("SELECT count(*) FROM \"FleetLoadLine\" WHERE id = ? AND \"organizationId\" = ? AND"
                        + " jsonb_typeof(\"serialNumbers\") = 'array' AND \"serialNumbers\" @> jsonb_build_array(?::text)",
                        List.of(loadLineId, org(), serial)) == 0) {
                    throw ApiException.badRequest("Serial number " + serial + " was not delivered on this delivery");
                }
            }
        }
        for (String serial : serials) {
            if (db.count("SELECT count(*) FROM \"SalesReturnLine\" rl JOIN \"SalesReturn\" r ON r.id = rl.\"returnId\""
                    + " WHERE rl.\"orderLineId\" = ? AND rl.id <> ? AND r.status IN ('received', 'inspected', 'closed') AND"
                    + " ? = ANY (rl.\"serialNumbers\") AND rl.\"organizationId\" = ?", List.of(l.get("orderLineId"),
                    l.get("id"), serial, org())) > 0) {
                throw ApiException.badRequest("Serial number " + serial + " was already returned");
            }
        }
    }

    private StockReversalService stockReturns;

    /** R1: returns are posted against the delivery's original issue. */
    @org.springframework.beans.factory.annotation.Autowired
    void setStockReturns(StockReversalService stockReturns) {
        this.stockReturns = stockReturns;
    }

    /** SG-7: what the goods cost when they left, so the return reverses that and not today's average. Per base unit. */
    private BigDecimal issueUnitCost(String orderLineId) {
        Map<String, Object> c = db.row("SELECT sum(ll.\"stockValue\") AS v, sum(COALESCE(ll.\"dispatchedQty\", 0) * ll.\"uomFactor\") AS q FROM \"FleetLoadLine\" ll"
                + " JOIN \"FleetDeliveryOrderLine\" dl ON dl.id = ll.\"deliveryOrderLineId\" WHERE dl.\"sourceLineId\" = ? AND ll.\"organizationId\" = ? AND"
                + " ll.\"stockValue\" IS NOT NULL", orderLineId, org());
        if (c == null || c.get("v") == null || dec(c.get("q")).signum() <= 0) {
            return null;
        }
        return dec(c.get("v")).divide(dec(c.get("q")), 6, RoundingMode.HALF_UP);
    }

    /**
     * Inspect: {@code lines [{lineId, disposition: restock|quarantine|damaged|scrap, locationId?, rejectedQty?, rejectReason?,
     * notes?}]}. A rejected quantity is refused: it goes back to the customer out of stock and earns no credit.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspect(String id, Map<String, Object> in) {
        Map<String, Object> r = lock(id, "received");
        Map<String, Map<String, Object>> given = new LinkedHashMap<>();
        if (in.get("lines") instanceof List<?> l) {
            for (Map<String, Object> x : (List<Map<String, Object>>) l) {
                given.put(str(x.get("lineId")), x);
            }
        }
        String from = str(r.get("receiveLocationId"));
        for (Map<String, Object> l : db.rows("SELECT rl.*, ol.\"uomId\", ol.\"uomFactor\", p.\"trackInventory\" FROM"
                + " \"SalesReturnLine\" rl JOIN \"SalesOrderLine\" ol ON ol.id = rl.\"orderLineId\" LEFT JOIN \"Product\" p ON"
                + " p.id = rl.\"productId\" WHERE rl.\"returnId\" = ?", id)) {
            Map<String, Object> g = given.getOrDefault(str(l.get("id")), Map.of());
            String disposition = g.get("disposition") == null ? "restock" : str(g.get("disposition"));
            if (!Set.of("restock", "quarantine", "damaged", "scrap").contains(disposition)) {
                throw ApiException.badRequest("disposition must be one of: damaged, quarantine, restock, scrap");
            }
            // ADR-S094 §7.2 / AD-9: the condition found; damaged or defective goods never go back to sellable stock
            String condition = blank(str(g.get("condition"))) ? switch (disposition) {
                case "restock" -> "usable";
                case "scrap" -> "defective";
                default -> "damaged";
            } : str(g.get("condition"));
            if (!Set.of("usable", "damaged", "defective").contains(condition)) {
                throw ApiException.badRequest("condition must be one of: damaged, defective, usable");
            }
            if ("restock".equals(disposition) && !"usable".equals(condition)) {
                throw ApiException.badRequest("Damaged or defective goods cannot go back to sellable stock: send "
                        + l.get("description") + " to quarantine or scrap");
            }
            BigDecimal rejected = dec(g.get("rejectedQty"));
            if (rejected.signum() < 0 || rejected.compareTo(dec(l.get("receivedQty"))) > 0) {
                throw ApiException.badRequest("Rejected quantity of " + l.get("description") + " is out of range");
            }
            if (rejected.signum() > 0 && blank(str(g.get("rejectReason")))) {
                throw ApiException.badRequest("Give a reason for rejecting " + l.get("description"));
            }
            BigDecimal qty = dec(l.get("receivedQty")).subtract(rejected);
            String target = str(g.get("locationId"));
            boolean tracked = Boolean.TRUE.equals(l.get("trackInventory")) && qty.signum() > 0;
            if (rejected.signum() > 0 && Boolean.TRUE.equals(l.get("trackInventory"))) {
                stock.issue(StockMove.of(str(l.get("productId")), from, rejected).variant(str(l.get("variantId")))
                        .uom(str(l.get("uomId")), dec(l.get("uomFactor"))).moveType("issue").reference(str(r.get("number")))
                        .source("sales_return", id).notes("Rejected on return " + r.get("number") + ": " + g.get("rejectReason")));
            }
            if (tracked && Set.of("quarantine", "damaged").contains(disposition)) {
                if (target == null) {
                    throw ApiException.badRequest("Choose the " + disposition + " location for " + l.get("description"));
                }
                if (!target.equals(from)) {
                    StockMove m = StockMove.transfer(str(l.get("productId")), from, target, qty)
                            .variant(str(l.get("variantId"))).uom(str(l.get("uomId")),
                            dec(l.get("uomFactor"))).reference(str(r.get("number"))).source("sales_return", id);
                    stock.transfer(m);
                }
            } else if (tracked && "scrap".equals(disposition)) {
                stock.issue(StockMove.of(str(l.get("productId")), from, qty).variant(str(l.get("variantId")))
                        .uom(str(l.get("uomId")), dec(l.get(
                        "uomFactor"))).moveType("waste").reference(str(r.get("number"))).source("sales_return", id)
                        .notes("Scrapped return " + r.get("number")));
            }
            db.update("UPDATE \"SalesReturnLine\" SET disposition = ?, \"dispositionLocationId\" = ?, \"inspectionNotes\" = ?,"
                    + " \"rejectedQty\" = ?, \"rejectReason\" = ?, condition = ? WHERE id = ?"
                            + " AND \"organizationId\" = ?", disposition, Set.of("quarantine", "damaged").contains(disposition)
                    ? target : from, str(g.get("notes")), rejected, str(g.get("rejectReason")), condition, l.get("id"),
                    org());
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'inspected', \"inspectedAt\" = now(), \"updatedAt\" = now() WHERE"
                + " id = ?", id);
        returnedProjection(str(r.get("orderId")));
        audit.record(AuditInput.of("SalesReturn", id, "update", null, Obj.of("status", "inspected")));
        return get(id);
    }

    /**
     * Close with an outcome: {@code credit_note} (default), {@code refund} (credit note + refund payment:
     * {@code paymentMethod}, {@code accountId}), {@code replacement} (new draft order for the returned quantity at no
     * charge) or {@code none}.
     */
    @Transactional
    public Map<String, Object> close(String id, Map<String, Object> in) {
        Map<String, Object> r = lock(id, null);
        boolean physical = Boolean.TRUE.equals(r.get("physicalReturn"));
        if (physical ? !"inspected".equals(r.get("status")) : !"approved".equals(r.get("status"))) {
            throw ApiException.badRequest(physical ? "Inspect the returned goods before closing" : "Approve the claim first");
        }
        String outcome = blank(str(in.get("outcome"))) ? "credit_note" : str(in.get("outcome"));
        if (!Set.of("credit_note", "refund", "replacement", "none").contains(outcome)) {
            throw ApiException.badRequest("outcome must be one of: credit_note, none, refund, replacement");
        }
        String orderId = str(r.get("orderId"));
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ?", orderId, org());
        List<LineInput> cnLines = new ArrayList<>();
        List<Map<String, Object>> cnLinks = new ArrayList<>();
        String invoiceId = null;
        List<Map<String, Object>> replacement = new ArrayList<>();
        Set<String> settledLines = new java.util.LinkedHashSet<>();
        for (Map<String, Object> l : db.rows("SELECT rl.*, ol.* , rl.id AS \"returnLineId\", rl.quantity AS \"claimQty\","
                + " ol.id AS \"lineId\" FROM \"SalesReturnLine\" rl JOIN \"SalesOrderLine\" ol ON ol.id = rl.\"orderLineId\""
                + " WHERE rl.\"returnId\" = ?", id)) {
            BigDecimal qty = physical ? dec(l.get("receivedQty")).subtract(dec(l.get("rejectedQty"))) : dec(l.get("claimQty"));
            if (qty.signum() <= 0) {
                continue;
            }
            if ("replacement".equals(outcome)) {
                replacement.add(Obj.of("productId", l.get("productId"), "description", "Replacement: " + l.get(
                        "description"), "quantity", qty, "uomId", l.get("uomId"), "unitPrice", 0, "priceSource", "manual"));
                continue;
            }
            if ("none".equals(outcome)) {
                continue;
            }
            BigDecimal net = dec(l.get("qtyInvoiced")).subtract(dec(l.get("qtyCredited")));
            BigDecimal credit = qty.min(net).max(BigDecimal.ZERO);
            if (credit.signum() > 0) {
                cnLines.add(new LineInput(str(l.get("productId")), null, null, null, str(settings.settings().get(
                        "salesReturnsAccountId")), "Return " + r.get("number")
                        + ": " + l.get("description"), credit, dec(l.get("unitPrice")), dec(l.get("discountPercent")), null,
                        null, null, "manual", str(l.get("taxId")), null, Boolean.TRUE.equals(l.get("taxInclusive")),
                        str(l.get("uomId"))));
                cnLinks.add(Obj.of("lineId", l.get("lineId"), "qty", credit));
                if (invoiceId == null) {
                    Map<String, Object> inv = db.row("SELECT k.\"documentId\" FROM \"SalesInvoiceLink\" k JOIN \"Document\" d"
                            + " ON d.id = k.\"documentId\" WHERE k.\"orderLineId\" = ? AND k.kind = 'regular' AND"
                            + " d.status::text IN ('posted', 'paid') ORDER BY d.\"createdAt\" DESC LIMIT 1", l.get("lineId"));
                    invoiceId = inv == null ? null : str(inv.get("documentId"));
                }
                db.update("UPDATE \"SalesReturnLine\" SET \"creditQty\" = ? WHERE id = ?"
                        + " AND \"organizationId\" = ?", credit, l.get("returnLineId"), org());
            }
            // ADR-S094 §4.5: the part not invoiced yet needs no credit; it leaves the billable quantity through the
            // returned-and-credited (R) or written-off (W) quantity, recomputed below.
            settledLines.add(str(l.get("lineId")));
        }
        String creditNoteId = null;
        String refundId = null;
        String replacementId = null;
        if (!cnLines.isEmpty()) {
            Map<String, Object> cn = creditNotes.create(new CreditNoteInput(str(r.get("partnerId")), LocalDate.now(BusinessZone.get())
                    .toString(), null, str(r.get("number")), "Return " + r.get("number") + " of sales order " + o.get(
                            "number"), invoiceId, cnLines));
            creditNoteId = str(cn.get("id"));
            creditNotes.post(creditNoteId, "none");
            creditFlow.markType(creditNoteId, "return");
            for (Map<String, Object> k : cnLinks) {
                db.update("INSERT INTO \"SalesInvoiceLink\" (id, \"organizationId\", \"orderId\", \"orderLineId\","
                        + " \"documentId\", kind, quantity, amount) VALUES (?, ?, ?, ?, ?, 'credit_note', ?, 0)",
                        SalesDb.newId(), org(), orderId, k.get("lineId"), creditNoteId, k.get("qty"));
            }
            if ("refund".equals(outcome)) {
                // Posting already applied the credit to the invoice's open balance; only what is left is paid in cash.
                BigDecimal refundable = creditNotes.takeOpenCredit(creditNoteId).setScale(2, RoundingMode.HALF_UP);
                if (refundable.signum() > 0) {
                    // AD-9: over the tender the invoice was paid with, unless one is chosen
                    String method = str(in.get("paymentMethod"));
                    String account = str(in.get("accountId"));
                    if (blank(method) && invoiceId != null) {
                        Map<String, Object> paid = db.row("SELECT p.\"paymentMethod\", p.\"accountId\" FROM"
                                + " \"PaymentAllocation\" a JOIN \"Payment\" p ON p.id = a.\"paymentId\" WHERE"
                                + " COALESCE(a.\"documentId\", a.\"invoiceId\") = ? AND a.\"organizationId\" = ? AND"
                                + " p.direction = 'inbound' AND p.\"voidedAt\" IS NULL ORDER BY p.\"createdAt\" DESC LIMIT 1",
                                invoiceId, org());
                        if (paid != null) {
                            method = str(paid.get("paymentMethod"));
                            account = blank(account) ? str(paid.get("accountId")) : account;
                        }
                    }
                    // FX-3: a foreign credit is refunded in its own currency at its carrying rate
                    refundId = str(creditFlow.payOut(creditNoteId, str(r.get("partnerId")), refundable,
                            method, account, in.get("exchangeRate") == null ? null
                                    : new BigDecimal(in.get("exchangeRate").toString()), "Refund " + r.get("number"))
                            .get("id"));
                }
            }
        }
        if (!replacement.isEmpty()) {
            Map<String, Object> rep = orders.create(Obj.of("partnerId", r.get("partnerId"), "locationId", o.get(
                    "locationId"), "siteId", o.get("siteId"), "notes", "Replacement for " + r.get("number"), "lines",
                    replacement));
            replacementId = str(rep.get("id"));
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'closed', outcome = ?, \"creditNoteId\" = ?, \"refundPaymentId\" = ?,"
                + " \"replacementOrderId\" = ?, \"closedAt\" = now(), \"updatedAt\" = now() WHERE id = ?"
                        + " AND \"organizationId\" = ?", outcome,
                creditNoteId, refundId, replacementId, id, org());
        db.update("UPDATE \"SalesOrderLine\" l SET \"qtyCredited\" = COALESCE((SELECT sum(k.quantity) FROM"
                + " \"SalesInvoiceLink\" k JOIN \"Document\" d ON d.id = k.\"documentId\" WHERE k.\"orderLineId\" = l.id AND"
                + " k.kind = 'credit_note' AND d.status::text IN ('posted', 'paid')), 0) WHERE l.\"orderId\" = ?", orderId);
        // ADR-S094 §4.5: what this return settled by a credit (R) or wrote off (W) leaves the billable quantity
        Map<String, BigDecimal> settledBefore = settledQty(orderId);
        returnedProjection(orderId);
        Map<String, BigDecimal> settledAfter = settledQty(orderId);
        for (String lineId : settledLines) {
            fulfilment.lessBillable(orderId, lineId, settledAfter.getOrDefault(lineId, BigDecimal.ZERO).subtract(
                    settledBefore.getOrDefault(lineId, BigDecimal.ZERO)), (physical ? "Returned on " : "Written off on ")
                            + r.get("number"));
        }
        if (r.get("complaintId") != null) {
            db.update("UPDATE \"CustomerComplaint\" SET status = 'resolved', resolution = ?, \"resolvedAt\" = now(),"
                    + " \"updatedAt\" = now() WHERE id = ?"
                            + " AND \"organizationId\" = ?", "Return " + r.get("number") + " closed: " + outcome,
                    r.get("complaintId"), org());
        }
        orders.refreshStatus(orderId);
        events.publishOnCommit(DomainEvents.SALES_RETURN_CLOSED, Obj.of("v", 1, "organizationId", org(), "returnId", id, "number",
                r.get("number"), "orderId", orderId, "outcome", outcome));
        audit.record(AuditInput.of("SalesReturn", id, "update", null, Obj.of("status", "closed", "outcome", outcome,
                "creditNoteId", creditNoteId)));
        Map<String, Object> out = get(id);
        out.put("creditAmount", creditNoteId == null ? 0 : JsNumbers.js(dec(creditNotes.findOne(creditNoteId).get(
                "totalAmount"))));
        return out;
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> r = lock(id, null);
        if (!Set.of("requested", "approved").contains(r.get("status"))) {
            throw ApiException.badRequest("Only a return not yet received can be cancelled");
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'cancelled', \"updatedAt\" = now() WHERE id = ?"
                + " AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("SalesReturn", id, "cancel"));
        orders.refreshStatus(str(r.get("orderId"))); // ADR-S094 §4.5: no longer holds the order open
        return get(id);
    }

    /** Per order line: returned and credited plus written off (R + W), as stored. */
    private Map<String, BigDecimal> settledQty(String orderId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> l : db.rows("SELECT id, \"qtyReturnCredited\" + \"qtyWrittenOff\" AS q FROM"
                + " \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"organizationId\" = ?", orderId, org())) {
            out.put(str(l.get("id")), dec(l.get("q")));
        }
        return out;
    }

    /**
     * ADR-S094 §4.5 per order line: goods back and kept (received − rejected); of those, the ones settled by a credit; and
     * quantities credited without a return (closed claims, W).
     */
    private void returnedProjection(String orderId) {
        db.update("""
                UPDATE "SalesOrderLine" l
                   SET "qtyReturned" = COALESCE((SELECT sum(rl."receivedQty" - COALESCE(rl."rejectedQty", 0))
                          FROM "SalesReturnLine" rl JOIN "SalesReturn" r ON r.id = rl."returnId"
                         WHERE rl."orderLineId" = l.id AND r."physicalReturn"
                           AND r.status IN ('received', 'inspected', 'closed')), 0),
                       "qtyReturnCredited" = COALESCE((SELECT sum(rl."receivedQty" - COALESCE(rl."rejectedQty", 0))
                          FROM "SalesReturnLine" rl JOIN "SalesReturn" r ON r.id = rl."returnId"
                         WHERE rl."orderLineId" = l.id AND r."physicalReturn" AND r.status = 'closed'
                           AND r.outcome IN ('credit_note', 'refund')), 0),
                       "qtyWrittenOff" = COALESCE((SELECT sum(rl.quantity)
                          FROM "SalesReturnLine" rl JOIN "SalesReturn" r ON r.id = rl."returnId"
                         WHERE rl."orderLineId" = l.id AND NOT r."physicalReturn" AND r.status = 'closed'
                           AND r.outcome IN ('credit_note', 'refund')), 0)
                 WHERE l."orderId" = ?""", orderId);
    }

    private Map<String, Object> lock(String id, String expected) {
        Map<String, Object> r = db.row("SELECT * FROM \"SalesReturn\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE",
                id, org());
        if (r == null) {
            throw ApiException.notFound("Return not found");
        }
        orders.require(str(r.get("orderId")));
        if (expected != null && !expected.equals(r.get("status"))) {
            throw ApiException.badRequest("Return " + r.get("number") + " is " + r.get("status"));
        }
        return r;
    }
}
