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
import java.util.Comparator;
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
    private WithholdingCertificateService withholdingCertificates;

    @org.springframework.beans.factory.annotation.Autowired
    void setWithholdingCertificates(WithholdingCertificateService withholdingCertificates) {
        this.withholdingCertificates = withholdingCertificates;
    }

    private DocumentFxRates fxRates;

    @org.springframework.beans.factory.annotation.Autowired
    void setFxRates(DocumentFxRates fxRates) {
        this.fxRates = fxRates;
    }

    private com.inventoryerp.kernel.settings.SettingResolverService settings;

    @org.springframework.beans.factory.annotation.Autowired
    void setSettings(com.inventoryerp.kernel.settings.SettingResolverService settings) {
        this.settings = settings;
    }

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

    /**
     * The document's stock location: a warehouse of its branch when it has one (sales readiness INV-10), else the
     * organisation's oldest warehouse (Nest behaviour).
     */
    private String warehouse(String org, Object branchId) {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse'"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY (\"branchId\" IS NOT DISTINCT FROM ?::text"
                        + " AND ?::text IS NOT NULL) DESC, \"createdAt\" ASC LIMIT 1")
                .params(org, branchId, branchId).query(String.class).optional().orElse(null);
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
                // Sales readiness R1: a draft is discarded without any ledger or stock effect.
                new Transition("draft", "cancelled", "cancel", "invoice:cancel", null, ctx -> { }, null),
                new Transition("posted", "cancelled", "cancel", "invoice:cancel",
                        ctx -> !(d(ctx.entity().get("amountPaid")).signum() > 0), this::cancelInvoice, null)));
    }

    /**
     * Review N1: an invoice a posted credit note already corrected is not cancelled as a whole — the cancel would
     * return all the issued stock and reverse all the revenue a second time (the credit note's restock and journal
     * stay). Remaining corrections go through credit notes. Runs under the workflow's row lock; a credit note posting
     * locks the invoice first too, so the two never interleave.
     */
    private void cancelInvoice(Context ctx) {
        String id = (String) ctx.entity().get("id");
        List<String> credits = jdbc.sql("SELECT d.\"documentNumber\" FROM \"Document\" d WHERE d.\"organizationId\" = ?"
                + " AND d.\"documentType\" = 'credit_note' AND d.status::text IN ('posted', 'paid') AND (d.\"reversedDocumentId\" = ?"
                + " OR EXISTS (SELECT 1 FROM \"CreditAllocation\" a WHERE a.\"creditDocumentId\" = d.id AND a.\"documentId\" = ?))"
                + " ORDER BY d.\"documentNumber\"").params(ctx.organizationId(), id, id).query(String.class).list();
        if (!credits.isEmpty()) {
            throw ApiException.badRequest("Invoice " + ctx.entity().get("documentNumber") + " has posted credit notes ("
                    + String.join(", ", credits) + "); it cannot be cancelled. Issue a credit note for the remaining"
                    + " amount instead.");
        }
        cancelDocument(ctx);
    }

    private void postInvoice(Context ctx) {
        Map<String, Object> doc = ctx.entity();
        String id = (String) doc.get("id");
        List<Map<String, Object>> lines = builder.lines(id, false);
        // A delivery (fleet 1.3) issues the goods of this invoice load by load: no automatic issue here. IPR F1: nor
        // when the invoice is awaiting delivery (its delivery order was cancelled or closed short with goods left; a
        // new delivery order or a collection issues the rest).
        boolean stockByDelivery = jdbc.sql("SELECT count(*) FROM \"Document\" WHERE id = ? AND"
                + " (data->>'stockIssuedBy' IS NOT NULL OR data->>'" + InvoiceGoodsService.AWAITING + "' IS NOT NULL)")
                .param(id).query(Long.class).single() > 0;
        if (!stockByDelivery) {
            // Audit S-2: issue in the engine's lock order (the posting groups below keep the document order).
            goods.issueStock(ctx.organizationId(), doc, lines, instant(doc.get("issueDate")),
                    null, "Auto-issue for invoice " + doc.get("documentNumber"));
        }
        Map<String, Object> full = builder.document(id);
        Grouped g = builder.groupForPosting((String) full.get("partnerId"), lines, "sales", null);
        List<Line> post = new ArrayList<>();
        String partnerId = (String) full.get("partnerId");
        post.add(new Line(g.counterAccount(), JsNumbers.dec(d(full.get("totalAmount"))), null, partnerId,
                "Invoice " + full.get("documentNumber"), null, null, null));
        String accrual = jdbc.sql("SELECT data->>'revenueAccruedBy' FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(id, com.inventoryerp.kernel.tenancy.TenantContext.organizationId())
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
            // R6 Q7 (showroom tax point at invoice): VAT held at confirm becomes output VAT now. Read from the accrual
            // itself, so a sale accrued under the other setting is reclassified the way it was booked.
            String deferredAccount = determination.mappedOptional(BranchSaleRecognitionService.DEFERRED_TAX_KEY);
            BigDecimal deferred = deferredAccount == null ? BigDecimal.ZERO : jdbc.sql("SELECT COALESCE(sum(credit - debit),"
                    + " 0) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?").params(accrual,
                            deferredAccount).query(BigDecimal.class).single();
            if (deferred.signum() != 0) {
                BigDecimal invoiceTax = BigDecimal.ZERO;
                for (BigDecimal amt : g.taxByAccount().values()) {
                    invoiceTax = invoiceTax.add(amt);
                }
                if (invoiceTax.compareTo(deferred) != 0) {
                    throw ApiException.badRequest("Invoice tax does not match the tax held for the sale");
                }
                post.add(new Line(deferredAccount, JsNumbers.dec(deferred), null, null, "Output tax now invoiced "
                        + full.get("documentNumber"), null, null, null));
                g.taxByAccount().forEach((acc, amt) -> post.add(Line.credit(acc, JsNumbers.dec(amt), "Output tax")));
            }
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
        String org = com.inventoryerp.kernel.tenancy.TenantContext.organizationId();
        long done = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'sales_invoice_cancel'"
                + " AND \"referenceId\" = ? AND \"organizationId\" = ?").params(invoiceId, org).query(Long.class).single();
        if (done > 0) {
            return;
        }
        long issued = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'sales_invoice' AND"
                + " \"referenceId\" = ? AND \"organizationId\" = ? AND \"quantityChange\" < 0").params(invoiceId, org)
                .query(Long.class).single();
        if (issued == 0) {
            return;
        }
        // R1: back onto the lot, serial, location and cost each unit left on (net of what credit notes returned).
        stockReturns.returnFromSource(new com.inventoryerp.inventory.application.StockReversalService.ReturnFromSource(
                com.inventoryerp.inventory.application.StockReversalService.IssueSource.of("sales_invoice", invoiceId),
                null, "sales_invoice_cancel", invoiceId, number,
                "Return of auto-issued stock on cancellation of invoice " + number, posting.reversalDate(issueDate),
                null, true, null));
    }


    private InvoiceGoodsService goods;

    /** IPR F1: the invoice stock issue loop, shared with the collection of undelivered goods. */
    @org.springframework.beans.factory.annotation.Autowired
    void setGoods(InvoiceGoodsService goods) {
        this.goods = goods;
    }

    private com.inventoryerp.inventory.application.StockReversalService stockReturns;

    /** R1: returns are posted against their original issue (lot, expiry, serial, location and cost). */
    @org.springframework.beans.factory.annotation.Autowired
    void setStockReturns(com.inventoryerp.inventory.application.StockReversalService stockReturns) {
        this.stockReturns = stockReturns;
    }

    // ------------------------------------------------------------------ credit note


    // ------------------------------------------------------------------ vendor bill

    /**
     * Vendor-bill cancel (review 2026-11-15, P-1; purchase-side twin of B7): the stock the bill auto-received (no
     * receipt to match) goes back out as a return to supplier (Dr GRNI / Cr stock at its issue cost); a difference
     * from the receipt cost (another layer or average picked) clears GRNI against price variance, so GRNI and stock
     * both return to their pre-bill state. Runs once per bill.
     */
    private void returnBillReceivedStock(String billId, String number, Instant issueDate) {
        String org = com.inventoryerp.kernel.tenancy.TenantContext.organizationId();
        long done = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'vendor_bill_cancel'"
                + " AND \"referenceId\" = ? AND \"organizationId\" = ?").params(billId, org).query(Long.class).single();
        if (done > 0) {
            return;
        }
        Instant date = posting.reversalDate(issueDate);
        BigDecimal received = Money.ZERO;
        BigDecimal returned = Money.ZERO;
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantId\", \"locationId\","
                + " \"quantityChange\", \"unitCost\" FROM \"InventoryLedger\" WHERE \"referenceType\" = 'vendor_bill'"
                + " AND \"referenceId\" = ? AND \"organizationId\" = ? AND \"quantityChange\" > 0 ORDER BY \"createdAt\", id")
                .params(billId, org))) {
            BigDecimal qty = d(r.get("quantityChange"));
            received = received.add(qty.multiply(d(r.get("unitCost"))));
            StockMove.Issued out = stock.issue(StockMove.of((String) r.get("productId"), (String) r.get("locationId"),
                    qty).variant((String) r.get("variantId")).moveType("return_to_supplier")
                    .source("vendor_bill_cancel", billId).reference(number).date(date)
                    .notes("Return of auto-received stock on cancellation of bill " + number));
            returned = returned.add(out.totalValue() == null ? Money.ZERO : out.totalValue());
        }
        BigDecimal diff = received.subtract(returned).setScale(6, java.math.RoundingMode.HALF_UP);
        if (diff.abs().compareTo(new BigDecimal("0.005")) >= 0) {
            String grni = determination.mapped("grni_accrued");
            String ppv = priceVarianceAccount();
            BigDecimal abs = diff.abs();
            posting.post(PostingRequest.of("PURCH", date, "Bill " + number + " cancelled · stock return cost difference",
                    "vendor_bill_cancel", billId, "vendor_bill_cancel:variance:" + billId, diff.signum() > 0
                            ? List.of(Line.debit(grni, JsNumbers.dec(abs), "GRNI cleared"),
                                    Line.credit(ppv, JsNumbers.dec(abs), "Return at a lower cost than received"))
                            : List.of(Line.debit(ppv, JsNumbers.dec(abs), "Return at a higher cost than received"),
                                    Line.credit(grni, JsNumbers.dec(abs), "GRNI cleared"))));
        }
    }

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
                            // IPR M-29: the supplier lock (the order bill post and goods-receipt post use), then the
                            // bill's auto-receipt rows, before reading whether a receipt absorbed them: a receipt
                            // posting at the same time either absorbed them already (refused below) or waits and then
                            // finds the bill cancelled.
                            matcher.lockSupplier(ctx.organizationId(), (String) doc.get("partnerId"));
                            jdbc.sql("SELECT id FROM \"VendorBillAutoReceipt\" WHERE \"organizationId\" = ? AND"
                                    + " \"vendorBillId\" = ? ORDER BY id FOR UPDATE").params(ctx.organizationId(),
                                    doc.get("id")).query().listOfRows();
                            String absorbedBy = matcher.absorbingReceipt(ctx.organizationId(), (String) doc.get("id"));
                            if (absorbedBy != null) {
                                // Purchase readiness H1: the goods are in stock through that receipt now.
                                throw ApiException.badRequest("Bill " + doc.get("documentNumber") + " cannot be cancelled:"
                                        + " the stock it received was matched by goods receipt " + absorbedBy
                                        + ". Raise a debit note for the bill instead.");
                            }
                            if (doc.get("journalEntryId") != null) {
                                posting.reverse((String) doc.get("journalEntryId"), null,
                                        "Void of " + doc.get("documentNumber"));
                            }
                            matcher.releaseForBill(ctx.organizationId(), (String) doc.get("id"));
                            // ACR-2: PO payments applied to the bill go back to their purchase orders
                            jdbc.sql("UPDATE \"PurchaseOrderBillApplication\" SET \"releasedAt\" = " + NOW + " WHERE"
                                    + " \"organizationId\" = ? AND \"vendorBillId\" = ? AND \"releasedAt\" IS NULL")
                                    .params(ctx.organizationId(), doc.get("id")).update();
                            returnBillReceivedStock((String) doc.get("id"), (String) doc.get("documentNumber"),
                                    instant(doc.get("issueDate")));
                            jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"postingKey\" = ?"
                                    + " AND status = 'posted'").params(ctx.organizationId(), "vendor_bill:fx_voucher:"
                                    + doc.get("id")).query(String.class).optional().ifPresent(fxId -> posting.reverse(fxId,
                                    null, "Void of " + doc.get("documentNumber")));
                            jdbc.sql("UPDATE \"WithholdingCertificate\" SET status = 'cancelled', \"cancelledAt\" = "
                                    + NOW + " WHERE \"documentId\" = ? AND \"organizationId\" = ? AND status = 'issued'")
                                    .params(doc.get("id"), ctx.organizationId()).update();
                            jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = 0, \"paymentStatus\" = 'not_paid',"
                                    + " \"updatedAt\" = " + NOW + " WHERE id = ?").param(doc.get("id")).update();
                        }, null)));
    }

    /**
     * The accrued receipt value of a bill line in the BILL's currency. A PO receipt is priced in the PO currency (the
     * bill's); a receipt without a PO is priced in functional currency, so for a foreign bill it is converted at the
     * bill's rate before it meets the bill price (review 2026-11-15, P-3: it was subtracted as if in bill currency).
     */
    private static BigDecimal accruedInBillCurrency(BillLineMatch match, BigDecimal billRate) {
        if (billRate.compareTo(BigDecimal.ONE) == 0) {
            return match.accruedReceiptValue();
        }
        BigDecimal value = Money.ZERO;
        for (BillReceiptMatcherService.Consumption c : match.consumptions()) {
            if (c.vouchered()) {
                continue;
            }
            BigDecimal v = c.quantity().multiply(c.receiptUnitCost());
            value = value.add(c.purchaseOrderLineId() == null ? v.divide(billRate, 6, java.math.RoundingMode.HALF_UP) : v);
        }
        return value;
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
        // Purchase readiness B3: receipt quantity already matched by earlier lines of this bill (stamped later).
        Map<String, BigDecimal> claimedReceiptQty = new HashMap<>();
        if (productById.values().stream().anyMatch(p -> Boolean.TRUE.equals(p.get("trackInventory")))) {
            // Purchase readiness H1: one supplier's bill and goods-receipt posts serialise (auto-receive vs absorb).
            matcher.lockSupplier(ctx.organizationId(), partnerId);
        }
        // FX-1: the rate the bill actually posts at, not its stored column (1 for API-created bills), so the receipt
        // variance and the vouchered-payable remeasurement see the real bill rate.
        BigDecimal documentRate = fxRates != null ? fxRates.effectiveRate(full, instant(full.get("issueDate")))
                : full.get("exchangeRate") == null || d(full.get("exchangeRate")).signum() <= 0
                        ? BigDecimal.ONE : d(full.get("exchangeRate"));
        List<Map<String, Object>> postable = new ArrayList<>();
        BigDecimal postableTotal = Money.ZERO;
        BigDecimal priceVariance = Money.ZERO;
        BigDecimal voucheredVariance = Money.ZERO;
        BigDecimal voucheredTaxDiff = Money.ZERO;
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
            BillLineMatch match = matcher.matchLine(ctx.organizationId(), partnerId, (String) line.get("productId"),
                    (String) line.get("variantId"), qty, line.get("uomFactor") == null ? null : d(line.get("uomFactor")),
                    claimedReceiptQty);
            matchByLine.put((String) line.get("id"), match);
            if (match.unmatchedQuantity().signum() > 0 && billsNeedReceipt()) {
                // Review 2026-11-17 (ACR-4): the bill would receive goods nobody counted in (three-way match).
                throw ApiException.badRequest("Bill line " + product.get("name") + ": "
                        + JsNumbers.dec(match.unmatchedQuantity()) + " not received yet — post the goods receipt first"
                        + " (Purchasing settings: a supplier bill needs a goods receipt)");
            }
            if (match.accruedQuantity().signum() > 0) {
                BigDecimal billUnit = Money.div(d(line.get("subtotal")), qty);
                priceVariance = priceVariance.add(billUnit.multiply(match.accruedQuantity()))
                        .subtract(accruedInBillCurrency(match, documentRate));
            }
            if (match.voucheredQuantity().signum() > 0) {
                BigDecimal share = Money.div(match.voucheredQuantity().min(qty), qty);
                voucheredBillTotal = voucheredBillTotal.add(d(line.get("total")).multiply(share));
                // P1-7 (post-parity), review 2026-11-17 ACR-3: the receipt voucher raised AP at the receipt price and the
                // PO tax rate; the bill is the open item at its own price and tax. The difference is posted either way
                // (Dr/Cr PPV, Dr/Cr input VAT, Cr/Dr AP) so the ledger's AP equals the bill's residual. A lower bill
                // price used to post nothing (AP stayed at the receipt price), and a higher one moved AP by the net
                // difference only while the residual carried the tax on it as well.
                BigDecimal billUnit = Money.div(d(line.get("subtotal")), qty);
                BigDecimal receiptValue = Money.ZERO;
                BigDecimal receiptTax = Money.ZERO;
                for (BillReceiptMatcherService.Consumption c : match.consumptions()) {
                    if (c.vouchered()) {
                        BigDecimal v = c.quantity().multiply(c.receiptUnitCost());
                        receiptValue = receiptValue.add(v);
                        receiptTax = receiptTax.add(Money.div(v.multiply(poTaxRate(c)), BigDecimal.valueOf(100)));
                    }
                }
                BigDecimal diff = billUnit.multiply(match.voucheredQuantity()).subtract(receiptValue);
                if (diff.abs().compareTo(VARIANCE_EPSILON) > 0) {
                    voucheredVariance = voucheredVariance.add(diff);
                }
                BigDecimal taxDiff = regularTax(line).multiply(share).subtract(receiptTax);
                if (taxDiff.abs().compareTo(VARIANCE_EPSILON) > 0) {
                    voucheredTaxDiff = voucheredTaxDiff.add(taxDiff);
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
            if (line.get("taxes") instanceof List<?> taxes) {
                // ADR-S071: the breakdown scales with the postable share (withholding / reverse charge post whole).
                List<Map<String, Object>> scaled = new ArrayList<>();
                for (Object o : taxes) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> t = new LinkedHashMap<>((Map<String, Object>) o);
                    t.put("amount", d(t.get("amount")).multiply(factor));
                    scaled.add(t);
                }
                part.put("taxes", scaled);
            }
            postable.add(part);
            postableTotal = postableTotal.add(d(line.get("total")).multiply(factor));
        }
        String entryId = null;
        postableTotal = postableTotal.add(voucheredVariance).add(voucheredTaxDiff);
        // ADR-S071: withholding (deducted from the payable) and reverse-charge VAT apply to the whole bill.
        DocumentBuilderService.SpecialTaxes special = builder.specialTaxes(lines);
        BigDecimal withholding = special.withholding();
        if (postableTotal.signum() != 0 || special.any()) {
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
            } else if (voucheredVariance.signum() < 0) {
                post.add(new MutableLine(priceVarianceAccount(), null, voucheredVariance.negate(), null,
                        "Purchase price variance (billed below receipt)"));
            }
            if (voucheredTaxDiff.signum() != 0) {
                String inputTax = determination.mapped("tax_receivable");
                post.add(voucheredTaxDiff.signum() > 0
                        ? new MutableLine(inputTax, voucheredTaxDiff, null, null, "Input tax (bill vs receipt)")
                        : new MutableLine(inputTax, null, voucheredTaxDiff.negate(), null, "Input tax (bill vs receipt)"));
            }
            BigDecimal counterCredit = postableTotal.subtract(withholding);
            if (!special.any()) {
                post.add(postableTotal.signum() >= 0
                        ? new MutableLine(g.counterAccount(), null, postableTotal, partnerId,
                                "Bill " + full.get("documentNumber"))
                        : new MutableLine(g.counterAccount(), postableTotal.negate(), null, partnerId,
                                "Bill " + full.get("documentNumber") + " · billed below the receipt voucher"));
            } else {
                if (counterCredit.signum() > 0) {
                    post.add(new MutableLine(g.counterAccount(), null, counterCredit, partnerId,
                            "Bill " + full.get("documentNumber")));
                } else if (counterCredit.signum() < 0) {
                    post.add(new MutableLine(g.counterAccount(), counterCredit.negate(), null, partnerId,
                            "Bill " + full.get("documentNumber") + " · withholding on vouchered receipts"));
                }
                if (withholding.signum() > 0) {
                    post.add(new MutableLine(determination.mapped("withholding_payable"), null, withholding, partnerId,
                            "Withholding tax · bill " + full.get("documentNumber")));
                }
                special.reverseChargeByTax().forEach((taxId, amt) -> {
                    post.add(new MutableLine(determination.mapped("tax_receivable"), amt, null, null,
                            "Reverse-charge input VAT"));
                    post.add(new MutableLine(determination.mapped("tax_payable"), null, amt, null,
                            "Reverse-charge output VAT"));
                });
            }
            List<Line> req = post.stream().map(MutableLine::toLine).toList();
            Map<String, Object> entry = posting.post(new PostingRequest("PURCH", instant(full.get("issueDate")),
                    "Bill " + full.get("documentNumber"), (String) full.get("currencyId"),
                    d(full.get("exchangeRate")), "vendor_bill", id, null, "vendor_bill:" + id, null, null,
                    null, req));
            entryId = (String) entry.get("id");
        }
        // Review M-5: a foreign-currency bill receives at the functional value (the rate its journal was posted at),
        // like a foreign-currency goods receipt (P0-5); a base-currency bill's rate is 1.
        BigDecimal billRate = entryId == null || fxRates == null ? null : fxRates.entryRate(entryId);
        BigDecimal functionalRate = billRate == null || billRate.signum() <= 0 ? BigDecimal.ONE : billRate;
        for (Map<String, Object> line : StockMove.inLockOrder(lines)) {
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
                    : warehouse(ctx.organizationId(), full.get("branchId"));
            if (location == null) {
                throw new IllegalStateException("No warehouse location for stockable line " + product.get("name"));
            }
            // IPR F11 (deviation from Nest, which went through a JS number): the bill line's net unit price stays a
            // decimal end to end (decimal.js precision, HALF_UP), so the receipt's value is the line's subtotal and the
            // GRNI credit it raises matches the bill's GRNI debit even for large amounts.
            BigDecimal unitCost = qty.signum() > 0 ? Money.div(d(line.get("subtotal")), qty) : Money.ZERO;
            if (functionalRate.compareTo(BigDecimal.ONE) != 0) {
                unitCost = unitCost.multiply(functionalRate).setScale(6, java.math.RoundingMode.HALF_UP);
            }
            StockMove m = StockMove.of((String) line.get("productId"), location, toReceive).unitCost(unitCost)
                    .variant((String) line.get("variantId"))
                    .uom(line.get("uomFactor") == null ? (String) product.get("purchaseUomId") : (String) line.get("uomId"),
                            line.get("uomFactor") == null ? null : d(line.get("uomFactor")))
                    .notes("Auto-receive for bill " + full.get("documentNumber"));
            stock.receiveFromBill(m, id, instant(full.get("issueDate")));
            // Purchase readiness H1: a later goods receipt of this supplier absorbs this quantity instead of adding it.
            matcher.recordAutoReceipt(ctx.organizationId(), id, (String) line.get("id"), partnerId,
                    (String) line.get("productId"), (String) line.get("variantId"), toReceive,
                    line.get("uomFactor") == null ? null : d(line.get("uomFactor")));
        }
        // ACC-4 (B15): the bill is the open AP item for its whole amount. Vouchered quantities were raised on AP by the
        // receipt voucher, so the bill posts no second AP for them, but the supplier is paid against this bill — its
        // residual carries the vouchered share at bill price (the billed-above-receipt variance is part of that share).
        BigDecimal openPayable = postableTotal.subtract(voucheredVariance).subtract(voucheredTaxDiff)
                .add(voucheredBillTotal).subtract(withholding);
        if (withholding.signum() > 0) {
            issueWithholdingCertificates(full, special, entryId);
        }
        // ACR-2: what the supplier was already paid on the purchase orders of the receipts this bill takes over.
        BigDecimal appliedFromPo = applyPurchaseOrderPayments(ctx.organizationId(), full, matchByLine.values());
        BigDecimal residual = openPayable.subtract(appliedFromPo).max(Money.ZERO);
        String paymentStatus = appliedFromPo.signum() <= 0 ? "not_paid"
                : residual.compareTo(VARIANCE_EPSILON) < 0 ? "paid" : "partial";
        jdbc.sql("UPDATE \"Document\" SET \"journalEntryId\" = ?, \"amountResidual\" = ?, \"paymentStatus\" ="
                        + " ?::\"PaymentStatus\", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(entryId, residual, paymentStatus, id).update();
        remeasureVoucheredPayable(full, matchByLine.values(), documentRate, partnerId);
    }

    /** {@code procurement.billWithoutReceipt = block}: a stockable bill line must be covered by posted receipts. */
    private boolean billsNeedReceipt() {
        return settings != null && "block".equals(settings.resolveString("procurement.billWithoutReceipt",
                com.inventoryerp.kernel.settings.SettingResolverService.SettingContext.EMPTY));
    }

    /** The PO line's tax rate (%) of a consumed receipt line; 0 without one. */
    private BigDecimal poTaxRate(BillReceiptMatcherService.Consumption c) {
        if (c.purchaseOrderLineId() == null) {
            return Money.ZERO;
        }
        return jdbc.sql("SELECT coalesce(\"taxRate\", 0) FROM \"PurchaseOrderLine\" WHERE id = ?")
                .param(c.purchaseOrderLineId()).query(BigDecimal.class).optional().orElse(Money.ZERO);
    }

    /** The line's ordinary tax (VAT): withholding and reverse charge post on their own (ADR-S071). */
    private static BigDecimal regularTax(Map<String, Object> line) {
        if (!(line.get("taxes") instanceof List<?> taxes) || taxes.isEmpty()) {
            return d(line.get("taxAmount"));
        }
        BigDecimal sum = Money.ZERO;
        for (Object o : taxes) {
            if (o instanceof Map<?, ?> t && !"withholding".equals(t.get("kind"))
                    && !"reverse_charge".equals(t.get("kind"))) {
                sum = sum.add(d(t.get("amount")));
            }
        }
        return sum;
    }

    /**
     * Review 2026-11-17 (ACR-2). A PO receipt raised AP (receipt voucher) and the supplier may have been paid on the
     * purchase order since; this bill now takes the receipt over and is the open item for it. Unapplied PO payments
     * (paid on the PO, not yet applied to one of its bills) are applied to the bill up to the receipt-priced share it
     * takes from that PO, and recorded per purchase order ({@code PurchaseOrderBillApplication}); the PO open item
     * drops by the same amount, so the supplier is neither shown nor paid twice. Only between a PO and a bill in the
     * same currency (a blank currency is the base one). The PO row is locked like its pay route locks it.
     *
     * @return the total applied (bill currency)
     */
    private BigDecimal applyPurchaseOrderPayments(String org, Map<String, Object> bill,
            java.util.Collection<BillLineMatch> matches) {
        Map<String, BigDecimal> shareByPo = new LinkedHashMap<>();
        String billCurrency = bill.get("currencyId") == null ? null : bill.get("currencyId").toString();
        for (BillLineMatch m : matches) {
            for (BillReceiptMatcherService.Consumption c : m.consumptions()) {
                if (!c.vouchered()) {
                    continue;
                }
                Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT g.\"purchaseOrderId\" AS po, po.\"currencyCode\" AS"
                        + " code FROM \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g ON g.id = l.\"goodsReceiptId\""
                        + " JOIN \"PurchaseOrder\" po ON po.id = g.\"purchaseOrderId\" WHERE l.id = ? AND"
                        + " g.\"organizationId\" = ?").params(c.goodsReceiptLineId(), org));
                if (po == null || !sameCurrency(billCurrency, (String) po.get("code"))) {
                    continue;
                }
                BigDecimal gross = c.quantity().multiply(c.receiptUnitCost()).multiply(BigDecimal.ONE.add(
                        Money.div(poTaxRate(c), BigDecimal.valueOf(100))));
                shareByPo.merge((String) po.get("po"), gross, BigDecimal::add);
            }
        }
        BigDecimal total = Money.ZERO;
        for (Map.Entry<String, BigDecimal> e : shareByPo.entrySet()) {
            String poId = e.getKey();
            Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT \"totalPaid\" FROM \"PurchaseOrder\" WHERE id = ? AND"
                    + " \"organizationId\" = ? FOR UPDATE").params(poId, org));
            if (po == null) {
                continue;
            }
            BigDecimal paid = Money.max(Money.dec(jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"PurchasePayment\""
                    + " WHERE \"organizationId\" = ? AND \"purchaseOrderId\" = ?").params(org, poId)
                    .query(BigDecimal.class).single()), Money.dec(po.get("totalPaid")));
            BigDecimal applied = Money.dec(jdbc.sql("SELECT coalesce(sum(amount), 0) FROM"
                    + " \"PurchaseOrderBillApplication\" WHERE \"organizationId\" = ? AND \"purchaseOrderId\" = ? AND"
                    + " \"releasedAt\" IS NULL").params(org, poId).query(BigDecimal.class).single());
            BigDecimal amount = Money.min(paid.subtract(applied), e.getValue()).setScale(6,
                    java.math.RoundingMode.HALF_UP);
            if (amount.compareTo(VARIANCE_EPSILON) < 0) {
                continue;
            }
            jdbc.sql("INSERT INTO \"PurchaseOrderBillApplication\" (id, \"organizationId\", \"purchaseOrderId\","
                    + " \"vendorBillId\", amount, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?)").params(
                    UUID.randomUUID().toString(), org, poId, bill.get("id"), amount,
                    com.inventoryerp.kernel.tenancy.TenantContext.userId()).update();
            total = total.add(amount);
        }
        return total;
    }

    private boolean sameCurrency(String billCurrency, String poCurrency) {
        String base = fxRates == null ? null : fxRates.base();
        String b = billCurrency == null || billCurrency.isBlank() ? base : billCurrency.trim();
        String p = poCurrency == null || poCurrency.isBlank() ? base : poCurrency.trim();
        return b == null || p == null || b.equalsIgnoreCase(p);
    }

    /**
     * Review 2026-11-15 (P-4). A foreign bill's vouchered share was raised on AP by the receipt voucher at the PO rate;
     * the bill is the open item and is paid off at the bill's rate. Re-measure that share to the bill rate now (Dr/Cr AP
     * in base, with no transaction amount, against FX gain / loss) so a fully paid bill leaves no AP residue. Only when
     * the PO is in the bill's currency; reversed with the bill on cancel ({@code vendor_bill:fx_voucher:<id>}).
     */
    private void remeasureVoucheredPayable(Map<String, Object> bill, java.util.Collection<BillLineMatch> matches,
            BigDecimal billRate, String partnerId) {
        String currency = (String) bill.get("currencyId");
        if (billRate.compareTo(BigDecimal.ONE) == 0 || currency == null) {
            return;
        }
        BigDecimal adjust = Money.ZERO;
        for (BillLineMatch m : matches) {
            for (BillReceiptMatcherService.Consumption c : m.consumptions()) {
                if (!c.vouchered()) {
                    continue;
                }
                // FX-2 G: the receipt's own rate (receipt-date valuation), else the PO rate for older receipts
                Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT coalesce(g.\"exchangeRate\", po.\"exchangeRate\") AS rate,"
                        + " po.\"currencyCode\" AS code,"
                        + " coalesce(pl.\"taxRate\", 0) AS \"taxRate\" FROM \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g"
                        + " ON g.id = l.\"goodsReceiptId\" JOIN \"PurchaseOrder\" po ON po.id = g.\"purchaseOrderId\""
                        + " LEFT JOIN \"PurchaseOrderLine\" pl ON pl.id = l.\"purchaseOrderLineId\" WHERE l.id = ?")
                        .param(c.goodsReceiptLineId()));
                if (po == null || po.get("code") == null || !currency.equalsIgnoreCase(po.get("code").toString())
                        || po.get("rate") == null || d(po.get("rate")).signum() <= 0) {
                    continue;
                }
                BigDecimal gross = c.quantity().multiply(c.receiptUnitCost()).multiply(BigDecimal.ONE.add(
                        Money.div(d(po.get("taxRate")), BigDecimal.valueOf(100))));
                adjust = adjust.add(gross.multiply(billRate.subtract(d(po.get("rate")))));
            }
        }
        adjust = adjust.setScale(6, java.math.RoundingMode.HALF_UP);
        if (adjust.abs().compareTo(VARIANCE_EPSILON) < 0) {
            return;
        }
        String id = (String) bill.get("id");
        String ap = determination.payableAccount(jdbc.sql("SELECT \"payableAccountId\" FROM \"Partner\" WHERE id = ?")
                .param(partnerId).query(String.class).optional().orElse(null));
        String amount = JsNumbers.dec(adjust.abs());
        String label = "Bill " + bill.get("documentNumber") + " · vouchered payable at the bill rate";
        posting.post(PostingRequest.of("PURCH", instant(bill.get("issueDate")), label, "vendor_bill", id,
                "vendor_bill:fx_voucher:" + id, adjust.signum() > 0
                        ? List.of(Line.debit(determination.mapped("fx_loss"), amount, label),
                                new Line(ap, null, amount, partnerId, label, null, null, null).inCurrency(currency, "0"))
                        : List.of(new Line(ap, amount, null, partnerId, label, null, null, null).inCurrency(currency, "0"),
                                Line.credit(determination.mapped("fx_gain"), amount, label))));
    }

    /** ADR-S071: one issued withholding certificate per withholding tax of the posted bill. */
    private void issueWithholdingCertificates(Map<String, Object> bill, DocumentBuilderService.SpecialTaxes special,
            String entryId) {
        Instant date = instant(bill.get("issueDate"));
        int year = date.atOffset(java.time.ZoneOffset.UTC).getYear();
        special.withholdingByTax().forEach((taxId, v) -> {
            if (v[2].signum() == 0) {
                return;
            }
            withholdingCertificates.issue(new WithholdingCertificateService.NewCertificate("issued",
                    (String) bill.get("partnerId"), (String) bill.get("id"), null, taxId, date, v[0], v[1], v[2],
                    (String) bill.get("currencyId"), entryId, null, "Bill " + bill.get("documentNumber")), year);
        });
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
        // FX-2 F: later allocations of the advance posted their realised FX separately; reverse those too
        for (String fxEntry : jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'payment_allocation' AND \"sourceId\" = ? AND status = 'posted'").params(payment.get("organizationId"),
                        payment.get("id")).query(String.class).list()) {
            posting.reverse(fxEntry, null, "Void of " + payment.get("paymentNumber") + ": " + reason);
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
        // Sales readiness R2b (P1-5): restore each settled document under a row lock, in id order, so a void cannot race
        // a concurrent receipt or credit allocation on the same invoice.
        List<Map<String, Object>> ordered = new ArrayList<>(allocations);
        ordered.sort(Comparator.comparing(a -> String.valueOf(a.get("invoiceId") != null ? a.get("invoiceId")
                : a.get("documentId"))));
        for (Map<String, Object> alloc : ordered) {
            if (alloc.get("invoiceId") != null) {
                Map<String, Object> inv = SqlRows.one(jdbc.sql("SELECT * FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ?"
                        + " FOR UPDATE").params(alloc.get("invoiceId"), org));
                if (inv != null) {
                    restore("Invoice", "InvoiceStatus", inv, d(alloc.get("amount")));
                }
                jdbc.sql("DELETE FROM \"PaymentAllocation\" WHERE id = ? AND \"organizationId\" = ?").params(alloc.get("id"), com.inventoryerp.kernel.tenancy.TenantContext.organizationId()).update();
                continue;
            }
            Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT * FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?"
                    + " FOR UPDATE").params(alloc.get("documentId"), org));
            if (doc != null) {
                restore("Document", "DocumentStatus", doc, d(alloc.get("amount")));
            }
            jdbc.sql("DELETE FROM \"PaymentAllocation\" WHERE id = ? AND \"organizationId\" = ?").params(alloc.get("id"), com.inventoryerp.kernel.tenancy.TenantContext.organizationId()).update();
        }
        jdbc.sql("UPDATE \"Payment\" SET \"allocatedAmount\" = 0, \"unallocatedAmount\" = 0, \"updatedAt\" = " + NOW
                + " WHERE id = ?").param(payment.get("id")).update();
        jdbc.sql("UPDATE \"WithholdingCertificate\" SET status = 'cancelled', \"cancelledAt\" = " + NOW
                + " WHERE \"paymentId\" = ? AND \"organizationId\" = ? AND status = 'issued'")
                .params(payment.get("id"), org).update();
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("id", payment.get("id"));
        outcome.put("voided", true);
        BusinessOutcome.record(jdbc, outcome, true);
    }

    /** Give an allocation back to an invoice / document: paid ↓, residual ↑, status recomputed. */
    private void restore(String table, String statusType, Map<String, Object> row, BigDecimal amount) {
        BigDecimal newPaid = d(row.get("amountPaid")).subtract(amount);
        BigDecimal newResidual = d(row.get("amountResidual")).add(amount);
        BigDecimal payable = d(row.get("totalAmount")).subtract(d(row.get("withholdingAmount")));
        String paymentStatus = newResidual.signum() <= 0 ? "paid"
                : newResidual.compareTo(payable) < 0 ? "partial" : "not_paid";
        String status = "paid".equals(String.valueOf(row.get("status"))) && newResidual.signum() > 0 ? "posted"
                : String.valueOf(row.get("status"));
        jdbc.sql("UPDATE \"" + table + "\" SET \"amountPaid\" = ?, \"amountResidual\" = ?, \"paymentStatus\" ="
                        + " ?::\"PaymentStatus\", status = ?::\"" + statusType + "\", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(newPaid, newResidual, paymentStatus, status, row.get("id")).update();
    }
}
