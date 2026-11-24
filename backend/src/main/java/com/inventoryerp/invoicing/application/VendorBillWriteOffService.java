package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.ControlAccountGuards;
import com.inventoryerp.accounting.application.DocumentReversalService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier write-off of a vendor bill's open balance (control-account policy, owner decision 2026-11-17: payment
 * write-offs go through the sub-ledger, symmetric with the customer write-off, which is a credit note against the
 * invoice).
 *
 * <p>A supplier sub-ledger adjustment, not a payment: Dr the supplier's payable (the partner override, else the
 * {@code accounts_payable} mapping, as a supplier payment relieves it; tagged with the supplier so the AP sub-ledger,
 * ageing and supplier ledger see it) / Cr a non-control income or expense account (the stated {@code accountId}, else
 * the {@code purchase_discount} mapping, "Purchase Discounts Received"). In the same transaction, under the bill's row
 * lock, the bill's residual drops by the amount and its payment status / status follow it, so the GL and the open item
 * move together. The written-off amount counts as settled ({@code amountPaid}), like a payment, and the bill void
 * refuses a bill with a live write-off (it reverses only the bill's own entry). The entry is posted in the bill's
 * currency and rate under the sub-ledger source {@code vendor_bill_writeoff} ({@code sourceId} = the bill,
 * {@code postingType = write_off}), which {@code SubledgerSources} knows, so a generic journal reversal of it is
 * refused; its posting key {@code vendor_bill_writeoff:<billId>:<n>} is unique per write-off, and the route requires an
 * Idempotency-Key, so a retried request replays instead of posting twice.
 */
@Service
public class VendorBillWriteOffService {

    /** Body: {@code amount} (null = the whole open balance), {@code accountId} (null = purchase_discount), reason. */
    public record WriteOffInput(BigDecimal amount, String accountId, String reason) {}

    /** Journal source of a supplier write-off (sourceId = the bill); registered in {@code SubledgerSources}. */
    public static final String SOURCE = "vendor_bill_writeoff";

    /** {@code postingType} of a write-off entry. */
    public static final String POSTING_TYPE = "write_off";

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final DocumentBuilderService builder;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AuditService audit;
    private final DocumentReversalService reversals;
    private final DocumentFxRates fx;

    public VendorBillWriteOffService(JdbcClient jdbc, DocumentBuilderService builder, PostingService posting,
            AccountDeterminationService determination, AuditService audit, DocumentReversalService reversals,
            DocumentFxRates fx) {
        this.jdbc = jdbc;
        this.builder = builder;
        this.posting = posting;
        this.determination = determination;
        this.audit = audit;
        this.reversals = reversals;
        this.fx = fx;
    }

    /** Write off {@code in.amount} (default: all) of a posted bill's open balance. */
    @Transactional
    public Map<String, Object> writeOff(String billId, WriteOffInput in) {
        String org = TenantContext.organizationId();
        String reason = in == null || in.reason() == null ? "" : in.reason().trim();
        if (reason.isEmpty()) {
            throw ApiException.badRequest("A reason is required to write off a bill");
        }
        // The bill's row lock serialises a write-off with payments, other write-offs and a void.
        jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? AND \"documentType\"::text ="
                + " 'vendor_bill' FOR UPDATE").params(billId, org).query().listOfRows();
        Map<String, Object> bill = builder.document(billId, "vendor_bill");
        if (bill == null) {
            throw ApiException.notFound("Vendor bill not found");
        }
        if (!"posted".equals(String.valueOf(bill.get("status")))) {
            throw ApiException.badRequest("Only a posted bill with an open balance can be written off (bill "
                    + bill.get("documentNumber") + " is " + bill.get("status") + ")");
        }
        BigDecimal residual = Money.dec(bill.get("amountResidual"));
        if (residual.signum() <= 0) {
            throw ApiException.badRequest("Bill " + bill.get("documentNumber") + " has no open balance to write off");
        }
        BigDecimal amount = in.amount() == null ? residual : in.amount();
        if (amount.signum() <= 0 || amount.compareTo(residual) > 0) {
            throw ApiException.badRequest("Amount must be greater than 0 and at most the open balance of "
                    + JsNumbers.dec(residual));
        }
        String account = in.accountId() == null || in.accountId().isBlank() ? determination.mapped("purchase_discount")
                : in.accountId().trim();
        ControlAccountGuards.assertWriteOffAccount(jdbc, org, "Bill write-off account", account, false);

        String partnerId = (String) bill.get("partnerId");
        String partnerPayable = jdbc.sql("SELECT \"payableAccountId\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(partnerId, org).query(String.class).optional().orElse(null);
        String payable = determination.payableAccount(partnerPayable);
        long seq = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" = ?"
                + " AND \"sourceId\" = ? AND \"postingType\" = ?").params(org, SOURCE, billId, POSTING_TYPE)
                .query(Long.class).single();
        String number = (String) bill.get("documentNumber");
        String branchId = (String) bill.get("branchId");
        String value = JsNumbers.dec(amount);
        // Finance readiness audit B5: a foreign bill is relieved at its carrying rate, the rate it posted at. A bill
        // storing the default 1 ("not stated", FX-1) posted at the rate table's rate, and that stored 1, passed on,
        // made the write-off post at today's table rate: the payable's base did not clear once the rate had moved.
        Object rate = fx.isForeign(bill) ? fx.documentRate(bill)
                : bill.get("exchangeRate") == null ? null : Money.dec(bill.get("exchangeRate"));
        Map<String, Object> entry = posting.post(new PostingRequest("PURCH", Instant.now(),
                "Write-off of bill " + number + ": " + reason, (String) bill.get("currencyId"), rate, SOURCE, billId,
                POSTING_TYPE, SOURCE + ":" + billId + ":" + (seq + 1), branchId, null, null, List.of(
                        new Line(payable, value, null, partnerId, "Bill write-off " + number, branchId, null, null),
                        new Line(account, null, value, null, "Bill write-off " + number + ": " + reason, branchId,
                                null, null))));

        BigDecimal newResidual = residual.subtract(amount);
        BigDecimal newPaid = Money.dec(bill.get("amountPaid")).add(amount);
        BigDecimal owed = Money.dec(bill.get("totalAmount")).subtract(Money.dec(bill.get("withholdingAmount")));
        jdbc.sql("UPDATE \"Document\" SET \"amountPaid\" = ?, \"amountResidual\" = ?, \"paymentStatus\" ="
                        + " ?::\"PaymentStatus\", status = ?::\"DocumentStatus\", \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(newPaid, newResidual, PaymentService.residualStatus(owed, newResidual),
                        newResidual.signum() <= 0 ? "paid" : "posted", billId, org)
                .update();
        audit.recordInTx(AuditInput.of("VendorBill", billId, "update",
                Obj.of("amountResidual", JsNumbers.dec(residual), "amountPaid", JsNumbers.dec(Money.dec(bill.get("amountPaid")))),
                Obj.of("kind", "write_off", "amount", value, "accountId", account, "reason", reason,
                        "journalEntryId", entry.get("id"), "amountResidual", JsNumbers.dec(newResidual))));

        Map<String, Object> out = builder.document(billId);
        out.put("lines", builder.lines(billId, false));
        out.put("partner", builder.partner(out.get("partnerId")));
        out.put("writtenOff", JsNumbers.dec(writtenOff(billId)));
        out.put("writeOff", Obj.of("journalEntryId", entry.get("id"), "entryNumber", entry.get("entryNumber"),
                "amount", value, "accountId", account, "reason", reason));
        return out;
    }

    /**
     * Reverse one supplier write-off of a bill (owner decision 2026-11-17: settlements are reversed at document level,
     * never by the generic journal reversal). Under the bill's row lock: the write-off entry is mirrored by
     * {@link PostingService#reverse} (original linked, partner tags kept), the bill's residual and {@code amountPaid}
     * get the amount back and its status / payment status follow, and the reversal is audited with its reason. A
     * reason is required; the {@code document_reversal} segregation-of-duties pair, when switched on, refuses the
     * user who posted the write-off. Idempotent: an already reversed write-off returns the bill unchanged.
     */
    @Transactional
    public Map<String, Object> reverseWriteOff(String billId, String entryId, String reasonIn) {
        String org = TenantContext.organizationId();
        String reason = reversals.requireReason(reasonIn, "reverse a bill write-off");
        jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? AND \"documentType\"::text ="
                + " 'vendor_bill' FOR UPDATE").params(billId, org).query().listOfRows();
        Map<String, Object> bill = builder.document(billId, "vendor_bill");
        if (bill == null) {
            throw ApiException.notFound("Vendor bill not found");
        }
        Map<String, Object> entry = jdbc.sql("SELECT id, \"entryNumber\", status::text AS status, \"createdBy\","
                        + " \"postedBy\" FROM \"JournalEntry\" WHERE id = ? AND \"organizationId\" = ? AND"
                        + " \"sourceType\" = ? AND \"sourceId\" = ? AND \"postingType\" = ?")
                .params(entryId, org, SOURCE, billId, POSTING_TYPE).query().listOfRows().stream().findFirst()
                .orElse(null);
        if (entry == null) {
            throw ApiException.notFound("Write-off not found on bill " + bill.get("documentNumber"));
        }
        if (!"posted".equals(entry.get("status"))) {
            return billView(billId); // already reversed: replay
        }
        if (!"posted".equals(String.valueOf(bill.get("status"))) && !"paid".equals(String.valueOf(bill.get("status")))) {
            throw ApiException.badRequest("Bill " + bill.get("documentNumber") + " is " + bill.get("status"));
        }
        java.util.List<String> makers = new java.util.ArrayList<>();
        for (String k : List.of("createdBy", "postedBy")) {
            if (entry.get(k) != null) {
                makers.add((String) entry.get(k));
            }
        }
        reversals.assertSeparated("VendorBill", billId, makers);
        BigDecimal amount = jdbc.sql("SELECT COALESCE(SUM(debit), 0) FROM \"JournalLine\" WHERE \"journalEntryId\" = ?"
                + " AND \"partnerId\" IS NOT NULL AND debit > 0").param(entryId).query(BigDecimal.class).single();
        Map<String, Object> reversal = posting.reverse(entryId, Instant.now(), "Reversal of bill write-off "
                + entry.get("entryNumber") + " (" + bill.get("documentNumber") + "): " + reason);

        BigDecimal residual = Money.dec(bill.get("amountResidual"));
        BigDecimal newResidual = residual.add(amount);
        BigDecimal newPaid = Money.dec(bill.get("amountPaid")).subtract(amount).max(BigDecimal.ZERO);
        BigDecimal owed = Money.dec(bill.get("totalAmount")).subtract(Money.dec(bill.get("withholdingAmount")));
        jdbc.sql("UPDATE \"Document\" SET \"amountPaid\" = ?, \"amountResidual\" = ?, \"paymentStatus\" ="
                        + " ?::\"PaymentStatus\", status = ?::\"DocumentStatus\", \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(newPaid, newResidual, PaymentService.residualStatus(owed, newResidual),
                        newResidual.signum() <= 0 ? "paid" : "posted", billId, org)
                .update();
        audit.recordInTx(AuditInput.of("VendorBill", billId, "update",
                Obj.of("amountResidual", JsNumbers.dec(residual), "writeOffEntryId", entryId),
                Obj.of("kind", "write_off_reversal", "amount", JsNumbers.dec(amount), "reason", reason,
                        "journalEntryId", reversal == null ? null : reversal.get("id"), "amountResidual",
                        JsNumbers.dec(newResidual))));
        return billView(billId);
    }

    private Map<String, Object> billView(String billId) {
        Map<String, Object> out = builder.document(billId);
        out.put("lines", builder.lines(billId, false));
        out.put("partner", builder.partner(out.get("partnerId")));
        out.put("writtenOff", JsNumbers.dec(writtenOff(billId)));
        return out;
    }

    /** Total written off a bill by live (posted, not reversed) write-off entries: their debits to the supplier. */
    @Transactional(readOnly = true)
    public BigDecimal writtenOff(String billId) {
        return jdbc.sql("SELECT COALESCE(SUM(l.debit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                        + " e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.\"sourceType\" = ? AND"
                        + " e.\"sourceId\" = ? AND e.\"postingType\" = ? AND e.status = 'posted' AND l.\"partnerId\" IS NOT"
                        + " NULL AND l.debit > 0")
                .params(TenantContext.organizationId(), SOURCE, billId, POSTING_TYPE).query(BigDecimal.class).single();
    }
}
