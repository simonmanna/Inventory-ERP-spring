package com.inventoryerp.invoicing.application;

import static com.inventoryerp.invoicing.application.DocumentBuilderService.NOW;
import static com.inventoryerp.invoicing.application.DocumentBuilderService.ts;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.CashSessionService;
import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.TenderAccount;
import com.inventoryerp.documents.domain.Document;
import com.inventoryerp.invoicing.domain.Payment;
import com.inventoryerp.invoicing.domain.PaymentAllocation;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.workflow.WorkflowService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Customer receipts (inbound) and supplier payments (outbound) (port of {@code payment.service.ts}): tender account
 * resolution, withholding, supplier-overpayment guard, store credit, allocations against documents / POS invoices,
 * the cash-session movement, and void through the payment workflow. Public API used by POS settle / refund.
 */
@Service
public class PaymentService {

    /** {@code PaymentAllocationDto}. */
    public record AllocationInput(String documentId, String invoiceId, BigDecimal amount) {}

    /** {@code CreatePaymentDto} (+ the programmatic {@code refundOfId}). */
    public record PaymentInput(String partnerId, String paymentDate, BigDecimal amount, String paymentMethod,
            String accountId, String reference, String branchId, List<AllocationInput> allocations,
            String cashSessionId, Boolean allowOverpayment, BigDecimal withholdingAmount, String refundOfId,
            String currencyCode, BigDecimal exchangeRate) {

        /** A payment in the organisation's base currency (the original contract). */
        public PaymentInput(String partnerId, String paymentDate, BigDecimal amount, String paymentMethod,
                String accountId, String reference, String branchId, List<AllocationInput> allocations,
                String cashSessionId, Boolean allowOverpayment, BigDecimal withholdingAmount, String refundOfId) {
            this(partnerId, paymentDate, amount, paymentMethod, accountId, reference, branchId, allocations,
                    cashSessionId, allowOverpayment, withholdingAmount, refundOfId, null, null);
        }
    }

    /** Foreign-currency settlement of a payment (PC-0, ADR-S054): rate used and per-document carrying rates. */
    private record FxSettlement(CurrencyService.Resolution rate, Map<String, BigDecimal> carrying,
            BigDecimal counterBase, BigDecimal cashBase, BigDecimal fx) {}

    /** Payment method of a customer-deposit application (only through {@link #createDepositApplication}). */
    public static final String DEPOSIT_METHOD = "customer_deposit";
    private static final ThreadLocal<Boolean> DEPOSIT_APPLICATION = new ThreadLocal<>();

    private final JdbcClient jdbc;
    private final EventBus events;
    private final SequenceService sequence;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final CashSessionService cashSessions;
    private final WorkflowService workflow;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final TransactionTemplate newTx;
    private final CurrencyService currency;
    private final DocumentFxRates fxRates;

    public PaymentService(JdbcClient jdbc, EventBus events, SequenceService sequence, PostingService posting,
            AccountDeterminationService determination, CashSessionService cashSessions, WorkflowService workflow,
            AuditService audit, ApprovalsService approvals, PlatformTransactionManager txm, CurrencyService currency,
            DocumentFxRates fxRates) {
        this.currency = currency;
        this.fxRates = fxRates;
        this.jdbc = jdbc;
        this.events = events;
        this.sequence = sequence;
        this.posting = posting;
        this.determination = determination;
        this.cashSessions = cashSessions;
        this.workflow = workflow;
        this.audit = audit;
        this.approvals = approvals;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static BigDecimal r6(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ queries

    /** Payments of one direction, newest first. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(int pageIn, int pageSizeIn, String search, String direction,
            String partnerId, String dateFrom, String dateTo) {
        int page = Math.max(1, pageIn == 0 ? 1 : pageIn);
        int pageSize = Math.min(200, Math.max(1, pageSizeIn == 0 ? 25 : pageSizeIn));
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ?");
        if (direction != null) {
            where.append(" AND direction::text = ?");
            params.add(direction);
        }
        if (partnerId != null && !partnerId.isEmpty()) {
            where.append(" AND \"partnerId\" = ?");
            params.add(partnerId);
        }
        if (search != null && !search.isEmpty()) {
            String like = "%" + BaseCrudService.escapeLike(search) + "%";
            where.append(" AND (\"paymentNumber\" ILIKE ? OR reference ILIKE ?)");
            params.add(like);
            params.add(like);
        }
        if (dateFrom != null && !dateFrom.isEmpty()) {
            where.append(" AND \"paymentDate\" >= ?");
            params.add(ts(dateFrom));
        }
        if (dateTo != null && !dateTo.isEmpty()) {
            Instant end = LocalDateTime.of(EntityPatch.toInstant(dateTo).atZone(BusinessZone.get()).toLocalDate(),
                    LocalTime.of(23, 59, 59, 999_000_000)).atZone(BusinessZone.get()).toInstant();
            where.append(" AND \"paymentDate\" <= ?");
            params.add(SqlRows.ts(end));
        }
        List<Map<String, Object>> rows = SqlRows.list(Payment.class, jdbc.sql("SELECT * FROM \"Payment\" WHERE" + where
                + " ORDER BY \"createdAt\" DESC LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize).params(params));
        for (Map<String, Object> p : rows) {
            p.put("partner", partner(p.get("partnerId")));
            p.put("_count", Obj.of("allocations", jdbc.sql("SELECT count(*) FROM \"PaymentAllocation\" WHERE"
                    + " \"paymentId\" = ?").param(p.get("id")).query(Long.class).single()));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"Payment\" WHERE" + where).params(params).query(Long.class).single();
        return PageResult.of(rows, page, pageSize, total);
    }

    private Map<String, Object> partner(Object id) {
        return id == null ? null : SqlRows.one(com.inventoryerp.core.domain.Partner.class,
                jdbc.sql("SELECT * FROM \"Partner\" WHERE id = ?").param(id));
    }

    /** One payment with partner and allocations (+ document); null when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> p = SqlRows.one(Payment.class, jdbc.sql("SELECT * FROM \"Payment\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
        if (p == null) {
            return null;
        }
        p.put("partner", partner(p.get("partnerId")));
        List<Map<String, Object>> allocs = SqlRows.list(PaymentAllocation.class, jdbc.sql("SELECT * FROM"
                + " \"PaymentAllocation\" WHERE \"paymentId\" = ?").param(id));
        for (Map<String, Object> a : allocs) {
            a.put("document", a.get("documentId") == null ? null : SqlRows.one(Document.class,
                    jdbc.sql("SELECT * FROM \"Document\" WHERE id = ?").param(a.get("documentId"))));
        }
        p.put("allocations", allocs);
        return p;
    }

    // ------------------------------------------------------------------ record

    /** Customer receipt: Dr Cash/Bank / Cr AR. */
    @Transactional
    public Map<String, Object> createReceipt(PaymentInput dto, boolean nested, boolean allowSessionOwnerMismatch) {
        return record(dto, "inbound", null, allowSessionOwnerMismatch, nested);
    }

    /**
     * Settle invoices from a customer deposit (ADR-S011 S6, sales-postings.md P9): an inbound receipt whose debit is the
     * deposit liability account ({@code dto.accountId}); allocations work exactly like a cash receipt. Only the
     * customer-deposit service calls this.
     */
    @Transactional
    public Map<String, Object> createDepositApplication(PaymentInput dto) {
        DEPOSIT_APPLICATION.set(true);
        try {
            return record(dto, "inbound", null, true, true);
        } finally {
            DEPOSIT_APPLICATION.remove();
        }
    }

    /** Supplier payment: Dr AP / Cr Cash|Bank (+ Cr withholding), behind the {@code supplier_payment} gate. */
    @Transactional
    public Map<String, Object> createSupplierPayment(PaymentInput dto, boolean nested) {
        Map<String, Object> snapshot = Obj.of("amount", JsNumbers.js(dto.amount()), "partnerId", dto.partnerId(),
                "paymentMethod", dto.paymentMethod() != null ? dto.paymentMethod() : "cash");
        Map<String, Object> approval = newTx.execute(s -> approvals.checkOrRequestApproval("supplier_payment",
                dto.partnerId(), snapshot));
        if (approval != null && Boolean.TRUE.equals(approval.get("needsApproval"))) {
            throw ApiException.forbidden("Vendor payment requires approval. Request ID: " + approval.get("requestId")
                    + ". Please have an authorized person approve it via the approvals endpoint.");
        }
        return record(dto, "outbound", null, false, nested);
    }

    /** Customer refund: outbound against the receivable (Dr AR / Cr Cash). */
    @Transactional
    public Map<String, Object> createCustomerRefund(PaymentInput dto, boolean nested, boolean allowSessionOwnerMismatch) {
        return record(dto, "outbound", "receivable", allowSessionOwnerMismatch, nested);
    }

    private Map<String, Object> record(PaymentInput dto, String direction, String counterOverride,
            boolean allowSessionOwnerMismatch, boolean nested) {
        String org = org();
        String userId = TenantContext.userId();
        Map<String, Object> partner = SqlRows.one(jdbc.sql("SELECT * FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL LIMIT 1").params(dto.partnerId(), org));
        if (partner == null) {
            throw ApiException.badRequest("Partner not found");
        }
        BigDecimal amount = r6(Money.dec(dto.amount()));
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Payment amount must be positive");
        }
        String method = dto.paymentMethod() != null ? dto.paymentMethod() : "cash";
        List<AllocationInput> allocations = dto.allocations() == null ? List.of() : dto.allocations();
        for (AllocationInput a : allocations) {
            if ((a.invoiceId() != null && !a.invoiceId().isEmpty()) == (a.documentId() != null && !a.documentId().isEmpty())) {
                throw ApiException.badRequest("Each allocation must identify exactly one invoice or document");
            }
        }
        String counterType = counterOverride != null ? counterOverride : "inbound".equals(direction) ? "receivable"
                : "payable";
        BigDecimal withholding = r6(Money.dec(dto.withholdingAmount()));
        if (withholding.signum() > 0) {
            if (!"outbound".equals(direction) || !"payable".equals(counterType)) {
                throw ApiException.badRequest("Withholding tax applies to supplier payments only");
            }
            if ("store_credit".equals(method)) {
                throw ApiException.badRequest("Withholding tax cannot be deducted from a store-credit payment");
            }
            if (withholding.compareTo(amount) >= 0) {
                throw ApiException.badRequest("Withholding tax must be less than the payment amount");
            }
        }
        BigDecimal netCash = amount.subtract(withholding);
        String baseCode = fxRates.base();
        String payCurrency = dto.currencyCode() == null || dto.currencyCode().isBlank() ? baseCode
                : dto.currencyCode().trim().toUpperCase();
        boolean foreign = !payCurrency.equals(baseCode);
        if (foreign && (withholding.signum() > 0 || "store_credit".equals(method) || DEPOSIT_METHOD.equals(method)
                || dto.cashSessionId() != null || dto.refundOfId() != null
                || allocations.stream().anyMatch(a -> a.invoiceId() != null && !a.invoiceId().isEmpty()))) {
            throw ApiException.badRequest("A foreign-currency payment settles documents only: no withholding, store"
                    + " credit, deposit, cash session, refund or POS invoice");
        }
        if ("outbound".equals(direction) && "payable".equals(counterType)) {
            BigDecimal outstanding = jdbc.sql("SELECT COALESCE(SUM(\"amountResidual\"), 0) FROM \"Document\" WHERE"
                            + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\" = 'vendor_bill'"
                            + " AND status IN ('posted', 'paid')"
                            + (foreign ? " AND upper(\"currencyId\") = ?" : " AND ?::text IS NULL"))
                    .params(org, dto.partnerId(), foreign ? payCurrency : null).query(BigDecimal.class).single();
            if (amount.compareTo(outstanding) > 0 && !Boolean.TRUE.equals(dto.allowOverpayment())) {
                throw ApiException.badRequest("Payment amount " + JsNumbers.dec(amount) + " exceeds supplier outstanding"
                        + " balance " + JsNumbers.dec(outstanding) + ". Overpayments require allowOverpayment=true.");
            }
        }
        TenderAccount.Resolved resolved;
        if (DEPOSIT_METHOD.equals(method)) {
            // Deposit application (ADR-S011 S6): the "tender" is the customer-deposit liability — Dr deposits / Cr AR.
            if (!Boolean.TRUE.equals(DEPOSIT_APPLICATION.get()) || dto.accountId() == null) {
                throw ApiException.badRequest("Unsupported payment method");
            }
            resolved = new TenderAccount.Resolved(dto.accountId(), "GEN", null);
        } else {
            resolved = TenderAccount.resolve(jdbc, determination, org, method, dto.accountId(), dto.cashSessionId());
        }
        String cashAccount = resolved.accountId();
        String sessionOwner = resolved.cashSessionId() == null ? null : jdbc.sql("SELECT \"userId\" FROM \"CashSession\""
                + " WHERE id = ?").param(resolved.cashSessionId()).query(String.class).optional().orElse(null);
        if (resolved.cashSessionId() != null && !java.util.Objects.equals(sessionOwner, userId)
                && !allowSessionOwnerMismatch) {
            throw ApiException.badRequest("Cash session belongs to a different cashier");
        }
        if ("cash".equals(method) && "outbound".equals(direction) && resolved.cashSessionId() != null
                && netCash.compareTo(cashSessions.expectedCash(resolved.cashSessionId())) > 0) {
            throw ApiException.badRequest("The drawer does not contain enough cash for this payment");
        }
        String counterAccount = "receivable".equals(counterType)
                ? determination.receivableAccount((String) partner.get("receivableAccountId"))
                : determination.payableAccount((String) partner.get("payableAccountId"));
        TreeSet<String> invoiceIds = new TreeSet<>();
        allocations.forEach(a -> {
            if (a.invoiceId() != null && !a.invoiceId().isEmpty()) {
                invoiceIds.add(a.invoiceId());
            }
        });
        Set<String> invoiceAccounts = new HashSet<>();
        for (String id : invoiceIds) {
            jdbc.sql("SELECT id FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, org)
                    .query().listOfRows();
            String receivable = jdbc.sql("SELECT \"receivableAccountId\" FROM \"Invoice\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(id, org).query(String.class).optional().orElse(null);
            if (receivable == null) {
                throw ApiException.badRequest("Reconcile the original invoice receivable account before collecting this"
                        + " payment");
            }
            invoiceAccounts.add(receivable);
        }
        boolean anyDocument = allocations.stream().anyMatch(a -> a.documentId() != null && !a.documentId().isEmpty());
        if (invoiceAccounts.size() > 1 || (!invoiceAccounts.isEmpty() && anyDocument)) {
            throw ApiException.badRequest("Collect invoices with different receivable accounts separately");
        }
        if (!invoiceAccounts.isEmpty()) {
            counterAccount = invoiceAccounts.iterator().next();
        }
        if (dto.refundOfId() != null) {
            counterAccount = refundCounterAccount(dto, direction, counterType, method, amount, cashAccount, org);
        }
        FxSettlement fxs = foreign ? fxSettlement(dto, direction, payCurrency, baseCode, amount, allocations, org)
                : null;
        String journalCode = resolved.journalCode();
        int year = EntityPatch.toInstant(dto.paymentDate()).atOffset(ZoneOffset.UTC).getYear();
        String paymentNumber = "inbound".equals(direction) ? sequence.next("payment:" + year, "PAY-" + year + "-", 6)
                : sequence.next("supplier-payment:" + year, "PV-" + year + "-", 6);
        String paymentId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Payment\" (id, \"organizationId\", \"paymentNumber\", direction, \"partnerId\","
                        + " \"paymentDate\", \"paymentMethod\", \"accountId\", \"cashSessionId\", \"refundOfId\", amount,"
                        + " \"withholdingAmount\", \"allocatedAmount\", \"unallocatedAmount\", reference, status, \"branchId\","
                        + " \"currencyId\", \"exchangeRate\", \"rateSource\", \"fxAmount\", \"createdAt\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?::\"PaymentDirection\", ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'posted', ?, ?, ?, ?, ?, "
                        + NOW + ", " + NOW + ")")
                .params(paymentId, org, paymentNumber, direction, dto.partnerId(), ts(dto.paymentDate()), method,
                        cashAccount, dto.cashSessionId(), dto.refundOfId(), amount, withholding, amount, dto.reference(),
                        dto.branchId(), fxs == null ? null : payCurrency, fxs == null ? BigDecimal.ONE : fxs.rate().rate(),
                        fxs == null ? null : fxs.rate().source(), fxs == null ? Money.ZERO : fxs.fx())
                .update();
        if ("store_credit".equals(method)) {
            storeCredit(dto, direction, amount, paymentId, org, userId);
        }
        List<Line> lines = new ArrayList<>();
        if (fxs != null) {
            lines.addAll(fxLines(fxs, direction, counterAccount, cashAccount, dto.partnerId(), payCurrency));
        } else if ("inbound".equals(direction)) {
            // a deposit application debits the customer's deposit liability: keep the partner on that line
            lines.add(new Line(cashAccount, JsNumbers.dec(amount), null, DEPOSIT_METHOD.equals(method) ? dto.partnerId()
                    : null, null, null, null, null));
            lines.add(new Line(counterAccount, null, JsNumbers.dec(amount), dto.partnerId(), null, null, null, null));
        } else {
            lines.add(new Line(counterAccount, JsNumbers.dec(amount), null, dto.partnerId(), null, null, null, null));
            lines.add(new Line(cashAccount, null, JsNumbers.dec(netCash), null, null, null, null, null));
            if (withholding.signum() > 0) {
                lines.add(new Line(determination.mapped("withholding_payable"), null, JsNumbers.dec(withholding),
                        dto.partnerId(), "Withholding tax deducted", null, null, null));
            }
        }
        String verb = "inbound".equals(direction) ? "Receipt" : "Payment";
        Map<String, Object> entry = posting.post(PostingRequest.of(journalCode, EntityPatch.toInstant(dto.paymentDate()),
                verb + " " + paymentNumber + " · " + partner.get("name"), "payment", paymentId, "payment:" + paymentId,
                lines));
        jdbc.sql("UPDATE \"Payment\" SET \"journalEntryId\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(entry.get("id"), paymentId).update();

        BigDecimal allocated = Money.ZERO;
        for (AllocationInput alloc : allocations) {
            BigDecimal allocAmount = r6(Money.dec(alloc.amount()));
            if (allocAmount.signum() <= 0) {
                continue;
            }
            if (alloc.invoiceId() != null && !alloc.invoiceId().isEmpty()) {
                jdbc.sql("SELECT id FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(alloc.invoiceId(), org).query().listOfRows();
                Map<String, Object> inv = SqlRows.one(jdbc.sql("SELECT * FROM \"Invoice\" WHERE id = ? AND"
                        + " \"organizationId\" = ?").params(alloc.invoiceId(), org));
                if (inv == null) {
                    throw ApiException.badRequest("Invoice " + alloc.invoiceId() + " not found");
                }
                if (!"inbound".equals(direction) || !dto.partnerId().equals(inv.get("partnerId"))
                        || List.of("refunded", "cancelled").contains(String.valueOf(inv.get("status")))) {
                    throw ApiException.badRequest("Invalid invoice allocation");
                }
                if (allocAmount.compareTo(Money.dec(inv.get("amountResidual"))) > 0) {
                    throw ApiException.badRequest("Allocation exceeds invoice balance");
                }
                jdbc.sql("INSERT INTO \"PaymentAllocation\" (id, \"organizationId\", \"paymentId\", \"invoiceId\", amount,"
                                + " \"createdAt\") VALUES (?, ?, ?, ?, ?, " + NOW + ")")
                        .params(UUID.randomUUID().toString(), org, paymentId, inv.get("id"), allocAmount).update();
                apply("Invoice", "InvoiceStatus", inv, allocAmount);
                allocated = allocated.add(allocAmount);
                allocatedEvent(org, paymentId, (String) inv.get("id"), allocAmount);
                continue;
            }
            Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT * FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(alloc.documentId(), org));
            if (doc == null) {
                throw ApiException.badRequest("Document " + alloc.documentId() + " not found");
            }
            if (!payCurrency.equals(fxRates.currencyOf(doc))) {
                throw ApiException.badRequest("Document " + doc.get("documentNumber") + " is in "
                        + fxRates.currencyOf(doc) + "; settle it with a " + fxRates.currencyOf(doc) + " payment");
            }
            BigDecimal carryingRate = fxs == null ? null : fxs.carrying().get((String) doc.get("id"));
            BigDecimal allocFx = carryingRate == null ? Money.ZERO : fxAmount(direction, allocAmount, carryingRate,
                    fxs.rate().rate());
            jdbc.sql("INSERT INTO \"PaymentAllocation\" (id, \"organizationId\", \"paymentId\", \"documentId\", amount,"
                            + " \"carryingRate\", \"settlementRate\", \"fxAmount\", \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                            + " ?, " + NOW + ")")
                    .params(UUID.randomUUID().toString(), org, paymentId, doc.get("id"), allocAmount, carryingRate,
                            fxs == null ? null : fxs.rate().rate(), allocFx).update();
            BigDecimal newResidual = Money.dec(doc.get("amountResidual")).subtract(allocAmount);
            if (newResidual.signum() < 0) {
                throw ApiException.badRequest("Allocation to document " + doc.get("documentNumber") + " exceeds its"
                        + " residual of " + JsNumbers.dec(Money.dec(doc.get("amountResidual")))
                        + ". Overpayments require an advance payment record.");
            }
            apply("Document", "DocumentStatus", doc, allocAmount);
            allocated = allocated.add(allocAmount);
            allocatedEvent(org, paymentId, (String) doc.get("id"), allocAmount);
            if ("inbound".equals(direction) && newResidual.signum() <= 0) {
                Map<String, Object> paid = new HashMap<>();
                paid.put("organizationId", org);
                paid.put("documentId", doc.get("id"));
                paid.put("documentNumber", doc.get("documentNumber"));
                events.publish("invoice.paid", paid);
            }
        }
        if (allocated.compareTo(amount) > 0) {
            throw ApiException.badRequest("Allocated amount exceeds the payment amount");
        }
        jdbc.sql("UPDATE \"Payment\" SET \"allocatedAmount\" = ?, \"unallocatedAmount\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ?").params(allocated, amount.subtract(allocated), paymentId).update();
        if ("cash".equals(method) && dto.cashSessionId() != null) {
            Map<String, Object> session = SqlRows.one(jdbc.sql("SELECT status::text AS status, \"userId\" FROM"
                    + " \"CashSession\" WHERE id = ? AND \"organizationId\" = ?").params(dto.cashSessionId(), org));
            if (session == null) {
                throw ApiException.badRequest("Cash session not found");
            }
            if (!"open".equals(session.get("status"))) {
                throw ApiException.badRequest("Cash session is not open");
            }
            if (!java.util.Objects.equals(session.get("userId"), userId) && !allowSessionOwnerMismatch) {
                throw ApiException.badRequest("Cash session belongs to a different cashier");
            }
            cashSessions.recordSaleOrRefund(dto.cashSessionId(), paymentId, "inbound".equals(direction) ? "sale"
                    : dto.refundOfId() != null ? "refund" : "supplier_payment", netCash);
        }
        audit.recordInTx(AuditInput.of("Payment", paymentId, "create", null, Obj.of("paymentNumber", paymentNumber,
                "direction", direction, "amount", JsNumbers.dec(amount), "partnerId", dto.partnerId())));
        Map<String, Object> received = new HashMap<>();
        received.put("organizationId", org);
        received.put("paymentId", paymentId);
        received.put("amount", JsNumbers.dec(amount));
        events.publish("payment.received", received);
        Map<String, Object> result = findOne(paymentId);
        if (!nested) {
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("id", paymentId);
            outcome.put("paymentNumber", paymentNumber);
            BusinessOutcome.record(jdbc, outcome, true);
        }
        return result;
    }

    /**
     * Base amounts of a foreign-currency payment (owner decision D-PC2): each allocated document is relieved at its
     * carrying rate, the unallocated remainder (an advance) and the cash at the payment rate; the difference is
     * realised FX (positive = gain).
     */
    private FxSettlement fxSettlement(PaymentInput dto, String direction, String payCurrency, String baseCode,
            BigDecimal amount, List<AllocationInput> allocations, String org) {
        CurrencyService.Resolution rate = currency.resolve(payCurrency, baseCode, dto.exchangeRate(),
                EntityPatch.toInstant(dto.paymentDate()));
        Map<String, BigDecimal> carrying = new HashMap<>();
        BigDecimal relieved = Money.ZERO;
        BigDecimal allocatedSum = Money.ZERO;
        for (AllocationInput a : allocations) {
            BigDecimal alloc = r6(Money.dec(a.amount()));
            if (alloc.signum() <= 0) {
                continue;
            }
            jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(a.documentId(), org).query().listOfRows();
            Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT * FROM \"Document\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(a.documentId(), org));
            if (doc == null) {
                throw ApiException.badRequest("Document " + a.documentId() + " not found");
            }
            if (!payCurrency.equals(fxRates.currencyOf(doc))) {
                throw ApiException.badRequest("Document " + doc.get("documentNumber") + " is in "
                        + fxRates.currencyOf(doc) + "; settle it with a " + fxRates.currencyOf(doc) + " payment");
            }
            BigDecimal docRate = fxRates.documentRate(doc);
            carrying.put((String) doc.get("id"), docRate);
            relieved = relieved.add(alloc.multiply(docRate));
            allocatedSum = allocatedSum.add(alloc);
        }
        BigDecimal advance = amount.subtract(allocatedSum).max(Money.ZERO).multiply(rate.rate());
        BigDecimal counterBase = r6(relieved.add(advance));
        BigDecimal cashBase = r6(amount.multiply(rate.rate()));
        BigDecimal fx = "inbound".equals(direction) ? cashBase.subtract(counterBase) : counterBase.subtract(cashBase);
        return new FxSettlement(rate, carrying, counterBase, cashBase, fx);
    }

    /** Realised FX of one allocation in base currency (positive = gain). */
    private static BigDecimal fxAmount(String direction, BigDecimal alloc, BigDecimal carryingRate,
            BigDecimal settlementRate) {
        BigDecimal diff = alloc.multiply(settlementRate.subtract(carryingRate));
        return r6("inbound".equals(direction) ? diff : diff.negate());
    }

    /** Base-currency lines of a foreign-currency payment, realised FX on the designated FX accounts only. */
    private List<Line> fxLines(FxSettlement fxs, String direction, String counterAccount, String cashAccount,
            String partnerId, String payCurrency) {
        List<Line> lines = new ArrayList<>();
        String label = "Realised FX · " + payCurrency + " @ " + JsNumbers.dec(fxs.rate().rate());
        if ("inbound".equals(direction)) {
            lines.add(new Line(cashAccount, JsNumbers.dec(fxs.cashBase()), null, null, null, null, null, null));
            lines.add(new Line(counterAccount, null, JsNumbers.dec(fxs.counterBase()), partnerId, null, null, null,
                    null));
        } else {
            lines.add(new Line(counterAccount, JsNumbers.dec(fxs.counterBase()), null, partnerId, null, null, null,
                    null));
            lines.add(new Line(cashAccount, null, JsNumbers.dec(fxs.cashBase()), null, null, null, null, null));
        }
        BigDecimal fx = fxs.fx();
        if (fx.signum() > 0) {
            lines.add(Line.credit(determination.mapped("fx_gain"), JsNumbers.dec(fx), label));
        } else if (fx.signum() < 0) {
            lines.add(Line.debit(determination.mapped("fx_loss"), JsNumbers.dec(fx.negate()), label));
        }
        return lines;
    }

    private String refundCounterAccount(PaymentInput dto, String direction, String counterType, String method,
            BigDecimal amount, String cashAccount, String org) {
        if (!"outbound".equals(direction) || !"receivable".equals(counterType)) {
            throw ApiException.badRequest("Invalid customer refund direction");
        }
        jdbc.sql("SELECT id FROM \"Payment\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(dto.refundOfId(), org).query().listOfRows();
        Map<String, Object> original = SqlRows.one(jdbc.sql("SELECT * FROM \"Payment\" WHERE id = ? AND \"organizationId\" = ?")
                .params(dto.refundOfId(), org));
        if (original == null || !"inbound".equals(String.valueOf(original.get("direction")))
                || "cancelled".equals(String.valueOf(original.get("status")))
                || !dto.partnerId().equals(original.get("partnerId")) || !method.equals(original.get("paymentMethod"))
                || amount.compareTo(Money.dec(original.get("amount")).subtract(Money.dec(original.get("refundedAmount")))) > 0) {
            throw ApiException.badRequest("Refund does not match the original payment");
        }
        if (!"cash".equals(method) && !java.util.Objects.equals(original.get("accountId"), cashAccount)) {
            throw ApiException.badRequest("Return electronic funds to the original account");
        }
        Map<String, Object> je = SqlRows.one(jdbc.sql("SELECT id, status::text AS status FROM \"JournalEntry\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(original.get("journalEntryId"), org));
        if (je == null || !"posted".equals(je.get("status"))) {
            throw ApiException.badRequest("Reconcile the reversed or missing original payment journal before refunding");
        }
        List<String> ar = jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND"
                + " \"partnerId\" = ? AND credit > 0").params(je.get("id"), dto.partnerId()).query(String.class).list();
        if (ar.isEmpty() || new HashSet<>(ar).size() != 1) {
            throw ApiException.badRequest("Original payment receivable posting is ambiguous");
        }
        return ar.getFirst();
    }

    private void storeCredit(PaymentInput dto, String direction, BigDecimal amount, String paymentId, String org,
            String userId) {
        if ("outbound".equals(direction) && dto.refundOfId() == null) {
            throw ApiException.badRequest("Store credit returns must reference the original payment");
        }
        jdbc.sql("SELECT id FROM \"StoreCredit\" WHERE \"partnerId\" = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(dto.partnerId(), org).query().listOfRows();
        Map<String, Object> credit = SqlRows.one(jdbc.sql("SELECT * FROM \"StoreCredit\" WHERE \"partnerId\" = ?"
                + " AND \"organizationId\" = ? LIMIT 1").params(dto.partnerId(), org));
        boolean expired = credit != null && credit.get("expiresAt") != null
                && !((Instant) credit.get("expiresAt")).isAfter(Instant.now());
        if (credit == null || ("inbound".equals(direction) && (!Boolean.TRUE.equals(credit.get("isActive")) || expired))) {
            throw ApiException.badRequest("Customer store credit is unavailable or expired");
        }
        BigDecimal delta = "inbound".equals(direction) ? amount.negate() : amount;
        BigDecimal after = Money.dec(credit.get("balance")).add(delta);
        if (after.signum() < 0) {
            throw ApiException.badRequest("Insufficient customer store credit");
        }
        jdbc.sql("UPDATE \"StoreCredit\" SET balance = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(after, credit.get("id")).update();
        jdbc.sql("INSERT INTO \"StoreCreditLedger\" (id, \"organizationId\", \"storeCreditId\", delta, \"balanceAfter\","
                        + " reason, \"documentId\", \"createdBy\", \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ")")
                .params(UUID.randomUUID().toString(), org, credit.get("id"), delta, after,
                        "inbound".equals(direction) ? "sale" : "refund", paymentId, userId)
                .update();
    }

    /** Settle an allocation onto an invoice / document. */
    private void apply(String table, String statusType, Map<String, Object> row, BigDecimal amount) {
        BigDecimal newPaid = Money.dec(row.get("amountPaid")).add(amount);
        BigDecimal newResidual = Money.dec(row.get("amountResidual")).subtract(amount);
        String status = newResidual.signum() <= 0 ? "paid" : String.valueOf(row.get("status"));
        jdbc.sql("UPDATE \"" + table + "\" SET \"amountPaid\" = ?, \"amountResidual\" = ?, \"paymentStatus\" ="
                        + " ?::\"PaymentStatus\", status = ?::\"" + statusType + "\", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(newPaid, newResidual, residualStatus(Money.dec(row.get("totalAmount")), newResidual), status,
                        row.get("id"))
                .update();
    }

    private void allocatedEvent(String org, String paymentId, String documentId, BigDecimal amount) {
        Map<String, Object> p = new HashMap<>();
        p.put("organizationId", org);
        p.put("paymentId", paymentId);
        p.put("documentId", documentId);
        p.put("amount", JsNumbers.dec(amount));
        events.publish("payment.allocated", p);
    }

    static String residualStatus(BigDecimal total, BigDecimal residual) {
        if (residual.signum() <= 0) {
            return residual.signum() < 0 ? "overpaid" : "paid";
        }
        return residual.compareTo(total) < 0 ? "partial" : "not_paid";
    }

    // ------------------------------------------------------------------ void

    /** Void a posted payment (reversal, drawer correction, residuals restored). */
    @Transactional
    public Map<String, Object> voidPayment(String id, String reason, String correctionSessionId) {
        String voidMethod = jdbc.sql("SELECT \"paymentMethod\" FROM \"Payment\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).query(String.class).optional().orElse(null);
        if (DEPOSIT_METHOD.equals(voidMethod)) {
            throw ApiException.badRequest("A deposit application is reversed from the customer deposit, not voided");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw ApiException.badRequest("A reason is required to void a payment");
        }
        Map<String, Object> payment = SqlRows.one(Payment.class, jdbc.sql("SELECT * FROM \"Payment\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
        if (payment == null) {
            throw ApiException.notFound("Payment not found");
        }
        if ("cancelled".equals(String.valueOf(payment.get("status")))) {
            return payment;
        }
        long invoiceAllocs = jdbc.sql("SELECT count(*) FROM \"PaymentAllocation\" WHERE \"paymentId\" = ?"
                + " AND \"invoiceId\" IS NOT NULL").param(id).query(Long.class).single();
        if (payment.get("refundOfId") != null || "store_credit".equals(payment.get("paymentMethod")) || invoiceAllocs > 0) {
            throw ApiException.badRequest("Use the linked POS invoice refund so tender, receivable and stock disposition"
                    + " remain consistent");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason.trim());
        payload.put("correctionSessionId", correctionSessionId);
        workflow.transition("payment", id, "void", payload, payment);
        Map<String, Object> voided = new HashMap<>();
        voided.put("organizationId", org());
        voided.put("paymentId", id);
        voided.put("amount", JsNumbers.dec(Money.dec(payment.get("amount"))));
        events.publish("payment.voided", voided);
        return SqlRows.one(Payment.class, jdbc.sql("SELECT * FROM \"Payment\" WHERE id = ?").param(id));
    }
}
