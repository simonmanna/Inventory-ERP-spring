package com.inventoryerp.expenses.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.AccountingDay;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.TreasuryGuards;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Petty cash (EX-4, ADR-S052): imprest funds on a cash-equivalent GL account with a custodian, a float and an optional
 * cap per payment; replenishment (Dr fund / Cr bank, default = float − balance); cash counts against the GL balance
 * with the difference posted to cash short &amp; over; the fund statement read from the ledger. Expenses are paid from
 * a fund by paying them from its account; only its custodian (or an expense approver) may, within the cap.
 */
@Service
public class PettyCashService {

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Create / update body. */
    public record FundInput(String name, String accountId, String custodianId, String branchId, BigDecimal floatAmount,
            BigDecimal maxPayment, Boolean isActive, String notes) {}

    /** Replenishment body; {@code amount} null = top up to the float. */
    public record ReplenishInput(String sourceAccountId, BigDecimal amount, String paymentMethod, String reference) {}

    /** Cash count body. */
    public record CountInput(BigDecimal countedAmount, Map<String, Object> denominations, String notes) {}

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AuditService audit;

    public PettyCashService(JdbcClient jdbc, PostingService posting, AccountResolverService accounts,
            AuditService audit) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.accounts = accounts;
        this.audit = audit;
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

    /** GL balance (base) of an account. */
    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ?"
                + " AND e.status IN ('posted', 'reversed')").params(org(), accountId).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"PettyCashFund\" WHERE \"organizationId\" = ? ORDER BY name")
                .param(org())).stream().map(this::shape).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> f = shape(require(id));
        f.put("counts", SqlRows.list(jdbc.sql("SELECT * FROM \"PettyCashCount\" WHERE \"fundId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY \"countedAt\" DESC LIMIT 20").params(id, org())).stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>(c);
                    for (String k : List.of("expectedBalance", "countedAmount", "difference")) {
                        m.put(k, JsNumbers.js(Money.dec(c.get(k))));
                    }
                    return m;
                }).toList());
        return f;
    }

    private Map<String, Object> shape(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        BigDecimal bal = balance((String) r.get("accountId"));
        BigDecimal flt = Money.dec(r.get("floatAmount"));
        m.put("floatAmount", JsNumbers.js(flt));
        m.put("maxPayment", r.get("maxPayment") == null ? null : JsNumbers.js(Money.dec(r.get("maxPayment"))));
        m.put("balance", JsNumbers.js(bal));
        m.put("replenishmentDue", JsNumbers.js(flt.subtract(bal).max(BigDecimal.ZERO)));
        m.put("account", SqlRows.one(jdbc.sql("SELECT id, code, name FROM \"Account\" WHERE id = ?").param(r.get("accountId"))));
        m.put("custodian", r.get("custodianId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, \"firstName\","
                + " \"lastName\", email FROM \"User\" WHERE id = ?").param(r.get("custodianId"))));
        m.put("lastCount", SqlRows.one(jdbc.sql("SELECT \"countedAt\", \"countedAmount\", difference FROM"
                + " \"PettyCashCount\" WHERE \"fundId\" = ? ORDER BY \"countedAt\" DESC LIMIT 1").param(r.get("id"))));
        return m;
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> f = SqlRows.one(jdbc.sql("SELECT * FROM \"PettyCashFund\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (f == null) {
            throw ApiException.notFound("Petty cash fund not found");
        }
        return f;
    }

    /**
     * Ledger statement of a fund between two dates (inclusive, default the current month): opening balance, one row
     * per journal line with its running balance, closing balance.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> statement(String id, String from, String to) {
        Map<String, Object> f = require(id);
        LocalDate start = from == null || from.isBlank() ? LocalDate.now().withDayOfMonth(1) : LocalDate.parse(from);
        LocalDate end = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        Timestamp s = AccountingDay.startTs(start, AccountingDay.zone(jdbc));
        Timestamp e = AccountingDay.endExclusiveTs(end, AccountingDay.zone(jdbc));
        String acc = (String) f.get("accountId");
        BigDecimal opening = jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l"
                + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                + " l.\"accountId\" = ? AND e.status IN ('posted', 'reversed') AND e.\"postingDate\" < ?")
                .params(org(), acc, s).query(BigDecimal.class).single();
        BigDecimal running = opening;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT e.\"postingDate\", e.\"entryNumber\", e.description,"
                + " e.\"sourceType\", e.\"sourceId\", l.\"baseDebit\" AS debit, l.\"baseCredit\" AS credit FROM"
                + " \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ?"
                + " AND l.\"accountId\" = ? AND e.status IN ('posted', 'reversed') AND e.\"postingDate\" >= ? AND"
                + " e.\"postingDate\" < ? ORDER BY e.\"postingDate\", e.\"createdAt\", l.\"lineNumber\"")
                .params(org(), acc, s, e))) {
            BigDecimal d = Money.dec(l.get("debit"));
            BigDecimal c = Money.dec(l.get("credit"));
            running = running.add(d).subtract(c);
            Map<String, Object> m = new LinkedHashMap<>(l);
            m.put("debit", JsNumbers.js(d));
            m.put("credit", JsNumbers.js(c));
            m.put("balance", JsNumbers.js(running));
            rows.add(m);
        }
        return Obj.of("fundId", id, "name", f.get("name"), "from", start.toString(), "to", end.toString(),
                "openingBalance", JsNumbers.js(opening), "lines", rows, "closingBalance", JsNumbers.js(running));
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public Map<String, Object> create(FundInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw ApiException.badRequest("Name is required");
        }
        if (in.floatAmount() == null || in.floatAmount().signum() <= 0) {
            throw ApiException.badRequest("floatAmount must be a positive number");
        }
        assertCashAccount(in.accountId());
        if (jdbc.sql("SELECT count(*) FROM \"PettyCashFund\" WHERE \"organizationId\" = ? AND \"accountId\" = ?")
                .params(org(), in.accountId()).query(Long.class).single() > 0) {
            throw ApiException.conflict("That account already backs a petty cash fund");
        }
        assertUser(in.custodianId());
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PettyCashFund\" (id, \"organizationId\", name, \"accountId\", \"custodianId\", \"branchId\","
                        + " \"floatAmount\", \"maxPayment\", notes, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), in.name().trim(), in.accountId(), blank(in.custodianId()), blank(in.branchId()),
                        in.floatAmount(), in.maxPayment(), in.notes(), actor())
                .update();
        audit.recordInTx(AuditInput.of("PettyCashFund", id, "create", null, Obj.of("name", in.name(), "float",
                JsNumbers.js(in.floatAmount()))));
        return findOne(id);
    }

    @Transactional
    public Map<String, Object> update(String id, FundInput in) {
        Map<String, Object> before = require(id);
        assertUser(in.custodianId());
        jdbc.sql("UPDATE \"PettyCashFund\" SET name = ?, \"custodianId\" = ?, \"branchId\" = ?, \"floatAmount\" = ?,"
                        + " \"maxPayment\" = ?, \"isActive\" = ?, notes = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(in.name() == null ? before.get("name") : in.name().trim(),
                        EntityPatch.present("custodianId", in.custodianId()) ? blank(in.custodianId())
                                : before.get("custodianId"),
                        EntityPatch.present("branchId", in.branchId()) ? blank(in.branchId()) : before.get("branchId"),
                        in.floatAmount() == null ? before.get("floatAmount") : in.floatAmount(),
                        EntityPatch.present("maxPayment", in.maxPayment()) ? in.maxPayment() : before.get("maxPayment"),
                        in.isActive() == null ? before.get("isActive") : in.isActive(),
                        in.notes() == null ? before.get("notes") : in.notes(), id, org())
                .update();
        audit.recordInTx(AuditInput.of("PettyCashFund", id, "update", before, null));
        return findOne(id);
    }

    /** Top the fund up from a bank / cash account: Dr fund account / Cr source. */
    @Transactional
    public Map<String, Object> replenish(String id, ReplenishInput in) {
        Map<String, Object> f = require(id);
        if (!Boolean.TRUE.equals(f.get("isActive"))) {
            throw ApiException.badRequest("Petty cash fund is inactive");
        }
        String fundAcc = (String) f.get("accountId");
        assertCashAccount(in.sourceAccountId());
        if (fundAcc.equals(in.sourceAccountId())) {
            throw ApiException.badRequest("Replenish the fund from another account");
        }
        BigDecimal amount = in.amount() != null ? in.amount()
                : Money.dec(f.get("floatAmount")).subtract(balance(fundAcc));
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("The fund is at or above its float: nothing to replenish");
        }
        TreasuryGuards.assertSufficientFunds(jdbc, org(), in.sourceAccountId(), amount,
                jdbc.sql("SELECT name FROM \"Account\" WHERE id = ?").param(in.sourceAccountId()).query(String.class)
                        .single());
        String value = JsNumbers.dec(amount);
        String label = "Petty cash replenishment - " + f.get("name");
        Map<String, Object> entry = posting.post(PostingRequest.of(ExpensesService.journalForMethod(in.paymentMethod()),
                Instant.now(), label, "petty_cash_replenish", id, "petty_cash_replenish:" + UUID.randomUUID(), List.of(
                        new Line(fundAcc, value, null, null, label, (String) f.get("branchId"), null, null),
                        Line.credit(in.sourceAccountId(), value, label))));
        audit.recordInTx(AuditInput.of("PettyCashFund", id, "post", null, Obj.of("replenished", value, "journalEntryId",
                entry.get("id"), "reference", in.reference())));
        Map<String, Object> out = findOne(id);
        out.put("journalEntryId", entry.get("id"));
        return out;
    }

    /** Count the cash: a difference against the GL balance posts to cash short &amp; over. Custodian or approver. */
    @Transactional
    public Map<String, Object> count(String id, CountInput in) {
        Map<String, Object> f = require(id);
        String actorId = actor();
        if (f.get("custodianId") != null && !actorId.equals(f.get("custodianId"))
                && !TenantContext.has("expense:approve")) {
            throw ApiException.forbidden("Only the custodian or an expense approver can count this fund");
        }
        if (in.countedAmount() == null || in.countedAmount().signum() < 0) {
            throw ApiException.badRequest("countedAmount must be zero or more");
        }
        String fundAcc = (String) f.get("accountId");
        BigDecimal expected = balance(fundAcc);
        BigDecimal diff = in.countedAmount().subtract(expected);
        String entryId = null;
        if (diff.signum() != 0) {
            String overShort = accounts.byMapping("cash_short_over");
            String value = JsNumbers.dec(diff.abs());
            String label = "Petty cash count " + (diff.signum() < 0 ? "short" : "over") + " - " + f.get("name");
            Map<String, Object> entry = posting.post(PostingRequest.of("CASH", Instant.now(), label, "petty_cash_count",
                    id, "petty_cash_count:" + UUID.randomUUID(), diff.signum() < 0
                            ? List.of(Line.debit(overShort, value, label), Line.credit(fundAcc, value, label))
                            : List.of(Line.debit(fundAcc, value, label), Line.credit(overShort, value, label))));
            entryId = (String) entry.get("id");
        }
        String countId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PettyCashCount\" (id, \"organizationId\", \"fundId\", \"expectedBalance\", \"countedAmount\","
                        + " difference, denominations, \"journalEntryId\", notes, \"countedById\") VALUES (?, ?, ?, ?, ?, ?,"
                        + " ?::jsonb, ?, ?, ?)")
                .params(countId, org(), id, expected, in.countedAmount(), diff,
                        in.denominations() == null ? null : EntityJson.toJson(in.denominations()), entryId, in.notes(),
                        actorId)
                .update();
        audit.recordInTx(AuditInput.of("PettyCashFund", id, "update", null, Obj.of("counted",
                JsNumbers.js(in.countedAmount()), "expected", JsNumbers.js(expected), "difference", JsNumbers.js(diff))));
        return findOne(id);
    }

    /**
     * Spending rule of a payment from {@code accountId} (called by expense payments): when the account backs an active
     * fund, the payer must be its custodian or hold {@code expense:approve}, and the amount within its cap.
     */
    void assertCanSpend(String accountId, BigDecimal amount) {
        Map<String, Object> f = SqlRows.one(jdbc.sql("SELECT * FROM \"PettyCashFund\" WHERE \"organizationId\" = ? AND"
                + " \"accountId\" = ? AND \"isActive\"").params(org(), accountId));
        if (f == null) {
            return;
        }
        if (f.get("custodianId") != null && !f.get("custodianId").equals(TenantContext.userId())
                && !TenantContext.has("expense:approve")) {
            throw ApiException.forbidden("Only the custodian can pay from petty cash fund " + f.get("name"));
        }
        if (f.get("maxPayment") != null && amount.compareTo(Money.dec(f.get("maxPayment"))) > 0) {
            throw ApiException.badRequest("Petty cash fund " + f.get("name") + " pays at most "
                    + JsNumbers.dec(Money.dec(f.get("maxPayment"))) + " per payment");
        }
    }

    private void assertCashAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw ApiException.badRequest("accountId is required");
        }
        if (jdbc.sql("SELECT count(*) FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.id = ? AND a.\"organizationId\" = ? AND c.\"isCashEquivalent\" AND a.\"isPostable\" AND"
                + " a.\"isActive\" AND a.\"deletedAt\" IS NULL").params(accountId, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Use an active, postable cash-equivalent account");
        }
        TreasuryGuards.assertNotDrawerAccount(jdbc, org(), accountId, "A petty cash fund");
    }

    private void assertUser(String userId) {
        if (userId != null && !userId.isBlank() && jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(userId, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("User not found");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
