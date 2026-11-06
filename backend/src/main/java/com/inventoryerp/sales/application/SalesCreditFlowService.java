package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.invoicing.application.CreditNoteService;
import com.inventoryerp.invoicing.application.CreditNoteService.CreditNoteInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.InvoiceDeliveryService;
import com.inventoryerp.invoicing.application.PaymentService;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit as its own flow (SG-7): a credit note with or without a return, allocating open credit to invoices, refunding
 * it, and writing a receivable off. All of it goes through {@link CreditNoteService} and {@link PaymentService}.
 */
@Service
public class SalesCreditFlowService {

    static final Set<String> TYPES = Set.of("return", "price_adjustment", "goodwill", "billing_error", "write_off");

    private final SalesDb db;
    private final CreditNoteService creditNotes;
    private final PaymentService payments;
    private final InvoiceDeliveryService delivery;
    private final SalesSettingsService settings;
    private final AuditService audit;

    public SalesCreditFlowService(SalesDb db, CreditNoteService creditNotes, PaymentService payments,
            InvoiceDeliveryService delivery, SalesSettingsService settings, AuditService audit) {
        this.db = db;
        this.creditNotes = creditNotes;
        this.payments = payments;
        this.delivery = delivery;
        this.settings = settings;
        this.audit = audit;
    }

    /** Credit notes, newest first. {@code open=true} keeps those with credit left. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Map<String, String> q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = " WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text = 'credit_note'";
        if (!blank(q.get("partnerId"))) {
            where += " AND d.\"partnerId\" = ?";
            params.add(q.get("partnerId"));
        }
        if ("true".equals(q.get("open"))) {
            where += " AND d.status::text = 'posted' AND d.\"amountResidual\" > 0";
        }
        return db.rows("SELECT d.id, d.\"documentNumber\", d.status::text AS status, d.\"issueDate\", d.\"totalAmount\", d.\"amountResidual\","
                + " d.\"partnerId\", p.name AS \"partnerName\", d.data->>'creditType' AS \"creditType\", d.reference FROM \"Document\" d LEFT JOIN"
                + " \"Partner\" p ON p.id = d.\"partnerId\"" + where + " ORDER BY d.\"createdAt\" DESC LIMIT 300", params);
    }

    /** A posted credit note without a return: {@code partnerId, invoiceId?, type, reason, lines[]}. Stock does not move. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> in) {
        String partner = str(in.get("partnerId"));
        if (blank(partner) || !db.exists("Partner", partner)) {
            throw ApiException.notFound("Partner not found");
        }
        String type = blank(str(in.get("type"))) ? "price_adjustment" : str(in.get("type"));
        if (!TYPES.contains(type) || "write_off".equals(type)) {
            throw ApiException.badRequest("type must be one of: billing_error, goodwill, price_adjustment, return");
        }
        String reason = str(in.get("reason"));
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        List<Map<String, Object>> raw = in.get("lines") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (raw.isEmpty()) {
            throw ApiException.badRequest("Add at least one line");
        }
        String account = "return".equals(type) ? str(settings.settings().get("salesReturnsAccountId")) : null;
        List<LineInput> lines = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            lines.add(new LineInput(blank(str(r.get("productId"))) ? null : str(r.get("productId")), null, null, null,
                    blank(str(r.get("accountId"))) ? account : str(r.get("accountId")), blank(str(r.get("description"))) ? reason
                            : str(r.get("description")), r.get("quantity") == null ? BigDecimal.ONE : dec(r.get("quantity")),
                    dec(r.get("unitPrice")), dec(r.get("discountPercent")), null, null, null, "manual", blank(str(r.get("taxId"))) ? null
                            : str(r.get("taxId")), null, Boolean.TRUE.equals(r.get("taxInclusive"))));
        }
        return post(partner, str(in.get("invoiceId")), type, reason, lines);
    }

    private Map<String, Object> post(String partner, String invoiceId, String type, String reason, List<LineInput> lines) {
        Map<String, Object> cn = creditNotes.create(new CreditNoteInput(partner, LocalDate.now().toString(), null, null, reason,
                blank(invoiceId) ? null : invoiceId, lines));
        String id = str(cn.get("id"));
        creditNotes.post(id, "none");
        markType(id, type);
        audit.record(AuditInput.of("Document", id, "create", null, Obj.of("creditType", type, "reason", reason)));
        return creditNotes.findOne(id);
    }

    void markType(String id, String type) {
        db.update("UPDATE \"Document\" SET data = COALESCE(data, '{}'::jsonb) || jsonb_build_object('creditType', ?::text),"
                + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", type, id, org());
    }

    @Transactional
    public Map<String, Object> allocate(String id, String invoiceId, BigDecimal amount) {
        return creditNotes.allocate(id, invoiceId, amount);
    }

    /** Pay the credit left on a posted credit note back to the customer. */
    @Transactional
    public Map<String, Object> refund(String id, Map<String, Object> in) {
        Map<String, Object> cn = creditNotes.findOne(id);
        BigDecimal open = creditNotes.takeOpenCredit(id).setScale(2, RoundingMode.HALF_UP);
        if (open.signum() <= 0) {
            throw ApiException.badRequest("This credit note has no credit left to refund");
        }
        Map<String, Object> p = payments.createCustomerRefund(new PaymentInput(str(cn.get("partnerId")), LocalDate.now().toString(),
                open, blank(str(in.get("paymentMethod"))) ? "bank" : str(in.get("paymentMethod")), str(in.get("accountId")),
                "Refund of credit note " + cn.get("documentNumber"), null, List.of(), null, true, null, null), true, true);
        audit.record(AuditInput.of("Document", id, "update", null, Obj.of("refund", JsNumbers.js(open), "paymentId", p.get("id"))));
        return Obj.of("creditNoteId", id, "refunded", JsNumbers.js(open), "paymentId", p.get("id"));
    }

    /**
     * Write off an invoice's open balance, whole or part: a credit note against it, debiting the bad debt account. Tax is not
     * reversed: recovering output tax on bad debts follows local tax law and is a separate entry.
     */
    @Transactional
    public Map<String, Object> writeOff(String invoiceId, Map<String, Object> in) {
        Map<String, Object> inv = db.row("SELECT id, \"partnerId\", \"documentNumber\", \"amountResidual\", status::text AS status FROM"
                + " \"Document\" WHERE id = ? AND \"organizationId\" = ? AND \"documentType\"::text = 'sales_invoice' FOR UPDATE", invoiceId, org());
        if (inv == null) {
            throw ApiException.notFound("Invoice not found");
        }
        if (!"posted".equals(inv.get("status"))) {
            throw ApiException.badRequest("Only a posted invoice can be written off");
        }
        String reason = str(in.get("reason"));
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        BigDecimal open = dec(inv.get("amountResidual"));
        BigDecimal amount = in.get("amount") == null ? open : dec(in.get("amount"));
        if (amount.signum() <= 0 || amount.compareTo(open) > 0) {
            throw ApiException.badRequest("Amount must be between 0 and the open balance of " + open.stripTrailingZeros().toPlainString());
        }
        String account = !blank(str(in.get("accountId"))) ? str(in.get("accountId")) : str(settings.settings().get("badDebtAccountId"));
        if (blank(account)) {
            throw ApiException.badRequest("Set the bad debt account in Sales Settings first");
        }
        LineInput line = new LineInput(null, null, null, null, account, "Write-off of " + inv.get("documentNumber") + ": " + reason,
                BigDecimal.ONE, amount, BigDecimal.ZERO, null, null, null, "manual", null, null, false);
        return post(str(inv.get("partnerId")), invoiceId, "write_off", reason, List.of(line));
    }

    @Transactional(readOnly = true)
    public byte[] pdf(String id) {
        return delivery.pdf(id).content();
    }
}
