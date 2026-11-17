package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Foreign-currency treasury administration (R-8, ADR-S076): the state of each foreign cash account, the designation of
 * legacy balances, and the realised / unrealised FX summary.
 */
@Service
public class FxTreasuryService {

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AuditService audit;

    public FxTreasuryService(JdbcClient jdbc, PostingService posting, AuditService audit) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.audit = audit;
    }

    /** Input of {@code POST /fx-treasury/designations}. */
    public record DesignationInput(String accountId, BigDecimal foreignBalance, String date, String note) {}

    /**
     * Every foreign-denominated cash, bank and mobile-money account with its state: {@code designated} (in FX treasury)
     * or {@code undesignated} (holds balances of unknown currency), its foreign balance, carrying value and the
     * unknown-currency balance a designation would take over.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> accounts() {
        String org = TenantContext.organizationId();
        String functional = ForeignCash.functional(jdbc, org);
        List<String> ids = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND c.\"isCashEquivalent\" AND"
                        + " a.\"currencyId\" IS NOT NULL AND upper(a.\"currencyId\") <> upper(?) ORDER BY a.code")
                .params(org, functional).query(String.class).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            ForeignCash.Denomination d = ForeignCash.of(jdbc, org, id);
            if (d == null) {
                continue;
            }
            ForeignCash.Balance bal = ForeignCash.balance(jdbc, org, id, d.currency());
            BigDecimal rate = bal.carryingRate();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountId", id);
            row.put("code", d.code());
            row.put("name", d.name());
            row.put("currencyCode", d.currency());
            row.put("functionalCurrency", functional);
            row.put("state", d.designated() ? "designated" : "undesignated");
            row.put("designatedAt", d.designatedAt());
            row.put("foreignBalance", plain(bal.foreign()));
            row.put("carryingValue", plain(bal.base()));
            row.put("carryingRate", rate == null ? null : plain(rate));
            row.put("unknownCurrencyBalance", plain(untaggedBase(org, id, d.currency())));
            out.add(row);
        }
        return out;
    }

    /**
     * Designate a legacy account's balance (plan P-11): the user states the foreign balance; one balanced,
     * functional-neutral entry moves the account's unknown-currency book value onto a line in the denomination. No
     * historical line changes and functional totals do not move; any gap to the current rate is recognised by the next
     * period-end revaluation.
     */
    @Transactional
    public Map<String, Object> designate(DesignationInput in) {
        if (in == null || in.accountId() == null || in.accountId().isBlank()) {
            throw ApiException.badRequest("accountId is required");
        }
        if (in.foreignBalance() == null) {
            throw ApiException.badRequest("foreignBalance is required");
        }
        String org = TenantContext.organizationId();
        TreasuryGuards.lockAccounts(jdbc, org, List.of(in.accountId()));
        ForeignCash.Denomination d = ForeignCash.of(jdbc, org, in.accountId());
        if (d == null) {
            throw ApiException.badRequest("Only a cash, bank or mobile-money account held in a foreign currency can be"
                    + " designated");
        }
        if (d.designatedAt() != null) {
            throw ApiException.badRequest("Account " + d.code() + " is already designated in " + d.currency());
        }
        BigDecimal stated = Money.dec(in.foreignBalance());
        ForeignCash.assertDecimals(jdbc, d.currency(), stated.abs());
        String functional = ForeignCash.functional(jdbc, org);
        BigDecimal unknown = untaggedBase(org, d.accountId(), d.currency());
        ForeignCash.Balance tagged = ForeignCash.balance(jdbc, org, d.accountId(), d.currency());
        BigDecimal delta = stated.subtract(tagged.foreign());
        String entryId = null;
        if (unknown.signum() != 0 || delta.signum() != 0) {
            if (unknown.signum() == 0 || delta.signum() != unknown.signum()) {
                throw ApiException.badRequest("Account " + d.code() + " holds " + plain(unknown) + " " + functional
                        + " of unknown currency; state a " + d.currency() + " balance of the same sign (it holds "
                        + plain(tagged.foreign()) + " " + d.currency() + " already)");
            }
            Instant date = in.date() == null || in.date().isBlank() ? Instant.now()
                    : OrgDates.orgPostingInstant(in.date(), OrgDates.orgTimezone(jdbc, org), Instant.now());
            String base = plain(unknown.abs());
            String foreign = plain(delta.abs());
            String label = "Designation of " + d.code() + " as " + d.currency() + " " + plain(stated);
            List<PostingRequest.Line> lines = unknown.signum() > 0
                    ? List.of(PostingRequest.Line.debit(d.accountId(), base, label).inCurrency(d.currency(), foreign),
                            PostingRequest.Line.credit(d.accountId(), base, label + " (unknown-currency balance)"))
                    : List.of(PostingRequest.Line.debit(d.accountId(), base, label + " (unknown-currency balance)"),
                            PostingRequest.Line.credit(d.accountId(), base, label).inCurrency(d.currency(), foreign));
            BigDecimal rate = unknown.abs().divide(delta.abs(), 10, RoundingMode.HALF_UP);
            Map<String, Object> entry = posting.post(PostingRequest.of("GEN", date, label, "fx_designation",
                            d.accountId(), "fx-designation:" + d.accountId(), lines),
                    List.of(new CurrencyService.Resolution(d.currency(), functional, rate, "carrying", date)));
            entryId = (String) entry.get("id");
        }
        jdbc.sql("UPDATE \"Account\" SET \"currencyDesignatedAt\" = ?, \"currencyDesignationEntryId\" = ?,"
                        + " \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?")
                .params(Timestamp.from(Instant.now()), entryId, d.accountId(), org).update();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("change", "fx_designation");
        values.put("currencyCode", d.currency());
        values.put("foreignBalance", plain(stated));
        values.put("carryingValue", plain(tagged.base().add(unknown)));
        values.put("journalEntryId", entryId);
        values.put("note", in.note());
        // AuditAction is a closed DB enum: the designation is an account update marked by "change"
        audit.recordInTx(AuditInput.of("Account", d.accountId(), "update", null, values));
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.remove("change");
        result.put("accountId", d.accountId());
        result.put("code", d.code());
        result.put("state", "designated");
        BusinessOutcome.record(jdbc, result, true);
        return result;
    }

    /**
     * Realised and unrealised FX (functional currency) between two dates, by origin: realised FX from payments,
     * treasury operations and credit notes; unrealised from period-end revaluations and their reversals.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(String fromDate, String toDate) {
        String org = TenantContext.organizationId();
        java.time.ZoneId zone = AccountingDay.zone(jdbc, org);
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        Instant from;
        Instant to;
        try {
            from = AccountingDay.start(fromDate == null || fromDate.isBlank() ? today.withDayOfMonth(1)
                    : java.time.LocalDate.parse(fromDate.substring(0, 10)), zone);
            to = AccountingDay.endExclusive(toDate == null || toDate.isBlank() ? today
                    : java.time.LocalDate.parse(toDate.substring(0, 10)), zone);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("from and to must be dates (YYYY-MM-DD)");
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT CASE WHEN e.\"sourceType\" = 'fx_revaluation' OR"
                        + " o.\"sourceType\" = 'fx_revaluation' THEN 'unrealised' ELSE 'realised' END AS kind,"
                        + " coalesce(o.\"sourceType\", e.\"sourceType\") AS origin, coalesce(sum(l.\"baseCredit\" -"
                        + " l.\"baseDebit\"), 0) AS gain FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                        + " l.\"journalEntryId\" LEFT JOIN \"JournalEntry\" o ON o.id = e.\"reversalOfId\" JOIN"
                        + " \"AccountMapping\" m ON m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" ="
                        + " l.\"organizationId\" AND m.key IN ('fx_gain', 'fx_loss') AND m.\"deletedAt\" IS NULL WHERE"
                        + " l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ?"
                        + " AND e.\"postingDate\" < ? GROUP BY 1, 2 ORDER BY 1, 2")
                .params(org, Timestamp.from(from), Timestamp.from(to)));
        BigDecimal realised = BigDecimal.ZERO;
        BigDecimal unrealised = BigDecimal.ZERO;
        List<Map<String, Object>> byOrigin = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal gain = SqlRows.dec(r, "gain");
            if ("unrealised".equals(r.get("kind"))) {
                unrealised = unrealised.add(gain);
            } else {
                realised = realised.add(gain);
            }
            byOrigin.add(Map.of("kind", r.get("kind"), "origin", String.valueOf(r.get("origin")), "gain", plain(gain)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("functionalCurrency", ForeignCash.functional(jdbc, org));
        out.put("realised", plain(realised));
        out.put("unrealised", plain(unrealised));
        out.put("total", plain(realised.add(unrealised)));
        out.put("byOrigin", byOrigin);
        return out;
    }

    /**
     * Book value (functional) of the account's lines that are not in its denomination, revaluations excluded; the base
     * side of a designation offsets it to zero.
     */
    private BigDecimal untaggedBase(String org, String accountId, String currency) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                        + " l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed') AND (l.\"currencyId\" IS NULL"
                        + " OR upper(l.\"currencyId\") <> ?) AND coalesce(e.\"sourceType\", '') <> 'fx_revaluation' AND NOT EXISTS"
                        + " (SELECT 1 FROM \"JournalEntry\" o WHERE o.id = e.\"reversalOfId\" AND o.\"sourceType\" ="
                        + " 'fx_revaluation')")
                .params(org, accountId, currency).query(BigDecimal.class).single();
    }
}
