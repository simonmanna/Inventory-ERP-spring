package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.TreasuryGuards.AccountRow;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers between payment accounts (port of {@code treasury.service.ts}): bank ↔ bank / wallet / safe,
 * never a register drawer; accounts locked before the balance check; posting key derived from the
 * Idempotency-Key; outcome recorded in the business transaction.
 */
@Service
public class TreasuryService {

    private final JdbcClient jdbc;
    private final EventBus events;
    private final PostingService posting;
    private final AuditService audit;
    private final CurrencyService currency;
    private final AccountDeterminationService determination;

    public TreasuryService(JdbcClient jdbc, EventBus events, PostingService posting, AuditService audit,
            CurrencyService currency, AccountDeterminationService determination) {
        this.jdbc = jdbc;
        this.events = events;
        this.posting = posting;
        this.audit = audit;
        this.currency = currency;
        this.determination = determination;
    }

    @Transactional
    public Map<String, Object> transfer(String fromAccountId, String toAccountId, BigDecimal rawAmount, String date,
            String reference) {
        return transfer(fromAccountId, toAccountId, rawAmount, date, reference, null, null);
    }

    /**
     * Transfer between payment accounts. With a foreign-currency account on either side (R-8, ADR-S076, plan P-8):
     * {@code rawAmount} is the amount sent in the source's currency and, across currencies, {@code receivedAmount} the
     * amount received in the destination's currency as the bank executed it. The source is relieved at its carrying
     * value; the destination takes the received amount at {@code exchangeRate} (else the rate table), or exactly when it
     * is a functional account; the difference, bank spread included, is realised FX.
     */
    @Transactional
    public Map<String, Object> transfer(String fromAccountId, String toAccountId, BigDecimal rawAmount, String date,
            String reference, BigDecimal receivedAmount, BigDecimal exchangeRate) {
        if (fromAccountId.equals(toAccountId)) {
            throw ApiException.badRequest("Source and destination accounts must differ");
        }
        BigDecimal amount = Money.dec(rawAmount);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Transfer amount must be positive");
        }
        String organizationId = TenantContext.organizationId();
        Instant postingDate = OrgDates.orgPostingInstant(date, OrgDates.orgTimezone(jdbc, organizationId), Instant.now());
        TreasuryGuards.lockAccounts(jdbc, organizationId, List.of(fromAccountId, toAccountId));
        AccountRow from = TreasuryGuards.requireAccount(jdbc, organizationId, fromAccountId, "Source account");
        AccountRow to = TreasuryGuards.requireAccount(jdbc, organizationId, toAccountId, "Destination account");
        if (!from.isCashEquivalent() || !to.isCashEquivalent()) {
            throw ApiException.badRequest("Transfers require two active payment accounts");
        }
        ForeignCash.Denomination fromFx = ForeignCash.of(jdbc, organizationId, from.id());
        ForeignCash.Denomination toFx = ForeignCash.of(jdbc, organizationId, to.id());
        if (fromFx != null || toFx != null) {
            TreasuryGuards.assertNotDrawerAccount(jdbc, organizationId, from.id(), "A transfer");
            TreasuryGuards.assertNotDrawerAccount(jdbc, organizationId, to.id(), "A transfer");
            return foreignTransfer(organizationId, postingDate, from, to, fromFx, toFx, amount, reference,
                    receivedAmount, exchangeRate);
        }
        if (!Objects.equals(from.currencyId(), to.currencyId())) {
            throw ApiException.badRequest("Transfers between different currencies need an FX conversion, not a transfer");
        }
        TreasuryGuards.assertNotDrawerAccount(jdbc, organizationId, from.id(), "A transfer");
        TreasuryGuards.assertNotDrawerAccount(jdbc, organizationId, to.id(), "A transfer");
        TreasuryGuards.assertSufficientFunds(jdbc, organizationId, from.id(), amount, from.name());
        String id = TreasuryGuards.operationId();
        String amt = PostingService.plain(amount);
        Map<String, Object> entry = posting.post(PostingRequest.of("BANK", postingDate,
                reference != null && !reference.isEmpty() ? "Funds transfer: " + reference
                        : "Funds transfer " + from.code() + " → " + to.code(),
                "treasury_transfer", id, "treasury:transfer:" + id,
                List.of(PostingRequest.Line.debit(to.id(), amt, null), PostingRequest.Line.credit(from.id(), amt, null))));
        String entryId = (String) entry.get("id");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("direction", "transfer");
        values.put("fromAccountId", from.id());
        values.put("toAccountId", to.id());
        values.put("amount", amt);
        values.put("journalEntryId", entryId);
        audit.recordInTx(AuditInput.of("TreasuryOperation", id, "create", null, values));
        BusinessOutcome.record(jdbc, entry, true);
        AfterCommit.run(() -> events.publish("bank.transfer", Map.of("organizationId", organizationId,
                "journalEntryId", entryId, "amount", amt)));
        return entry;
    }

    private Map<String, Object> foreignTransfer(String org, Instant postingDate, AccountRow from, AccountRow to,
            ForeignCash.Denomination fromFx, ForeignCash.Denomination toFx, BigDecimal amount, String reference,
            BigDecimal receivedAmount, BigDecimal exchangeRate) {
        ForeignCash.requireDesignated(fromFx);
        ForeignCash.requireDesignated(toFx);
        String functional = ForeignCash.functional(jdbc, org);
        String fromCcy = fromFx == null ? functional : fromFx.currency();
        String toCcy = toFx == null ? functional : toFx.currency();
        ForeignCash.assertDecimals(jdbc, fromCcy, amount);
        List<CurrencyService.Resolution> legs = new ArrayList<>();
        // source: relieved at its carrying value (funds checked in its own currency)
        BigDecimal relieved;
        if (fromFx != null) {
            ForeignCash.Balance bal = ForeignCash.balance(jdbc, org, from.id(), fromCcy);
            ForeignCash.assertFunds(bal, amount, from.name(), fromCcy);
            relieved = ForeignCash.relieve(bal, amount, BigDecimal.ZERO); // funds checked: never beyond the balance
            legs.add(new CurrencyService.Resolution(fromCcy, functional, relieved.divide(amount, 10,
                    RoundingMode.HALF_UP), "carrying", postingDate));
        } else {
            TreasuryGuards.assertSufficientFunds(jdbc, org, from.id(), amount, from.name());
            relieved = amount;
        }
        BigDecimal received;
        BigDecimal targetBase;
        if (fromCcy.equals(toCcy)) {
            if (receivedAmount != null && Money.dec(receivedAmount).compareTo(amount) != 0) {
                throw ApiException.badRequest("A transfer within " + fromCcy + " moves the same amount; record bank"
                        + " charges as a withdrawal");
            }
            received = amount;
            targetBase = relieved;
        } else {
            if (receivedAmount == null || Money.dec(receivedAmount).signum() <= 0) {
                throw ApiException.badRequest("State the amount received in " + toCcy + " for a " + fromCcy + " → "
                        + toCcy + " transfer");
            }
            received = Money.dec(receivedAmount);
            ForeignCash.assertDecimals(jdbc, toCcy, received);
            if (toFx == null) {
                targetBase = received;
            } else {
                CurrencyService.Resolution rate = currency.resolveStated(toCcy, functional, exchangeRate, postingDate, "treasury");
                legs.add(rate);
                targetBase = received.multiply(rate.rate()).setScale(6, RoundingMode.HALF_UP);
            }
        }
        BigDecimal fx = targetBase.subtract(relieved);
        String id = TreasuryGuards.operationId();
        String description = reference != null && !reference.isEmpty() ? "Funds transfer: " + reference
                : "Funds transfer " + from.code() + " → " + to.code();
        PostingRequest.Line debit = PostingRequest.Line.debit(to.id(), PostingService.plain(targetBase), null);
        PostingRequest.Line credit = PostingRequest.Line.credit(from.id(), PostingService.plain(relieved), null);
        List<PostingRequest.Line> lines = new ArrayList<>();
        lines.add(toFx == null ? debit : debit.inCurrency(toCcy, PostingService.plain(received)));
        lines.add(fromFx == null ? credit : credit.inCurrency(fromCcy, PostingService.plain(amount)));
        String fxLabel = "Realised FX · " + fromCcy + " → " + toCcy;
        if (fx.signum() > 0) {
            lines.add(PostingRequest.Line.credit(determination.mapped("fx_gain"), PostingService.plain(fx), fxLabel));
        } else if (fx.signum() < 0) {
            lines.add(PostingRequest.Line.debit(determination.mapped("fx_loss"), PostingService.plain(fx.negate()),
                    fxLabel));
        }
        Map<String, Object> entry = posting.post(PostingRequest.of("BANK", postingDate, description,
                "treasury_transfer", id, "treasury:transfer:" + id, lines), legs);
        String entryId = (String) entry.get("id");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("direction", "transfer");
        values.put("fromAccountId", from.id());
        values.put("toAccountId", to.id());
        values.put("amount", PostingService.plain(amount));
        values.put("fromCurrency", fromCcy);
        values.put("receivedAmount", PostingService.plain(received));
        values.put("toCurrency", toCcy);
        values.put("fxAmount", PostingService.plain(fx));
        values.put("journalEntryId", entryId);
        audit.recordInTx(AuditInput.of("TreasuryOperation", id, "create", null, values));
        BusinessOutcome.record(jdbc, entry, true);
        String amt = PostingService.plain(amount);
        AfterCommit.run(() -> events.publish("bank.transfer", Map.of("organizationId", org, "journalEntryId", entryId,
                "amount", amt)));
        return entry;
    }
}
