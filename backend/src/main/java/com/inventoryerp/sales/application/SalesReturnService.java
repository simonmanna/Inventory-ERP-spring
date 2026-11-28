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

    @org.springframework.beans.factory.annotation.Autowired
    void setCollaborators(SalesAccess access, SalesSettingsService settings,
            @org.springframework.context.annotation.Lazy SalesCreditFlowService creditFlow) {
        this.access = access;
        this.settings = settings;
        this.creditFlow = creditFlow;
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
        r.put("lines", db.rows("SELECT * FROM \"SalesReturnLine\" WHERE \"returnId\" = ? ORDER BY description", id));
        return r;
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
            db.update("INSERT INTO \"SalesReturnLine\" (id, \"organizationId\", \"returnId\", \"orderLineId\", \"productId\","
                    + " \"variantId\", description, quantity) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", SalesDb.newId(), org(), id,
                    ol.get("id"), ol.get("productId"), ol.get("variantId"), ol.get("description"), qty);
        }
        if (!blank(str(in.get("complaintId")))) {
            db.update("UPDATE \"CustomerComplaint\" SET \"returnId\" = ?, status = 'investigating', \"updatedAt\" = now()"
                    + " WHERE id = ? AND \"organizationId\" = ?", id, str(in.get("complaintId")), org());
        }
        audit.record(AuditInput.of("SalesReturn", id, "create", null, Obj.of("number", number, "orderId", orderId)));
        return get(id);
    }

    /**
     * What the customer actually holds, minus quantities on other open / done returns. Sales readiness DEL-10: for goods
     * that went out on deliveries that is the accepted quantity (not what is in transit, damaged, short or rejected);
     * otherwise what was delivered or invoiced (net of credits).
     */
    private BigDecimal returnable(Map<String, Object> ol) {
        BigDecimal delivered = dec(ol.get("qtyDispatched")).signum() > 0 ? dec(ol.get("qtyAccepted"))
                : dec(ol.get("qtyDelivered")).max(dec(ol.get("qtyInvoiced")).subtract(dec(ol.get("qtyCredited"))));
        BigDecimal claimed = dec(db.row("SELECT COALESCE(sum(l.quantity), 0) AS q FROM \"SalesReturnLine\" l JOIN"
                + " \"SalesReturn\" r ON r.id = l.\"returnId\" WHERE l.\"orderLineId\" = ? AND r.status NOT IN ('rejected',"
                + " 'cancelled')", ol.get("id")).get("q"));
        BigDecimal open = delivered.subtract(claimed).max(BigDecimal.ZERO);
        // IPR F2: goods that left stock on a recorded issue can come back only up to what that issue still has out
        // (net of every return already linked to it, credit-note restocks included), less what returns not yet
        // received have claimed. A line without any issue is a financial credit only (receive restocks nothing).
        List<String> codes = issueLedgerCodes(str(ol.get("id")));
        if (codes.isEmpty() || ol.get("productId") == null) {
            return open;
        }
        BigDecimal outBase = BigDecimal.ZERO;
        for (StockReversalService.Returnable x : stockReturns.returnableByProduct(
                StockReversalService.IssueSource.ledgerCodes(codes))) {
            if (x.productId().equals(str(ol.get("productId")))
                    && java.util.Objects.equals(x.variantId(), str(ol.get("variantId")))) {
                outBase = outBase.add(x.quantity());
            }
        }
        BigDecimal factor = dec(ol.get("uomFactor")).signum() > 0 ? dec(ol.get("uomFactor")) : BigDecimal.ONE;
        BigDecimal pending = dec(db.row("SELECT COALESCE(sum(l.quantity), 0) AS q FROM \"SalesReturnLine\" l JOIN"
                + " \"SalesReturn\" r ON r.id = l.\"returnId\" WHERE l.\"orderLineId\" = ? AND r.\"physicalReturn\" AND"
                + " r.status IN ('requested', 'approved')", ol.get("id")).get("q"));
        BigDecimal issuedLeft = outBase.divide(factor, 3, RoundingMode.HALF_UP).subtract(pending).max(BigDecimal.ZERO);
        return open.min(issuedLeft);
    }

    /**
     * IPR F2: the base quantity a return line actually brought back into stock (its {@code sales_return} receipt rows);
     * zero for a line received as a financial credit only, whose inspection then moves no stock.
     */
    private BigDecimal restockedBase(String returnId, String returnLineId) {
        return dec(db.row("SELECT COALESCE(sum(\"quantityChange\"), 0) AS q FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"referenceType\" = 'sales_return' AND \"referenceId\" = ? AND"
                + " \"sourceLineId\" = ? AND \"quantityChange\" > 0", org(), returnId, returnLineId).get("q"));
    }

    @Transactional
    public Map<String, Object> approve(String id) {
        Map<String, Object> r = lock(id, "requested");
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
        // IPR LK-3: the sales order and its lines before the engine (return → order → order lines → stock), the order
        // the fulfilment rebuild and the sales delivery routes use; the lines are updated after the restock.
        orders.lock(str(r.get("orderId")));
        db.rows("SELECT id FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"organizationId\" = ? ORDER BY id FOR UPDATE",
                r.get("orderId"), org());
        if (!Boolean.TRUE.equals(r.get("physicalReturn"))) {
            throw ApiException.badRequest("This return is a claim without goods; close it directly");
        }
        String location = blank(str(in.get("locationId"))) ? str(r.get("receiveLocationId")) : str(in.get("locationId"));
        if (location == null) {
            throw ApiException.badRequest("Choose the location the goods are received into");
        }
        Map<String, BigDecimal> given = new LinkedHashMap<>();
        if (in.get("lines") instanceof List<?> l) {
            for (Map<String, Object> x : (List<Map<String, Object>>) l) {
                given.put(str(x.get("lineId")), dec(x.get("receivedQty")));
            }
        }
        for (Map<String, Object> l : db.rows("SELECT rl.*, ol.\"uomId\", ol.\"uomFactor\", p.\"trackInventory\" FROM"
                + " \"SalesReturnLine\" rl JOIN \"SalesOrderLine\" ol ON ol.id = rl.\"orderLineId\" LEFT JOIN \"Product\" p ON"
                + " p.id = rl.\"productId\" WHERE rl.\"returnId\" = ?", id)) {
            BigDecimal qty = given.getOrDefault(str(l.get("id")), dec(l.get("quantity")));
            if (qty.signum() < 0 || qty.compareTo(dec(l.get("quantity"))) > 0) {
                throw ApiException.badRequest("Received quantity of " + l.get("description") + " is out of range");
            }
            BigDecimal issueCost = issueUnitCost(str(l.get("orderLineId")));
            if (qty.signum() > 0 && Boolean.TRUE.equals(l.get("trackInventory"))) {
                List<String> issueCodes = issueLedgerCodes(str(l.get("orderLineId")));
                if (!issueCodes.isEmpty()) {
                    // R1: the goods come back on the lot, expiry and serial the delivery issued them from, at that
                    // cost, into the receiving location; the delivery's rows are the authority.
                    stockReturns.returnFromSource(new StockReversalService.ReturnFromSource(
                            StockReversalService.IssueSource.ledgerCodes(issueCodes),
                            List.of(new StockReversalService.ReturnLine(str(l.get("productId")),
                                    str(l.get("variantId")), qty, str(l.get("uomId")), dec(l.get("uomFactor")), null,
                                    null, str(l.get("id")))),
                            "sales_return", id, str(r.get("number")), null, null, location, true, null));
                }
                // IPR F2: goods with no recorded stock issue never left stock (e.g. an invoice of an ordered-policy line
                // that was never delivered): nothing is restocked; the return is a financial credit only. IPR review 1
                // (N2): the goods did come back physically, so the line is flagged for manual review, not skipped.
                if (issueCodes.isEmpty()) {
                    flagUnmatched(r, l, qty, location);
                }
            }
            db.update("UPDATE \"SalesReturnLine\" SET \"receivedQty\" = ?, \"issueUnitCost\" = ? WHERE id = ?"
                    + " AND \"organizationId\" = ?", qty, issueCost, l.get("id"), org());
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'received', \"receiveLocationId\" = ?, \"receivedAt\" = now(),"
                + " \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?", location, id, org());
        returnedProjection(str(r.get("orderId")));
        events.publishOnCommit(DomainEvents.SALES_RETURN_RECEIVED, Obj.of("v", 1, "organizationId", org(), "returnId", id,
                "number", r.get("number"), "orderId", r.get("orderId")));
        audit.record(AuditInput.of("SalesReturn", id, "update", null, Obj.of("status", "received", "location", location)));
        return get(id);
    }

    /**
     * R1 / IPR review 1 (N2): the stock moves that issued an order line's goods. Fleet is not the only path:
     * <ul>
     *   <li>the dispatch of its delivery-order load lines ({@code FleetLoadLine.stockLedgerCode}), also of a delivery
     *   made for the line's invoice (the delivery line names the invoice line);</li>
     *   <li>a legacy load line without its code: the trip's {@code fleet_trip} issue rows of that product;</li>
     *   <li>the line's invoice issue ({@code sales_invoice} rows naming the linked invoice line): a collection of
     *   undelivered goods, or an invoice posted before sales orders marked their invoices as delivered by fleet.</li>
     * </ul>
     * A return against these rows never brings back more than they still have out ({@code returnFromSource}).
     */
    private List<String> issueLedgerCodes(String orderLineId) {
        return db.rows("SELECT DISTINCT code FROM ("
                + " SELECT ll.\"stockLedgerCode\" AS code FROM \"FleetLoadLine\" ll"
                + " JOIN \"FleetDeliveryOrderLine\" dl ON dl.id = ll.\"deliveryOrderLineId\""
                + " WHERE ll.\"organizationId\" = ? AND ll.\"stockLedgerCode\" IS NOT NULL"
                + " AND (dl.\"sourceLineId\" = ? OR dl.\"sourceLineId\" IN (SELECT k.\"documentLineId\""
                + " FROM \"SalesInvoiceLink\" k WHERE k.\"organizationId\" = ? AND k.\"orderLineId\" = ?"
                + " AND k.\"documentLineId\" IS NOT NULL))"
                + " UNION SELECT l.\"ledgerCode\" FROM \"FleetLoadLine\" ll"
                + " JOIN \"FleetDeliveryOrderLine\" dl ON dl.id = ll.\"deliveryOrderLineId\""
                + " JOIN \"InventoryLedger\" l ON l.\"organizationId\" = ll.\"organizationId\""
                + " AND l.\"referenceType\" = 'fleet_trip' AND l.\"referenceId\" = ll.\"loadId\""
                + " AND l.\"productId\" = ll.\"productId\" AND l.\"quantityChange\" < 0 AND l.\"reversesLedgerId\" IS NULL"
                + " WHERE ll.\"organizationId\" = ? AND ll.\"stockLedgerCode\" IS NULL AND dl.\"sourceLineId\" = ?"
                + " UNION SELECT l.\"ledgerCode\" FROM \"SalesInvoiceLink\" k"
                + " JOIN \"InventoryLedger\" l ON l.\"organizationId\" = k.\"organizationId\""
                + " AND l.\"referenceType\" = 'sales_invoice' AND l.\"referenceId\" = k.\"documentId\""
                + " AND l.\"sourceLineId\" = k.\"documentLineId\" AND l.\"quantityChange\" < 0"
                + " AND l.\"reversesLedgerId\" IS NULL"
                + " WHERE k.\"organizationId\" = ? AND k.\"orderLineId\" = ? AND k.kind = 'regular'"
                + ") x WHERE code IS NOT NULL", org(), orderLineId, org(), orderLineId, org(), orderLineId, org(),
                orderLineId).stream().map(x -> str(x.get("code"))).toList();
    }

    /** IPR review 1 (N2): kind of the Posting Monitor row of a physical return with no stock issue to return against. */
    public static final String UNMATCHED_RETURN = "sales_return_unmatched";

    /**
     * IPR review 1 (N2): goods of a return line came back physically but no stock issue of its order line is known,
     * so nothing was restocked (never more than was issued). An open {@value #UNMATCHED_RETURN} row tells staff to
     * check the goods and restock them by hand (a direct stock receipt) when they did leave stock.
     */
    private void flagUnmatched(Map<String, Object> r, Map<String, Object> l, BigDecimal qty, String location) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("returnId", r.get("id"));
        payload.put("returnNumber", r.get("number"));
        payload.put("returnLineId", l.get("id"));
        payload.put("orderId", r.get("orderId"));
        payload.put("orderLineId", l.get("orderLineId"));
        payload.put("variantId", l.get("variantId"));
        payload.put("receivedQty", JsNumbers.js(qty));
        db.update("INSERT INTO \"InventoryException\" (id, \"organizationId\", \"locationId\", \"productId\","
                + " description, quantity, kind, reason, payload, status, \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?::jsonb, 'open', CURRENT_TIMESTAMP)", SalesDb.newId(), org(), location, l.get("productId"),
                "Return " + r.get("number") + ": " + l.get("description") + " received without a stock issue to"
                        + " return against", qty, UNMATCHED_RETURN, "No delivery, collection or invoice issue of this"
                        + " order line was found, so the goods were not restocked; check them and restock by hand if"
                        + " they left stock", com.inventoryerp.shared.web.EntityJson.toJson(payload));
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
            BigDecimal rejected = dec(g.get("rejectedQty"));
            if (rejected.signum() < 0 || rejected.compareTo(dec(l.get("receivedQty"))) > 0) {
                throw ApiException.badRequest("Rejected quantity of " + l.get("description") + " is out of range");
            }
            if (rejected.signum() > 0 && blank(str(g.get("rejectReason")))) {
                throw ApiException.badRequest("Give a reason for rejecting " + l.get("description"));
            }
            BigDecimal qty = dec(l.get("receivedQty")).subtract(rejected);
            String target = str(g.get("locationId"));
            // IPR F2: a line received as a financial credit only (nothing restocked) moves no stock at inspection.
            boolean stocked = Boolean.TRUE.equals(l.get("trackInventory"))
                    && restockedBase(id, str(l.get("id"))).signum() > 0;
            boolean tracked = stocked && qty.signum() > 0;
            if (rejected.signum() > 0 && stocked) {
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
                    + " \"rejectedQty\" = ?, \"rejectReason\" = ? WHERE id = ?"
                            + " AND \"organizationId\" = ?", disposition, Set.of("quarantine", "damaged").contains(disposition)
                    ? target : from, str(g.get("notes")), rejected, str(g.get("rejectReason")), l.get("id"), org());
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'inspected', \"inspectedAt\" = now(), \"updatedAt\" = now() WHERE"
                + " id = ?", id);
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
        // IPR LK-3: the sales order before its lines (they are updated below), as every other sales path.
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ? FOR UPDATE", orderId, org());
        List<LineInput> cnLines = new ArrayList<>();
        List<Map<String, Object>> cnLinks = new ArrayList<>();
        String invoiceId = null;
        List<Map<String, Object>> replacement = new ArrayList<>();
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
            BigDecimal credit = qty.min(net);
            BigDecimal uninvoiced = qty.subtract(credit);
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
            if (uninvoiced.signum() > 0) {
                // not invoiced yet: the returned quantity is no longer billable (recorded decision)
                BigDecimal before = dec(l.get("qtyBillable"));
                BigDecimal after = before.subtract(uninvoiced).max(dec(l.get("qtyInvoiced")));
                db.update("INSERT INTO \"SalesFulfilmentDecision\" (id, \"organizationId\", \"orderId\", \"orderLineId\","
                        + " basis, quantity, \"previousQty\", reason, \"decidedById\") VALUES (?, ?, ?, ?, 'manual', ?, ?, ?, ?)",
                        SalesDb.newId(), org(), orderId, l.get("lineId"), after, before, "Returned on " + r.get("number"),
                        user());
                db.update("UPDATE \"SalesOrderLine\" SET \"qtyBillable\" = ? WHERE id = ?"
                        + " AND \"organizationId\" = ?", after, l.get("lineId"), org());
            }
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
                    // FX-3: a foreign credit is refunded in its own currency at its carrying rate
                    refundId = str(creditFlow.payOut(creditNoteId, str(r.get("partnerId")), refundable,
                            str(in.get("paymentMethod")), str(in.get("accountId")), in.get("exchangeRate") == null ? null
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
        return get(id);
    }

    private void returnedProjection(String orderId) {
        db.update("UPDATE \"SalesOrderLine\" l SET \"qtyReturned\" = COALESCE((SELECT sum(rl.\"receivedQty\") FROM"
                + " \"SalesReturnLine\" rl JOIN \"SalesReturn\" r ON r.id = rl.\"returnId\" WHERE rl.\"orderLineId\" = l.id"
                + " AND r.status IN ('received', 'inspected', 'closed')), 0) WHERE l.\"orderId\" = ?", orderId);
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
