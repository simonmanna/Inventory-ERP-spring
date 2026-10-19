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
import java.time.Instant;
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

    public TreasuryService(JdbcClient jdbc, EventBus events, PostingService posting, AuditService audit) {
        this.jdbc = jdbc;
        this.events = events;
        this.posting = posting;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> transfer(String fromAccountId, String toAccountId, BigDecimal rawAmount, String date,
            String reference) {
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
}
