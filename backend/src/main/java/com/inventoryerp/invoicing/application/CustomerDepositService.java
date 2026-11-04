package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.TenderAccount;
import com.inventoryerp.invoicing.application.PaymentService.AllocationInput;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.workflow.WorkflowService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer deposits / advances (ADR-S011 S6, sales-postings.md P2, P3, P9, P10). A deposit is a liability to the
 * customer until it is applied to an invoice or refunded:
 * <ul>
 *   <li>receipt without invoice — Dr tender / Cr Customer Deposits (P2);</li>
 *   <li>down-payment invoice posted (lines on the deposit account) — Dr AR / Cr Customer Deposits (+ VAT) (P3); the
 *       deposit is registered when the invoice posts;</li>
 *   <li>application to an invoice — a deposit-backed receipt allocated to it: Dr Customer Deposits / Cr AR (P9);</li>
 *   <li>refund — Dr Customer Deposits / Cr tender (P10).</li>
 * </ul>
 * The deposit subledger (amount − applied − refunded of open deposits) equals the deposit account balance.
 */
@Service
public class CustomerDepositService {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final PaymentService payments;
    private final AccountResolverService accounts;
    private final AccountDeterminationService determination;
    private final SequenceService sequence;
    private final AuditService audit;
    private final WorkflowService workflow;

    public CustomerDepositService(JdbcClient jdbc, PostingService posting, PaymentService payments,
            AccountResolverService accounts, AccountDeterminationService determination, SequenceService sequence,
            AuditService audit, WorkflowService workflow) {
        this.workflow = workflow;
        this.jdbc = jdbc;
        this.posting = posting;
        this.payments = payments;
        this.accounts = accounts;
        this.determination = determination;
        this.sequence = sequence;
        this.audit = audit;
    }

    /** Receipt input. */
    public record DepositInput(String partnerId, String salesOrderId, BigDecimal amount, String paymentMethod,
            String accountId, String receivedDate, String reference, String notes) {}

    private static String org() {
        return TenantContext.organizationId();
    }

    /** The customer-deposit liability account (created on first use, mapping {@code customer_deposit}). */
    public String depositAccount() {
        return accounts.ensureByCode("2350", "Customer Deposits", "current_liabilities", "customer_deposit");
    }

    // ------------------------------------------------------------------ receipt / down payment

    /** Deposit received (P2): Dr tender / Cr Customer Deposits. */
    @Transactional
    public Map<String, Object> receive(DepositInput in) {
        String org = org();
        if (in.partnerId() == null || jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(in.partnerId(), org).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Partner not found");
        }
        BigDecimal amount = in.amount() == null ? BigDecimal.ZERO : in.amount().setScale(6, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Deposit amount must be positive");
        }
        String method = in.paymentMethod() == null ? "bank" : in.paymentMethod();
        if ("store_credit".equals(method) || PaymentService.DEPOSIT_METHOD.equals(method)) {
            throw ApiException.badRequest("Unsupported payment method");
        }
        TenderAccount.Resolved tender = TenderAccount.resolve(jdbc, determination, org, method, in.accountId(), null);
        LocalDate date = in.receivedDate() == null ? LocalDate.now() : LocalDate.parse(in.receivedDate().substring(0, 10));
        String id = UUID.randomUUID().toString();
        String number = sequence.next("customer_deposit:" + date.getYear(), "DEP-" + date.getYear() + "-", 5);
        String depositAccount = depositAccount();
        Map<String, Object> entry = posting.post(PostingRequest.of(tender.journalCode(), date.atStartOfDay()
                .toInstant(ZoneOffset.UTC), "Customer deposit " + number, "customer_deposit", id, "customer_deposit:" + id,
                List.of(Line.debit(tender.accountId(), amount, "Deposit " + number),
                        new Line(depositAccount, null, amount, in.partnerId(), "Deposit " + number, null, null, null))));
        jdbc.sql("INSERT INTO \"CustomerDeposit\" (id, \"organizationId\", number, \"partnerId\", \"salesOrderId\", source,"
                + " \"paymentMethod\", \"accountId\", amount, \"receivedDate\", \"journalEntryId\", reference, notes,"
                + " \"createdById\") VALUES (?, ?, ?, ?, ?, 'receipt', ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org, number, in.partnerId(), in.salesOrderId(), method, tender.accountId(), amount,
                        java.sql.Date.valueOf(date), entry.get("id"), in.reference(), in.notes(), TenantContext.userId())
                .update();
        audit.record(AuditInput.of("CustomerDeposit", id, "create", null, Obj.of("number", number, "amount", amount,
                "salesOrderId", in.salesOrderId())));
        return get(id);
    }

    /** One verified showroom payment of a branch cash sale (ADR-S056 P2). */
    public record CashSaleDeposit(String partnerId, String cashSaleId, String cashSaleNumber, String branchId,
            BigDecimal amount, String paymentMethod, String accountId, LocalDate receivedDate, String reference,
            String postingKey) {}

    /**
     * Cash-sale deposit (ADR-S056 P2): Dr the branch tender account (fixed by the branch profile) / Cr Customer
     * Deposits, linked to the submission. Such a deposit settles only the invoice of that submission (see
     * {@link #apply}) and is refunded or released only through the submission ({@link #refundCashSale},
     * {@link #releaseCashSale}). Idempotent on {@code postingKey}: a replay returns the existing deposit.
     */
    @Transactional
    public Map<String, Object> receiveForCashSale(CashSaleDeposit in) {
        String org = org();
        BigDecimal amount = in.amount() == null ? BigDecimal.ZERO : in.amount().setScale(6, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Deposit amount must be positive");
        }
        if (in.cashSaleId() == null || in.accountId() == null) {
            throw ApiException.badRequest("A cash-sale deposit needs its submission and tender account");
        }
        TenderAccount.Resolved tender = TenderAccount.resolve(jdbc, determination, org, in.paymentMethod(), in.accountId(),
                null);
        String id = UUID.randomUUID().toString();
        LocalDate date = in.receivedDate() == null ? LocalDate.now() : in.receivedDate();
        String depositAccount = depositAccount();
        Map<String, Object> entry = posting.post(new PostingRequest(tender.journalCode(), date.atStartOfDay()
                .toInstant(ZoneOffset.UTC), "Cash sale " + in.cashSaleNumber() + " payment", null, null,
                "customer_deposit", id, null, in.postingKey(), in.branchId(), null, null, List.of(
                        new Line(tender.accountId(), amount, null, null, "Cash sale " + in.cashSaleNumber(), in.branchId(),
                                null, null),
                        new Line(depositAccount, null, amount, in.partnerId(), "Cash sale " + in.cashSaleNumber(),
                                in.branchId(), null, null))));
        Map<String, Object> existing = SqlRows.one(jdbc.sql("SELECT id FROM \"CustomerDeposit\" WHERE \"journalEntryId\" = ?"
                + " AND \"organizationId\" = ?").params(entry.get("id"), org));
        if (existing != null) {
            return get((String) existing.get("id"));
        }
        String number = sequence.next("customer_deposit:" + date.getYear(), "DEP-" + date.getYear() + "-", 5);
        jdbc.sql("INSERT INTO \"CustomerDeposit\" (id, \"organizationId\", number, \"partnerId\", source, \"cashSaleId\","
                + " \"branchId\", \"paymentMethod\", \"accountId\", amount, \"receivedDate\", \"journalEntryId\", reference,"
                + " notes, \"createdById\") VALUES (?, ?, ?, ?, 'cash_sale', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org, number, in.partnerId(), in.cashSaleId(), in.branchId(), in.paymentMethod(),
                        tender.accountId(), amount, java.sql.Date.valueOf(date), entry.get("id"), in.reference(),
                        "Cash sale " + in.cashSaleNumber(), TenantContext.userId())
                .update();
        audit.record(AuditInput.of("CustomerDeposit", id, "create", null, Obj.of("number", number, "amount", amount,
                "cashSaleId", in.cashSaleId())));
        return get(id);
    }

    /** Cash-sale deposits of a submission (oldest first). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> cashSaleDeposits(String cashSaleId) {
        return SqlRows.list(jdbc.sql("SELECT d.*, d.amount - d.\"appliedAmount\" - d.\"refundedAmount\" AS available FROM"
                + " \"CustomerDeposit\" d WHERE d.\"organizationId\" = ? AND d.\"cashSaleId\" = ? ORDER BY d.\"createdAt\","
                + " d.id").params(org(), cashSaleId));
    }

    /**
     * Unapply every live application of the submission's deposits to {@code documentId} (ADR-S056 P7, before the
     * invoice is cancelled): the deposit-backed receipt is voided (reversal entry, invoice residual restored) and the
     * deposit is open again, still linked to its submission.
     */
    @Transactional
    public void unapplyCashSale(String cashSaleId, String documentId, String reason) {
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT a.* FROM \"DepositApplication\" a JOIN \"CustomerDeposit\""
                + " d ON d.id = a.\"depositId\" WHERE a.\"organizationId\" = ? AND a.\"documentId\" = ? AND a.kind ="
                + " 'application' AND a.\"reversedAt\" IS NULL AND d.\"cashSaleId\" = ? ORDER BY a.\"createdAt\"")
                .params(org(), documentId, cashSaleId))) {
            String depositId = (String) a.get("depositId");
            lock(depositId);
            workflow.transition("payment", (String) a.get("paymentId"), "void", Map.of("reason", reason == null
                    ? "Invoice cancelled" : reason), null);
            BigDecimal amount = SqlRows.dec(a, "amount");
            jdbc.sql("UPDATE \"DepositApplication\" SET \"reversedAt\" = " + NOW + " WHERE id = ?").param(a.get("id"))
                    .update();
            jdbc.sql("UPDATE \"CustomerDeposit\" SET \"appliedAmount\" = \"appliedAmount\" - ?, status = 'open',"
                    + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(amount, depositId).update();
            audit.record(AuditInput.of("CustomerDeposit", depositId, "update", null, Obj.of("unapplied", amount,
                    "documentId", documentId)));
        }
    }

    /** Refund an unapplied cash-sale deposit (ADR-S056 P6, void with refund): Dr Customer Deposits / Cr the tender. */
    @Transactional
    public Map<String, Object> refundCashSale(String depositId, String reason) {
        Map<String, Object> d = lock(depositId);
        if (!"cash_sale".equals(d.get("source"))) {
            throw ApiException.badRequest("Not a cash-sale deposit");
        }
        return refundInternal(d, null, (String) d.get("paymentMethod"), (String) d.get("accountId"), reason);
    }

    /**
     * Release a cash-sale deposit from its voided submission and keep it as a documented customer advance (ADR-S056
     * P6, void without refund): it becomes a plain receipt deposit, applicable to any invoice of the customer.
     */
    @Transactional
    public Map<String, Object> releaseCashSale(String depositId, String reason) {
        Map<String, Object> d = lock(depositId);
        if (!"cash_sale".equals(d.get("source"))) {
            throw ApiException.badRequest("Not a cash-sale deposit");
        }
        jdbc.sql("UPDATE \"CustomerDeposit\" SET source = 'receipt', notes = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params("Advance kept from voided cash sale" + (reason == null ? "" : ": " + reason), depositId).update();
        audit.record(AuditInput.of("CustomerDeposit", depositId, "update", null, Obj.of("releasedFromCashSale",
                d.get("cashSaleId"), "reason", reason)));
        return get(depositId);
    }

    private static void assertNotCashSale(Map<String, Object> d) {
        if ("cash_sale".equals(d.get("source"))) {
            throw ApiException.badRequest("A cash-sale deposit is refunded or voided only through its cash sale");
        }
    }

    /**
     * A posted down-payment invoice (P3): register the deposit for the net amount posted to the deposit account.
     * Idempotent per invoice; ignored for other invoices.
     */
    @Transactional
    public Map<String, Object> registerDownPayment(String documentId) {
        String org = org();
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT id, \"documentNumber\", \"partnerId\", status::text AS status,"
                + " \"issueDate\", data->>'downPaymentFor' AS \"orderId\" FROM \"Document\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(documentId, org));
        if (doc == null || doc.get("orderId") == null || !List.of("posted", "paid").contains(doc.get("status"))) {
            return null;
        }
        Map<String, Object> existing = SqlRows.one(jdbc.sql("SELECT id FROM \"CustomerDeposit\" WHERE \"documentId\" = ?")
                .param(documentId));
        if (existing != null) {
            return get((String) existing.get("id"));
        }
        BigDecimal net = jdbc.sql("SELECT COALESCE(sum(subtotal), 0) FROM \"DocumentLine\" WHERE \"documentId\" = ? AND"
                + " \"accountId\" = ?").params(documentId, depositAccount()).query(BigDecimal.class).single();
        if (net.signum() <= 0) {
            return null;
        }
        LocalDate date = doc.get("issueDate") instanceof Instant i ? i.atZone(ZoneOffset.UTC).toLocalDate() : LocalDate.now();
        String id = UUID.randomUUID().toString();
        String number = sequence.next("customer_deposit:" + date.getYear(), "DEP-" + date.getYear() + "-", 5);
        jdbc.sql("INSERT INTO \"CustomerDeposit\" (id, \"organizationId\", number, \"partnerId\", \"salesOrderId\", source,"
                + " \"documentId\", amount, \"receivedDate\", reference, \"createdById\") VALUES (?, ?, ?, ?, ?,"
                + " 'down_payment_invoice', ?, ?, ?, ?, ?)").params(id, org, number, doc.get("partnerId"),
                        doc.get("orderId"), documentId, net, java.sql.Date.valueOf(date), doc.get("documentNumber"),
                        TenantContext.userId()).update();
        audit.record(AuditInput.of("CustomerDeposit", id, "create", null, Obj.of("number", number, "downPaymentInvoice",
                doc.get("documentNumber"))));
        return get(id);
    }

    // ------------------------------------------------------------------ application / refund

    /** Apply part of a deposit to a posted invoice of the same customer (P9). */
    @Transactional
    public Map<String, Object> apply(String depositId, String documentId, BigDecimal amountIn) {
        Map<String, Object> d = lock(depositId);
        if (!"open".equals(d.get("status"))) {
            throw ApiException.badRequest("Deposit " + d.get("number") + " is " + d.get("status"));
        }
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT id, \"partnerId\", \"amountResidual\", status::text AS status,"
                + " data->>'downPaymentFor' AS dp FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"documentType\" = 'sales_invoice'").params(documentId, org()));
        if (doc == null) {
            throw ApiException.notFound("Invoice not found");
        }
        if ("cash_sale".equals(d.get("source")) && SqlRows.one(jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"sourceType\" = 'cash_sale' AND \"sourceId\" = ?").params(documentId, org(),
                d.get("cashSaleId"))) == null) {
            throw ApiException.badRequest("A cash-sale deposit settles only the invoice of its own cash sale");
        }
        if (!d.get("partnerId").equals(doc.get("partnerId"))) {
            throw ApiException.badRequest("The deposit and the invoice belong to different customers");
        }
        if (!List.of("posted").contains(doc.get("status"))) {
            throw ApiException.badRequest("Only a posted, unpaid invoice can receive a deposit");
        }
        if (doc.get("dp") != null && "down_payment_invoice".equals(d.get("source"))) {
            throw ApiException.badRequest("A down-payment deposit cannot settle a down-payment invoice");
        }
        BigDecimal available = available(d);
        BigDecimal residual = SqlRows.dec(doc, "amountResidual");
        BigDecimal amount = amountIn == null ? available.min(residual) : amountIn.setScale(6, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Nothing to apply");
        }
        if (amount.compareTo(available) > 0) {
            throw ApiException.badRequest("Deposit " + d.get("number") + " has only " + JsNumbers.dec(available)
                    + " available");
        }
        if (amount.compareTo(residual) > 0) {
            throw ApiException.badRequest("The invoice has only " + JsNumbers.dec(residual) + " left to pay");
        }
        Map<String, Object> pay = payments.createDepositApplication(new PaymentInput((String) d.get("partnerId"),
                LocalDate.now().toString(), amount, PaymentService.DEPOSIT_METHOD, depositAccount(), "Deposit "
                        + d.get("number"), null, List.of(new AllocationInput(documentId, null, amount)), null, false, null,
                null));
        jdbc.sql("INSERT INTO \"DepositApplication\" (id, \"organizationId\", \"depositId\", \"documentId\", \"paymentId\","
                + " amount, kind, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, 'application', ?)").params(UUID.randomUUID()
                        .toString(), org(), depositId, documentId, pay.get("id"), amount, TenantContext.userId()).update();
        settle(depositId, amount, BigDecimal.ZERO);
        audit.record(AuditInput.of("CustomerDeposit", depositId, "update", null, Obj.of("applied", amount, "documentId",
                documentId)));
        return get(depositId);
    }

    /** Refund (part of) an unapplied deposit (P10): Dr Customer Deposits / Cr tender. */
    @Transactional
    public Map<String, Object> refund(String depositId, BigDecimal amountIn, String method, String accountId,
            String reason) {
        Map<String, Object> d = lock(depositId);
        assertNotCashSale(d);
        return refundInternal(d, amountIn, method, accountId, reason);
    }

    private Map<String, Object> refundInternal(Map<String, Object> d, BigDecimal amountIn, String method,
            String accountId, String reason) {
        String depositId = (String) d.get("id");
        if (!"open".equals(d.get("status"))) {
            throw ApiException.badRequest("Deposit " + d.get("number") + " is " + d.get("status"));
        }
        BigDecimal available = available(d);
        BigDecimal amount = amountIn == null ? available : amountIn.setScale(6, RoundingMode.HALF_UP);
        if (amount.signum() <= 0 || amount.compareTo(available) > 0) {
            throw ApiException.badRequest("Refund must be between 0 and the available " + JsNumbers.dec(available));
        }
        String m = method == null ? "bank" : method;
        if ("store_credit".equals(m) || PaymentService.DEPOSIT_METHOD.equals(m)) {
            throw ApiException.badRequest("Unsupported payment method");
        }
        TenderAccount.Resolved tender = TenderAccount.resolve(jdbc, determination, org(), m, accountId, null);
        String key = "customer_deposit_refund:" + depositId + ":" + UUID.randomUUID();
        Map<String, Object> entry = posting.post(PostingRequest.of(tender.journalCode(), Instant.now(), "Deposit refund "
                + d.get("number"), "customer_deposit", depositId, key, List.of(new Line(depositAccount(), amount, null,
                        (String) d.get("partnerId"), "Refund " + d.get("number"), null, null, null), Line.credit(
                                tender.accountId(), amount, "Refund " + d.get("number")))));
        jdbc.sql("INSERT INTO \"DepositApplication\" (id, \"organizationId\", \"depositId\", \"documentId\", \"paymentId\","
                + " amount, kind, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, 'refund', ?)").params(UUID.randomUUID()
                        .toString(), org(), depositId, depositId, entry.get("id"), amount, TenantContext.userId()).update();
        settle(depositId, BigDecimal.ZERO, amount);
        audit.record(AuditInput.of("CustomerDeposit", depositId, "update", null, Obj.of("refunded", amount, "reason",
                reason)));
        return get(depositId);
    }

    /** Void an untouched receipt deposit (reverses its journal entry). */
    @Transactional
    public Map<String, Object> voidDeposit(String depositId, String reason) {
        Map<String, Object> d = lock(depositId);
        assertNotCashSale(d);
        if (!"open".equals(d.get("status")) || SqlRows.dec(d, "appliedAmount").signum() > 0
                || SqlRows.dec(d, "refundedAmount").signum() > 0 || !"receipt".equals(d.get("source"))) {
            throw ApiException.badRequest("Only an unused deposit receipt can be voided");
        }
        posting.reverse((String) d.get("journalEntryId"), Instant.now(), "Void deposit " + d.get("number") + (reason == null
                ? "" : ": " + reason));
        jdbc.sql("UPDATE \"CustomerDeposit\" SET status = 'void', notes = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(reason, depositId).update();
        audit.record(AuditInput.of("CustomerDeposit", depositId, "cancel", null, Obj.of("reason", reason)));
        return get(depositId);
    }

    private void settle(String depositId, BigDecimal applied, BigDecimal refunded) {
        jdbc.sql("UPDATE \"CustomerDeposit\" SET \"appliedAmount\" = \"appliedAmount\" + ?, \"refundedAmount\" ="
                + " \"refundedAmount\" + ?, status = CASE WHEN amount - \"appliedAmount\" - ? - \"refundedAmount\" - ? <= 0"
                + " THEN 'closed' ELSE status END, \"updatedAt\" = " + NOW + " WHERE id = ?").params(applied, refunded,
                        applied, refunded, depositId).update();
    }

    private static BigDecimal available(Map<String, Object> d) {
        return SqlRows.dec(d, "amount").subtract(SqlRows.dec(d, "appliedAmount")).subtract(SqlRows.dec(d,
                "refundedAmount"));
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT d.*, p.name AS \"partnerName\", d.amount - d.\"appliedAmount\""
                + " - d.\"refundedAmount\" AS available FROM \"CustomerDeposit\" d LEFT JOIN \"Partner\" p ON p.id ="
                + " d.\"partnerId\" WHERE d.id = ? AND d.\"organizationId\" = ?").params(id, org()));
        if (d == null) {
            throw ApiException.notFound("Deposit not found");
        }
        d.put("applications", SqlRows.list(jdbc.sql("SELECT a.*, doc.\"documentNumber\" FROM \"DepositApplication\" a LEFT"
                + " JOIN \"Document\" doc ON doc.id = a.\"documentId\" WHERE a.\"depositId\" = ? ORDER BY a.\"createdAt\"")
                .param(id)));
        return d;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String partnerId, String salesOrderId, String status) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ?");
        if (partnerId != null) {
            where.append(" AND d.\"partnerId\" = ?");
            params.add(partnerId);
        }
        if (salesOrderId != null) {
            where.append(" AND d.\"salesOrderId\" = ?");
            params.add(salesOrderId);
        }
        if (status != null) {
            where.append(" AND d.status = ?");
            params.add(status);
        }
        return SqlRows.list(jdbc.sql("SELECT d.*, p.name AS \"partnerName\", d.amount - d.\"appliedAmount\" -"
                + " d.\"refundedAmount\" AS available FROM \"CustomerDeposit\" d LEFT JOIN \"Partner\" p ON p.id ="
                + " d.\"partnerId\"" + where + " ORDER BY d.\"receivedDate\", d.\"createdAt\"").params(params));
    }

    /** Unapplied deposit balance of a customer (credit exposure bucket D). */
    @Transactional(readOnly = true)
    public BigDecimal openBalance(String partnerId) {
        return jdbc.sql("SELECT COALESCE(sum(amount - \"appliedAmount\" - \"refundedAmount\"), 0) FROM \"CustomerDeposit\""
                + " WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND status = 'open'").params(org(), partnerId)
                .query(BigDecimal.class).single();
    }

    private Map<String, Object> lock(String id) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT * FROM \"CustomerDeposit\" WHERE id = ? AND \"organizationId\" ="
                + " ? FOR UPDATE").params(id, org()));
        if (d == null) {
            throw ApiException.notFound("Deposit not found");
        }
        return d;
    }
}
