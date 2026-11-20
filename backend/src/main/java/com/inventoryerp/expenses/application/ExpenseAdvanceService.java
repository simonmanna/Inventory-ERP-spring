package com.inventoryerp.expenses.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.ForeignCash;
import com.inventoryerp.accounting.application.TreasuryGuards;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee cash advances (EX-3, ADR-S052): request → approve (not by the employee) → disburse (Dr employee expense
 * advance / Cr cash-bank) → settled by expense claims (Dr reimbursement payable / Cr advance) and refunds (Dr cash /
 * Cr advance). An advance is {@code settled} once nothing is outstanding; a claim larger than the advance is topped
 * up by paying the claim.
 */
@Service
public class ExpenseAdvanceService {

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Request body. */
    public record AdvanceInput(String employeeId, String purpose, BigDecimal amount, String neededBy, String branchId,
            String notes, String currencyCode) {

        /** A functional-currency advance. */
        public AdvanceInput(String employeeId, String purpose, BigDecimal amount, String neededBy, String branchId,
                String notes) {
            this(employeeId, purpose, amount, neededBy, branchId, notes, null);
        }
    }

    /** Disbursement / refund body. */
    public record MoneyInput(String paymentMethod, String accountId, String reference, BigDecimal amount,
            BigDecimal exchangeRate) {

        /** Without a stated exchange rate. */
        public MoneyInput(String paymentMethod, String accountId, String reference, BigDecimal amount) {
            this(paymentMethod, accountId, reference, amount, null);
        }
    }

    private final JdbcClient jdbc;
    private final SequenceService sequence;
    private final AuditService audit;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final SegregationOfDutiesService sod;
    private final ExpenseControlService controls;
    private final EventBus events;
    private final PettyCashService pettyCash;
    private final com.inventoryerp.accounting.application.CurrencyService currencies;

    public ExpenseAdvanceService(JdbcClient jdbc, SequenceService sequence, AuditService audit, PostingService posting,
            AccountResolverService accounts, SegregationOfDutiesService sod, ExpenseControlService controls,
            EventBus events, PettyCashService pettyCash,
            com.inventoryerp.accounting.application.CurrencyService currencies) {
        this.currencies = currencies;
        this.jdbc = jdbc;
        this.sequence = sequence;
        this.audit = audit;
        this.posting = posting;
        this.accounts = accounts;
        this.sod = sod;
        this.controls = controls;
        this.events = events;
        this.pettyCash = pettyCash;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String actor() {
        String id = TenantContext.userId();
        if (id == null) {
            throw ApiException.forbidden("An authenticated user is required");
        }
        return id;
    }

    /** The employee expense advance account (created with its mapping on first use). */
    String advanceAccount() {
        return accounts.ensureByCode("1357", "Employee Expense Advances", "current_asset", "expense_advance");
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String employeeId) {
        StringBuilder where = new StringBuilder(" WHERE \"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status);
        }
        if (employeeId != null && !employeeId.isBlank()) {
            where.append(" AND \"employeeId\" = ?");
            params.add(employeeId);
        }
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ExpenseAdvance\"" + where + " ORDER BY \"createdAt\" DESC LIMIT 500")
                .params(params)).stream().map(this::shape).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> a = shape(require(id, false));
        a.put("movements", SqlRows.list(jdbc.sql("SELECT id, kind, amount, \"claimId\", \"accountId\", \"journalEntryId\","
                + " reference, \"createdById\", \"createdAt\" FROM \"ExpenseAdvanceMovement\" WHERE \"advanceId\" = ?"
                + " AND \"organizationId\" = ? ORDER BY \"createdAt\"").params(id, org())).stream().map(m -> {
                    Map<String, Object> x = new LinkedHashMap<>(m);
                    x.put("amount", JsNumbers.js(Money.dec(m.get("amount"))));
                    return x;
                }).toList());
        a.put("claims", SqlRows.list(jdbc.sql("SELECT id, \"claimNumber\", title, status, \"totalAmount\","
                + " \"advanceApplied\" FROM \"ExpenseClaim\" WHERE \"advanceId\" = ? AND \"organizationId\" = ?"
                + " ORDER BY \"createdAt\"").params(id, org())));
        return a;
    }

    private Map<String, Object> shape(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        for (String k : List.of("amount", "disbursedAmount", "settledAmount", "refundedAmount")) {
            m.put(k, JsNumbers.js(Money.dec(r.get(k))));
        }
        m.put("outstanding", JsNumbers.js(outstanding(r)));
        Map<String, Object> u = SqlRows.one(jdbc.sql("SELECT \"firstName\", \"lastName\", email FROM \"User\" WHERE id = ?")
                .param(r.get("employeeId")));
        m.put("employee", u);
        return m;
    }

    static BigDecimal outstanding(Map<String, Object> a) {
        return Money.dec(a.get("disbursedAmount")).subtract(Money.dec(a.get("settledAmount")))
                .subtract(Money.dec(a.get("refundedAmount")));
    }

    Map<String, Object> require(String id, boolean lock) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT * FROM \"ExpenseAdvance\" WHERE id = ? AND \"organizationId\" = ?"
                + (lock ? " FOR UPDATE" : "")).params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Expense advance not found");
        }
        return a;
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public Map<String, Object> create(AdvanceInput in) {
        String actorId = actor();
        if (in.purpose() == null || in.purpose().isBlank()) {
            throw ApiException.badRequest("A purpose is required");
        }
        if (in.amount() == null || in.amount().signum() <= 0) {
            throw ApiException.badRequest("amount must be a positive number");
        }
        String employee = in.employeeId() == null || in.employeeId().isBlank() ? actorId : in.employeeId();
        if (jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(employee, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("User not found");
        }
        // FX-4: an advance may be in a foreign currency; its amounts are then in that currency
        String ccy = blank(in.currencyCode()) == null ? null : in.currencyCode().trim().toUpperCase();
        if (ccy != null && ccy.equals(posting.baseCurrency())) {
            ccy = null;
        }
        if (ccy != null && jdbc.sql("SELECT count(*) FROM \"Currency\" WHERE upper(code) = ?").param(ccy)
                .query(Long.class).single() == 0) {
            throw ApiException.badRequest("Currency " + ccy + " not found");
        }
        String id = UUID.randomUUID().toString();
        String number = sequence.next("expense_advance", "ADV-", 5);
        jdbc.sql("INSERT INTO \"ExpenseAdvance\" (id, \"organizationId\", \"advanceNumber\", \"employeeId\", purpose, amount,"
                        + " \"neededBy\", \"branchId\", notes, \"createdById\", \"currencyCode\") VALUES (?, ?, ?, ?, ?, ?,"
                        + " ?::date, ?, ?, ?, ?)")
                .params(id, org(), number, employee, in.purpose().trim(), in.amount(), blank(in.neededBy()),
                        blank(in.branchId()), in.notes(), actorId, ccy)
                .update();
        audit.recordInTx(AuditInput.of("ExpenseAdvance", id, "create", null, Obj.of("advanceNumber", number, "amount",
                JsNumbers.js(in.amount()))));
        publish("expense.advance_requested", Obj.of("advanceId", id));
        return findOne(id);
    }

    @Transactional
    public Map<String, Object> approve(String id, String notes) {
        Map<String, Object> a = require(id, true);
        expect(a, "requested");
        String actorId = actor();
        if (actorId.equals(a.get("employeeId")) || actorId.equals(a.get("createdById"))) {
            throw ApiException.forbidden("The employee or requester of an advance cannot approve it");
        }
        jdbc.sql("UPDATE \"ExpenseAdvance\" SET status = 'approved', \"approvedById\" = ?, \"approvedAt\" = " + NOW
                + ", \"decisionNotes\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?").params(actorId, notes, id).update();
        audit.recordInTx(AuditInput.of("ExpenseAdvance", id, "approve", null, Obj.of("reason", notes)));
        controls.notify("notifyOnDecision", (String) a.get("employeeId"), "Advance " + a.get("advanceNumber")
                + " approved", (String) a.get("purpose"), Obj.of("advanceId", id));
        return findOne(id);
    }

    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> a = require(id, true);
        expect(a, "requested");
        jdbc.sql("UPDATE \"ExpenseAdvance\" SET status = 'rejected', \"decisionNotes\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ?").params(reason, id).update();
        audit.recordInTx(AuditInput.of("ExpenseAdvance", id, "reject", null, Obj.of("reason", reason)));
        controls.notify("notifyOnDecision", (String) a.get("employeeId"), "Advance " + a.get("advanceNumber")
                + " rejected", reason, Obj.of("advanceId", id));
        return findOne(id);
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> a = require(id, true);
        if (!List.of("requested", "approved").contains(a.get("status"))) {
            throw ApiException.badRequest("Only a requested or approved advance can be cancelled");
        }
        jdbc.sql("UPDATE \"ExpenseAdvance\" SET status = 'cancelled', \"updatedAt\" = " + NOW + " WHERE id = ?").param(id)
                .update();
        audit.recordInTx(AuditInput.of("ExpenseAdvance", id, "cancel"));
        return findOne(id);
    }

    /** Pay out an approved advance: Dr employee expense advance / Cr the cash-equivalent account. */
    @Transactional
    public Map<String, Object> disburse(String id, MoneyInput in) {
        Map<String, Object> a = require(id, true);
        expect(a, "approved");
        String actorId = actor();
        sod.assertSeparated(SegregationOfDutiesService.EXPENSE_PAYMENT, "ExpenseAdvance", id,
                List.of((String) a.get("employeeId")));
        BigDecimal amount = Money.dec(a.get("amount"));
        String cash = cashAccount(in.accountId());
        if (a.get("currencyCode") != null) {
            return disburseForeign(a, in, amount, cash, actorId);
        }
        ForeignCash.assertFunctional(jdbc, org(), cash, "An expense advance");
        TreasuryGuards.assertSufficientFunds(jdbc, org(), cash, amount, accountName(cash));
        pettyCash.assertCanSpend(cash, amount);
        String value = JsNumbers.dec(amount);
        String label = "Advance " + a.get("advanceNumber") + " - " + a.get("purpose");
        String movementId = UUID.randomUUID().toString();
        Map<String, Object> entry = posting.post(PostingRequest.of(ExpensesService.journalForMethod(in.paymentMethod()),
                Instant.now(), label, "expense_advance", id, "expense_advance:" + movementId, List.of(
                        new Line(advanceAccount(), value, null, null, label, (String) a.get("branchId"), null, null),
                        Line.credit(cash, value, label))));
        movement(movementId, id, "disburse", amount, null, cash, (String) entry.get("id"), in.reference(), actorId);
        jdbc.sql("UPDATE \"ExpenseAdvance\" SET status = 'disbursed', \"disbursedAmount\" = ?, \"disbursedAt\" = " + NOW
                + ", \"updatedAt\" = " + NOW + " WHERE id = ?").params(amount, id).update();
        audit.recordInTx(AuditInput.of("ExpenseAdvance", id, "post", null, Obj.of("disbursed", value, "journalEntryId",
                entry.get("id"))));
        publish("expense.advance_disbursed", Obj.of("advanceId", id));
        controls.notify("notifyOnPayment", (String) a.get("employeeId"), "Advance " + a.get("advanceNumber")
                + " paid out", label, Obj.of("advanceId", id));
        return findOne(id);
    }

    /** The employee returns unused cash: Dr the cash-equivalent account / Cr employee expense advance. */
    @Transactional
    public Map<String, Object> refund(String id, MoneyInput in) {
        Map<String, Object> a = require(id, true);
        expect(a, "disbursed");
        BigDecimal open = outstanding(a);
        BigDecimal amount = in.amount() == null ? open : in.amount();
        if (amount.signum() <= 0 || amount.compareTo(open) > 0) {
            throw ApiException.badRequest("Refund amount must be between 0 and the outstanding advance of "
                    + JsNumbers.dec(open));
        }
        String cash = cashAccount(in.accountId());
        if (a.get("currencyCode") != null) {
            return refundForeign(a, in, amount, cash);
        }
        String value = JsNumbers.dec(amount);
        String label = "Advance " + a.get("advanceNumber") + " refund";
        String movementId = UUID.randomUUID().toString();
        Map<String, Object> entry = posting.post(PostingRequest.of(ExpensesService.journalForMethod(in.paymentMethod()),
                Instant.now(), label, "expense_advance", id, "expense_advance:" + movementId, List.of(
                        Line.debit(cash, value, label), Line.credit(advanceAccount(), value, label))));
        movement(movementId, id, "refund", amount, null, cash, (String) entry.get("id"), in.reference(), actor());
        jdbc.sql("UPDATE \"ExpenseAdvance\" SET \"refundedAmount\" = \"refundedAmount\" + ?, status = CASE WHEN"
                + " \"disbursedAmount\" - \"settledAmount\" - \"refundedAmount\" - ? <= 0 THEN 'settled' ELSE status END,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(amount, amount, id).update();
        audit.recordInTx(AuditInput.of("ExpenseAdvance", id, "post", null, Obj.of("refunded", value)));
        return findOne(id);
    }

    /** FX-4: the functional value per advance-currency unit it was paid out at; {@code null} for a functional advance. */
    static BigDecimal carryingRate(Map<String, Object> a) {
        if (a.get("currencyCode") == null || a.get("disbursedBase") == null) {
            return null;
        }
        BigDecimal disbursed = Money.dec(a.get("disbursedAmount"));
        return disbursed.signum() == 0 ? null
                : Money.dec(a.get("disbursedBase")).divide(disbursed, 10, java.math.RoundingMode.HALF_UP);
    }

    /** The open advance in the functional currency (claims are functional): a foreign advance at its carrying rate. */
    static BigDecimal outstandingFunctional(Map<String, Object> a) {
        BigDecimal rate = carryingRate(a);
        BigDecimal open = outstanding(a);
        return rate == null ? open : open.multiply(rate).setScale(2, java.math.RoundingMode.DOWN);
    }

    /**
     * FX-4: pay out a foreign advance. From an account held in its currency: funds check and relief at the account's
     * carrying value. From a functional account: the payment rate (stated, else table, context expense). The advance
     * line carries the currency; its functional value is kept as {@code disbursedBase}.
     */
    private Map<String, Object> disburseForeign(Map<String, Object> a, MoneyInput in, BigDecimal amount, String cash,
            String actorId) {
        String ccy = (String) a.get("currencyCode");
        String id = (String) a.get("id");
        ForeignCash.Denomination held = ForeignCash.of(jdbc, org(), cash);
        if (held != null && !held.currency().equals(ccy)) {
            throw ApiException.badRequest("Account " + held.name() + " is held in " + held.currency() + "; this advance"
                    + " is in " + ccy);
        }
        BigDecimal base;
        com.inventoryerp.accounting.application.CurrencyService.Resolution rate = null;
        if (held != null) {
            TreasuryGuards.lockAccounts(jdbc, org(), List.of(cash));
            ForeignCash.Balance bal = ForeignCash.balance(jdbc, org(), cash, ccy);
            ForeignCash.assertFunds(bal, amount, held.name(), ccy);
            base = ForeignCash.relieve(bal, amount, BigDecimal.ONE);
            pettyCash.assertCanSpend(cash, amount);
        } else {
            rate = currencies.resolveStated(ccy, posting.baseCurrency(), in.exchangeRate(), Instant.now(), "expense");
            base = amount.multiply(rate.rate()).setScale(6, java.math.RoundingMode.HALF_UP);
            TreasuryGuards.assertSufficientFunds(jdbc, org(), cash, base, accountName(cash));
            pettyCash.assertCanSpend(cash, base);
        }
        String label = "Advance " + a.get("advanceNumber") + " - " + a.get("purpose");
        String movementId = UUID.randomUUID().toString();
        Line cashLine = Line.credit(cash, JsNumbers.dec(base), label);
        PostingRequest req = PostingRequest.of(ExpensesService.journalForMethod(in.paymentMethod()), Instant.now(), label,
                "expense_advance", id, "expense_advance:" + movementId, List.of(
                        new Line(advanceAccount(), JsNumbers.dec(base), null, null, label, (String) a.get("branchId"),
                                null, null).inCurrency(ccy, JsNumbers.dec(amount)),
                        held != null ? cashLine.inCurrency(ccy, JsNumbers.dec(amount)) : cashLine));
        Map<String, Object> entry = rate == null ? posting.post(req) : posting.post(req, List.of(rate));
        movement(movementId, id, "disburse", amount, null, cash, (String) entry.get("id"), in.reference(), actorId);
        jdbc.sql("UPDATE \"ExpenseAdvance\" SET status = 'disbursed', \"disbursedAmount\" = ?, \"disbursedBase\" = ?,"
                + " \"disbursedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ?").params(amount, base, id).update();
        audit.recordInTx(AuditInput.of("ExpenseAdvance", id, "post", null, Obj.of("disbursed", JsNumbers.dec(amount),
                "currencyCode", ccy, "functionalValue", JsNumbers.dec(base), "journalEntryId", entry.get("id"))));
        publish("expense.advance_disbursed", Obj.of("advanceId", id));
        controls.notify("notifyOnPayment", (String) a.get("employeeId"), "Advance " + a.get("advanceNumber")
                + " paid out", label, Obj.of("advanceId", id));
        return findOne(id);
    }

    /**
     * FX-4: unused foreign cash returned. Into an account held in the advance currency it moves at the advance's carrying
     * value (no FX). Into a functional account it is valued at the refund rate; the difference to the advance's carrying
     * value is realised FX.
     */
    private Map<String, Object> refundForeign(Map<String, Object> a, MoneyInput in, BigDecimal amount, String cash) {
        String ccy = (String) a.get("currencyCode");
        String id = (String) a.get("id");
        ForeignCash.Denomination held = ForeignCash.of(jdbc, org(), cash);
        if (held != null && !held.currency().equals(ccy)) {
            throw ApiException.badRequest("Account " + held.name() + " is held in " + held.currency() + "; this advance"
                    + " is in " + ccy);
        }
        BigDecimal advanceBase = amount.multiply(carryingRate(a)).setScale(6, java.math.RoundingMode.HALF_UP);
        com.inventoryerp.accounting.application.CurrencyService.Resolution rate = held != null ? null
                : currencies.resolveStated(ccy, posting.baseCurrency(), in.exchangeRate(), Instant.now(), "expense");
        BigDecimal cashBase = rate == null ? advanceBase
                : amount.multiply(rate.rate()).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal fx = cashBase.subtract(advanceBase);
        String label = "Advance " + a.get("advanceNumber") + " refund";
        Line cashLine = Line.debit(cash, JsNumbers.dec(cashBase), label);
        List<Line> lines = new java.util.ArrayList<>(List.of(held != null ? cashLine.inCurrency(ccy,
                JsNumbers.dec(amount)) : cashLine, Line.credit(advanceAccount(), JsNumbers.dec(advanceBase), label)
                .inCurrency(ccy, JsNumbers.dec(amount))));
        String fxLabel = "Realised FX · " + label;
        if (fx.signum() > 0) {
            lines.add(Line.credit(accounts.byMapping("fx_gain"), JsNumbers.dec(fx), fxLabel));
        } else if (fx.signum() < 0) {
            lines.add(Line.debit(accounts.byMapping("fx_loss"), JsNumbers.dec(fx.negate()), fxLabel));
        }
        String movementId = UUID.randomUUID().toString();
        PostingRequest req = PostingRequest.of(ExpensesService.journalForMethod(in.paymentMethod()), Instant.now(), label,
                "expense_advance", id, "expense_advance:" + movementId, lines);
        Map<String, Object> entry = rate == null ? posting.post(req) : posting.post(req, List.of(rate));
        movement(movementId, id, "refund", amount, null, cash, (String) entry.get("id"), in.reference(), actor());
        jdbc.sql("UPDATE \"ExpenseAdvance\" SET \"refundedAmount\" = \"refundedAmount\" + ?, status = CASE WHEN"
                + " \"disbursedAmount\" - \"settledAmount\" - \"refundedAmount\" - ? <= 0 THEN 'settled' ELSE status END,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(amount, amount, id).update();
        audit.recordInTx(AuditInput.of("ExpenseAdvance", id, "post", null, Obj.of("refunded", JsNumbers.dec(amount),
                "currencyCode", ccy, "fxAmount", JsNumbers.dec(fx))));
        return findOne(id);
    }

    /**
     * Record a claim's settlement against the advance (called by {@link ExpenseClaimService}). {@code amount} is
     * functional. FX-4: a foreign advance is consumed at its carrying rate; a balanced entry on the advance account moves
     * the settled value into the advance's currency bucket so its foreign balance ties.
     */
    void recordSettlement(String advanceId, String claimId, BigDecimal amount, String journalEntryId) {
        Map<String, Object> a = require(advanceId, false);
        BigDecimal rate = carryingRate(a);
        if (rate != null) {
            BigDecimal units = amount.divide(rate, 6, java.math.RoundingMode.HALF_UP).min(outstanding(a));
            String acc = advanceAccount();
            String label = "Advance " + a.get("advanceNumber") + " settled by claim (in " + a.get("currencyCode") + ")";
            posting.post(PostingRequest.of("GEN", Instant.now(), label, "expense_advance", advanceId,
                    "expense_advance:settle:" + UUID.randomUUID(), List.of(Line.debit(acc, JsNumbers.dec(amount), label),
                            Line.credit(acc, JsNumbers.dec(amount), label).inCurrency((String) a.get("currencyCode"),
                                    JsNumbers.dec(units)))));
            amount = units;
        }
        movement(UUID.randomUUID().toString(), advanceId, "settle", amount, claimId, advanceAccount(), journalEntryId,
                null, actor());
        jdbc.sql("UPDATE \"ExpenseAdvance\" SET \"settledAmount\" = \"settledAmount\" + ?, status = CASE WHEN"
                + " \"disbursedAmount\" - \"settledAmount\" - \"refundedAmount\" - ? <= 0 THEN 'settled' ELSE status END,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(amount, amount, advanceId).update();
    }

    private void movement(String id, String advanceId, String kind, BigDecimal amount, String claimId, String accountId,
            String entryId, String reference, String actorId) {
        jdbc.sql("INSERT INTO \"ExpenseAdvanceMovement\" (id, \"organizationId\", \"advanceId\", kind, amount, \"claimId\","
                        + " \"accountId\", \"journalEntryId\", reference, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), advanceId, kind, amount, claimId, accountId, entryId, reference, actorId).update();
    }

    /** An active, postable cash-equivalent account that is not a register drawer. */
    String cashAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw ApiException.badRequest("accountId is required");
        }
        long ok = jdbc.sql("SELECT count(*) FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.id = ? AND a.\"organizationId\" = ? AND c.\"isCashEquivalent\" AND a.\"isPostable\" AND a.\"isActive\""
                + " AND a.\"deletedAt\" IS NULL").params(accountId, org()).query(Long.class).single();
        if (ok == 0) {
            throw ApiException.badRequest("Pay from an active cash, bank or mobile-money account");
        }
        TreasuryGuards.assertNotDrawerAccount(jdbc, org(), accountId, "An expense advance");
        return accountId;
    }

    private String accountName(String id) {
        return jdbc.sql("SELECT name FROM \"Account\" WHERE id = ?").param(id).query(String.class).single();
    }

    private static void expect(Map<String, Object> a, String status) {
        if (!status.equals(a.get("status"))) {
            throw ApiException.badRequest("Advance is " + a.get("status") + ", expected " + status);
        }
    }

    private void publish(String event, Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>(payload);
        p.put("organizationId", org());
        events.publish(event, p);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
