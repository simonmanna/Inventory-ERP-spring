package com.inventoryerp.invoicing.application;

import static com.inventoryerp.invoicing.application.DocumentBuilderService.NOW;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.documents.domain.Document;
import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Grouped;
import com.inventoryerp.invoicing.application.DocumentBuilderService.HeaderInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.Savepoints;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credit notes (port of {@code credit-note.service.ts}): draft create, post (Dr revenue + tax / Cr AR), apply the
 * credit to the reversed invoice's residual, and restock (COGS reversal) or scrap the returned goods.
 */
@Service
public class CreditNoteService {

    private static final Logger log = LoggerFactory.getLogger(CreditNoteService.class);

    /** {@code CreateCreditNoteDto}. */
    public record CreditNoteInput(String partnerId, String issueDate, String currencyId, String reference, String notes,
            String reversedDocumentId, List<LineInput> lines) {}

    private final JdbcClient jdbc;
    private final EventBus events;
    private final PostingService posting;
    private final DocumentBuilderService builder;
    private final StockService stock;
    private final ApprovalsService approvals;
    private final PosStockLocation posLocation;
    private final TransactionTemplate newTx;
    private final DocumentFxRates fxRates;

    public CreditNoteService(JdbcClient jdbc, EventBus events, PostingService posting, DocumentBuilderService builder,
            StockService stock, ApprovalsService approvals, PosStockLocation posLocation, PlatformTransactionManager txm,
            DocumentFxRates fxRates) {
        this.fxRates = fxRates;
        this.jdbc = jdbc;
        this.events = events;
        this.posting = posting;
        this.builder = builder;
        this.stock = stock;
        this.approvals = approvals;
        this.posLocation = posLocation;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * PC-0 (ADR-S054, D-PC2): a foreign-currency credit note that settles part of an invoice of the same currency at
     * another rate realises the difference on the receivable — FX gain / loss only.
     */
    private void realiseFx(Map<String, Object> cn, Map<String, Object> inv, BigDecimal reduce, String cnEntryId,
            String receivable, String partnerId, Instant date) {
        String cur = fxRates.currencyOf(cn);
        if (cur.equals(fxRates.base()) || !cur.equals(fxRates.currencyOf(inv))) {
            return;
        }
        BigDecimal cnRate = fxRates.entryRate(cnEntryId);
        BigDecimal invRate = fxRates.documentRate(inv);
        if (cnRate == null || cnRate.compareTo(invRate) == 0) {
            return;
        }
        BigDecimal fx = reduce.multiply(cnRate.subtract(invRate)).setScale(6, java.math.RoundingMode.HALF_UP);
        List<Line> lines = fxRates.realiseOnReceivable(fx, receivable, partnerId, "Realised FX · credit note "
                + cn.get("documentNumber") + " on " + inv.get("documentNumber"));
        if (!lines.isEmpty()) {
            posting.post(PostingRequest.of("SALES", date, "Realised FX · credit note " + cn.get("documentNumber"),
                    "credit_note_fx", (String) cn.get("id"), "credit_note_fx:" + cn.get("id"), lines));
        }
    }

    /** Credit notes newest first (search on number) with partner and line count. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PageQuery q) {
        int page = Math.max(1, q.page() == null || q.page() == 0 ? 1 : q.page());
        int pageSize = Math.min(200, Math.max(1, q.pageSize() == null || q.pageSize() == 0 ? 25 : q.pageSize()));
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = " \"organizationId\" = ? AND \"documentType\" = 'credit_note'";
        if (q.search() != null && !q.search().isEmpty()) {
            where += " AND \"documentNumber\" ILIKE ?";
            params.add("%" + BaseCrudService.escapeLike(q.search()) + "%");
        }
        List<Map<String, Object>> rows = SqlRows.list(Document.class, jdbc.sql("SELECT * FROM \"Document\" WHERE" + where
                + " ORDER BY \"createdAt\" DESC LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize).params(params));
        for (Map<String, Object> d : rows) {
            d.put("partner", builder.partner(d.get("partnerId")));
            d.put("_count", Obj.of("lines", jdbc.sql("SELECT count(*) FROM \"DocumentLine\" WHERE \"documentId\" = ?")
                    .param(d.get("id")).query(Long.class).single()));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"Document\" WHERE" + where).params(params).query(Long.class).single();
        return PageResult.of(rows, page, pageSize, total);
    }

    /** One credit note with ordered lines and partner; null when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> d = builder.document(id, "credit_note");
        if (d == null) {
            return null;
        }
        d.put("lines", builder.lines(id, true));
        d.put("partner", builder.partner(d.get("partnerId")));
        return d;
    }

    /** Create a draft credit note. */
    @Transactional
    public Map<String, Object> create(CreditNoteInput dto) {
        builder.assertVariants(dto.lines(), false, true);
        assertVariantResolvable(dto.reversedDocumentId(), dto.lines());
        List<LineInput> lines = withInvoiceUnits(dto.reversedDocumentId(), dto.lines());
        // A credit note against an invoice is in that invoice's currency and rate unless it says otherwise.
        Map<String, Object> reversed = dto.reversedDocumentId() == null || dto.currencyId() != null ? null
                : SqlRows.one(jdbc.sql("SELECT \"currencyId\", \"exchangeRate\" FROM \"Document\" WHERE id = ? AND"
                        + " \"organizationId\" = ?").params(dto.reversedDocumentId(), org()));
        String currencyId = reversed == null ? dto.currencyId() : (String) reversed.get("currencyId");
        BigDecimal rate = reversed == null ? null : Money.dec(reversed.get("exchangeRate"));
        return builder.createDocument("credit_note", new HeaderInput(dto.partnerId(), dto.issueDate(), null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, currencyId, rate,
                dto.reference(), dto.notes(), dto.reversedDocumentId(), null, null), lines);
    }

    /**
     * Phase I2 (audit U-5/U-6): a credit-note line for a product (and variant) the credited invoice sold on one line
     * keeps that line's unit and frozen factor (its own unit when it names the same one, the invoice's when it names
     * none), so a pack-size change since the sale never changes what the credit restocks.
     */
    private List<LineInput> withInvoiceUnits(String invoiceId, List<LineInput> lines) {
        if (invoiceId == null || lines == null) {
            return lines;
        }
        List<LineInput> out = new ArrayList<>();
        for (LineInput l : lines) {
            boolean product = l.productId() != null && !l.productId().isBlank()
                    && (l.lineType() == null || "product".equals(l.lineType()));
            if (!product) {
                out.add(l);
                continue;
            }
            String variantId = l.variantId() != null && !l.variantId().isBlank() ? l.variantId()
                    : invoiceVariant(invoiceId, l.productId());
            List<Map<String, Object>> sold = SqlRows.list(jdbc.sql("SELECT \"uomId\", \"uomFactor\" FROM \"DocumentLine\""
                    + " WHERE \"documentId\" = ? AND \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT"
                    + " DISTINCT FROM ? AND \"uomFactor\" IS NOT NULL").params(invoiceId, org(), l.productId(), variantId));
            Map<String, Object> match = null;
            for (Map<String, Object> r : sold) {
                if (l.uomId() == null || l.uomId().isBlank() ? sold.size() == 1 : l.uomId().equals(r.get("uomId"))) {
                    match = r;
                    break;
                }
            }
            out.add(match == null || match.get("uomId") == null ? l
                    : l.withUnitSnapshot((String) match.get("uomId"), Money.dec(match.get("uomFactor"))));
        }
        return out;
    }

    /**
     * Phase I2 (audit U-6, M-2): where and at what base-unit cost the credited invoice issued this product / variant —
     * the location that issued most of it and the average cost of the issue — or null when the invoice issued no stock
     * (goods delivered by Fleet, or a service).
     */
    private Map<String, Object> invoiceIssue(String invoiceId, String productId, String variantId) {
        if (invoiceId == null) {
            return null;
        }
        return SqlRows.one(jdbc.sql("SELECT \"locationId\", SUM(-\"quantityChange\" * \"unitCost\") / NULLIF(SUM("
                + "-\"quantityChange\"), 0) AS \"unitCost\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceType\" = 'sales_invoice' AND \"referenceId\" = ? AND \"productId\" = ? AND \"variantId\" IS"
                + " NOT DISTINCT FROM ? AND \"quantityChange\" < 0 GROUP BY \"locationId\" ORDER BY SUM(-\"quantityChange\")"
                + " DESC LIMIT 1").params(org(), invoiceId, productId, variantId));
    }

    /**
     * Apply {@code amount} of a posted credit note's open credit to a posted invoice of the same customer (ACC-4, B9
     * open-item matching). Both residuals drop by the amount and a {@code CreditAllocation} is recorded; no GL entry
     * (both documents sit on the receivable control account).
     */
    @Transactional
    public Map<String, Object> allocate(String creditNoteId, String invoiceId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("amount must be greater than zero");
        }
        jdbc.sql("SELECT id FROM \"Document\" WHERE id IN (?, ?) AND \"organizationId\" = ? ORDER BY id FOR UPDATE")
                .params(creditNoteId, invoiceId, org()).query().listOfRows();
        Map<String, Object> cn = builder.document(creditNoteId, "credit_note");
        if (cn == null) {
            throw ApiException.notFound("Credit note not found");
        }
        Map<String, Object> inv = builder.document(invoiceId, "sales_invoice");
        if (inv == null) {
            throw ApiException.notFound("Invoice not found");
        }
        if (!"posted".equals(String.valueOf(cn.get("status")))) {
            throw ApiException.badRequest("Only a posted credit note can be allocated");
        }
        String invStatus = String.valueOf(inv.get("status"));
        if (!"posted".equals(invStatus)) {
            throw ApiException.badRequest("Credit can only be allocated to an open posted invoice");
        }
        if (!String.valueOf(cn.get("partnerId")).equals(String.valueOf(inv.get("partnerId")))) {
            throw ApiException.badRequest("Credit note and invoice belong to different customers");
        }
        BigDecimal open = Money.dec(cn.get("amountResidual"));
        if (amount.compareTo(open) > 0) {
            throw ApiException.badRequest("Allocation " + JsNumbers.dec(amount) + " exceeds the credit note's open"
                    + " credit " + JsNumbers.dec(open));
        }
        if (amount.compareTo(Money.dec(inv.get("amountResidual"))) > 0) {
            throw ApiException.badRequest("Allocation " + JsNumbers.dec(amount) + " exceeds the invoice's residual "
                    + JsNumbers.dec(Money.dec(inv.get("amountResidual"))));
        }
        applyTo(creditNoteId, inv, amount);
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(open.subtract(amount), creditNoteId).update();
        return builder.document(creditNoteId);
    }

    /**
     * Close a posted credit note's open balance so it can be paid out, and return that balance. The caller must pay it in
     * the same transaction: once taken, the credit can no longer be allocated or count toward credit exposure.
     */
    @Transactional
    public BigDecimal takeOpenCredit(String creditNoteId) {
        jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(creditNoteId, org()).query().listOfRows();
        Map<String, Object> cn = builder.document(creditNoteId, "credit_note");
        if (cn == null) {
            throw ApiException.notFound("Credit note not found");
        }
        if (!"posted".equals(String.valueOf(cn.get("status")))) {
            throw ApiException.badRequest("Only a posted credit note can be refunded");
        }
        BigDecimal open = Money.dec(cn.get("amountResidual"));
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = 0, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .param(creditNoteId).update();
        return open;
    }

    /** Lower an invoice's residual by {@code amount} of a credit and record the allocation. */
    private void applyTo(String creditNoteId, Map<String, Object> inv, BigDecimal amount) {
        String invoiceId = (String) inv.get("id");
        String invStatus = String.valueOf(inv.get("status"));
        BigDecimal newResidual = Money.dec(inv.get("amountResidual")).subtract(amount);
        boolean paid = newResidual.signum() <= 0;
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = ?, \"paymentStatus\" = ?::\"PaymentStatus\","
                        + " status = ?::\"DocumentStatus\", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(newResidual, paid ? "paid" : "partial", paid ? "paid" : invStatus, invoiceId).update();
        jdbc.sql("INSERT INTO \"CreditAllocation\" (id, \"organizationId\", \"creditDocumentId\", \"documentId\", amount,"
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org(), creditNoteId, invoiceId, amount, TenantContext.userId())
                .update();
    }

    /**
     * ACC-4 (B9): a credit note against an invoice cannot credit more of a product than the invoice sold (net of earlier
     * posted credit notes), nor more than the invoice's total.
     */
    /**
     * ADR-S058: the variant a credit-note line restocks when the line names none — the one variant of that product on
     * the credited invoice (null when the invoice has none or several).
     */
    private String invoiceVariant(String invoiceId, String productId) {
        if (invoiceId == null) {
            return null;
        }
        List<String> ids = jdbc.sql("SELECT DISTINCT \"variantId\" FROM \"DocumentLine\" WHERE \"documentId\" = ? AND"
                + " \"productId\" = ? AND \"organizationId\" = ? AND \"variantId\" IS NOT NULL").params(invoiceId, productId,
                        org()).query(String.class).list();
        return ids.size() == 1 ? ids.getFirst() : null;
    }

    /**
     * Phase I2 (D2, audit V-6): a credit-note line for a product with variants that names none restocks the credited
     * invoice's variant; when that invoice holds several variants of the product the line must say which one, instead
     * of the restock landing on the parent product.
     */
    private void assertVariantResolvable(String invoiceId, List<LineInput> lines) {
        if (invoiceId == null || lines == null) {
            return;
        }
        for (LineInput l : lines) {
            if (l.productId() == null || l.productId().isBlank() || (l.variantId() != null && !l.variantId().isBlank())
                    || (l.lineType() != null && !"product".equals(l.lineType()))) {
                continue;
            }
            long variants = jdbc.sql("SELECT count(DISTINCT \"variantId\") FROM \"DocumentLine\" WHERE \"documentId\" = ?"
                    + " AND \"productId\" = ? AND \"organizationId\" = ? AND \"variantId\" IS NOT NULL").params(invoiceId,
                            l.productId(), org()).query(Long.class).single();
            if (variants > 1) {
                String name = jdbc.sql("SELECT name FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(l.productId(), org()).query(String.class).optional().orElse(l.productId());
                throw ApiException.badRequest("Choose a variant for " + name);
            }
        }
    }

    private void assertWithinInvoice(String creditNoteId, String invoiceId, List<Map<String, Object>> lines) {
        if (invoiceId == null) {
            return;
        }
        // Review M-1: compare per product AND variant, in stock (base) units — a box of 24 is 24, not 1.
        Map<String, BigDecimal> invoiced = new HashMap<>();
        for (Map<String, Object> l : builder.lines(invoiceId, false)) {
            if (l.get("productId") != null) {
                invoiced.merge(creditKey(invoiceId, l), baseQty(l), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> credited = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"productId\", l.\"variantId\", l.quantity,"
                + " l.\"uomFactor\" FROM \"DocumentLine\" l JOIN \"Document\" d ON d.id = l.\"documentId\" WHERE"
                + " d.\"organizationId\" = ? AND d.\"documentType\" = 'credit_note' AND d.\"reversedDocumentId\" = ? AND"
                + " d.status::text IN ('posted', 'paid') AND d.id <> ? AND l.\"productId\" IS NOT NULL")
                .params(org(), invoiceId, creditNoteId))) {
            credited.merge(creditKey(invoiceId, r), baseQty(r), BigDecimal::add);
        }
        for (Map<String, Object> l : lines) {
            String productId = (String) l.get("productId");
            if (productId == null) {
                continue;
            }
            String key = creditKey(invoiceId, l);
            BigDecimal already = credited.merge(key, baseQty(l), BigDecimal::add);
            BigDecimal sold = invoiced.getOrDefault(key, Money.ZERO);
            if (already.compareTo(sold) > 0) {
                throw ApiException.badRequest("Credit note credits " + JsNumbers.dec(already) + " of a product the"
                        + " invoice sold " + JsNumbers.dec(sold) + " of");
            }
        }
        Map<String, Object> inv = builder.document(invoiceId);
        if (inv != null) {
            BigDecimal priorTotal = jdbc.sql("SELECT coalesce(sum(\"totalAmount\"), 0) FROM \"Document\" WHERE"
                    + " \"organizationId\" = ? AND \"documentType\" = 'credit_note' AND \"reversedDocumentId\" = ? AND"
                    + " status::text IN ('posted', 'paid') AND id <> ?").params(org(), invoiceId, creditNoteId)
                    .query(BigDecimal.class).single();
            BigDecimal mine = Money.ZERO;
            for (Map<String, Object> l : lines) {
                mine = mine.add(Money.dec(l.get("total")));
            }
            if (priorTotal.add(mine).compareTo(Money.dec(inv.get("totalAmount"))) > 0) {
                throw ApiException.badRequest("Credit notes would credit more than the invoice total of "
                        + JsNumbers.dec(Money.dec(inv.get("totalAmount"))));
            }
        }
    }

    /** Product + variant of a line; a credit line without a variant takes the invoice's only variant (as at post). */
    private String creditKey(String invoiceId, Map<String, Object> line) {
        String productId = (String) line.get("productId");
        String variantId = line.get("variantId") != null ? (String) line.get("variantId")
                : invoiceVariant(invoiceId, productId);
        return productId + "|" + (variantId == null ? "" : variantId);
    }

    /** A line's quantity in stock units (its frozen factor; 1 when it has none). */
    private static BigDecimal baseQty(Map<String, Object> line) {
        BigDecimal q = Money.dec(line.get("quantity"));
        return line.get("uomFactor") == null ? q : q.multiply(Money.dec(line.get("uomFactor")));
    }

    /** Post a credit note; {@code disposition} restock (default) or scrap. */
    @Transactional
    public Map<String, Object> post(String id, String disposition) {
        Map<String, Object> approval = newTx.execute(s -> approvals.checkOrRequestApproval("credit_note", id, Map.of()));
        if (approval != null && Boolean.TRUE.equals(approval.get("needsApproval"))) {
            throw ApiException.forbidden("Credit note posting requires approval. Request ID: " + approval.get("requestId")
                    + ". Please have an authorized person approve it via the approvals endpoint.");
        }
        Map<String, Object> cn = builder.document(id, "credit_note");
        if (cn == null) {
            throw ApiException.notFound("Credit note not found");
        }
        // Review N1 / audit A-1: lock the credited invoice, then the credit note (the invoice cancel locks the invoice
        // first as well), and read both states under the locks: two concurrent posts never both restock and journal,
        // and a credit note never posts against an invoice that was cancelled meanwhile.
        String credited = (String) cn.get("reversedDocumentId");
        if (credited != null) {
            String invoiceStatus = jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?"
                    + " FOR UPDATE").params(credited, org()).query(String.class).optional().orElse(null);
            if ("cancelled".equals(invoiceStatus)) {
                throw ApiException.badRequest("The credited invoice is cancelled; its stock and revenue were already"
                        + " reversed");
            }
        }
        String status = jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(id, org()).query(String.class).optional().orElse(String.valueOf(cn.get("status")));
        if ("posted".equals(status) || "paid".equals(status)) {
            throw ApiException.badRequest("Credit note is already posted");
        }
        if ("cancelled".equals(status)) {
            throw ApiException.badRequest("Credit note is cancelled");
        }
        List<Map<String, Object>> lines = builder.lines(id, false);
        String partnerId = (String) cn.get("partnerId");
        assertWithinInvoice(id, (String) cn.get("reversedDocumentId"), lines);
        Grouped g = builder.groupForPosting(partnerId, lines, "sales", null);
        List<Line> post = new ArrayList<>();
        g.itemLines().forEach(it -> post.add(new Line(it.accountId(), JsNumbers.dec(it.amount()), null, partnerId,
                "Revenue (credit)", null, it.costCenterId(), it.glDimensions())));
        g.taxByAccount().forEach((acc, amt) -> post.add(Line.debit(acc, JsNumbers.dec(amt), "Tax (credit)")));
        BigDecimal total = Money.dec(cn.get("totalAmount"));
        post.add(new Line(g.counterAccount(), null, JsNumbers.dec(total), partnerId,
                "Credit note " + cn.get("documentNumber"), null, null, null));
        Instant issueDate = (Instant) cn.get("issueDate");
        Map<String, Object> entry = posting.post(new PostingRequest("SALES", issueDate, "Credit note " + cn.get("documentNumber"),
                (String) cn.get("currencyId"), Money.dec(cn.get("exchangeRate")), "credit_note", id, null, null, null,
                null, null, post));
        jdbc.sql("UPDATE \"Document\" SET status = 'posted', \"postedAt\" = " + NOW + ", \"postedBy\" = ?,"
                        + " \"journalEntryId\" = ?, \"amountResidual\" = 0, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(TenantContext.userId(), entry.get("id"), id).update();
        String reversed = (String) cn.get("reversedDocumentId");
        BigDecimal applied = Money.ZERO;
        if (reversed != null) {
            Map<String, Object> inv = builder.document(reversed, "sales_invoice");
            String invStatus = inv == null ? null : String.valueOf(inv.get("status"));
            if (inv != null && ("posted".equals(invStatus) || "paid".equals(invStatus))) {
                BigDecimal residual = Money.dec(inv.get("amountResidual"));
                BigDecimal reduce = total.compareTo(residual) > 0 ? residual : total;
                if (reduce.signum() > 0) {
                    applyTo(id, inv, reduce);
                    applied = reduce;
                    realiseFx(cn, inv, reduce, (String) entry.get("id"), g.counterAccount(), partnerId, issueDate);
                }
            }
        }
        // ACC-4 (B9): what the invoice could not absorb stays open on the credit note for later allocation.
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = ? WHERE id = ?").params(total.subtract(applied), id)
                .update();
        if ("restock".equals(disposition)) {
            // Deliberate deviation from Nest (SG-0): a failed restock now fails the whole post, so the books never credit
            // goods that did not come back. Goods that are not coming back are posted with disposition scrap.
            String warehouse = posLocation.resolve(org(), null);
            for (Map<String, Object> ln : lines) {
                String productId = (String) ln.get("productId");
                BigDecimal qty = Money.dec(ln.get("quantity"));
                if (productId == null || qty.signum() <= 0) {
                    continue;
                }
                Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT name, \"trackInventory\", \"productType\"::text"
                        + " AS t FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                        .params(productId, org()));
                if (product != null && Boolean.TRUE.equals(product.get("trackInventory"))
                        && ("stockable".equals(product.get("t")) || "consumable".equals(product.get("t")))) {
                    String variantId = ln.get("variantId") != null ? (String) ln.get("variantId")
                            : invoiceVariant(reversed, productId);
                    // U-6 / M-2: goods the invoice issued come back where they left, at the cost they left at.
                    Map<String, Object> issued = invoiceIssue(reversed, productId, variantId);
                    String location = issued != null ? (String) issued.get("locationId") : warehouse;
                    try {
                        if (location == null) {
                            throw ApiException.badRequest("no stock location is configured");
                        }
                        stock.receiveReturn(StockMove.of(productId, location, qty)
                                .variant(variantId).uom((String) ln.get("uomId"), ln.get("uomFactor") == null ? null
                                        : Money.dec(ln.get("uomFactor")))
                                .unitCost(issued == null || issued.get("unitCost") == null ? null
                                        : Money.dec(issued.get("unitCost")))
                                .reference((String) cn.get("documentNumber")).source("credit_note", id).sourceLine((String) ln.get("id"))
                                .date(issueDate));
                    } catch (ApiException e) {
                        throw ApiException.badRequest("Cannot restock " + product.get("name") + ": " + e.getMessage()
                                + ". Post with disposition scrap if the goods are not coming back.");
                    }
                }
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org());
        payload.put("documentId", id);
        payload.put("documentNumber", cn.get("documentNumber"));
        events.publish("creditnote.issued", payload);
        Map<String, Object> out = builder.document(id);
        out.put("lines", builder.lines(id, false));
        out.put("partner", builder.partner(out.get("partnerId")));
        return out;
    }
}
