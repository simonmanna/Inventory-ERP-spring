package com.inventoryerp.sales.application;

import com.inventoryerp.invoicing.application.SourceRef;
import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.invoicing.application.CreditNoteService;
import com.inventoryerp.invoicing.application.CreditNoteService.CreditNoteInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-S094 C3b (§7.4): the Reverse of a cash Sale. The whole sale is undone after its confirm, in three steps, each
 * committed on its own:
 * <ol>
 *   <li><b>Approval</b> (TA-10), only when the organization's credit notes need approval: the reversal's credit note
 *       is prepared as a draft and its approval asked for, before anything is cancelled at the fiscal authority. The
 *       reversal stops (409) until it is approved.
 *   <li><b>Fiscal cancel</b> (phase 1, TA-5): the sale's fiscal document is cancelled at the provider first
 *       ({@link CashSaleFiscalService#gateSaleReversal}). The reversal stops (409) while the cancellation is pending.
 *   <li><b>The reversal</b> (phase 2, T2), one transaction:
 *       <ul>
 *         <li>the return of every line, received into the counter's location on the delivery the goods left on
 *             (serials checked) and inspected as the cashier found it;
 *         <li>the credit note against the sale's invoice;
 *         <li>the settlement: paid back over each tender the sale was paid with, or kept on the customer's account;
 *         <li>the sale cancelled as reversed, its outcome amounts kept (I6).
 *       </ul>
 *       Any failure rolls all of it back. After a cancelled fiscal document the sale shows "reversal pending" until the
 *       reversal is asked for again.
 * </ol>
 * Partial returns go through the return flow; the sale stays done. A sale that already has returns or credit notes is
 * not reversed here.
 *
 * <p>The approval and fiscal steps are retry-safe bookkeeping (they reuse their draft and their live cancellation), so
 * they never keep the reversal's idempotency key. A 409 from them is a definitive answer for that key; once the
 * approval or the cancellation is through, the reversal is asked for again with a new key. A failure of T2 rolls back
 * and releases the key.
 */
@Service
public class SalesCashReversalService {

    private static final Set<String> FIELDS = Set.of("reason", "returnReason", "settlement", "locationId", "lines");
    private static final Set<String> LINE_FIELDS = Set.of("orderLineId", "quantity", "serialNumbers", "condition",
            "disposition", "locationId", "notes");

    private final SalesDb db;
    private final SalesOrderService orders;
    private final SalesCashSaleService cashSales;
    private final SalesReturnService returns;
    private final SalesCreditFlowService creditFlow;
    private final SalesOutcomeService outcome;
    private final CashSaleFiscalService fiscal;
    private final CreditNoteService creditNotes;
    private final ApprovalsService approvals;
    private final AuditService audit;
    private final EventBus events;
    private final TransactionTemplate tx;
    private final TransactionTemplate retrySafe;

    public SalesCashReversalService(SalesDb db, SalesOrderService orders, SalesCashSaleService cashSales,
            SalesReturnService returns, SalesCreditFlowService creditFlow, SalesOutcomeService outcome,
            CashSaleFiscalService fiscal, CreditNoteService creditNotes, ApprovalsService approvals, AuditService audit,
            EventBus events, PlatformTransactionManager txm) {
        this.db = db;
        this.orders = orders;
        this.cashSales = cashSales;
        this.returns = returns;
        this.creditFlow = creditFlow;
        this.outcome = outcome;
        this.fiscal = fiscal;
        this.creditNotes = creditNotes;
        this.approvals = approvals;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
        this.retrySafe = new TransactionTemplate(txm);
        this.retrySafe.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.retrySafe.setName(BusinessOutcome.RETRY_SAFE_TX);
    }

    /** One line coming back: the sale line, its quantity, and the cashier's findings. */
    private record Back(Map<String, Object> line, BigDecimal qty, List<String> serials, String condition,
            String disposition, String locationId, String notes) {}

    /** A checked reversal request. */
    private record Plan(String id, String reason, String returnReason, String settlement, String locationId,
            List<Back> lines) {}

    /**
     * Reverse a done cash sale. {@code reason} is required. {@code settlement}: {@code refund} (the default: paid back
     * over each tender the sale was paid with) or {@code credit} (kept on the customer's account). {@code locationId}:
     * where the goods come back (default the sale's location). {@code returnReason}: the return's reason code (default
     * {@code other}). {@code lines}: every goods line of the sale as the cashier found it — {@code orderLineId},
     * {@code quantity} (all of it), {@code serialNumbers} (serial-tracked goods), {@code condition} and
     * {@code disposition} (as a return's inspection; default usable and restock), {@code locationId} (the quarantine or
     * damaged location) and {@code notes}. Not transactional itself: see the class comment for its three steps.
     */
    public Map<String, Object> reverse(String id, Map<String, Object> in) {
        Plan plan = plan(id, in == null ? Map.of() : in);
        approvalGate(plan);
        Map<String, Object> o = db.row("SELECT \"fiscalStatus\", \"reversalRequestedAt\" FROM \"SalesOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ?", id, org());
        if (Set.of("issued", "cancellation_pending").contains(str(o.get("fiscalStatus")))
                && o.get("reversalRequestedAt") == null) {
            // the fiscal cancel cannot be undone: the sale records who asked for its reversal, and why, before it
            retrySafe.executeWithoutResult(s -> requested(id, plan.reason(), null));
        }
        fiscal.gateSaleReversal(id, plan.reason());
        return tx.execute(s -> complete(plan));
    }

    // ------------------------------------------------------------------ the request

    @SuppressWarnings("unchecked")
    private Plan plan(String id, Map<String, Object> in) {
        SalesCashSaleService.unknownFields(in, FIELDS);
        Map<String, Object> o = orders.require(id);
        reversible(o);
        String reason = str(in.get("reason"));
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        String settlement = blank(str(in.get("settlement"))) ? "refund" : str(in.get("settlement"));
        if (!Set.of("refund", "credit").contains(settlement)) {
            throw ApiException.badRequest("settlement must be one of: credit, refund");
        }
        String returnReason = blank(str(in.get("returnReason"))) ? "other" : str(in.get("returnReason"));
        if (!SalesReturnService.REASONS.contains(returnReason)) {
            throw ApiException.badRequest("returnReason must be one of: " + String.join(", ",
                    SalesReturnService.REASONS.stream().sorted().toList()));
        }
        String location = blank(str(in.get("locationId"))) ? str(o.get("locationId")) : str(in.get("locationId"));
        if (location == null) {
            throw ApiException.badRequest("Choose the location the goods come back to");
        }
        if (!(in.get("lines") instanceof List<?> given) || given.isEmpty()) {
            throw ApiException.badRequest("lines should not be empty: confirm each line as it comes back");
        }
        Map<String, Map<String, Object>> findings = new LinkedHashMap<>();
        for (Object x : given) {
            if (!(x instanceof Map<?, ?> m)) {
                throw ApiException.badRequest("lines must be objects");
            }
            Map<String, Object> g = (Map<String, Object>) m;
            SalesCashSaleService.unknownFields(g, LINE_FIELDS);
            if (findings.put(str(g.get("orderLineId")), g) != null) {
                throw ApiException.badRequest("Each line comes back once");
            }
        }
        List<Back> lines = new ArrayList<>();
        for (Map<String, Object> l : db.rows("SELECT * FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY \"sortOrder\", id", id, org())) {
            BigDecimal held = dec(l.get("quantity")).subtract(dec(l.get("qtyCancelled")));
            if (held.signum() <= 0) {
                continue;
            }
            Map<String, Object> g = findings.remove(str(l.get("id")));
            if (g == null) {
                if (l.get("productId") != null) {
                    throw ApiException.badRequest("A reversal takes every line back: " + l.get("description")
                            + " is missing; return part of a sale through a return");
                }
                // a line without goods (a charge) is credited with the rest; nothing to inspect
                lines.add(new Back(l, held, List.of(), "usable", "restock", null, null));
                continue;
            }
            BigDecimal qty = g.get("quantity") == null ? held : dec(g.get("quantity"));
            if (qty.compareTo(held) != 0) {
                throw ApiException.badRequest("A reversal takes every line back in full: " + l.get("description") + " is "
                        + JsNumbers.js(qty) + " of " + JsNumbers.js(held) + "; return part of a sale through a return");
            }
            lines.add(new Back(l, qty, SalesDb.strings(g.get("serialNumbers")).stream().filter(v -> !blank(v))
                    .map(String::trim).toList(), str(g.get("condition")), blank(str(g.get("disposition"))) ? "restock"
                            : str(g.get("disposition")), str(g.get("locationId")), str(g.get("notes"))));
        }
        if (!findings.isEmpty()) {
            throw ApiException.notFound("Order line not found");
        }
        return new Plan(id, reason.trim(), returnReason, settlement, location, lines);
    }

    /** 400 unless the sale is a done cash sale, not reversed, with no return or credit note of its own yet. */
    private void reversible(Map<String, Object> o) {
        if (!"cash".equals(o.get("saleType"))) {
            throw ApiException.badRequest("Only a cash sale is reversed; return an order's goods through a return");
        }
        if (o.get("reversedAt") != null) {
            throw ApiException.badRequest("This sale is already reversed");
        }
        if (!"done".equals(o.get("status"))) {
            throw ApiException.badRequest("Only a completed cash sale can be reversed");
        }
        String id = str(o.get("id"));
        if (db.count("SELECT count(*) FROM \"SalesReturn\" WHERE \"orderId\" = ? AND \"organizationId\" = ? AND status"
                + " NOT IN ('rejected', 'cancelled')", List.of(id, org())) > 0
                || db.count("SELECT count(*) FROM \"SalesInvoiceLink\" k JOIN \"Document\" d ON d.id = k.\"documentId\""
                        + " WHERE k.\"orderId\" = ? AND k.\"organizationId\" = ? AND k.kind = 'credit_note' AND"
                        + " d.status::text IN ('posted', 'paid')", List.of(id, org())) > 0
                || db.count("SELECT count(*) FROM \"Document\" d WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text"
                        + " = 'credit_note' AND d.status::text IN ('posted', 'paid') AND d.\"reversedDocumentId\" IN (SELECT"
                        + " k.\"documentId\" FROM \"SalesInvoiceLink\" k WHERE k.\"orderId\" = ? AND k.kind = 'regular')",
                        List.of(org(), id)) > 0) {
            throw ApiException.badRequest("This sale already has returns or credit notes: take the rest back through a"
                    + " return");
        }
    }

    /** The sale's posted invoice. */
    private String invoiceOf(String orderId) {
        Map<String, Object> inv = db.row("SELECT k.\"documentId\" FROM \"SalesInvoiceLink\" k JOIN \"Document\" d ON d.id ="
                + " k.\"documentId\" WHERE k.\"orderId\" = ? AND k.\"organizationId\" = ? AND k.kind = 'regular' AND"
                + " d.status::text IN ('posted', 'paid') ORDER BY d.\"createdAt\" DESC LIMIT 1", orderId, org());
        if (inv == null) {
            throw ApiException.badRequest("This sale has no posted invoice to credit");
        }
        return str(inv.get("documentId"));
    }

    /** Record who asked for the reversal and why (once), and the credit note prepared for it. */
    private void requested(String id, String reason, String creditNoteId) {
        db.update("UPDATE \"SalesOrder\" SET \"reversalRequestedAt\" = COALESCE(\"reversalRequestedAt\", " + NOW + "),"
                + " \"reversalRequestedById\" = COALESCE(\"reversalRequestedById\", ?), \"reversalReason\" ="
                + " COALESCE(\"reversalReason\", ?), \"reversalCreditNoteId\" = COALESCE(?, \"reversalCreditNoteId\"),"
                + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", user(), reason, creditNoteId, id,
                org());
    }

    // ------------------------------------------------------------------ A. approval (TA-10)

    /**
     * When posting a credit note needs approval, prepare the reversal's credit note as a draft (once) and ask for its
     * approval (the check {@code CreditNoteService.post} makes), in a transaction of its own: 409 until approved. Runs
     * before the fiscal cancel, so a refused reversal leaves the fiscal document alone.
     */
    private void approvalGate(Plan p) {
        Map<String, Object> o = db.row("SELECT \"reversalCreditNoteId\" FROM \"SalesOrder\" WHERE id = ? AND"
                + " \"organizationId\" = ?", p.id(), org());
        if (o.get("reversalCreditNoteId") == null && !approvals.wouldRequireApproval("credit_note", Map.of())) {
            return;
        }
        String[] pending = new String[2];
        retrySafe.executeWithoutResult(s -> {
            Map<String, Object> sale = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ? FOR"
                    + " UPDATE", p.id(), org());
            reversible(sale);
            String cn = draftCredit(sale, p);
            Map<String, Object> r = approvals.checkOrRequestApproval("credit_note", cn, Map.of());
            if (r != null && Boolean.TRUE.equals(r.get("needsApproval"))) {
                pending[0] = str(r.get("requestId"));
                pending[1] = str(db.row("SELECT \"documentNumber\" FROM \"Document\" WHERE id = ? AND \"organizationId\" ="
                        + " ?", cn, org()).get("documentNumber"));
                if (db.count("SELECT count(*) FROM \"SalesOrderEvent\" WHERE \"orderId\" = ? AND \"organizationId\" = ? AND"
                        + " action = 'reverse_approval_requested' AND data->>'requestId' = ?", List.of(p.id(), org(),
                                pending[0])) == 0) {
                    cashSales.event(p.id(), "reverse_approval_requested", null, null, p.reason(), Obj.of("creditNoteId",
                            cn, "requestId", pending[0]));
                }
            }
        });
        if (pending[0] != null) {
            throw ApiException.conflict("Credit note " + (pending[1] == null ? "" : pending[1] + " ") + "for this reversal"
                    + " needs approval (request " + pending[0] + "); reverse the sale again once it is approved");
        }
    }

    /**
     * The reversal's draft credit note: the one prepared earlier while it is still a draft, else a new one for every
     * invoiced line of the sale, at its invoiced price, against the sale's invoice.
     */
    private String draftCredit(Map<String, Object> sale, Plan p) {
        String id = str(sale.get("id"));
        String prepared = str(sale.get("reversalCreditNoteId"));
        if (prepared != null && db.count("SELECT count(*) FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " status::text = 'draft' AND \"documentType\"::text = 'credit_note'", List.of(prepared, org())) > 0) {
            return prepared;
        }
        String number = str(sale.get("number"));
        List<LineInput> lines = new ArrayList<>();
        for (Back b : p.lines()) {
            BigDecimal credit = dec(b.line().get("qtyInvoiced")).subtract(dec(b.line().get("qtyCredited")));
            if (credit.signum() > 0) {
                lines.add(returns.creditLine(b.line(), credit, "Reversal of " + number));
            }
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Nothing on this sale is invoiced to credit");
        }
        String invoiceId = invoiceOf(id);
        String cn = str(creditNotes.create(new CreditNoteInput(str(sale.get("partnerId")), LocalDate.now(BusinessZone.get())
                .toString(), null, number, "Reversal of cash sale " + number + ": " + p.reason(), invoiceId, lines),
                new SourceRef.CreditFor(invoiceId, "sales")).get("id"));
        requested(id, p.reason(), cn);
        return cn;
    }

    // ------------------------------------------------------------------ C. the reversal (T2)

    private Map<String, Object> complete(Plan p) {
        String id = p.id();
        Map<String, Object> o = orders.lock(id); // the sale before anything it owns (lock order)
        reversible(o);
        if (!Set.of("none", "failed", "cancelled").contains(str(o.get("fiscalStatus")))) {
            throw ApiException.conflict("The sale's fiscal document must be cancelled before the sale is reversed");
        }
        String number = str(o.get("number"));
        String cn = draftCredit(o, p);
        // the return: every line back, received on the delivery it left on and inspected as the cashier found it
        List<Map<String, Object>> claim = new ArrayList<>();
        for (Back b : p.lines()) {
            claim.add(Obj.of("orderLineId", b.line().get("id"), "quantity", b.qty()));
        }
        Map<String, Object> ret = returns.create(Obj.of("orderId", id, "reason", p.returnReason(), "receiveLocationId",
                p.locationId(), "notes", "Reversal of " + number + ": " + p.reason(), "lines", claim));
        String returnId = str(ret.get("id"));
        returns.approve(returnId);
        Map<String, String> returnLine = new LinkedHashMap<>();
        for (Map<String, Object> rl : db.rows("SELECT id, \"orderLineId\" FROM \"SalesReturnLine\" WHERE \"returnId\" = ?"
                + " AND \"organizationId\" = ?", returnId, org())) {
            returnLine.put(str(rl.get("orderLineId")), str(rl.get("id")));
        }
        List<Map<String, Object>> received = new ArrayList<>();
        List<Map<String, Object>> inspected = new ArrayList<>();
        for (Back b : p.lines()) {
            String lineId = returnLine.get(str(b.line().get("id")));
            received.add(Obj.of("lineId", lineId, "receivedQty", b.qty(), "serialNumbers", b.serials()));
            inspected.add(Obj.of("lineId", lineId, "disposition", b.disposition(), "condition", b.condition(),
                    "locationId", b.locationId(), "notes", b.notes()));
        }
        returns.receive(returnId, Obj.of("locationId", p.locationId(), "lines", received));
        returns.inspect(returnId, Obj.of("lines", inspected));
        returns.close(returnId, Obj.of("outcome", "credit_note"), cn);
        // the settlement
        List<String> refunds = "refund".equals(p.settlement()) ? refund(o, cn) : List.of();
        if ("credit".equals(p.settlement())) {
            orders.keepCredit(id, "Reversal of " + number + ": " + p.reason());
        }
        // I6: the sale is cancelled by its reversal; what was invoiced, credited, received and refunded stays on it
        int version = ((Number) db.row("SELECT version FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", id,
                org()).get("version")).intValue();
        Instant now = Instant.now();
        db.patch("SalesOrder", id, Obj.of("status", "cancelled", "cancelledAt", now, "cancelledById", user(),
                "cancelReason", p.reason(), "reversedAt", now, "reversedById", user(), "reversalReturnId", returnId,
                "reversalCreditNoteId", cn, "version", version + 1), true);
        requested(id, p.reason(), cn);
        outcome.refresh(id);
        String cnNumber = str(db.row("SELECT \"documentNumber\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?",
                cn, org()).get("documentNumber"));
        cashSales.event(id, "reverse", "done", "cancelled", p.reason(), Obj.of("returnId", returnId, "returnNumber",
                ret.get("number"), "creditNoteId", cn, "creditNoteNumber", cnNumber, "settlement", p.settlement(),
                "refunds", refunds));
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("cashSale", "reversed", "returnId", returnId,
                "creditNoteId", cn, "settlement", p.settlement(), "refunds", refunds)));
        events.publishOnCommit(DomainEvents.SALES_ORDER_CANCELLED, Obj.of("v", 1, "organizationId", org(), "orderId", id,
                "number", number, "reversed", true, "returnId", returnId, "creditNoteId", cn));
        BusinessOutcome.record(db.jdbc(), Obj.of("id", id, "number", number, "returnId", returnId, "creditNoteId", cn,
                "creditNoteNumber", cnNumber, "refunds", refunds), true);
        return orders.get(id);
    }

    /**
     * Pay the credit back over each tender the sale was paid with, each its own amount, method and account (AD-9). The
     * credit must equal what the sale was paid: anything else is not a full reversal.
     */
    private List<String> refund(Map<String, Object> o, String cn) {
        BigDecimal refundable = creditNotes.takeOpenCredit(cn).setScale(2, RoundingMode.HALF_UP);
        List<Map<String, Object>> tenders = db.rows("SELECT * FROM \"SalesOrderPayment\" WHERE \"orderId\" = ? AND"
                + " \"organizationId\" = ? AND status = 'posted' ORDER BY \"createdAt\", id", o.get("id"), org());
        BigDecimal paid = BigDecimal.ZERO;
        for (Map<String, Object> t : tenders) {
            paid = paid.add(dec(t.get("amount")));
        }
        paid = paid.setScale(2, RoundingMode.HALF_UP);
        if (refundable.compareTo(paid) != 0) {
            throw ApiException.conflict("The reversal's credit (" + JsNumbers.js(refundable) + ") does not match what the"
                    + " sale was paid (" + JsNumbers.js(paid) + "); take the goods back through a return");
        }
        List<String> out = new ArrayList<>();
        for (Map<String, Object> t : tenders) {
            Map<String, Object> payment = creditFlow.payOut(cn, str(o.get("partnerId")), dec(t.get("amount")).setScale(2,
                    RoundingMode.HALF_UP), str(t.get("method")), str(t.get("accountId")), null, "Refund of "
                            + o.get("number"));
            out.add(str(payment.get("paymentNumber")));
        }
        return out;
    }
}
