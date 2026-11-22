package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.accounting.application.ForeignCash;
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
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<DepositReceiptBranding> branding;
    private final CurrencyService currency;
    private final DocumentFxRates fxRates;

    public CustomerDepositService(JdbcClient jdbc, PostingService posting, PaymentService payments,
            AccountResolverService accounts, AccountDeterminationService determination, SequenceService sequence,
            AuditService audit, WorkflowService workflow, ObjectProvider<DepositReceiptBranding> branding,
            CurrencyService currency, DocumentFxRates fxRates) {
        this.currency = currency;
        this.fxRates = fxRates;
        this.workflow = workflow;
        this.branding = branding;
        this.jdbc = jdbc;
        this.posting = posting;
        this.payments = payments;
        this.accounts = accounts;
        this.determination = determination;
        this.sequence = sequence;
        this.audit = audit;
    }

    /**
     * Receipt input. {@code currencyCode} null / blank = the functional currency; a foreign deposit (ADR-S080) is
     * received into an account held in that currency, at {@code exchangeRate} (functional per unit) or the rate table.
     */
    public record DepositInput(String partnerId, String salesOrderId, BigDecimal amount, String paymentMethod,
            String accountId, String receivedDate, String reference, String notes, String currencyCode,
            BigDecimal exchangeRate, String kind) {

        /** A functional-currency receipt. */
        public DepositInput(String partnerId, String salesOrderId, BigDecimal amount, String paymentMethod,
                String accountId, String receivedDate, String reference, String notes) {
            this(partnerId, salesOrderId, amount, paymentMethod, accountId, receivedDate, reference, notes, null, null,
                    null);
        }

        /** A receipt in {@code currencyCode}; an advance (FX-2 D default). */
        public DepositInput(String partnerId, String salesOrderId, BigDecimal amount, String paymentMethod,
                String accountId, String receivedDate, String reference, String notes, String currencyCode,
                BigDecimal exchangeRate) {
            this(partnerId, salesOrderId, amount, paymentMethod, accountId, receivedDate, reference, notes, currencyCode,
                    exchangeRate, null);
        }
    }

    /** FX-2 D: deposit kinds. An advance is a contract liability (IFRIC 22); a refundable deposit is monetary. */
    public static final List<String> KINDS = List.of("advance", "refundable");

    /** The deposit's currency (functional when not stated). */
    private String currencyOf(Map<String, Object> d) {
        Object c = d.get("currencyCode");
        return c == null || c.toString().isBlank() ? fxRates.base() : c.toString().trim().toUpperCase();
    }

    private static BigDecimal r6(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** The customer-deposit liability account (created on first use, mapping {@code customer_deposit}). */
    public String depositAccount() {
        return accounts.ensureByCode("2350", "Customer Deposits", "current_liabilities", "customer_deposit");
    }

    /** FX-2 D: the refundable-deposit liability account (created on first use, mapping customer_refundable_deposit). */
    public String refundableAccount() {
        return accounts.ensureByCode("2176", "Refundable Customer Deposits", "current_liability",
                "customer_refundable_deposit");
    }

    /** FX-2 D: the liability account a deposit of {@code kind} sits on (unclassified = the advance account). */
    private String liabilityAccount(Object kind) {
        return "refundable".equals(kind) ? refundableAccount() : depositAccount();
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
        // FX-2 D: every new deposit has a kind; a sales-order deposit or a receipt without one is an advance.
        String kind = in.kind() == null || in.kind().isBlank() ? "advance" : in.kind().trim().toLowerCase();
        if (!KINDS.contains(kind)) {
            throw ApiException.badRequest("kind must be one of the following values: advance, refundable");
        }
        if ("refundable".equals(kind) && in.salesOrderId() != null) {
            throw ApiException.badRequest("A sales-order deposit is an advance, not a refundable deposit");
        }
        String base = fxRates.base();
        String cur = in.currencyCode() == null || in.currencyCode().isBlank() ? base
                : in.currencyCode().trim().toUpperCase();
        boolean foreign = !cur.equals(base);
        LocalDate date = in.receivedDate() == null ? LocalDate.now() : LocalDate.parse(in.receivedDate().substring(0, 10));
        TenderAccount.Resolved tender = TenderAccount.resolve(jdbc, determination, org, method, in.accountId(), null,
                foreign ? cur : null);
        BigDecimal rate = BigDecimal.ONE;
        String rateSource = null;
        if (foreign) {
            // ADR-S080: foreign money is received into an account held in that currency, never booked as functional
            ForeignCash.Denomination held = ForeignCash.of(jdbc, org, tender.accountId());
            if (held == null || !held.currency().equals(cur)) {
                throw ApiException.badRequest("A " + cur + " deposit must be received into a bank, cash or mobile-money"
                        + " account held in " + cur);
            }
            ForeignCash.assertDecimals(jdbc, cur, amount);
            CurrencyService.Resolution r = currency.resolveStated(cur, base, in.exchangeRate(), date.atStartOfDay()
                    .toInstant(ZoneOffset.UTC), "deposit");
            rate = r.rate();
            rateSource = r.source();
        }
        BigDecimal baseAmount = foreign ? r6(amount.multiply(rate)) : amount;
        String id = UUID.randomUUID().toString();
        String number = sequence.next("customer_deposit:" + date.getYear(), "DEP-" + date.getYear() + "-", 5);
        String depositAccount = liabilityAccount(kind);
        Line cash = Line.debit(tender.accountId(), baseAmount, "Deposit " + number);
        Line liability = new Line(depositAccount, null, baseAmount, in.partnerId(), "Deposit " + number, null, null, null);
        String fx = JsNumbers.dec(amount);
        Map<String, Object> entry = posting.post(PostingRequest.of(tender.journalCode(), date.atStartOfDay()
                .toInstant(ZoneOffset.UTC), "Customer deposit " + number, "customer_deposit", id, "customer_deposit:" + id,
                foreign ? List.of(cash.inCurrency(cur, fx), liability.inCurrency(cur, fx)) : List.of(cash, liability)));
        jdbc.sql("INSERT INTO \"CustomerDeposit\" (id, \"organizationId\", number, \"partnerId\", \"salesOrderId\", source,"
                + " \"paymentMethod\", \"accountId\", amount, \"receivedDate\", \"journalEntryId\", reference, notes,"
                + " \"createdById\", \"currencyCode\", \"exchangeRate\", \"rateSource\", kind) VALUES (?, ?, ?, ?, ?,"
                + " 'receipt', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org, number, in.partnerId(), in.salesOrderId(), method, tender.accountId(), amount,
                        java.sql.Date.valueOf(date), entry.get("id"), in.reference(), in.notes(), TenantContext.userId(),
                        foreign ? cur : null, rate, rateSource, kind)
                .update();
        audit.record(AuditInput.of("CustomerDeposit", id, "create", null, Obj.of("number", number, "amount", amount,
                "currencyCode", cur, "exchangeRate", rate, "salesOrderId", in.salesOrderId(), "kind", kind)));
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
                + " notes, \"createdById\", kind) VALUES (?, ?, ?, ?, 'cash_sale', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'advance')")
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
                + " \"issueDate\", data->>'downPaymentFor' AS \"orderId\", \"currencyId\", \"exchangeRate\","
                + " \"journalEntryId\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(documentId, org));
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
        // ADR-S080: a foreign down-payment invoice holds the deposit in its currency at the invoice's carrying rate
        String cur = fxRates.currencyOf(doc);
        boolean foreign = !cur.equals(fxRates.base());
        BigDecimal rate = foreign ? fxRates.documentRate(doc) : BigDecimal.ONE;
        String id = UUID.randomUUID().toString();
        String number = sequence.next("customer_deposit:" + date.getYear(), "DEP-" + date.getYear() + "-", 5);
        jdbc.sql("INSERT INTO \"CustomerDeposit\" (id, \"organizationId\", number, \"partnerId\", \"salesOrderId\", source,"
                + " \"documentId\", amount, \"receivedDate\", reference, \"createdById\", \"currencyCode\", \"exchangeRate\","
                + " \"rateSource\", kind) VALUES (?, ?, ?, ?, ?, 'down_payment_invoice', ?, ?, ?, ?, ?, ?, ?, ?, 'advance')")
                .params(id, org, number, doc.get("partnerId"), doc.get("orderId"), documentId, net,
                        java.sql.Date.valueOf(date), doc.get("documentNumber"), TenantContext.userId(), foreign ? cur : null,
                        rate, foreign ? "document" : null).update();
        audit.record(AuditInput.of("CustomerDeposit", id, "create", null, Obj.of("number", number, "downPaymentInvoice",
                doc.get("documentNumber"))));
        return get(id);
    }

    // ------------------------------------------------------------------ application / refund

    /** Apply part of a deposit to a posted invoice of the same customer (P9). */
    @Transactional
    public Map<String, Object> apply(String depositId, String documentId, BigDecimal amountIn) {
        return apply(depositId, documentId, amountIn, null, null);
    }

    /**
     * {@link #apply(String, String, BigDecimal)} with the application payment's reference and branch set by the caller
     * (a branch cash sale carries its tender, reference and payer onto the invoice); null keeps the defaults
     * ({@code Deposit <number>}, no branch).
     */
    @Transactional
    public Map<String, Object> apply(String depositId, String documentId, BigDecimal amountIn, String reference,
            String branchId) {
        Map<String, Object> d = lock(depositId);
        if (!"open".equals(d.get("status"))) {
            throw ApiException.badRequest("Deposit " + d.get("number") + " is " + d.get("status"));
        }
        if ("refundable".equals(d.get("kind"))) {
            throw ApiException.badRequest("Deposit " + d.get("number") + " is a refundable deposit; classify it as an"
                    + " advance before applying it to an invoice");
        }
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT id, \"partnerId\", \"amountResidual\", status::text AS status,"
                + " data->>'downPaymentFor' AS dp, \"currencyId\", \"documentNumber\" FROM \"Document\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"documentType\" = 'sales_invoice'").params(documentId, org()));
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
        String cur = currencyOf(d);
        if (!cur.equals(fxRates.currencyOf(doc))) {
            // ADR-S080: no silent conversion; a deposit settles invoices in its own currency only
            throw ApiException.badRequest("Deposit " + d.get("number") + " is in " + cur + "; it can only settle "
                    + cur + " invoices (" + doc.get("documentNumber") + " is in " + fxRates.currencyOf(doc) + ")");
        }
        boolean foreign = !cur.equals(fxRates.base());
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
        // a foreign deposit is relieved at its own rate (non-monetary, IFRIC 22); the gap to the invoice rate is
        // realised FX, computed by the payment's FX settlement exactly as for a foreign receipt
        Map<String, Object> pay = payments.createDepositApplication(new PaymentInput((String) d.get("partnerId"),
                LocalDate.now().toString(), amount, PaymentService.DEPOSIT_METHOD, depositAccount(),
                reference == null ? "Deposit " + d.get("number") : reference, branchId,
                List.of(new AllocationInput(documentId, null, amount)), null, false, null,
                null, foreign ? cur : null, foreign ? SqlRows.dec(d, "exchangeRate") : null, null, null));
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
        String cur = currencyOf(d);
        boolean foreign = !cur.equals(fxRates.base());
        TenderAccount.Resolved tender = TenderAccount.resolve(jdbc, determination, org(), m, accountId, null,
                foreign ? cur : null);
        String key = "customer_deposit_refund:" + depositId + ":" + UUID.randomUUID();
        String label = "Refund " + d.get("number");
        List<Line> lines;
        if (!foreign) {
            lines = List.of(new Line(liabilityAccount(d.get("kind")), amount, null, (String) d.get("partnerId"), label,
                    null, null, null), Line.credit(tender.accountId(), amount, label));
        } else {
            lines = foreignRefundLines(d, cur, amount, tender.accountId(), exchangeRate.get(), label);
        }
        Map<String, Object> entry = posting.post(PostingRequest.of(tender.journalCode(), Instant.now(), "Deposit refund "
                + d.get("number"), "customer_deposit", depositId, key, lines));
        jdbc.sql("INSERT INTO \"DepositApplication\" (id, \"organizationId\", \"depositId\", \"documentId\", \"paymentId\","
                + " amount, kind, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, 'refund', ?)").params(UUID.randomUUID()
                        .toString(), org(), depositId, depositId, entry.get("id"), amount, TenantContext.userId()).update();
        settle(depositId, BigDecimal.ZERO, amount);
        audit.record(AuditInput.of("CustomerDeposit", depositId, "update", null, Obj.of("refunded", amount, "reason",
                reason)));
        return get(depositId);
    }

    /** Spot rate stated on a foreign refund (only used beyond the account's balance; else the rate table). */
    private static final ThreadLocal<BigDecimal> exchangeRate = new ThreadLocal<>();

    /**
     * Refund of a foreign deposit (ADR-S080): the liability is relieved at the deposit's own rate, the money leaves the
     * account held in that currency at its carrying value (R-8 relief, funds checked in the currency), and the
     * difference is realised FX.
     */
    private List<Line> foreignRefundLines(Map<String, Object> d, String cur, BigDecimal amount, String cashAccount,
            BigDecimal statedRate, String label) {
        String org = org();
        ForeignCash.Denomination held = ForeignCash.of(jdbc, org, cashAccount);
        if (held == null || !held.currency().equals(cur)) {
            throw ApiException.badRequest("A " + cur + " deposit is refunded from a bank, cash or mobile-money account"
                    + " held in " + cur);
        }
        com.inventoryerp.accounting.application.TreasuryGuards.lockAccounts(jdbc, org, List.of(cashAccount));
        ForeignCash.Balance bal = ForeignCash.balance(jdbc, org, cashAccount, cur);
        ForeignCash.assertFunds(bal, amount, held.name(), cur);
        BigDecimal spot = currency.resolveStated(cur, fxRates.base(), statedRate, Instant.now(), "deposit").rate();
        BigDecimal cashBase = ForeignCash.relieve(bal, amount, spot);
        BigDecimal depositBase = available(d).compareTo(amount) == 0 ? remainingBase(d) : r6(amount.multiply(SqlRows.dec(d,
                "exchangeRate")));
        String fx = JsNumbers.dec(amount);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(liabilityAccount(d.get("kind")), depositBase, null, (String) d.get("partnerId"), label, null,
                null, null).inCurrency(cur, fx));
        lines.add(Line.credit(cashAccount, cashBase, label).inCurrency(cur, fx));
        BigDecimal diff = depositBase.subtract(cashBase);
        String fxLabel = "Realised FX · " + cur + " deposit " + d.get("number");
        if (diff.signum() > 0) {
            lines.add(Line.credit(determination.mapped("fx_gain"), diff, fxLabel));
        } else if (diff.signum() < 0) {
            lines.add(Line.debit(determination.mapped("fx_loss"), diff.negate(), fxLabel));
        }
        return lines;
    }

    /** Functional value still on the liability for this deposit (exact on the last relief, no rounding residue). */
    private BigDecimal remainingBase(Map<String, Object> d) {
        BigDecimal rate = SqlRows.dec(d, "exchangeRate");
        BigDecimal used = SqlRows.dec(d, "appliedAmount").add(SqlRows.dec(d, "refundedAmount"));
        return r6(SqlRows.dec(d, "amount").multiply(rate)).subtract(r6(used.multiply(rate)));
    }

    /** Refund with a stated spot rate for a foreign deposit (see {@link #refund}). */
    @Transactional
    public Map<String, Object> refund(String depositId, BigDecimal amountIn, String method, String accountId,
            String reason, BigDecimal statedRate) {
        exchangeRate.set(statedRate);
        try {
            return refund(depositId, amountIn, method, accountId, reason);
        } finally {
            exchangeRate.remove();
        }
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

    /**
     * Reverse one application of a deposit to an invoice (document reversals, owner decision 2026-11-17: a payment
     * that is a deposit application cannot be voided directly, and only a cash sale could unapply one). With a reason,
     * under the deposit's row lock, in one transaction: the application's payment is voided through the payment workflow
     * (its journal entry mirrored — Dr AR / Cr customer deposits — and the invoice's residual and status restored), the
     * application is stamped {@code reversedAt} (kept, never deleted) and the amount returns to the deposit, which
     * re-opens. A replay of a reversed application returns the deposit unchanged. Cash-sale deposits stay with their
     * cash sale.
     */
    @Transactional
    public Map<String, Object> reverseApplication(String depositId, String paymentId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A reason is required to reverse a deposit application");
        }
        Map<String, Object> d = lock(depositId);
        assertNotCashSale(d);
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT * FROM \"DepositApplication\" WHERE \"organizationId\" = ? AND"
                + " \"depositId\" = ? AND \"paymentId\" = ? AND kind = 'application'").params(org(), depositId, paymentId));
        if (a == null) {
            throw ApiException.notFound("Deposit application not found");
        }
        if (a.get("reversedAt") != null) {
            return get(depositId);
        }
        workflow.transition("payment", paymentId, "void", Map.of("reason", "Deposit application reversed: "
                + reason.strip()), null);
        BigDecimal amount = SqlRows.dec(a, "amount");
        jdbc.sql("UPDATE \"DepositApplication\" SET \"reversedAt\" = " + NOW + " WHERE id = ?").param(a.get("id"))
                .update();
        jdbc.sql("UPDATE \"CustomerDeposit\" SET \"appliedAmount\" = \"appliedAmount\" - ?, status = 'open',"
                + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(amount, depositId).update();
        audit.recordInTx(AuditInput.of("CustomerDeposit", depositId, "update", null, Obj.of("unapplied", amount,
                "documentId", a.get("documentId"), "paymentId", paymentId, "reason", reason.strip())));
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

    /**
     * FX-2 D: open-or-closed deposits not yet classified, oldest first, for one-by-one review (no blanket
     * classification). Each row carries its source, order, currency and open amount.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> classificationReview() {
        return SqlRows.list(jdbc.sql("SELECT d.id, d.number, d.\"partnerId\", p.name AS \"partnerName\", d.source,"
                + " d.\"salesOrderId\", d.status, coalesce(d.\"currencyCode\", o.\"currencyCode\") AS currency,"
                + " d.amount, d.amount - d.\"appliedAmount\" - d.\"refundedAmount\" AS available, d.\"receivedDate\""
                + " FROM \"CustomerDeposit\" d JOIN \"Organization\" o ON o.id = d.\"organizationId\" LEFT JOIN"
                + " \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.\"organizationId\" = ? AND d.kind IS NULL"
                + " ORDER BY d.\"receivedDate\", d.number").param(org()));
    }

    /**
     * FX-2 D: classify a deposit as {@code advance} or {@code refundable} (audited, with a reason). When the open part
     * moves between the advance account and the refundable account it is transferred at its carrying value through
     * the posting engine (source {@code deposit_classification}): no FX. A closed deposit is classified for reporting
     * only. Reclassifying is the only way back.
     */
    @Transactional
    public Map<String, Object> classify(String depositId, String kind, String reason) {
        if (kind == null || !KINDS.contains(kind)) {
            throw ApiException.badRequest("kind must be one of the following values: advance, refundable");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A reason is required to classify a deposit");
        }
        Map<String, Object> d = lock(depositId);
        Object current = d.get("kind");
        if (kind.equals(current)) {
            throw ApiException.badRequest("Deposit " + d.get("number") + " is already classified as " + kind);
        }
        if ("refundable".equals(kind) && "cash_sale".equals(d.get("source"))) {
            throw ApiException.badRequest("A cash-sale deposit is an advance on its own sale");
        }
        String from = liabilityAccount(current);
        String to = liabilityAccount(kind);
        BigDecimal available = available(d);
        String entryId = null;
        if ("open".equals(d.get("status")) && available.signum() > 0 && !from.equals(to)) {
            String cur = currencyOf(d);
            boolean foreign = !cur.equals(fxRates.base());
            BigDecimal carrying = foreign ? remainingBase(d) : available;
            String label = "Deposit " + d.get("number") + " classified " + kind;
            Line out = new Line(from, carrying, null, (String) d.get("partnerId"), label, null, null, null);
            Line in = new Line(to, null, carrying, (String) d.get("partnerId"), label, null, null, null);
            String fx = JsNumbers.dec(available);
            Map<String, Object> entry = posting.post(PostingRequest.of("GEN", Instant.now(), label,
                    "deposit_classification", depositId, "deposit_classification:" + depositId + ":" + UUID.randomUUID(),
                    foreign ? List.of(out.inCurrency(cur, fx), in.inCurrency(cur, fx)) : List.of(out, in)));
            entryId = (String) entry.get("id");
        }
        jdbc.sql("UPDATE \"CustomerDeposit\" SET kind = ?, \"classifiedAt\" = now(), \"classifiedById\" = ?,"
                + " \"classificationReason\" = ?, \"updatedAt\" = now() WHERE id = ?")
                .params(kind, TenantContext.userId(), reason.trim(), depositId).update();
        audit.record(AuditInput.of("CustomerDeposit", depositId, "update", Obj.of("kind", current), Obj.of("kind", kind,
                "reason", reason.trim(), "transferEntryId", entryId)));
        return get(depositId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT d.*, p.name AS \"partnerName\", d.amount - d.\"appliedAmount\""
                + " - d.\"refundedAmount\" AS available, round((d.amount - d.\"appliedAmount\" - d.\"refundedAmount\") *"
                + " d.\"exchangeRate\", 6) AS \"availableBase\", round(d.amount * d.\"exchangeRate\", 6)"
                + " AS \"baseAmount\", coalesce(d.\"currencyCode\", o.\"currencyCode\") AS currency FROM"
                + " \"Organization\" o, \"CustomerDeposit\" d LEFT JOIN \"Partner\" p ON p.id ="
                + " d.\"partnerId\" WHERE d.id = ? AND d.\"organizationId\" = ? AND o.id = d.\"organizationId\"").params(id,
                        org()));
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
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ? AND o.id = d.\"organizationId\"");
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
                + " d.\"refundedAmount\" AS available, round((d.amount - d.\"appliedAmount\" - d.\"refundedAmount\") *"
                + " d.\"exchangeRate\", 6) AS \"availableBase\", round(d.amount * d.\"exchangeRate\", 6)"
                + " AS \"baseAmount\", coalesce(d.\"currencyCode\", o.\"currencyCode\") AS currency FROM"
                + " \"Organization\" o, \"CustomerDeposit\" d LEFT JOIN \"Partner\" p ON p.id ="
                + " d.\"partnerId\"" + where + " ORDER BY d.\"receivedDate\", d.\"createdAt\"").params(params));
    }

    /** A rendered deposit receipt. */
    public record ReceiptPdf(String fileName, byte[] content) {}

    /** Printable receipt of a deposit (any source), headed as set in Sales settings → Deposit receipt. */
    @Transactional(readOnly = true)
    public ReceiptPdf receiptPdf(String id) {
        Map<String, Object> d = get(id);
        return new ReceiptPdf("deposit-" + d.get("number") + ".pdf", CustomerDepositReceiptPdf.render(receipt(d,
                branding(null))));
    }

    /**
     * Preview of an unsaved receipt layout (Sales settings): the latest deposit of the organization printed with it, or
     * a sample receipt when there is none. Nothing is saved.
     */
    @Transactional(readOnly = true)
    public byte[] previewPdf(Object unsavedLayout) {
        DepositReceiptBranding.Branding b = branding(unsavedLayout == null ? Map.of() : unsavedLayout);
        Map<String, Object> latest = SqlRows.one(jdbc.sql("SELECT id FROM \"CustomerDeposit\" WHERE \"organizationId\" = ?"
                + " ORDER BY \"createdAt\" DESC LIMIT 1").param(org()));
        if (latest != null) {
            return CustomerDepositReceiptPdf.render(receipt(get((String) latest.get("id")), b));
        }
        Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?")
                .param(org()));
        String currency = o == null || o.get("currencyCode") == null ? "" : str(o.get("currencyCode"));
        return CustomerDepositReceiptPdf.render(new CustomerDepositReceiptPdf.Receipt(b, "DEP-0000-00000",
                LocalDate.now().toString(), "open", "Sample Customer Ltd", "CUST-000", null, "SO-0000-00000",
                "Advance / deposit", "Bank transfer", "TRX-0000", null, currency, new BigDecimal("1000000"),
                new BigDecimal("400000"), BigDecimal.ZERO, new BigDecimal("600000"), null, List.of(
                        new CustomerDepositReceiptPdf.HistoryRow(LocalDate.now().toString(), "Applied to invoice"
                                + " INV-0000-000000", new BigDecimal("400000"), false)), null));
    }

    private DepositReceiptBranding.Branding branding(Object unsavedLayout) {
        DepositReceiptBranding provider = branding.getIfAvailable();
        if (provider != null) {
            return provider.branding(unsavedLayout);
        }
        Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT name, \"receiptHeader\" FROM \"Organization\" WHERE id = ?")
                .param(org()));
        CustomerDepositReceiptPdf.Header h = header(o);
        return new DepositReceiptBranding.Branding("DEPOSIT RECEIPT", h.businessName(), h.lines(), null,
                DEFAULT_FOOTER, true);
    }

    /** Receipt footer when no layout sets one. */
    public static final String DEFAULT_FOOTER = "This receipt confirms an advance payment held on the customer's"
            + " account. It is not a tax invoice. The amount will be set against future invoices or refunded.";

    private CustomerDepositReceiptPdf.Receipt receipt(Map<String, Object> d, DepositReceiptBranding.Branding b) {
        String org = org();
        Map<String, Object> partner = SqlRows.one(jdbc.sql("SELECT name, code, \"taxNumber\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(d.get("partnerId"), org));
        String order = d.get("salesOrderId") == null ? null : jdbc.sql("SELECT number FROM \"SalesOrder\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(d.get("salesOrderId"), org).query(String.class).optional().orElse(null);
        String receivedBy = d.get("createdById") == null ? null : jdbc.sql("SELECT trim(\"firstName\" || ' ' ||"
                + " coalesce(\"lastName\", '')) FROM \"User\" WHERE id = ?").param(d.get("createdById")).query(String.class)
                .optional().orElse(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apps = (List<Map<String, Object>>) d.get("applications");
        List<CustomerDepositReceiptPdf.HistoryRow> history = apps.stream().map(a -> new CustomerDepositReceiptPdf.HistoryRow(
                day(a.get("createdAt")), "refund".equals(a.get("kind")) ? "Refund" : "Applied to invoice "
                        + (a.get("documentNumber") == null ? "" : a.get("documentNumber")), SqlRows.dec(a, "amount"),
                a.get("reversedAt") != null)).toList();
        String source = switch (String.valueOf(d.get("source"))) {
            case "down_payment_invoice" -> "Down payment (invoice " + (d.get("reference") == null ? "" : d.get("reference"))
                    + ")";
            case "cash_sale" -> "Cash sale payment";
            default -> "Advance / deposit";
        };
        String cur = currencyOf(d);
        String exchangeNote = null;
        if (!cur.equals(fxRates.base())) {
            java.text.DecimalFormat f = new java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols.getInstance(
                    java.util.Locale.US));
            exchangeNote = "1 " + cur + " = " + f.format(SqlRows.dec(d, "exchangeRate")) + " " + fxRates.base() + " ("
                    + fxRates.base() + " " + f.format(SqlRows.dec(d, "baseAmount")) + ")";
        }
        return new CustomerDepositReceiptPdf.Receipt(b, str(d.get("number")), day(d.get("receivedDate")),
                str(d.get("status")), partner == null ? "" : str(partner.get("name")), partner == null ? null
                        : (String) partner.get("code"), partner == null ? null : (String) partner.get("taxNumber"), order,
                source, methodLabel((String) d.get("paymentMethod")), "down_payment_invoice".equals(d.get("source"))
                        ? null : (String) d.get("reference"), (String) d.get("notes"), cur, SqlRows.dec(d, "amount"),
                SqlRows.dec(d,
                                        "appliedAmount"), SqlRows.dec(d, "refundedAmount"), SqlRows.dec(d, "available"),
                receivedBy, history, exchangeNote);
    }

    private static CustomerDepositReceiptPdf.Header header(Map<String, Object> o) {
        String name = o == null ? "ERP" : str(o.get("name"));
        List<String> lines = new ArrayList<>();
        if (o != null && o.get("receiptHeader") instanceof tools.jackson.databind.JsonNode n) {
            if (n.hasNonNull("businessName") && !n.get("businessName").asString().isBlank()) {
                name = n.get("businessName").asString();
            }
            for (String k : List.of("addressLine1", "addressLine2", "phone")) {
                if (n.hasNonNull(k) && !n.get(k).asString().isBlank()) {
                    lines.add(n.get(k).asString());
                }
            }
            if (n.hasNonNull("taxId") && !n.get("taxId").asString().isBlank()) {
                lines.add("TIN: " + n.get("taxId").asString());
            }
        }
        return new CustomerDepositReceiptPdf.Header(name, lines);
    }

    private static String methodLabel(String m) {
        if (m == null) {
            return null;
        }
        return switch (m) {
            case "bank" -> "Bank transfer";
            case "cash" -> "Cash";
            case "cheque" -> "Cheque";
            case "mobile_money" -> "Mobile money";
            default -> m.replace('_', ' ');
        };
    }

    private static String day(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Instant i) {
            return i.atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().toLocalDate().toString();
        }
        String s = v.toString();
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /** Unapplied deposit balance of a customer (credit exposure bucket D). */
    @Transactional(readOnly = true)
    public BigDecimal openBalance(String partnerId) {
        // functional value at each deposit's own rate (ADR-S080)
        return jdbc.sql("SELECT COALESCE(sum((amount - \"appliedAmount\" - \"refundedAmount\") * \"exchangeRate\"), 0) FROM"
                + " \"CustomerDeposit\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND status = 'open'").params(org(),
                        partnerId)
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
