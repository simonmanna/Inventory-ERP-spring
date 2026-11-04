package com.inventoryerp.invoicing.application;

import static com.inventoryerp.invoicing.application.DocumentBuilderService.NOW;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.JournalService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.invoicing.application.BillReceiptMatcherService.BillLineMatch;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Grouped;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.workflow.WorkflowDefinition;
import com.inventoryerp.kernel.workflow.WorkflowDefinition.Context;
import com.inventoryerp.kernel.workflow.WorkflowDefinition.Transition;
import com.inventoryerp.kernel.workflow.WorkflowRegistry;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Registers the invoicing workflows (port of {@code invoicing-workflows.initializer.ts}, ADR-007): invoice,
 * credit note, vendor bill (receipt matching, PPV, stock-in of the unmatched remainder) and payment void (drawer
 * corrections, residual restore, business outcome).
 */
@Component
public class InvoicingWorkflowsInitializer {

    private final WorkflowRegistry registry;
    private final JdbcClient jdbc;
    private final PostingService posting;
    private final StockService stock;
    private final DocumentBuilderService builder;
    private final AccountDeterminationService determination;
    private final JournalService journals;
    private final BillReceiptMatcherService matcher;

    public InvoicingWorkflowsInitializer(WorkflowRegistry registry, JdbcClient jdbc, PostingService posting,
            StockService stock, DocumentBuilderService builder, AccountDeterminationService determination,
            JournalService journals, BillReceiptMatcherService matcher) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.posting = posting;
        this.stock = stock;
        this.builder = builder;
        this.determination = determination;
        this.journals = journals;
        this.matcher = matcher;
    }

    @PostConstruct
    void register() {
        registry.register(invoiceWorkflow());
        registry.register(creditNoteWorkflow());
        registry.register(vendorBillWorkflow());
        registry.register(paymentWorkflow());
    }

    private static BigDecimal d(Object v) {
        return Money.dec(v);
    }

    private static Instant instant(Object v) {
        return v instanceof Instant i ? i : v instanceof java.sql.Timestamp t ? (Instant) SqlRows.value(t)
                : com.inventoryerp.kernel.crud.EntityPatch.toInstant(String.valueOf(v));
    }

    private String warehouse(String org) {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse'"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" ASC LIMIT 1")
                .param(org).query(String.class).optional().orElse(null);
    }

    private Map<String, Object> product(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"Product\" WHERE id = ? AND \"deletedAt\" IS NULL LIMIT 1").param(id));
    }

    // ------------------------------------------------------------------ invoice

    private WorkflowDefinition invoiceWorkflow() {
        return new WorkflowDefinition("invoice", "draft", List.of(
                new Transition("draft", "posted", "post", "invoice:post", ctx -> {
                    Object status = ctx.entity().get("status");
                    return !"posted".equals(String.valueOf(status)) && !"paid".equals(String.valueOf(status))
                            && !"cancelled".equals(String.valueOf(status));
                }, this::postInvoice, null),
                new Transition("posted", "cancelled", "cancel", "invoice:cancel",
                        ctx -> !(d(ctx.entity().get("amountPaid")).signum() > 0), this::cancelDocument, null)));
    }

    private void postInvoice(Context ctx) {
        Map<String, Object> doc = ctx.entity();
        String id = (String) doc.get("id");
        List<Map<String, Object>> lines = builder.lines(id, false);
        // A delivery (fleet 1.3) issues the goods of this invoice load by load: no automatic issue here.
        boolean stockByDelivery = jdbc.sql("SELECT count(*) FROM \"Document\" WHERE id = ? AND"
                + " data->>'stockIssuedBy' IS NOT NULL").param(id).query(Long.class).single() > 0;
        for (Map<String, Object> line : stockByDelivery ? List.<Map<String, Object>>of() : lines) {
            String productId = (String) line.get("productId");
            Map<String, Object> product = productId == null ? null : product(productId);
            if (product != null && Boolean.TRUE.equals(product.get("trackInventory"))) {
                String location = warehouse(ctx.organizationId());
                if (location == null) {
                    throw new IllegalStateException("No warehouse location for stockable line " + product.get("name"));
                }
                StockMove m = StockMove.of(productId, location, d(line.get("quantity")))
                        .uom((String) line.get("uomId"), line.get("uomFactor") == null ? null : d(line.get("uomFactor")))
                        .date(instant(doc.get("issueDate"))).source("sales_invoice", id)
                        .notes("Auto-issue for invoice " + doc.get("documentNumber"));
                stock.issue(m);
            }
        }
        Map<String, Object> full = builder.document(id);
        Grouped g = builder.groupForPosting((String) full.get("partnerId"), lines, "sales", null);
        List<Line> post = new ArrayList<>();
        String partnerId = (String) full.get("partnerId");
        post.add(new Line(g.counterAccount(), JsNumbers.dec(d(full.get("totalAmount"))), null, partnerId,
                "Invoice " + full.get("documentNumber"), null, null, null));
        String accrual = jdbc.sql("SELECT data->>'revenueAccruedBy' FROM \"Document\" WHERE id = ?").param(id)
                .query(String.class).optional().orElse(null);
        if (accrual != null) {
            // Branch cash sale (ADR-S056 P4): revenue and VAT were accrued at showroom confirm — the invoice only
            // reclassifies the accrual to AR, for exactly the accrued amount.
            String unbilled = determination.mapped("unbilled_receivable");
            BigDecimal accrued = jdbc.sql("SELECT COALESCE(sum(debit), 0) FROM \"JournalLine\" WHERE \"journalEntryId\" = ?"
                    + " AND \"accountId\" = ?").params(accrual, unbilled).query(BigDecimal.class).single();
            if (accrued.compareTo(d(full.get("totalAmount"))) != 0) {
                throw ApiException.badRequest("Invoice total does not match the accrued sale");
            }
            post.add(new Line(unbilled, null, JsNumbers.dec(accrued), partnerId, "Accrued cash sale "
                    + full.get("documentNumber"), null, null, null));
        } else {
            g.itemLines().forEach(it -> post.add(new Line(it.accountId(), null, JsNumbers.dec(it.amount()), partnerId,
                    "Revenue", null, it.costCenterId(), it.glDimensions())));
            g.taxByAccount().forEach((acc, amt) -> post.add(Line.credit(acc, JsNumbers.dec(amt), "Output tax")));
        }
        var journal = journals.resolveSalesJournal((String) full.get("invoicingJournalId"));
        Map<String, Object> entry = posting.post(new PostingRequest(journal.getCode(), instant(full.get("issueDate")),
                "Invoice " + full.get("documentNumber"), (String) full.get("currencyId"),
                d(full.get("exchangeRate")), "invoice", id, null, null, accrual == null ? null : (String) full.get("branchId"),
                null, null, post));
        jdbc.sql("UPDATE \"Document\" SET \"journalEntryId\" = ?, \"amountResidual\" = ?, \"paymentStatus\" = 'not_paid',"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(entry.get("id"), d(full.get("totalAmount")), id).update();
    }

    /** Invoice cancel: reverse the JE, return the auto-issued stock (with its COGS), zero the residual. */
    private void cancelDocument(Context ctx) {
        Map<String, Object> doc = ctx.entity();
        if (doc.get("journalEntryId") != null) {
            posting.reverse((String) doc.get("journalEntryId"), null, "Cancellation of " + doc.get("documentNumber"));
        }
        returnIssuedStock((String) doc.get("id"), (String) doc.get("documentNumber"),
                instant(doc.get("issueDate")));
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = 0, \"paymentStatus\" = 'not_paid', \"updatedAt\" = " + NOW
                + " WHERE id = ?").param(doc.get("id")).update();
    }

    /**
     * ACC-3 (B7): compensate the invoice's own stock issue. Each issue movement ({@code sales_invoice}) comes back as a
     * linked {@code return_in} at the cost it left at, so stock quantity, stock value and COGS return to their
     * pre-invoice state (a FIFO lot returns as a new layer at that cost). Goods issued by a delivery are not the
     * invoice's to return (credit note + return). Runs once: an existing compensation for the invoice is never repeated.
     */
    private void returnIssuedStock(String invoiceId, String number, Instant issueDate) {
        long done = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'sales_invoice_cancel'"
                + " AND \"referenceId\" = ?").param(invoiceId).query(Long.class).single();
        if (done > 0) {
            return;
        }
        for (Map<String, Object> issue : SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantId\", \"locationId\","
                + " \"quantityChange\", \"unitCost\" FROM \"InventoryLedger\" WHERE \"referenceType\" ="
                + " 'sales_invoice' AND \"referenceId\" = ? AND \"quantityChange\" < 0 ORDER BY \"createdAt\", id")
                .param(invoiceId))) {
            stock.receiveReturn(StockMove.of((String) issue.get("productId"), (String) issue.get("locationId"),
                    d(issue.get("quantityChange")).negate()).variant((String) issue.get("variantId"))
                    .unitCost(d(issue.get("unitCost"))).source("sales_invoice_cancel", invoiceId)
                    .reference(number).date(posting.reversalDate(issueDate))
                    .notes("Return of auto-issued stock on cancellation of invoice " + number));
        }
    }

    // ------------------------------------------------------------------ credit note

    private WorkflowDefinition creditNoteWorkflow() {
        return new WorkflowDefinition("credit_note", "draft", List.of(
                new Transition("draft", "posted", "post", "credit_note:post", null, ctx -> {
                    String id = (String) ctx.entity().get("id");
                    Map<String, Object> full = builder.document(id);
                    List<Map<String, Object>> lines = builder.lines(id, false);
                    String partnerId = (String) full.get("partnerId");
                    Grouped g = builder.groupForPosting(partnerId, lines, "sales", null);
                    List<Line> post = new ArrayList<>();
                    post.add(new Line(g.counterAccount(), null, JsNumbers.dec(d(full.get("totalAmount"))), partnerId,
                            "Credit note " + full.get("documentNumber"), null, null, null));
                    g.itemLines().forEach(it -> post.add(new Line(it.accountId(), JsNumbers.dec(it.amount()), null,
                            partnerId, "Revenue reversal", null, it.costCenterId(), it.glDimensions())));
                    g.taxByAccount().forEach((acc, amt) -> post.add(Line.debit(acc, JsNumbers.dec(amt),
                            "Output tax reversal")));
                    posting.post(new PostingRequest("SALES", instant(full.get("issueDate")),
                            "Credit note " + full.get("documentNumber"), (String) full.get("currencyId"),
                            d(full.get("exchangeRate")), "credit_note", id, null, null, null, null, null,
                            post));
                }, null)));
    }

    // ------------------------------------------------------------------ vendor bill

    private String priceVarianceAccount() {
        try {
            return determination.mapped("purchase_price_variance");
        } catch (RuntimeException e) {
            return determination.mapped("stock_adjustment_expense");
        }
    }

    private WorkflowDefinition vendorBillWorkflow() {
        return new WorkflowDefinition("vendor_bill", "draft", List.of(
                new Transition("draft", "posted", "post", "expense:post", null, this::postBill, null),
                new Transition("posted", "cancelled", "cancel", "expense:cancel",
                        ctx -> !(d(ctx.entity().get("amountPaid")).signum() > 0), ctx -> {
                            Map<String, Object> doc = ctx.entity();
                            if (doc.get("journalEntryId") != null) {
                                posting.reverse((String) doc.get("journalEntryId"), null,
                                        "Void of " + doc.get("documentNumber"));
                            }
                            matcher.releaseForBill(ctx.organizationId(), (String) doc.get("id"));
                            jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = 0, \"paymentStatus\" = 'not_paid',"
                                    + " \"updatedAt\" = " + NOW + " WHERE id = ?").param(doc.get("id")).update();
                        }, null)));
    }

    /** Bill-vs-receipt differences below half a cent are rounding, not variance. */
    private static final BigDecimal VARIANCE_EPSILON = new BigDecimal("0.005");

    private void postBill(Context ctx) {
        String id = (String) ctx.entity().get("id");
        Map<String, Object> full = builder.document(id);
        String partnerId = (String) full.get("partnerId");
        List<Map<String, Object>> lines = builder.lines(id, false);
        Map<String, Map<String, Object>> productById = new HashMap<>();
        for (Map<String, Object> l : lines) {
            if (l.get("productId") != null) {
                Map<String, Object> p = product((String) l.get("productId"));
                if (p != null) {
                    productById.put((String) l.get("productId"), p);
                }
            }
        }
        Map<String, BillLineMatch> matchByLine = new HashMap<>();
        List<Map<String, Object>> postable = new ArrayList<>();
        BigDecimal postableTotal = Money.ZERO;
        BigDecimal priceVariance = Money.ZERO;
        BigDecimal voucheredVariance = Money.ZERO;
        BigDecimal voucheredBillTotal = Money.ZERO;
        for (Map<String, Object> line : lines) {
            Map<String, Object> product = productById.get((String) line.get("productId"));
            boolean stockable = product != null && Boolean.TRUE.equals(product.get("trackInventory"));
            BigDecimal qty = d(line.get("quantity"));
            if (!stockable || qty.signum() <= 0) {
                postable.add(line);
                postableTotal = postableTotal.add(d(line.get("total")));
                continue;
            }
            BillLineMatch match = matcher.matchLine(ctx.organizationId(), partnerId, (String) line.get("productId"), qty,
                    line.get("uomFactor") == null ? null : d(line.get("uomFactor")));
            matchByLine.put((String) line.get("id"), match);
            if (match.accruedQuantity().signum() > 0) {
                BigDecimal billUnit = Money.div(d(line.get("subtotal")), qty);
                priceVariance = priceVariance.add(billUnit.multiply(match.accruedQuantity()))
                        .subtract(match.accruedReceiptValue());
            }
            if (match.voucheredQuantity().signum() > 0) {
                voucheredBillTotal = voucheredBillTotal.add(d(line.get("total"))
                        .multiply(Money.div(match.voucheredQuantity().min(qty), qty)));
                // P1-7 (post-parity): the receipt voucher raised AP at the receipt price; a bill priced higher owes the
                // difference too (Dr PPV / Cr AP). A lower bill price is a match exception settled by a supplier credit.
                BigDecimal billUnit = Money.div(d(line.get("subtotal")), qty);
                BigDecimal receiptValue = Money.ZERO;
                for (BillReceiptMatcherService.Consumption c : match.consumptions()) {
                    if (c.vouchered()) {
                        receiptValue = receiptValue.add(c.quantity().multiply(c.receiptUnitCost()));
                    }
                }
                BigDecimal diff = billUnit.multiply(match.voucheredQuantity()).subtract(receiptValue);
                if (diff.compareTo(VARIANCE_EPSILON) > 0) {
                    voucheredVariance = voucheredVariance.add(diff);
                }
            }
            BigDecimal postableQty = qty.subtract(match.voucheredQuantity());
            if (postableQty.signum() <= 0) {
                continue;
            }
            if (postableQty.compareTo(qty) == 0) {
                postable.add(line);
                postableTotal = postableTotal.add(d(line.get("total")));
                continue;
            }
            BigDecimal factor = Money.div(postableQty, qty);
            Map<String, Object> part = new LinkedHashMap<>(line);
            part.put("quantity", postableQty);
            part.put("subtotal", d(line.get("subtotal")).multiply(factor));
            part.put("taxAmount", d(line.get("taxAmount")).multiply(factor));
            part.put("total", d(line.get("total")).multiply(factor));
            postable.add(part);
            postableTotal = postableTotal.add(d(line.get("total")).multiply(factor));
        }
        String entryId = null;
        postableTotal = postableTotal.add(voucheredVariance);
        if (postableTotal.signum() > 0) {
            String grni = determination.mapped("grni_accrued");
            Grouped g = builder.groupForPosting(partnerId, postable, "purchase", grni);
            List<MutableLine> post = new ArrayList<>();
            g.itemLines().forEach(it -> post.add(new MutableLine(it.accountId(), it.amount(), null, partnerId,
                    it.accountId().equals(grni) ? "GRNI clearing" : "Expense", it.costCenterId(), it.glDimensions())));
            g.taxByAccount().forEach((acc, amt) -> post.add(new MutableLine(acc, amt, null, null, "Input tax")));
            if (priceVariance.signum() != 0) {
                String ppv = priceVarianceAccount();
                // The largest GRNI line absorbs the variance (lines split by analytic dimensions, ADR-S025).
                MutableLine target = null;
                for (MutableLine l : post) {
                    if (l.accountId.equals(grni) && l.debit != null
                            && (target == null || l.debit.compareTo(target.debit) > 0)) {
                        target = l;
                    }
                }
                if (target != null) {
                    target.debit = target.debit.subtract(priceVariance);
                }
                post.add(priceVariance.signum() > 0
                        ? new MutableLine(ppv, priceVariance, null, null, "Purchase price variance")
                        : new MutableLine(ppv, null, priceVariance.negate(), null, "Purchase price variance"));
            }
            if (voucheredVariance.signum() > 0) {
                post.add(new MutableLine(priceVarianceAccount(), voucheredVariance, null, null,
                        "Purchase price variance (billed above receipt)"));
            }
            post.add(new MutableLine(g.counterAccount(), null, postableTotal, partnerId,
                    "Bill " + full.get("documentNumber")));
            List<Line> req = post.stream().map(MutableLine::toLine).toList();
            Map<String, Object> entry = posting.post(new PostingRequest("PURCH", instant(full.get("issueDate")),
                    "Bill " + full.get("documentNumber"), (String) full.get("currencyId"),
                    d(full.get("exchangeRate")), "vendor_bill", id, null, "vendor_bill:" + id, null, null,
                    null, req));
            entryId = (String) entry.get("id");
        }
        for (Map<String, Object> line : lines) {
            Map<String, Object> product = productById.get((String) line.get("productId"));
            if (product == null || !Boolean.TRUE.equals(product.get("trackInventory"))) {
                continue;
            }
            BillLineMatch match = matchByLine.get((String) line.get("id"));
            BigDecimal qty = d(line.get("quantity"));
            if (match != null) {
                matcher.consume(ctx.organizationId(), id, (String) line.get("id"), match);
            }
            BigDecimal toReceive = match != null ? match.unmatchedQuantity() : qty;
            if (toReceive.signum() <= 0) {
                continue;
            }
            String location = match != null && match.locationId() != null ? match.locationId()
                    : warehouse(ctx.organizationId());
            if (location == null) {
                throw new IllegalStateException("No warehouse location for stockable line " + product.get("name"));
            }
            BigDecimal unitCost = qty.signum() > 0 ? new BigDecimal(JsNumbers.toJsString(
                    Money.div(d(line.get("subtotal")), qty).doubleValue())) : Money.ZERO;
            StockMove m = StockMove.of((String) line.get("productId"), location, toReceive).unitCost(unitCost)
                    .uom(line.get("uomFactor") == null ? (String) product.get("purchaseUomId") : (String) line.get("uomId"),
                            line.get("uomFactor") == null ? null : d(line.get("uomFactor")))
                    .notes("Auto-receive for bill " + full.get("documentNumber"));
            stock.receiveFromBill(m, id, instant(full.get("issueDate")));
        }
        // ACC-4 (B15): the bill is the open AP item for its whole amount. Vouchered quantities were raised on AP by the
        // receipt voucher, so the bill posts no second AP for them, but the supplier is paid against this bill — its
        // residual carries the vouchered share at bill price (the billed-above-receipt variance is part of that share).
        BigDecimal openPayable = postableTotal.subtract(voucheredVariance).add(voucheredBillTotal);
        jdbc.sql("UPDATE \"Document\" SET \"journalEntryId\" = ?, \"amountResidual\" = ?, \"paymentStatus\" = 'not_paid',"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(entryId, openPayable, id).update();
    }

    private static final class MutableLine {
        final String accountId;
        BigDecimal debit;
        final BigDecimal credit;
        final String partnerId;
        final String description;

        final String costCenterId;
        final Map<String, Object> dimensions;

        MutableLine(String accountId, BigDecimal debit, BigDecimal credit, String partnerId, String description) {
            this(accountId, debit, credit, partnerId, description, null, null);
        }

        MutableLine(String accountId, BigDecimal debit, BigDecimal credit, String partnerId, String description,
                String costCenterId, Map<String, Object> dimensions) {
            this.accountId = accountId;
            this.debit = debit;
            this.credit = credit;
            this.partnerId = partnerId;
            this.description = description;
            this.costCenterId = costCenterId;
            this.dimensions = dimensions;
        }

        Line toLine() {
            return new Line(accountId, debit == null ? null : JsNumbers.dec(debit),
                    credit == null ? null : JsNumbers.dec(credit), partnerId, description, null, costCenterId,
                    dimensions);
        }
    }

    // ------------------------------------------------------------------ payment

    private WorkflowDefinition paymentWorkflow() {
        return new WorkflowDefinition("payment", "posted", List.of(
                new Transition("posted", "cancelled", "void", "payment:void", null, this::voidPayment, null)));
    }

    private void voidPayment(Context ctx) {
        Map<String, Object> payload = ctx.payload() == null ? Map.of() : ctx.payload();
        String reason = payload.get("reason") == null ? "" : String.valueOf(payload.get("reason")).trim();
        if (reason.isEmpty()) {
            throw ApiException.badRequest("A reason is required to void a payment");
        }
        String org = ctx.organizationId();
        jdbc.sql("SELECT id FROM \"Payment\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(ctx.entityId(), org).query().listOfRows();
        Map<String, Object> payment = SqlRows.one(com.inventoryerp.invoicing.domain.Payment.class,
                jdbc.sql("SELECT * FROM \"Payment\" WHERE id = ? AND \"organizationId\" = ?").params(ctx.entityId(), org));
        if (payment == null || !"posted".equals(String.valueOf(payment.get("status")))) {
            throw ApiException.badRequest("Only a posted payment can be voided");
        }
        List<Map<String, Object>> allocations = SqlRows.list(com.inventoryerp.invoicing.domain.PaymentAllocation.class,
                jdbc.sql("SELECT * FROM \"PaymentAllocation\" WHERE \"paymentId\" = ?").param(payment.get("id")));
        List<Map<String, Object>> movements = SqlRows.list(jdbc.sql("SELECT * FROM \"CashMovement\" WHERE \"paymentId\" = ?"
                + " AND \"organizationId\" = ?").params(payment.get("id"), org));
        String correctionSessionId = payload.get("correctionSessionId") == null ? null
                : String.valueOf(payload.get("correctionSessionId"));
        record Target(Map<String, Object> movement, String sessionId, String correctionOf) {}
        List<Target> targets = new ArrayList<>();
        for (Map<String, Object> movement : movements) {
            String sessionId = (String) movement.get("cashSessionId");
            jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(sessionId, org).query().listOfRows();
            Map<String, Object> original = SqlRows.one(jdbc.sql("SELECT * FROM \"CashSession\" WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(sessionId, org));
            if (original != null && "open".equals(String.valueOf(original.get("status")))) {
                targets.add(new Target(movement, (String) original.get("id"), null));
                continue;
            }
            if (correctionSessionId == null) {
                throw ApiException.badRequest("This cash payment belongs to a closed shift. Choose your current open shift"
                        + " on the same register to post the correction.");
            }
            if (!ctx.permissions().contains("cash_session:correct")) {
                throw ApiException.forbidden("Correcting a closed shift requires cash_session:correct");
            }
            jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(correctionSessionId, org).query().listOfRows();
            Map<String, Object> current = SqlRows.one(jdbc.sql("SELECT * FROM \"CashSession\" WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(correctionSessionId, org));
            if (current == null || !"open".equals(String.valueOf(current.get("status")))) {
                throw ApiException.badRequest("The correction shift must be open");
            }
            if (!java.util.Objects.equals(current.get("userId"), ctx.userId())) {
                throw ApiException.forbidden("Post corrections only into your own open shift");
            }
            Object originalDrawer = original != null && original.get("drawerAccountId") != null
                    ? original.get("drawerAccountId") : payment.get("accountId");
            if (!java.util.Objects.equals(current.get("drawerAccountId"), originalDrawer)) {
                throw ApiException.badRequest("The correction must be posted on the same register drawer that took the"
                        + " payment");
            }
            targets.add(new Target(movement, (String) current.get("id"), sessionId));
        }
        for (Target t : targets) {
            if (!"sale".equals(String.valueOf(t.movement().get("movementType")))) {
                continue;
            }
            Map<String, Object> session = SqlRows.one(jdbc.sql("SELECT \"openingFloat\" FROM \"CashSession\" WHERE id = ?")
                    .param(t.sessionId()));
            BigDecimal expected = d(session.get("openingFloat"));
            for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT \"movementType\"::text AS t, amount FROM"
                    + " \"CashMovement\" WHERE \"cashSessionId\" = ?").param(t.sessionId()))) {
                expected = List.of("sale", "pay_in", "adjustment").contains(m.get("t")) ? expected.add(d(m.get("amount")))
                        : expected.subtract(d(m.get("amount")));
            }
            if (expected.compareTo(d(t.movement().get("amount"))) < 0) {
                throw ApiException.badRequest("The drawer does not contain enough cash to return this payment");
            }
        }
        Map<String, Object> reversal = null;
        if (payment.get("journalEntryId") != null) {
            reversal = posting.reverse((String) payment.get("journalEntryId"), null,
                    "Void of " + payment.get("paymentNumber") + ": " + reason);
        }
        for (Target t : targets) {
            BigDecimal amount = d(t.movement().get("amount"));
            jdbc.sql("INSERT INTO \"CashMovement\" (id, \"organizationId\", \"cashSessionId\", \"movementType\", amount,"
                            + " reason, \"counterpartAccountId\", \"journalEntryId\", \"reversalOfMovementId\","
                            + " \"correctionOfSessionId\", \"performedBy\", \"createdAt\") VALUES (?, ?, ?, 'adjustment', ?, ?,"
                            + " ?, ?, ?, ?, ?, " + NOW + ")")
                    .params(UUID.randomUUID().toString(), org, t.sessionId(),
                            "sale".equals(String.valueOf(t.movement().get("movementType"))) ? amount.negate() : amount,
                            "Void of " + payment.get("paymentNumber") + ": " + reason,
                            t.movement().get("counterpartAccountId"), reversal == null ? null : reversal.get("id"),
                            t.movement().get("id"), t.correctionOf(), ctx.userId())
                    .update();
        }
        jdbc.sql("UPDATE \"Payment\" SET \"voidedAt\" = " + NOW + ", \"voidedById\" = ?, \"voidReason\" = ?,"
                        + " \"voidedAllocations\" = ?::jsonb, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(ctx.userId(), reason, EntityJson.toJson(allocations), payment.get("id")).update();
        for (Map<String, Object> alloc : allocations) {
            if (alloc.get("invoiceId") != null) {
                Map<String, Object> inv = SqlRows.one(jdbc.sql("SELECT * FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(alloc.get("invoiceId"), org));
                if (inv != null) {
                    restore("Invoice", "InvoiceStatus", inv, d(alloc.get("amount")));
                }
                jdbc.sql("DELETE FROM \"PaymentAllocation\" WHERE id = ?").param(alloc.get("id")).update();
                continue;
            }
            Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT * FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(alloc.get("documentId"), org));
            if (doc != null) {
                restore("Document", "DocumentStatus", doc, d(alloc.get("amount")));
            }
            jdbc.sql("DELETE FROM \"PaymentAllocation\" WHERE id = ?").param(alloc.get("id")).update();
        }
        jdbc.sql("UPDATE \"Payment\" SET \"allocatedAmount\" = 0, \"unallocatedAmount\" = 0, \"updatedAt\" = " + NOW
                + " WHERE id = ?").param(payment.get("id")).update();
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("id", payment.get("id"));
        outcome.put("voided", true);
        BusinessOutcome.record(jdbc, outcome, true);
    }

    /** Give an allocation back to an invoice / document: paid ↓, residual ↑, status recomputed. */
    private void restore(String table, String statusType, Map<String, Object> row, BigDecimal amount) {
        BigDecimal newPaid = d(row.get("amountPaid")).subtract(amount);
        BigDecimal newResidual = d(row.get("amountResidual")).add(amount);
        String paymentStatus = newResidual.signum() <= 0 ? "paid"
                : newResidual.compareTo(d(row.get("totalAmount"))) < 0 ? "partial" : "not_paid";
        String status = "paid".equals(String.valueOf(row.get("status"))) && newResidual.signum() > 0 ? "posted"
                : String.valueOf(row.get("status"));
        jdbc.sql("UPDATE \"" + table + "\" SET \"amountPaid\" = ?, \"amountResidual\" = ?, \"paymentStatus\" ="
                        + " ?::\"PaymentStatus\", status = ?::\"" + statusType + "\", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(newPaid, newResidual, paymentStatus, status, row.get("id")).update();
    }
}
