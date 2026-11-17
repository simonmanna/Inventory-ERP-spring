package com.inventoryerp.procurement.application;

import com.inventoryerp.core.application.ProductVariantService;
import com.inventoryerp.core.application.UomConversionService.UnitRole;
import com.inventoryerp.core.application.UomConversionService.LineUnit;
import com.inventoryerp.core.application.UomConversionService;
import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.num;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.procurement.domain.DebitNote;
import com.inventoryerp.procurement.domain.DebitNoteLine;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Debit notes (port of {@code debit-notes.service.ts}): outbound (to a customer, Dr AR / Cr revenue) and inbound
 * (from a supplier). An inbound {@code returned_goods} note is a return-to-vendor: stock leaves at cost and the note
 * owns the balanced GL (Dr AP / Cr Stock Valuation ± return variance).
 */
@Service
public class DebitNotesService {

    /** {@code CreateDebitNoteDto}. */
    public record CreateInput(String direction, String partnerId, String documentId, String reason, String reasonNote,
            String issueDate, String currencyCode, BigDecimal exchangeRate, String notes, String locationId,
            List<LineInput> lines) {}

    /** {@code DebitNoteLineDto}. */
    public record LineInput(String productId, String description, BigDecimal quantity, BigDecimal unitPrice,
            String taxId, String notes, String uomId, String variantId) {

        public LineInput(String productId, String description, BigDecimal quantity, BigDecimal unitPrice,
                String taxId, String notes, String uomId) {
            this(productId, description, quantity, unitPrice, taxId, notes, uomId, null);
        }

        public LineInput(String productId, String description, BigDecimal quantity, BigDecimal unitPrice,
                String taxId, String notes) {
            this(productId, description, quantity, unitPrice, taxId, notes, null, null);
        }
    }

    private final JdbcClient jdbc;
    private final UomConversionService units;
    private final SequenceService sequence;
    private final EventBus events;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final StockService stock;
    private final TransactionTemplate tx;
    private final ProductVariantService variants;

    public DebitNotesService(JdbcClient jdbc, SequenceService sequence, EventBus events, AuditService audit,
            ApprovalsService approvals, PostingService posting, AccountDeterminationService determination,
            StockService stock, PlatformTransactionManager txm, UomConversionService units,
            ProductVariantService variants) {
        this.jdbc = jdbc;
        this.variants = variants;
        this.units = units;
        this.sequence = sequence;
        this.events = events;
        this.audit = audit;
        this.approvals = approvals;
        this.posting = posting;
        this.determination = determination;
        this.stock = stock;
        this.tx = new TransactionTemplate(txm);
    }

    /** Draft note with lines; tax is computed at post time. */
    public Map<String, Object> create(CreateInput input) {
        String orgId = org();
        if (input.lines() == null || input.lines().isEmpty()) {
            throw ApiException.badRequest("At least one line required");
        }
        long partner = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(input.partnerId(), orgId).query(Long.class).single();
        if (partner == 0) {
            throw ApiException.notFound("Partner not found");
        }
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        String prefix = "outbound".equals(input.direction()) ? "DBN-OUT-" + year + "-" : "DBN-IN-" + year + "-";
        String noteNumber = sequence.next("debitnote:" + year, prefix, 5);
        BigDecimal subtotal = Money.ZERO;
        for (LineInput ln : input.lines()) {
            subtotal = subtotal.add(ln.quantity().multiply(ln.unitPrice()));
        }
        BigDecimal sub = subtotal;
        // Phase I2 (D2, audit V-8): a returned variant must be the product's own (archived allowed: the stock exists).
        for (LineInput ln : input.lines()) {
            variants.assertOwn(ln.productId(), ln.variantId());
        }
        // Returned quantities are in the line unit (default: the product's purchase unit), frozen with its factor.
        List<LineUnit> lineUnits = new ArrayList<>();
        for (LineInput ln : input.lines()) {
            lineUnits.add(units.resolveLineUnit(ln.productId(), ln.uomId(), UnitRole.PURCHASE));
        }
        Map<String, Object> note = tx.execute(st -> {
            String id = UUID.randomUUID().toString();
            Instant issueDate = ProcurementSql.date(input.issueDate());
            List<Object> params = new ArrayList<>(List.of(id, orgId, noteNumber, input.direction(), input.partnerId()));
            params.add(input.documentId());
            params.add(input.reason());
            params.add(input.reasonNote());
            if (issueDate != null) {
                params.add(SqlRows.ts(issueDate));
            }
            params.add(input.currencyCode() != null ? input.currencyCode() : "USD");
            params.add(input.exchangeRate() != null ? input.exchangeRate() : BigDecimal.ONE);
            params.add(sub);
            params.add(sub);
            params.add(input.notes());
            params.add(input.locationId() != null && !input.locationId().isEmpty()
                    ? JsonNodeText.of("locationId", input.locationId()) : "{}");
            params.add(user());
            Map<String, Object> created = SqlRows.one(DebitNote.class, jdbc.sql("INSERT INTO \"DebitNote\" (id,"
                            + " \"organizationId\", \"noteNumber\", direction, \"partnerId\", \"documentId\", reason,"
                            + " \"reasonNote\", \"issueDate\", \"currencyCode\", \"exchangeRate\", subtotal, \"taxAmount\","
                            + " \"totalAmount\", status, notes, \"customFields\", \"createdBy\", \"createdAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?::\"DebitNoteReason\", ?, " + (issueDate != null ? "?" : NOW)
                            + ", ?, ?, ?, 0, ?, 'draft', ?, ?::jsonb, ?, " + NOW + ", " + NOW + ") RETURNING *")
                    .params(params));
            List<Map<String, Object>> lines = new ArrayList<>();
            int idx = 0;
            for (LineInput ln : input.lines()) {
                BigDecimal lineSub = ln.quantity().multiply(ln.unitPrice());
                LineUnit unit = lineUnits.get(idx);
                lines.add(SqlRows.one(DebitNoteLine.class, jdbc.sql("INSERT INTO \"DebitNoteLine\" (id, \"organizationId\","
                                + " \"debitNoteId\", \"productId\", description, quantity, \"unitPrice\", \"taxId\", subtotal,"
                                + " total, \"lineNumber\", notes, \"uomId\", \"uomFactor\", \"variantId\") VALUES (?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *")
                        .params(UUID.randomUUID().toString(), orgId, id, ln.productId(), ln.description(), ln.quantity(),
                                ln.unitPrice(), ln.taxId(), lineSub, lineSub, ++idx, ln.notes(), unit.uomId(),
                                unit.factor(), ln.variantId() == null || ln.variantId().isBlank() ? null
                                        : ln.variantId())));
            }
            created.put("lines", lines);
            return created;
        });
        audit.record(AuditInput.of("DebitNote", (String) note.get("id"), "create", null, Obj.of("noteNumber", noteNumber,
                "direction", input.direction())));
        events.publish("debit_note.created", Obj.of("organizationId", orgId, "noteId", note.get("id"), "noteNumber",
                noteNumber, "direction", input.direction()));
        return note;
    }

    /** Tiny JSON object writer for the {@code customFields} stash. */
    private static final class JsonNodeText {
        static String of(String key, String value) {
            return "{\"" + key + "\":" + tools.jackson.databind.json.JsonMapper.builder().build()
                    .writeValueAsString(value) + "}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadForPost(String id, String orgId) {
        Map<String, Object> note = SqlRows.one(DebitNote.class, jdbc.sql("SELECT * FROM \"DebitNote\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        if (note == null) {
            return null;
        }
        note.put("lines", lines(id));
        note.put("partner", SqlRows.one(Partner.class, jdbc.sql("SELECT * FROM \"Partner\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(note.get("partnerId"), orgId)));
        return note;
    }

    private List<Map<String, Object>> lines(Object noteId) {
        return SqlRows.list(DebitNoteLine.class, jdbc.sql("SELECT * FROM \"DebitNoteLine\" WHERE \"debitNoteId\" = ?")
                .param(noteId));
    }

    private static String partnerCol(Map<String, Object> note, String col) {
        @SuppressWarnings("unchecked")
        Map<String, Object> partner = (Map<String, Object>) note.get("partner");
        return partner == null ? null : (String) partner.get(col);
    }

    /** Post behind the debit_note approval gate; returned goods from a supplier take the RTV path. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String id) {
        String orgId = org();
        Map<String, Object> note = loadForPost(id, orgId);
        if (note == null) {
            throw ApiException.notFound("Debit note not found");
        }
        if (!"draft".equals(note.get("status"))) {
            throw ApiException.badRequest("Cannot post debit note in status " + note.get("status"));
        }
        List<Map<String, Object>> noteLines = (List<Map<String, Object>>) note.get("lines");
        BigDecimal amount = Money.ZERO;
        for (Map<String, Object> ln : noteLines) {
            amount = amount.add(Money.dec(ln.get("subtotal")));
        }
        Map<String, Object> gate = approvals.checkOrRequestApproval("debit_note", id, Obj.of("amount",
                JsNumbers.js(amount), "direction", note.get("direction"), "reason", note.get("reason")));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            throw ApiException.badRequest("Approval required before posting debit note. Pending approval request "
                    + gate.get("requestId") + ".");
        }
        boolean outbound = "outbound".equals(note.get("direction"));
        if (!outbound && "inbound".equals(note.get("direction")) && "returned_goods".equals(note.get("reason"))) {
            return postSupplierReturn(note);
        }

        String journalCode = outbound ? "SALES" : "PURCH";
        String counterAccount = outbound ? determination.receivableAccount(partnerCol(note, "receivableAccountId"))
                : determination.payableAccount(partnerCol(note, "payableAccountId"));
        List<Line> postingLines = new ArrayList<>();
        // ACC-4 (B8): each line's tax (from its taxId) is part of the note: input VAT on a supplier's note, output VAT
        // on a note to a customer; the AR / AP leg carries subtotal + tax.
        Map<String, BigDecimal> taxByAccount = new java.util.LinkedHashMap<>();
        BigDecimal noteTax = Money.ZERO;
        for (Map<String, Object> ln : noteLines) {
            BigDecimal tax = lineTax(ln, outbound, taxByAccount);
            noteTax = noteTax.add(tax);
            // Dr AR (outbound) / Cr AP (inbound) per line, tagged with the partner so the sub-ledger and ageing see it.
            String v = JsNumbers.dec(Money.dec(ln.get("subtotal")).add(tax));
            String partnerId = (String) note.get("partnerId");
            postingLines.add(outbound ? new Line(counterAccount, v, null, partnerId, null, null, null, null)
                    : new Line(counterAccount, null, v, partnerId, null, null, null, null));
        }
        taxByAccount.forEach((acc, amt) -> postingLines.add(outbound ? Line.credit(acc, JsNumbers.dec(amt), "Output tax")
                : Line.debit(acc, JsNumbers.dec(amt), "Input tax")));
        BigDecimal noteTotal = Money.dec(note.get("subtotal")).add(noteTax);
        BigDecimal taxTotal = noteTax;
        for (Map<String, Object> ln : noteLines) {
            String accountId = null;
            if (ln.get("productId") != null) {
                Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT c.\"incomeAccountId\", c.\"expenseAccountId\""
                        + " FROM \"Product\" p LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\" WHERE p.id = ?"
                        + " AND p.\"organizationId\" = ?").params(ln.get("productId"), orgId));
                if (product != null) {
                    if (outbound && product.get("incomeAccountId") != null) {
                        accountId = (String) product.get("incomeAccountId");
                    } else if (!outbound && "inbound".equals(note.get("direction"))
                            && product.get("expenseAccountId") != null) {
                        accountId = (String) product.get("expenseAccountId");
                    }
                }
            }
            if (accountId == null) {
                accountId = outbound ? determination.mapped("sales_revenue") : determination.mapped("default_expense");
            }
            // B1 (post-parity): Nest credited the expense leg too, so an inbound note never balanced. A supplier's
            // debit note raises what we owe: Dr expense / Cr AP.
            Number amt = JsNumbers.js(Money.dec(ln.get("subtotal")));
            postingLines.add(outbound ? Line.credit(accountId, amt, null) : Line.debit(accountId, amt, null));
            // Control-account policy (V518, "close now"): the income / expense side is configuration (product category,
            // mapping); on a designated AR / AP account it would move AR / AP with no open item behind it.
            com.inventoryerp.accounting.application.ControlAccountGuards.assertNotControl(jdbc, orgId,
                    "Debit note line account (product category or default mapping)", accountId);
        }
        // The status claim, the journal and the flip land together; the claim serialises concurrent posts (P0-3).
        Map<String, Object> updated = tx.execute(st -> {
            Map<String, Object> row = markPosted(id, orgId);
            posting.post(PostingRequest.of(journalCode, (Instant) note.get("issueDate"),
                    (outbound ? "Debit note to customer" : "Debit note from supplier") + " " + note.get("noteNumber"),
                    "debit_note", id, "debit_note:" + id, postingLines));
            jdbc.sql("UPDATE \"DebitNote\" SET \"taxAmount\" = ?, \"totalAmount\" = ? WHERE id = ?")
                    .params(taxTotal, noteTotal, id).update();
            row.put("taxAmount", taxTotal);
            row.put("totalAmount", noteTotal);
            BigDecimal raised = raiseReferencedDocument((String) note.get("documentId"), outbound, noteTotal);
            recordDocumentAdjustment(id, raised);
            audit.recordInTx(AuditInput.of("DebitNote", id, "update", null, Obj.of("status", "posted")));
            return row;
        });
        events.publish("debit_note.posted", Obj.of("organizationId", orgId, "noteId", id, "direction",
                note.get("direction"), "amount", JsNumbers.dec(Money.dec(note.get("totalAmount")))));
        return updated;
    }

    /** Tax of one note line at its tax's rate (exclusive, rounded to cents), accumulated per GL account. */
    private BigDecimal lineTax(Map<String, Object> ln, boolean outbound, Map<String, BigDecimal> byAccount) {
        if (ln.get("taxId") == null) {
            return Money.ZERO;
        }
        Map<String, Object> tax = SqlRows.one(jdbc.sql("SELECT rate, \"accountId\", type::text AS type, \"isGroup\""
                + " FROM \"Tax\" WHERE id = ? AND \"organizationId\" = ?").params(ln.get("taxId"), org()));
        if (tax == null || "withholding".equals(tax.get("type"))) {
            return Money.ZERO;
        }
        if (Boolean.TRUE.equals(tax.get("isGroup"))) {
            // ADR-S071: a group applies its ordered children (exclusive; compound on the running total), one tax line
            // per account; withholding and reverse-charge children do not apply to a note.
            BigDecimal net = Money.dec(ln.get("subtotal"));
            BigDecimal total = Money.ZERO;
            for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT t.rate, t.\"accountId\", t.\"isCompound\" FROM"
                    + " \"TaxGroupMember\" m JOIN \"Tax\" t ON t.id = m.\"childTaxId\" WHERE m.\"groupTaxId\" = ? AND"
                    + " m.\"organizationId\" = ? AND t.\"deletedAt\" IS NULL AND t.type::text <> 'withholding' AND NOT"
                    + " t.\"reverseCharge\" ORDER BY m.sequence").params(ln.get("taxId"), org()))) {
                BigDecimal base = Boolean.TRUE.equals(c.get("isCompound")) ? net.add(total) : net;
                BigDecimal amount = base.multiply(Money.dec(c.get("rate"))).divide(BigDecimal.valueOf(100), 2,
                        java.math.RoundingMode.HALF_UP);
                if (amount.signum() == 0) {
                    continue;
                }
                String account = outbound
                        ? (c.get("accountId") != null ? (String) c.get("accountId") : determination.mapped("tax_payable"))
                        : determination.mapped("tax_receivable");
                byAccount.merge(account, amount, BigDecimal::add);
                total = total.add(amount);
            }
            return total;
        }
        BigDecimal amount = Money.dec(ln.get("subtotal")).multiply(Money.dec(tax.get("rate")))
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        if (amount.signum() == 0) {
            return Money.ZERO;
        }
        String account = outbound
                ? (tax.get("accountId") != null ? (String) tax.get("accountId") : determination.mapped("tax_payable"))
                : determination.mapped("tax_receivable");
        byAccount.merge(account, amount, BigDecimal::add);
        return amount;
    }

    /**
     * ACC-4 (B8): a note raised against a posted bill (from the supplier) or invoice (to the customer) adds to that
     * document's open amount, so it is settled with it and the sub-ledger matches the GL.
     */
    private BigDecimal raiseReferencedDocument(String documentId, boolean outbound, BigDecimal amount) {
        if (documentId == null || amount.signum() <= 0) {
            return Money.ZERO;
        }
        int raised = jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = \"amountResidual\" + ?, status = CASE WHEN status::text ="
                        + " 'paid' THEN 'posted'::\"DocumentStatus\" ELSE status END, \"paymentStatus\" = CASE WHEN"
                        + " \"amountPaid\" > 0 THEN 'partial'::\"PaymentStatus\" ELSE 'not_paid'::\"PaymentStatus\" END,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"documentType\"::text"
                        + " = ? AND status::text IN ('posted', 'paid')")
                .params(amount, documentId, org(), outbound ? "sales_invoice" : "vendor_bill").update();
        return raised > 0 ? amount : Money.ZERO;
    }

    /**
     * Document reversals (V520): what the note moved its referenced document's residual by (+ raised, − lowered), so a
     * reversal gives back exactly that.
     */
    private void recordDocumentAdjustment(String noteId, BigDecimal adjustment) {
        jdbc.sql("UPDATE \"DebitNote\" SET \"documentAdjustment\" = ? WHERE id = ?").params(adjustment, noteId).update();
    }

    /**
     * A returned-goods note settles that much of the referenced bill's open amount (never below zero); returns what it
     * actually took off the residual.
     */
    private BigDecimal lowerReferencedBill(String documentId, BigDecimal amount) {
        if (documentId == null || amount.signum() <= 0) {
            return Money.ZERO;
        }
        BigDecimal residual = jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"documentType\"::text = 'vendor_bill' AND status::text = 'posted' FOR UPDATE")
                .params(documentId, org()).query(BigDecimal.class).optional().orElse(null);
        if (residual == null) {
            return Money.ZERO;
        }
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = GREATEST(\"amountResidual\" - ?, 0),"
                        + " status = CASE WHEN \"amountResidual\" - ? <= 0.005 THEN 'paid'::\"DocumentStatus\" ELSE status END,"
                        + " \"paymentStatus\" = CASE WHEN \"amountResidual\" - ? <= 0.005 THEN 'paid'::\"PaymentStatus\""
                        + " ELSE 'partial'::\"PaymentStatus\" END, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"documentType\"::text = 'vendor_bill'"
                        + " AND status::text = 'posted'")
                .params(amount, amount, amount, documentId, org()).update();
        return residual.signum() <= 0 ? Money.ZERO : residual.min(amount);
    }

    /** Purchase price variance, the stock adjustment expense account when no PPV account is mapped. */
    private String returnVarianceAccount() {
        try {
            return determination.mapped("purchase_price_variance");
        } catch (RuntimeException e) {
            return determination.mapped("stock_adjustment_expense");
        }
    }

    private Map<String, Object> markPosted(String id, String orgId) {
        Map<String, Object> row = SqlRows.one(DebitNote.class, jdbc.sql("UPDATE \"DebitNote\" SET status = 'posted',"
                        + " \"postedAt\" = " + NOW + ", \"postedById\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ? AND status = 'draft' AND \"deletedAt\" IS NULL RETURNING *")
                .params(user(), id, orgId));
        if (row == null) {
            // P0-3: only one concurrent post can claim the draft; the loser must not move stock or post again.
            throw ApiException.conflict("Debit note was already posted by another request");
        }
        return row;
    }

    /**
     * Return-to-vendor: ship stock back at cost (quantity-only issue) and post Dr AP (gross) / Cr Stock Valuation and
     * Cr input VAT, with the supplier-credit vs cost variance to purchase price variance. The referenced bill's open
     * amount drops by the note's gross so the supplier is not paid for returned goods. One transaction. (Review
     * 2026-11-15, P-5: Nest posted the net price only, kept the VAT, left the bill's residual and used the stock
     * adjustment accounts.)
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postSupplierReturn(Map<String, Object> note) {
        String orgId = org();
        String id = (String) note.get("id");
        Map<String, Object> updated = tx.execute(st -> {
            Map<String, Object> claimed = markPosted(id, orgId);
            Map<String, Object> location = resolveReturnLocation(note, orgId);
            String apAccount = determination.payableAccount(partnerCol(note, "payableAccountId"));
            String stockValAccount = determination.mapped("stock_valuation");
            List<Line> postingLines = new ArrayList<>();
            BigDecimal totalAp = Money.ZERO;
            Map<String, BigDecimal> taxByAccount = new java.util.LinkedHashMap<>();
            BigDecimal noteTax = Money.ZERO;
            String variance = returnVarianceAccount();
            for (Map<String, Object> ln : (List<Map<String, Object>>) note.get("lines")) {
                BigDecimal price = Money.dec(ln.get("subtotal"));
                BigDecimal tax = lineTax(ln, false, taxByAccount);
                noteTax = noteTax.add(tax);
                totalAp = totalAp.add(tax);
                BigDecimal qty = Money.dec(ln.get("quantity"));
                Map<String, Object> product = ln.get("productId") == null ? null : SqlRows.one(jdbc.sql("SELECT"
                        + " \"trackInventory\", \"productType\"::text AS \"productType\" FROM \"Product\" WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(ln.get("productId"), orgId));
                boolean isStock = product != null && Boolean.TRUE.equals(product.get("trackInventory"))
                        && ("stockable".equals(product.get("productType"))
                                || "consumable".equals(product.get("productType")));
                totalAp = totalAp.add(price);
                if (isStock && qty.signum() > 0 && location != null) {
                    StockMove move = StockMove.of((String) ln.get("productId"), (String) location.get("id"), qty)
                            .variant((String) ln.get("variantId"))
                            .uom((String) ln.get("uomId"), ln.get("uomFactor") == null ? null
                                    : Money.dec(ln.get("uomFactor")))
                            .moveType("return_to_supplier").skipGl().source("debit_note", id)
                            .reference((String) note.get("noteNumber"));
                    Issued issueRes = stock.issue(move);
                    BigDecimal cost = issueRes == null ? Money.ZERO : Money.dec(issueRes.totalValue());
                    if (cost.signum() > 0) {
                        postingLines.add(Line.credit(stockValAccount, JsNumbers.dec(cost), null));
                    }
                    // Return variance: supplier credit (price) vs the stock cost removed.
                    BigDecimal diff = price.subtract(cost);
                    if (diff.signum() > 0) {
                        postingLines.add(Line.credit(variance, JsNumbers.dec(diff), "Return price variance"));
                    } else if (diff.signum() < 0) {
                        postingLines.add(Line.debit(variance, JsNumbers.dec(diff.negate()), "Return price variance"));
                    }
                } else if (price.signum() > 0) {
                    // Non-stock line on an RTV (e.g. a credited service/fee): reduce expense.
                    postingLines.add(Line.credit(determination.mapped("default_expense"), JsNumbers.dec(price), null));
                }
            }
            taxByAccount.forEach((acc, amt) -> postingLines.add(Line.credit(acc, JsNumbers.dec(amt), "Input tax reversal")));
            if (totalAp.signum() > 0) {
                postingLines.addFirst(new Line(apAccount, JsNumbers.dec(totalAp), null, (String) note.get("partnerId"),
                        null, null, null, null));
            }
            posting.post(PostingRequest.of("PURCH", (Instant) note.get("issueDate"), "Return to vendor "
                    + note.get("noteNumber"), "debit_note", id, "debit_note_rtv:" + id, postingLines));
            jdbc.sql("UPDATE \"DebitNote\" SET \"taxAmount\" = ?, \"totalAmount\" = ? WHERE id = ?")
                    .params(noteTax, totalAp, id).update();
            claimed.put("taxAmount", noteTax);
            claimed.put("totalAmount", totalAp);
            BigDecimal lowered = lowerReferencedBill((String) note.get("documentId"), totalAp);
            recordDocumentAdjustment(id, lowered.negate());
            return claimed;
        });
        audit.record(AuditInput.of("DebitNote", id, "update", null, Obj.of("status", "posted", "kind",
                "return_to_vendor")));
        events.publish("debit_note.posted", Obj.of("organizationId", orgId, "noteId", id, "direction",
                note.get("direction"), "amount", JsNumbers.dec(Money.dec(note.get("totalAmount")))));
        return updated;
    }

    /** RTV source warehouse: the stashed {@code customFields.locationId}, else the org's active warehouse. */
    private Map<String, Object> resolveReturnLocation(Map<String, Object> note, String orgId) {
        Object custom = note.get("customFields");
        String stashed = null;
        if (custom instanceof JsonNode node && node.has("locationId") && !node.get("locationId").isNull()) {
            stashed = node.get("locationId").asString();
        } else if (custom instanceof Map<?, ?> m && m.get("locationId") != null) {
            stashed = String.valueOf(m.get("locationId"));
        }
        if (stashed != null && !stashed.isEmpty()) {
            Map<String, Object> loc = SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryLocation\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(stashed, orgId));
            if (loc != null) {
                return loc;
            }
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse'"
                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL LIMIT 1").param(orgId));
    }

    /** Cancel a draft (a posted note is reversed with {@link #reverse}). */
    public Map<String, Object> cancel(String id) {
        String orgId = org();
        Map<String, Object> note = SqlRows.one(DebitNote.class, jdbc.sql("SELECT * FROM \"DebitNote\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        if (note == null) {
            throw ApiException.notFound("Debit note not found");
        }
        if ("posted".equals(note.get("status"))) {
            throw ApiException.badRequest("Cannot cancel a posted debit note; reverse it instead"
                    + " (PATCH /procurement/debit-notes/" + id + "/reverse)");
        }
        if ("reversed".equals(note.get("status"))) {
            throw ApiException.badRequest("Debit note " + note.get("noteNumber") + " is already reversed");
        }
        Map<String, Object> row = SqlRows.one(DebitNote.class, jdbc.sql("UPDATE \"DebitNote\" SET status = 'cancelled',"
                + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " RETURNING *").params(id, orgId));
        if (row == null) {
            throw new IllegalStateException("No record was found for an update.");
        }
        return row;
    }

    // ------------------------------------------------------------------ reversal

    private com.inventoryerp.accounting.application.DocumentReversalService reversals;

    @org.springframework.beans.factory.annotation.Autowired
    void setReversals(com.inventoryerp.accounting.application.DocumentReversalService reversals) {
        this.reversals = reversals;
    }

    private static final BigDecimal CENT = new BigDecimal("0.005");

    /**
     * Debit-note document reversal (owner decision 2026-11-17; a posted note could not be cancelled and its only undo
     * was the generic journal reversal). With a reason, behind the {@code debit_note_reverse} approval gate, the
     * {@code debit_note:reverse} permission and the {@code document_reversal} separation-of-duties pair, in one
     * transaction under row locks on the referenced invoice / bill and the note:
     * <ul>
     *   <li>sub-ledger: what the note did to its referenced document is given back — a note that raised the open amount
     *       ({@code documentAdjustment} &gt; 0) lowers it again, refused when that part was already paid (void the
     *       payments first); a return-to-vendor that lowered the bill raises it again. A note posted before V520 has no
     *       recorded adjustment: a raise is taken as its total, a lowering as min(total, payable − paid − residual). A
     *       referenced document that is cancelled is left alone;</li>
     *   <li>GL: its {@code debit_note} entries (note and return-to-vendor) are mirrored by {@link PostingService#reverse};</li>
     *   <li>stock: goods a return-to-vendor shipped back come back in at the cost they left at ({@code reversal_in},
     *       no separate GL: the mirrored entry restores the stock valuation);</li>
     *   <li>the note becomes {@code reversed} and a {@code DocumentReversal} row keeps reason, who, when and the entries.</li>
     * </ul>
     * A replay on a reversed note returns it unchanged.
     */
    public Map<String, Object> reverse(String id, String reasonIn) {
        String orgId = org();
        Map<String, Object> note = SqlRows.one(DebitNote.class, jdbc.sql("SELECT * FROM \"DebitNote\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        if (note == null) {
            throw ApiException.notFound("Debit note not found");
        }
        if ("reversed".equals(note.get("status"))) {
            return reversedView(id);
        }
        String reason = reversals.requireReason(reasonIn, "reverse a debit note");
        String number = (String) note.get("noteNumber");
        if (!"posted".equals(note.get("status"))) {
            throw ApiException.badRequest("Only a posted debit note can be reversed (debit note " + number + " is "
                    + note.get("status") + "; cancel a draft instead)");
        }
        reversals.requireApproval("debit_note_reverse", id, "debit note " + number, Money.dec(note.get("totalAmount")));
        reversals.assertSeparated("DebitNote", id, java.util.Arrays.asList((String) note.get("createdBy"),
                (String) note.get("postedById")));
        tx.executeWithoutResult(st -> {
            String documentId = (String) note.get("documentId");
            if (documentId != null) {
                jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(documentId, orgId).query().listOfRows();
            }
            Map<String, Object> locked = SqlRows.one(jdbc.sql("SELECT status, \"totalAmount\", \"documentAdjustment\","
                    + " direction, reason::text AS reason FROM \"DebitNote\" WHERE id = ? AND \"organizationId\" = ?"
                    + " FOR UPDATE").params(id, orgId));
            if ("reversed".equals(locked.get("status"))) {
                return; // a concurrent reversal won the lock: replay
            }
            if (!"posted".equals(locked.get("status"))) {
                throw ApiException.badRequest("Only a posted debit note can be reversed");
            }
            boolean rtv = "inbound".equals(locked.get("direction")) && "returned_goods".equals(locked.get("reason"));
            BigDecimal total = Money.dec(locked.get("totalAmount"));
            List<Map<String, Object>> unwound = new ArrayList<>();
            if (documentId != null) {
                unwound.addAll(restoreReferencedDocument(documentId, number, rtv, total,
                        locked.get("documentAdjustment") == null ? null : Money.dec(locked.get("documentAdjustment"))));
            }
            String text = "Reversal of debit note " + number + ": " + reason;
            for (String entryId : jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                    + " \"sourceType\" = 'debit_note' AND \"sourceId\" = ? AND status = 'posted' ORDER BY \"postedAt\", id")
                    .params(orgId, id).query(String.class).list()) {
                posting.reverse(entryId, null, text);
            }
            if (rtv) {
                bringBackReturnedStock(id, number);
            }
            jdbc.sql("UPDATE \"DebitNote\" SET status = 'reversed', \"updatedAt\" = " + NOW + ", \"updatedBy\" = ?"
                    + " WHERE id = ?").params(user(), id).update();
            reversals.record(new com.inventoryerp.accounting.application.DocumentReversalService.Recording("debit_note",
                    id, number, (String) note.get("partnerId"), reason, "DebitNote", List.of("debit_note"),
                    List.of("debit_note_reversal"), unwound));
        });
        return reversedView(id);
    }

    /** Undo the note's effect on its referenced invoice / bill; the unwound adjustment for the reversal record. */
    private List<Map<String, Object>> restoreReferencedDocument(String documentId, String number, boolean rtv,
            BigDecimal total, BigDecimal recorded) {
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT id, \"documentNumber\", \"documentType\"::text AS type,"
                + " status::text AS status, \"totalAmount\", \"withholdingAmount\", \"amountPaid\", \"amountResidual\""
                + " FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(documentId, org()));
        if (doc == null || !("posted".equals(doc.get("status")) || "paid".equals(doc.get("status")))) {
            return List.of();
        }
        BigDecimal residual = Money.dec(doc.get("amountResidual"));
        BigDecimal payable = Money.dec(doc.get("totalAmount")).subtract(Money.dec(doc.get("withholdingAmount")));
        BigDecimal paid = Money.dec(doc.get("amountPaid"));
        BigDecimal delta;
        if (!rtv) {
            BigDecimal raised = recorded != null ? recorded : total;
            if (raised.signum() <= 0) {
                return List.of();
            }
            if (residual.add(CENT).compareTo(raised) < 0) {
                List<Map<String, Object>> payments = reversals.appliedPayments(documentId);
                throw ApiException.badRequest("Debit note " + number + " cannot be reversed: the " + JsNumbers.dec(raised)
                        + " it added to " + doc.get("documentNumber") + " is already settled (open " + JsNumbers.dec(residual)
                        + ")" + (payments.isEmpty() ? "" : "; void " + com.inventoryerp.accounting.application
                                .DocumentReversalService.describePayments(payments) + " first") + ".");
            }
            delta = raised.negate();
        } else {
            BigDecimal lowered = recorded != null ? recorded.negate()
                    : total.min(payable.subtract(paid).subtract(residual).max(Money.ZERO));
            if (lowered.signum() <= 0) {
                return List.of();
            }
            delta = lowered;
        }
        BigDecimal newResidual = residual.add(delta).max(Money.ZERO);
        String paymentStatus = newResidual.compareTo(CENT) <= 0 ? "paid" : paid.signum() > 0
                || newResidual.compareTo(payable) < 0 ? "partial" : "not_paid";
        String status = newResidual.compareTo(CENT) <= 0 ? "paid" : "posted";
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = ?, \"paymentStatus\" = ?::\"PaymentStatus\", status ="
                        + " ?::\"DocumentStatus\", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(newResidual, paymentStatus, status, documentId).update();
        return List.of(Obj.of("kind", rtv ? "bill_residual_restored" : "document_residual_lowered", "documentId",
                documentId, "documentNumber", doc.get("documentNumber"), "amount", JsNumbers.dec(delta.abs()),
                "residualBefore", JsNumbers.dec(residual), "residualAfter", JsNumbers.dec(newResidual)));
    }

    /** Receive back what a return-to-vendor shipped out, at the cost it left at (once per note; no GL of its own). */
    private void bringBackReturnedStock(String id, String number) {
        long done = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'debit_note_reversal'"
                + " AND \"referenceId\" = ? AND \"organizationId\" = ?").params(id, org()).query(Long.class).single();
        if (done > 0) {
            return;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantId\", \"locationId\","
                + " \"quantityChange\", \"unitCost\" FROM \"InventoryLedger\" WHERE \"referenceType\" = 'debit_note' AND"
                + " \"referenceId\" = ? AND \"organizationId\" = ? AND \"quantityChange\" < 0 ORDER BY \"productId\","
                + " \"variantId\" NULLS FIRST, \"createdAt\", id").params(id, org()))) {
            try {
                stock.receive(StockMove.of((String) r.get("productId"), (String) r.get("locationId"),
                        Money.dec(r.get("quantityChange")).negate()).variant((String) r.get("variantId"))
                        .unitCost(Money.dec(r.get("unitCost"))).moveType("reversal_in")
                        .source("debit_note_reversal", id).reference(number)
                        .notes("Goods back from supplier on reversal of debit note " + number));
            } catch (ApiException e) {
                throw ApiException.badRequest("Cannot reverse debit note " + number + ": the returned goods cannot be"
                        + " taken back into stock (" + e.getMessage() + ")");
            }
        }
    }

    /** The note with lines and partner, plus its {@code reversal} record once reversed. */
    private Map<String, Object> reversedView(String id) {
        Map<String, Object> out = loadForPost(id, org());
        Map<String, Object> r = reversals.find("debit_note", id);
        if (out != null && r != null) {
            out.put("reversal", r);
        }
        return out;
    }

    /** Latest 200 notes (by issue date) with lines and the partner name. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String direction, String status) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = " \"organizationId\" = ? AND \"deletedAt\" IS NULL";
        if (direction != null && !direction.isEmpty()) {
            where += " AND direction = ?";
            params.add(direction);
        }
        if (status != null && !status.isEmpty()) {
            where += " AND status = ?";
            params.add(status);
        }
        List<Map<String, Object>> rows = SqlRows.list(DebitNote.class, jdbc.sql("SELECT * FROM \"DebitNote\" WHERE"
                + where + " ORDER BY \"issueDate\" DESC LIMIT 200").params(params));
        for (Map<String, Object> r : rows) {
            r.put("lines", lines(r.get("id")));
            r.put("partner", SqlRows.one(jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(r.get("partnerId"), org())));
        }
        return rows;
    }

    /** Note with lines and partner; {@code null} when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        return loadForPost(id, org());
    }
}
