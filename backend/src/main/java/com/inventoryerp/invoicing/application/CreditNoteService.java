package com.inventoryerp.invoicing.application;

import static com.inventoryerp.invoicing.application.DocumentBuilderService.NOW;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.documents.domain.Document;
import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Grouped;
import com.inventoryerp.invoicing.application.DocumentBuilderService.HeaderInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
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
    private com.inventoryerp.accounting.application.JournalService journals;

    /** Sales journals (INV-13); optional for plain constructions. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJournals(com.inventoryerp.accounting.application.JournalService journals) {
        this.journals = journals;
    }

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
        // Sales readiness R2 (P1-4): the approval request it writes is reused on retry, so its commit must not keep the key.
        this.newTx.setName(BusinessOutcome.RETRY_SAFE_TX);
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
        realiseFx(cn, inv, reduce, cnEntryId, receivable, partnerId, date, "credit_note_fx:" + cn.get("id"));
    }

    private void realiseFx(Map<String, Object> cn, Map<String, Object> inv, BigDecimal reduce, String cnEntryId,
            String receivable, String partnerId, Instant date, String postingKey) {
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
                + cn.get("documentNumber") + " on " + inv.get("documentNumber"), cur);
        if (!lines.isEmpty()) {
            posting.post(PostingRequest.of("SALES", date, "Realised FX · credit note " + cn.get("documentNumber"),
                    "credit_note_fx", (String) cn.get("id"), postingKey, lines));
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
        if ("cancelled".equals(String.valueOf(d.get("status"))) && reversals != null) {
            Map<String, Object> r = reversals.find("credit_note", id);
            if (r != null) {
                d.put("reversal", r);
            }
        }
        return d;
    }

    /** A document currency as an upper-case code, the organisation's currency when none is set. */
    private String currencyCode(Object currencyId) {
        if (currencyId != null && !currencyId.toString().isBlank()) {
            return currencyId.toString().trim().toUpperCase(java.util.Locale.ROOT);
        }
        return jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org()).query(String.class)
                .optional().orElse("").toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Sales readiness R2b (INV-4): a credit note credits only a posted (or paid) customer invoice of its own customer.
     * Before this it could credit another customer's invoice or a draft, posting a revenue reversal and open credit that
     * could then be refunded.
     */
    private static void assertCreditable(Map<String, Object> invoice, String partnerId) {
        if (invoice == null || !"sales_invoice".equals(invoice.get("documentType"))) {
            throw ApiException.badRequest("The credited document must be a customer invoice");
        }
        if (!String.valueOf(invoice.get("partnerId")).equals(partnerId)) {
            throw ApiException.badRequest("Invoice " + invoice.get("documentNumber") + " belongs to another customer");
        }
        if (!List.of("posted", "paid").contains(String.valueOf(invoice.get("status")))) {
            throw ApiException.badRequest("Invoice " + invoice.get("documentNumber") + " is " + invoice.get("status")
                    + "; only a posted invoice can be credited");
        }
    }

    /**
     * Create a draft credit note owned by {@code source} (ADR-S094 §8.2): {@link SourceRef.CreditFor} for a module
     * credit or one that names its invoice, {@link SourceRef.Manual} for a standalone one.
     */
    @Transactional
    public Map<String, Object> create(CreditNoteInput dto, SourceRef source) {
        builder.assertVariants(dto.lines(), false, true);
        assertVariantResolvable(dto.reversedDocumentId(), dto.lines());
        List<LineInput> lines = withInvoiceUnits(dto.reversedDocumentId(), dto.lines());
        // A credit note against an invoice is in that invoice's currency and rate; another currency would settle the
        // invoice by the raw number (review 2026-11-15, P-7), so it is refused.
        Map<String, Object> reversed = dto.reversedDocumentId() == null ? null
                : SqlRows.one(jdbc.sql("SELECT \"currencyId\", \"exchangeRate\", \"documentType\"::text AS \"documentType\","
                        + " \"partnerId\", status::text AS status, \"documentNumber\" FROM \"Document\" WHERE id = ? AND"
                        + " \"organizationId\" = ?").params(dto.reversedDocumentId(), org()));
        if (dto.reversedDocumentId() != null) {
            assertCreditable(reversed, dto.partnerId());
        }
        if (reversed != null && dto.currencyId() != null
                && !currencyCode(dto.currencyId()).equals(currencyCode(reversed.get("currencyId")))) {
            throw ApiException.badRequest("A credit note must be in the credited invoice's currency ("
                    + currencyCode(reversed.get("currencyId")) + ")");
        }
        // an explicit (same) currency takes the table rate, as before; otherwise the invoice's currency and rate
        boolean inherit = reversed != null && dto.currencyId() == null;
        String currencyId = inherit ? (String) reversed.get("currencyId") : dto.currencyId();
        BigDecimal rate = inherit ? Money.dec(reversed.get("exchangeRate")) : null;
        return builder.createDocument("credit_note", source, new HeaderInput(dto.partnerId(), dto.issueDate(), null,
                null, null,
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
     * R1 (P1-2, owner-approved 2026-11-23): where the credited invoice's goods left stock. An invoice that issued its
     * own stock → its {@code sales_invoice} rows; an invoice whose stock a cash sale issued ({@code stockIssuedBy},
     * source {@code cash_sale}) → that sale's {@code cash_sale} rows; an invoice delivered by Fleet → the
     * {@code fleet_trip} rows of the loads of the delivery orders that carried its goods (from the invoice, its sales
     * order, or allocated to it). Null when the credit note credits no invoice.
     */
    private StockReversalService.IssueSource invoiceIssueSource(String invoiceId) {
        if (invoiceId == null) {
            return null;
        }
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT \"sourceType\", \"sourceId\", data->>'stockIssuedBy' AS"
                + " \"stockIssuedBy\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                .params(invoiceId, org()));
        if (doc == null) {
            return null;
        }
        if (doc.get("stockIssuedBy") == null) {
            return StockReversalService.IssueSource.of("sales_invoice", invoiceId);
        }
        if ("cash_sale".equals(doc.get("sourceType")) && doc.get("sourceId") != null) {
            return StockReversalService.IssueSource.of("cash_sale", (String) doc.get("sourceId"));
        }
        String salesOrder = "sales_order".equals(doc.get("sourceType")) ? (String) doc.get("sourceId") : null;
        List<String> loads = jdbc.sql("SELECT DISTINCT l.id FROM \"FleetLoad\" l JOIN \"FleetDeliveryOrder\" o ON"
                + " o.id = l.\"deliveryOrderId\" WHERE l.\"organizationId\" = ? AND o.\"organizationId\" = ? AND"
                + " ((o.\"sourceType\" = 'invoice' AND o.\"sourceId\" = ?) OR o.\"goodsInvoiceId\" = ?"
                + " OR (o.\"sourceType\" = 'sales_order' AND o.\"sourceId\" = ?) OR o.id IN (SELECT \"deliveryOrderId\""
                + " FROM \"FleetDeliveryInvoiceLine\" WHERE \"documentId\" = ? AND \"organizationId\" = ?))")
                .params(org(), org(), invoiceId, invoiceId, salesOrder, invoiceId, org()).query(String.class).list();
        return StockReversalService.IssueSource.of("fleet_trip", loads);
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
        if (!currencyCode(cn.get("currencyId")).equals(currencyCode(inv.get("currencyId")))) {
            throw ApiException.badRequest("Credit note and invoice are in different currencies");
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
        // FX-2 F: a foreign credit settling an invoice carried at another rate realises the difference
        String cnEntry = (String) cn.get("journalEntryId");
        if (cnEntry != null && !fxRates.currencyOf(cn).equals(fxRates.base())) {
            String partnerId = String.valueOf(cn.get("partnerId"));
            String receivable = jdbc.sql("SELECT l.\"accountId\" FROM \"JournalLine\" l JOIN \"Account\" a ON a.id ="
                    + " l.\"accountId\" JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE l.\"journalEntryId\" = ?"
                    + " AND l.\"partnerId\" = ? AND c.key = 'receivable' ORDER BY l.\"lineNumber\" LIMIT 1")
                    .params(cnEntry, partnerId).query(String.class).optional().orElse(null);
            if (receivable != null) {
                realiseFx(cn, inv, amount, cnEntry, receivable, partnerId, Instant.now(), "credit_note_fx:"
                        + creditNoteId + ":allocation:" + java.util.UUID.randomUUID());
            }
        }
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
        // FX-3: a foreign credit is taken in its own currency; the caller refunds it through
        // PaymentService#createForeignCreditRefund at the credit's carrying rate (see foreignCredit).
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = 0, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .param(creditNoteId).update();
        return open;
    }

    /** A foreign credit's currency and carrying rate. */
    public record ForeignCredit(String currency, BigDecimal carryingRate) {}

    /**
     * FX-3: the currency and carrying rate (the rate its journal entry was posted at) of a foreign-currency credit note,
     * or {@code null} for a functional-currency one.
     */
    @Transactional(readOnly = true)
    public ForeignCredit foreignCredit(String creditNoteId) {
        Map<String, Object> cn = builder.document(creditNoteId, "credit_note");
        if (cn == null) {
            throw ApiException.notFound("Credit note not found");
        }
        String currency = fxRates.currencyOf(cn);
        if (currency.equals(fxRates.base())) {
            return null;
        }
        BigDecimal rate = cn.get("journalEntryId") == null ? null : fxRates.entryRate((String) cn.get("journalEntryId"));
        return new ForeignCredit(currency, rate != null && rate.signum() > 0 ? rate : fxRates.documentRate(cn));
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
            // Sales readiness INV-14: both rows in id order, the order allocate and reverse use, so a post racing an
            // allocation of the same pair cannot deadlock.
            jdbc.sql("SELECT id FROM \"Document\" WHERE id IN (?, ?) AND \"organizationId\" = ? ORDER BY id FOR UPDATE")
                    .params(credited, id, org()).query().listOfRows();
            Map<String, Object> invoice = SqlRows.one(jdbc.sql("SELECT \"documentType\"::text AS \"documentType\","
                    + " \"partnerId\", status::text AS status, \"documentNumber\" FROM \"Document\" WHERE id = ? AND"
                    + " \"organizationId\" = ? FOR UPDATE").params(credited, org()));
            if (invoice != null && "cancelled".equals(invoice.get("status"))) {
                throw ApiException.badRequest("The credited invoice is cancelled; its stock and revenue were already"
                        + " reversed");
            }
            assertCreditable(invoice, (String) cn.get("partnerId"));
        }
        String status = jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(id, org()).query(String.class).optional().orElse(String.valueOf(cn.get("status")));
        if ("posted".equals(status) || "paid".equals(status)) {
            throw ApiException.badRequest("Credit note is already posted");
        }
        // Sales readiness INV-11: the legal number is taken now, in this transaction (gap-free).
        cn.put("documentNumber", builder.numberAtPost(id));
        if ("cancelled".equals(status)) {
            throw ApiException.badRequest("Credit note is cancelled");
        }
        List<Map<String, Object>> lines = builder.lines(id, false);
        String partnerId = (String) cn.get("partnerId");
        assertWithinInvoice(id, (String) cn.get("reversedDocumentId"), lines);
        // ADR-S094 C1b (AD-15): a credit note on a down-payment invoice takes back the advance it recorded — on the
        // deposit account only — and reduces the deposit (after the entry, below), refused once the deposit was consumed.
        boolean downPayment = credited != null && Boolean.TRUE.equals(jdbc.sql("SELECT data->>'downPaymentFor' IS NOT"
                + " NULL FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(credited, org())
                .query(Boolean.class).optional().orElse(false));
        BigDecimal depositCredit = Money.ZERO;
        if (downPayment) {
            String depositAccount = deposits.getObject().depositAccount();
            String dpTax = jdbc.sql("SELECT \"taxId\" FROM \"DocumentLine\" WHERE \"documentId\" = ? AND \"accountId\" = ?"
                    + " ORDER BY \"lineNumber\" LIMIT 1").params(credited, depositAccount).query(String.class).optional()
                    .orElse(null);
            for (Map<String, Object> ln : lines) {
                if (ln.get("productId") != null || !depositAccount.equals(ln.get("accountId"))) {
                    throw ApiException.badRequest("A credit note on a down-payment invoice credits the customer deposit"
                            + " account only");
                }
                if (!java.util.Objects.equals(dpTax, ln.get("taxId"))) {
                    throw ApiException.badRequest("A credit note on a down-payment invoice takes the tax of the down"
                            + " payment");
                }
                depositCredit = depositCredit.add(Money.dec(ln.get("subtotal")));
            }
        }
        Grouped g = builder.groupForPosting(partnerId, lines, "sales", null);
        List<Line> post = new ArrayList<>();
        g.itemLines().forEach(it -> post.add(new Line(it.accountId(), JsNumbers.dec(it.amount()), null, partnerId,
                "Revenue (credit)", null, it.costCenterId(), it.glDimensions())));
        g.taxByAccount().forEach((acc, amt) -> post.add(Line.debit(acc, JsNumbers.dec(amt), "Tax (credit)")));
        BigDecimal total = Money.dec(cn.get("totalAmount"));
        post.add(new Line(g.counterAccount(), null, JsNumbers.dec(total), partnerId,
                "Credit note " + cn.get("documentNumber"), null, null, null));
        Instant issueDate = (Instant) cn.get("issueDate");
        // Sales readiness INV-13: a credit note posts to the sales journal of the invoice it credits (else the org's
        // default sales journal), not a hard-coded "SALES" code.
        String creditedJournal = cn.get("reversedDocumentId") == null ? null : jdbc.sql("SELECT \"invoicingJournalId\""
                + " FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(cn.get("reversedDocumentId"), org())
                .query(String.class).optional().orElse(null);
        String journalCode = journals == null ? "SALES" : journals.resolveSalesJournal(creditedJournal).getCode();
        Map<String, Object> entry = posting.post(new PostingRequest(journalCode, issueDate, "Credit note " + cn.get("documentNumber"),
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
        if (downPayment) {
            deposits.getObject().consume(credited, id, depositCredit, "credit_note");
        }
        if ("restock".equals(disposition)) {
            // Deliberate deviation from Nest (SG-0): a failed restock now fails the whole post, so the books never credit
            // goods that did not come back. Goods that are not coming back are posted with disposition scrap.
            // R1 (P1-2): the goods come back against the credited invoice's own issue (lot, serial, location, cost);
            // with no issue to return against the restock is refused instead of guessing a location and a cost.
            StockReversalService.IssueSource source = invoiceIssueSource(reversed);
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
                    // U-6 / M-2 / R1: goods the invoice issued come back where they left, on the lot and serial they
                    // left on, at the cost they left at.
                    try {
                        if (source == null) {
                            throw ApiException.badRequest("the credit note credits no invoice whose stock issue it can"
                                    + " return against");
                        }
                        stockReturns.returnFromSource(new StockReversalService.ReturnFromSource(source,
                                List.of(new StockReversalService.ReturnLine(productId, variantId, qty,
                                        (String) ln.get("uomId"), ln.get("uomFactor") == null ? null
                                                : Money.dec(ln.get("uomFactor")), null, null, (String) ln.get("id"))),
                                "credit_note", id, (String) cn.get("documentNumber"), null, issueDate, null, true,
                                null));
                    } catch (ApiException e) {
                        throw ApiException.badRequest("Cannot restock " + product.get("name") + ": " + e.getMessage()
                                + ". Post with disposition scrap if the goods are not coming back.");
                    }
                }
            }
        }
        if (postGuards != null) {
            // ADR-S094 C4b: what the credit settles outside (its fiscal credit note) is queued in this transaction.
            postGuards.orderedStream().forEach(guard -> guard.afterCreditNotePost(id));
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org());
        payload.put("documentId", id);
        payload.put("documentNumber", cn.get("documentNumber"));
        events.publishOnCommit("creditnote.issued", payload);
        Map<String, Object> out = builder.document(id);
        out.put("lines", builder.lines(id, false));
        out.put("partner", builder.partner(out.get("partnerId")));
        return out;
    }

    // ------------------------------------------------------------------ reversal

    private StockReversalService stockReturns;

    /** ADR-S094 C1b: resolved lazily (the deposit service depends on payments). */
    private org.springframework.beans.factory.ObjectProvider<CustomerDepositService> deposits;

    @org.springframework.beans.factory.annotation.Autowired
    void setDeposits(org.springframework.beans.factory.ObjectProvider<CustomerDepositService> deposits) {
        this.deposits = deposits;
    }

    /** Resolved at posting time, so the guards (which depend on modules that use this service) form no bean cycle. */
    private org.springframework.beans.factory.ObjectProvider<InvoicePostGuard> postGuards;

    @org.springframework.beans.factory.annotation.Autowired
    void setPostGuards(org.springframework.beans.factory.ObjectProvider<InvoicePostGuard> postGuards) {
        this.postGuards = postGuards;
    }

    /** R1: restocks are posted against the credited invoice's original issue. */
    @org.springframework.beans.factory.annotation.Autowired
    void setStockReturns(StockReversalService stockReturns) {
        this.stockReturns = stockReturns;
    }

    private com.inventoryerp.accounting.application.DocumentReversalService reversals;

    @org.springframework.beans.factory.annotation.Autowired
    void setReversals(com.inventoryerp.accounting.application.DocumentReversalService reversals) {
        this.reversals = reversals;
    }

    private static final BigDecimal CENT = new BigDecimal("0.005");

    /**
     * Credit-note document reversal (owner decision 2026-11-17; a posted credit note — a sales write-off included —
     * had no reversal but the generic journal one). With a reason, behind the {@code credit_note_reverse} approval gate,
     * the {@code credit_note:reverse} permission and the {@code document_reversal} separation-of-duties pair, in one
     * transaction under row locks on the credit note and every invoice it was applied to (id order, as
     * {@link #allocate} locks):
     * <ul>
     *   <li>refused while credit was paid out: a refund payment still posted (void it first), a payment applied to the
     *       note, or a refunded remainder that no voided refund accounts for;</li>
     *   <li>GL: its journal entry and realised-FX entries ({@code credit_note_fx}) are mirrored by
     *       {@link PostingService#reverse} (an entry already reversed is left alone);</li>
     *   <li>allocations: each {@code CreditAllocation} is unwound — the invoice's residual and payment status are
     *       restored (paid → posted) and the row is removed, kept in the {@code DocumentReversal} record;</li>
     *   <li>stock: goods the note restocked are issued back out (source {@code credit_note_cancel}) at the current cost
     *       (Dr COGS / Cr stock); refused when they are no longer on hand;</li>
     *   <li>the note becomes {@code cancelled} with residual 0, cancelledAt / cancelledBy, and a {@code DocumentReversal}
     *       row (reason, entries, unwound allocations).</li>
     * </ul>
     * A replay on a cancelled note returns it unchanged; a concurrent second call waits on the lock and then replays.
     */
    @Transactional
    public Map<String, Object> reverse(String id, String reasonIn) {
        Map<String, Object> cn = builder.document(id, "credit_note");
        if (cn == null) {
            throw ApiException.notFound("Credit note not found");
        }
        if ("cancelled".equals(String.valueOf(cn.get("status")))) {
            return reversedView(id);
        }
        String reason = reversals.requireReason(reasonIn, "reverse a credit note");
        String number = (String) cn.get("documentNumber");
        reversals.requireApproval("credit_note_reverse", id, "credit note " + number, Money.dec(cn.get("totalAmount")));
        reversals.assertSeparated("Document", id, java.util.Arrays.asList((String) cn.get("createdBy"),
                (String) cn.get("postedBy")));

        List<String> lockIds = new ArrayList<>(jdbc.sql("SELECT DISTINCT \"documentId\" FROM \"CreditAllocation\" WHERE"
                + " \"creditDocumentId\" = ? AND \"organizationId\" = ?").params(id, org()).query(String.class).list());
        lockIds.add(id);
        jdbc.sql("SELECT id FROM \"Document\" WHERE id = ANY(?::text[]) AND \"organizationId\" = ? ORDER BY id FOR UPDATE")
                .params(lockIds.toArray(String[]::new), org()).query().listOfRows();
        cn = builder.document(id, "credit_note");
        String status = String.valueOf(cn.get("status"));
        if ("cancelled".equals(status)) {
            return reversedView(id);
        }
        if (!"posted".equals(status) && !"paid".equals(status)) {
            throw ApiException.badRequest("Only a posted credit note can be reversed (credit note " + number + " is "
                    + status + ")");
        }

        List<Map<String, Object>> allocations = SqlRows.list(jdbc.sql("SELECT a.id, a.\"documentId\", a.amount,"
                + " a.\"createdAt\", d.\"documentNumber\", d.status::text AS \"documentStatus\" FROM \"CreditAllocation\" a"
                + " JOIN \"Document\" d ON d.id = a.\"documentId\" WHERE a.\"creditDocumentId\" = ? AND"
                + " a.\"organizationId\" = ? ORDER BY a.\"createdAt\", a.id").params(id, org()));
        assertCreditNotPaidOut(cn, allocations);

        // GL: the note's own entry and its realised FX, mirrored (never twice: only posted entries).
        String note = "Reversal of credit note " + number + ": " + reason;
        List<String> entries = new ArrayList<>();
        if (cn.get("journalEntryId") != null) {
            entries.add((String) cn.get("journalEntryId"));
        }
        entries.addAll(jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'credit_note_fx' AND \"sourceId\" = ? ORDER BY \"postedAt\", id").params(org(), id)
                .query(String.class).list());
        for (String entryId : entries) {
            String entryStatus = jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(entryId, org()).query(String.class).optional().orElse(null);
            if ("posted".equals(entryStatus)) {
                posting.reverse(entryId, null, note);
            }
        }

        // Sub-ledger: give every applied credit back to its invoice.
        List<Map<String, Object>> unwound = new ArrayList<>();
        for (Map<String, Object> a : allocations) {
            BigDecimal amount = Money.dec(a.get("amount"));
            Map<String, Object> inv = SqlRows.one(jdbc.sql("SELECT id, \"totalAmount\", \"withholdingAmount\","
                    + " \"amountResidual\", status::text AS status FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(a.get("documentId"), org()));
            BigDecimal residual = Money.dec(inv.get("amountResidual")).add(amount);
            BigDecimal payable = Money.dec(inv.get("totalAmount")).subtract(Money.dec(inv.get("withholdingAmount")));
            String paymentStatus = residual.signum() <= 0 ? "paid" : residual.compareTo(payable) < 0 ? "partial"
                    : "not_paid";
            String invStatus = "paid".equals(inv.get("status")) && residual.signum() > 0 ? "posted"
                    : String.valueOf(inv.get("status"));
            jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = ?, \"paymentStatus\" = ?::\"PaymentStatus\", status ="
                            + " ?::\"DocumentStatus\", \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(residual, paymentStatus, invStatus, inv.get("id")).update();
            jdbc.sql("DELETE FROM \"CreditAllocation\" WHERE id = ? AND \"organizationId\" = ?").params(a.get("id"), org()).update();
            unwound.add(Obj.of("kind", "credit_allocation", "creditAllocationId", a.get("id"), "documentId",
                    a.get("documentId"), "documentNumber", a.get("documentNumber"), "amount", JsNumbers.dec(amount),
                    "allocatedAt", a.get("createdAt") == null ? null : String.valueOf(a.get("createdAt"))));
        }

        takeBackRestock(id, number, (Instant) cn.get("issueDate"));

        jdbc.sql("UPDATE \"Document\" SET status = 'cancelled', \"amountResidual\" = 0, \"cancelledAt\" = " + NOW + ","
                + " \"cancelledBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?").params(TenantContext.userId(), id)
                .update();
        // ADR-S094 C1b: the down-payment deposit this note reduced is open again.
        deposits.getObject().releaseConsumption(id);
        reversals.record(new com.inventoryerp.accounting.application.DocumentReversalService.Recording("credit_note", id,
                number, (String) cn.get("partnerId"), reason, "Document", List.of("credit_note", "credit_note_fx"),
                List.of("credit_note_cancel"), unwound));
        return reversedView(id);
    }

    /**
     * Credit that left the note other than through its allocations blocks the reversal: payments applied to the note,
     * or a refunded remainder (total − residual − allocations) unless every refund payment of the note is voided.
     */
    private void assertCreditNotPaidOut(Map<String, Object> cn, List<Map<String, Object>> allocations) {
        String id = (String) cn.get("id");
        String number = (String) cn.get("documentNumber");
        List<Map<String, Object>> paid = reversals.appliedPayments(id);
        if (!paid.isEmpty()) {
            throw ApiException.badRequest("Credit note " + number + " cannot be reversed while payments are applied to"
                    + " it: " + com.inventoryerp.accounting.application.DocumentReversalService.describePayments(paid)
                    + ". Void them first.");
        }
        BigDecimal allocated = Money.ZERO;
        for (Map<String, Object> a : allocations) {
            allocated = allocated.add(Money.dec(a.get("amount")));
        }
        BigDecimal refunded = Money.dec(cn.get("totalAmount")).subtract(Money.dec(cn.get("amountResidual")))
                .subtract(allocated);
        if (refunded.compareTo(CENT) <= 0) {
            return;
        }
        List<Map<String, Object>> refunds = SqlRows.list(jdbc.sql("SELECT \"paymentNumber\", status::text AS status FROM"
                + " \"Payment\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND direction::text = 'outbound' AND"
                + " reference = ? ORDER BY \"paymentNumber\"").params(org(), cn.get("partnerId"),
                        "Refund of credit note " + number));
        List<String> live = refunds.stream().filter(r -> !"cancelled".equals(r.get("status")))
                .map(r -> String.valueOf(r.get("paymentNumber"))).toList();
        if (refunds.isEmpty() || !live.isEmpty()) {
            throw ApiException.badRequest("Credit note " + number + " cannot be reversed: " + JsNumbers.dec(refunded)
                    + " of its credit was refunded to the customer" + (live.isEmpty() ? ""
                            : " (refund payment " + String.join(", ", live) + ")") + ". Void the refund payment first.");
        }
    }

    /** Issue back out the goods the note restocked (once per note), refusing when they are no longer on hand. */
    private void takeBackRestock(String id, String number, Instant issueDate) {
        long done = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'credit_note_cancel' AND"
                + " \"referenceId\" = ? AND \"organizationId\" = ?").params(id, org()).query(Long.class).single();
        if (done > 0) {
            return;
        }
        // R1: each restock row goes back out from the lot / serial it restored, at the cost it came back at (the
        // carried cost of an unlotted AVCO quant), naming the row it reverses so the invoice's issue is returnable
        // again.
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.id, l.\"productId\", l.\"variantId\","
                + " l.\"locationId\", l.\"batchId\", l.\"serialId\", l.\"unitCost\", l.\"quantityChange\" AS qty,"
                + " b.\"batchNumber\", s.\"serialNumber\" FROM \"InventoryLedger\" l LEFT JOIN \"InventoryBatch\" b ON"
                + " b.id = l.\"batchId\" LEFT JOIN \"InventorySerial\" s ON s.id = l.\"serialId\""
                + " WHERE l.\"referenceType\" ="
                + " 'credit_note' AND l.\"referenceId\" = ? AND l.\"organizationId\" = ? AND l.\"quantityChange\" > 0"
                + " ORDER BY l.\"productId\", l.\"variantId\" NULLS FIRST, l.\"locationId\", l.\"createdAt\", l.id")
                .params(id, org()))) {
            BigDecimal qty = Money.dec(r.get("qty"));
            if (qty.signum() <= 0) {
                continue;
            }
            try {
                StockMove m = StockMove.of((String) r.get("productId"), (String) r.get("locationId"), qty)
                        .variant((String) r.get("variantId")).requireAvailable(true)
                        .source("credit_note_cancel", id).reference(number).date(posting.reversalDate(issueDate))
                        .notes("Restocked goods taken back on reversal of credit note " + number)
                        .reverses((String) r.get("id"))
                        .pin(r.get("batchId") == null ? null : List.of((String) r.get("batchId")),
                                r.get("serialId") == null ? null : List.of((String) r.get("serialId")),
                                Money.dec(r.get("unitCost")));
                if (r.get("batchNumber") != null) {
                    m.batch((String) r.get("batchNumber")).strategy("MANUAL");
                }
                if (r.get("serialNumber") != null) {
                    m.serials(List.of((String) r.get("serialNumber")));
                }
                stock.issue(m);
            } catch (ApiException e) {
                String name = jdbc.sql("SELECT name FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?").params(r.get("productId"), org())
                        .query(String.class).optional().orElse(String.valueOf(r.get("productId")));
                throw ApiException.badRequest("Cannot reverse credit note " + number + ": the " + JsNumbers.dec(qty)
                        + " of " + name + " it restocked cannot be taken back (" + e.getMessage() + ")");
            }
        }
    }

    /** The cancelled note with lines, partner and its {@code reversal} record. */
    private Map<String, Object> reversedView(String id) {
        Map<String, Object> out = builder.document(id);
        out.put("lines", builder.lines(id, false));
        out.put("partner", builder.partner(out.get("partnerId")));
        Map<String, Object> r = reversals.find("credit_note", id);
        if (r != null) {
            out.put("reversal", r);
        }
        return out;
    }
}
