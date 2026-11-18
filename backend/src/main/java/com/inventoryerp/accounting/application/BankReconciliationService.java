package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bank reconciliation (port of {@code bank-reconciliation.service.ts}): statement import (idempotent on
 * {@code externalRef}), greedy exact-amount match against posted payments — and, review R5, unreconciled GL lines on
 * the bank account — within a date tolerance, unmatch and status. Amounts compare in the bank account's currency
 * (review R9).
 */
@Service
public class BankReconciliationService {

    /** One imported statement line. */
    public record StatementLine(String postedAt, String externalRef, String description, BigDecimal amount,
            String currencyCode) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;

    public BankReconciliationService(JdbcClient jdbc, AuditService audit, EventBus events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    private BankMatchingService matching;

    /** ADR-S073: rules after import, allocations and rule-entry undo; optional so hand-built instances still work. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMatching(BankMatchingService matching) {
        this.matching = matching;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Transactional
    public Map<String, Object> importStatement(String bankAccountId, List<StatementLine> lines, String currencyCode) {
        String organizationId = TenantContext.organizationId();
        int imported = 0;
        int skipped = 0;
        // R-8 (ADR-S076): a foreign-currency account reconciles in its own currency, so its statement must be in it
        ForeignCash.Denomination fx = ForeignCash.of(jdbc, organizationId, bankAccountId);
        if (fx != null) {
            for (StatementLine l : lines) {
                String c = l.currencyCode() != null ? l.currencyCode() : currencyCode != null ? currencyCode
                        : fx.currency();
                if (!c.equalsIgnoreCase(fx.currency())) {
                    throw ApiException.badRequest("Statement lines in " + c + " cannot be imported into account "
                            + fx.code() + ", which is held in " + fx.currency());
                }
            }
            currencyCode = currencyCode == null ? fx.currency() : currencyCode;
        }
        for (StatementLine l : lines) {
            assertUnlocked(organizationId, bankAccountId, OrgDates.parse(l.postedAt()));
            if (l.externalRef() != null && !l.externalRef().isEmpty()) {
                long existing = jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ?"
                        + " AND \"bankAccountId\" = ? AND \"externalRef\" = ?").params(organizationId, bankAccountId, l.externalRef())
                        .query(Long.class).single();
                if (existing > 0) {
                    skipped++;
                    continue;
                }
            }
            jdbc.sql("INSERT INTO \"BankStatementLine\" (id, \"organizationId\", \"bankAccountId\", \"postedAt\", \"externalRef\","
                            + " description, amount, \"currencyCode\", status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'unmatched')")
                    .params(UUID.randomUUID().toString(), organizationId, bankAccountId,
                            FiscalPeriodControl.ts(OrgDates.parse(l.postedAt())), l.externalRef(), l.description(),
                            l.amount(), l.currencyCode() != null ? l.currencyCode() : currencyCode)
                    .update();
            imported++;
        }
        int i = imported;
        int s = skipped;
        if (matching != null && imported > 0) {
            // ADR-S073: matching rules run once the import committed, one transaction per line.
            AfterCommit.run(() -> matching.afterImport(organizationId, bankAccountId));
        }
        AfterCommit.run(() -> events.publish("bank_statement.imported", body("organizationId", organizationId,
                "bankAccountId", bankAccountId, "imported", i, "skipped", s)));
        return body("imported", imported, "skipped", skipped);
    }

    @Transactional
    public Map<String, Object> match(String bankAccountId, Double dateToleranceDays, String notes) {
        return match(bankAccountId, dateToleranceDays, notes, false);
    }

    /**
     * Auto-match: a negative statement line pairs with an outbound payment of the same size, a positive one with an
     * inbound payment, and a payment already matched (in any run) is never taken twice. ACC-6 (B5, owner decision
     * D-B5): this is now the only mode — Nest's unsigned rule never matched an outgoing line and could re-match a
     * payment across runs; {@code signed} is accepted and ignored. Lines inside a locked statement are left alone.
     * Review R5: a line no payment explains is matched to an unreconciled posted GL line on the bank account of the
     * same direction and size (a treasury transfer, expense, petty-cash top-up or payroll payment), again never one
     * matched before. Sizes compare in the account's currency (review R9).
     */
    @Transactional
    public Map<String, Object> match(String bankAccountId, Double dateToleranceDays, String notes, boolean signed) {
        String organizationId = TenantContext.organizationId();
        double tolerance = dateToleranceDays == null ? 3 : dateToleranceDays;
        String runId = UUID.randomUUID().toString();
        // one matcher per account at a time: concurrent runs (or a manual match) could take the same payment twice
        lockAccount(organizationId, bankAccountId);
        jdbc.sql("INSERT INTO \"BankReconciliationRun\" (id, \"organizationId\", \"bankAccountId\", \"startedAt\", \"totalAmount\","
                        + " notes, \"createdById\") VALUES (?, ?, ?, now(), 0, ?, ?)")
                .params(runId, organizationId, bankAccountId, notes, TenantContext.userId()).update();
        Instant locked = lockedThrough(organizationId, bankAccountId);
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT * FROM \"BankStatementLine\" WHERE \"organizationId\" = ?"
                + " AND \"bankAccountId\" = ? AND status = 'unmatched'" + (locked == null ? "" : " AND \"postedAt\" > ?")
                + " ORDER BY \"postedAt\" ASC").params(locked == null ? List.of(organizationId, bankAccountId)
                : List.of(organizationId, bankAccountId, FiscalPeriodControl.ts(locked))));
        int matched = 0;
        Set<String> used = new HashSet<>();
        Set<String> usedJournalLines = new HashSet<>();
        long tolMs = (long) (tolerance * 24 * 60 * 60 * 1000);
        ForeignCash.Denomination fx = ForeignCash.of(jdbc, organizationId, bankAccountId);
        for (Map<String, Object> line : lines) {
            Instant at = (Instant) line.get("postedAt");
            BigDecimal amount = SqlRows.dec(line, "amount");
            String candidate = signedCandidate(organizationId, bankAccountId, amount, at, tolMs);
            if (candidate != null && !used.contains(candidate)) {
                int updated = jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'matched', \"matchedPaymentId\" = ?,"
                                + " \"matchedRunId\" = ? WHERE id = ? AND status = 'unmatched'")
                        .params(candidate, runId, line.get("id")).update();
                if (updated == 1) {
                    used.add(candidate);
                    matched++;
                }
                continue;
            }
            String journalLine = BankMatchingService.journalLineCandidate(jdbc, organizationId, bankAccountId, fx,
                    amount, at, tolMs);
            if (journalLine != null && !usedJournalLines.contains(journalLine)) {
                int updated = jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'matched', \"matchedJournalLineId\" = ?,"
                                + " \"matchedRunId\" = ? WHERE id = ? AND status = 'unmatched'")
                        .params(journalLine, runId, line.get("id")).update();
                if (updated == 1) {
                    usedJournalLines.add(journalLine);
                    matched++;
                }
            }
        }
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            totalAmount = totalAmount.add(SqlRows.dec(l, "amount"));
        }
        int unmatched = lines.size() - matched;
        jdbc.sql("UPDATE \"BankReconciliationRun\" SET \"finishedAt\" = now(), matched = ?, unmatched = ?, \"totalAmount\" = ?"
                + " WHERE id = ?").params(matched, unmatched, totalAmount, runId).update();
        audit.recordInTx(AuditInput.of("BankReconciliationRun", runId, "create", null, body("matched", matched,
                "unmatched", unmatched)));
        int m = matched;
        AfterCommit.run(() -> events.publish("bank_reconciliation.ran", body("organizationId", organizationId,
                "bankAccountId", bankAccountId, "runId", runId, "matched", m, "unmatched", unmatched)));
        return body("runId", runId, "matched", matched, "unmatched", unmatched);
    }

    /** Serialises matching and statement locking on one bank account until the transaction ends. */
    private void lockAccount(String org, String bankAccountId) {
        lockBankAccount(jdbc, org, bankAccountId);
    }

    /** As {@link #lockAccount}, for the other bank-matching paths (ADR-S073) in this package. */
    static void lockBankAccount(JdbcClient jdbc, String org, String bankAccountId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(? || '|bankrec|' || ?, 0))").params(org, bankAccountId)
                .query().listOfRows();
    }

    /**
     * Closest unmatched payment of the line's size and direction within the tolerance; the size is the payment's cash
     * movement in the account's currency (review R9), so a foreign payment into a functional account matches.
     */
    private String signedCandidate(String org, String bankAccountId, BigDecimal amount, Instant at, long tolMs) {
        return jdbc.sql("SELECT p.id FROM \"Payment\" p WHERE p.\"organizationId\" = ? AND p.\"accountId\" = ?"
                        + " AND p.status = 'posted' AND p.direction::text = ? AND "
                        + BankMatchingService.accountAmountSql("p") + " = ? AND p.\"paymentDate\" >= ?"
                        + " AND p.\"paymentDate\" <= ? AND " + BankMatchingService.UNMATCHED_PAYMENT
                        + " ORDER BY abs(extract(epoch FROM p.\"paymentDate\" - ?::timestamp)), p.id LIMIT 1")
                .params(org, bankAccountId, amount.signum() < 0 ? "outbound" : "inbound", amount.abs(),
                        FiscalPeriodControl.ts(at.minus(Duration.ofMillis(tolMs))),
                        FiscalPeriodControl.ts(at.plus(Duration.ofMillis(tolMs))), FiscalPeriodControl.ts(at))
                .query(String.class).optional().orElse(null);
    }

    /** Statement lines of one account, newest first, optionally one status. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> lines(String bankAccountId, String status, PageQuery q) {
        if (bankAccountId == null || bankAccountId.isEmpty()) {
            throw ApiException.badRequest("bankAccountId is required");
        }
        int page = Math.max(1, q.page() == null || q.page() == 0 ? 1 : q.page());
        int pageSize = Math.min(200, Math.max(1, q.pageSize() == null || q.pageSize() == 0 ? 25 : q.pageSize()));
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(), bankAccountId));
        StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ? AND l.\"bankAccountId\" = ?");
        if (status != null && !status.isEmpty()) {
            where.append(" AND l.status = ?");
            params.add(status);
        }
        if (q.search() != null && !q.search().isEmpty()) {
            where.append(" AND (l.description ILIKE ? OR l.\"externalRef\" ILIKE ?)");
            String like = "%" + BaseCrudService.escapeLike(q.search()) + "%";
            params.add(like);
            params.add(like);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.id, l.\"postedAt\", l.\"externalRef\","
                + " l.description, l.amount, l.\"currencyCode\", l.status, l.note, l.\"matchedPaymentId\","
                + " l.\"matchedRuleId\", l.\"journalEntryId\", l.\"matchedJournalLineId\","
                + " p.\"paymentNumber\" AS \"matchedPaymentNumber\", je.\"entryNumber\" AS \"matchedEntryNumber\""
                + " FROM \"BankStatementLine\" l"
                + " LEFT JOIN \"Payment\" p ON p.id = l.\"matchedPaymentId\" AND p.\"organizationId\" = "
                + "l.\"organizationId\""
                + " LEFT JOIN \"JournalLine\" jl ON jl.id = l.\"matchedJournalLineId\" AND jl.\"organizationId\" ="
                + " l.\"organizationId\" LEFT JOIN \"JournalEntry\" je ON je.id = jl.\"journalEntryId\""
                + where + " ORDER BY l.\"postedAt\" DESC, l.id LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize)
                .params(params));
        rows.forEach(r -> r.put("amount", JsNumbers.dec(SqlRows.dec(r, "amount"))));
        long total = jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" l" + where).params(params).query(Long.class)
                .single();
        return PageResult.of(rows, page, pageSize, total);
    }

    /** The last auto-match runs of one account. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs(String bankAccountId) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT id, \"startedAt\", \"finishedAt\", matched,"
                + " unmatched, \"totalAmount\", notes FROM \"BankReconciliationRun\" WHERE \"organizationId\" = ?"
                + " AND \"bankAccountId\" = ? ORDER BY \"startedAt\" DESC LIMIT 20")
                .params(TenantContext.organizationId(), bankAccountId));
        rows.forEach(r -> r.put("totalAmount", JsNumbers.dec(SqlRows.dec(r, "totalAmount"))));
        return rows;
    }

    /**
     * Where the account stands: GL balance, statement totals by state, and posted payments the bank has not shown yet
     * (the outstanding items of a reconciliation statement).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(String bankAccountId) {
        String org = TenantContext.organizationId();
        ForeignCash.Denomination fx = ForeignCash.of(jdbc, org, bankAccountId);
        BigDecimal book = ledgerBalance(org, bankAccountId, fx, null);
        Map<String, Object> lines = SqlRows.one(jdbc.sql("SELECT COALESCE(SUM(amount), 0) AS total,"
                + " COALESCE(SUM(amount) FILTER (WHERE status = 'matched'), 0) AS matched,"
                + " COALESCE(SUM(amount) FILTER (WHERE status = 'unmatched'), 0) AS unmatched,"
                + " COALESCE(SUM(amount) FILTER (WHERE status = 'excluded'), 0) AS excluded,"
                + " count(*) FILTER (WHERE status = 'unmatched') AS \"unmatchedCount\","
                + " count(*) FILTER (WHERE status = 'excluded') AS \"excludedCount\" FROM \"BankStatementLine\""
                + " WHERE \"organizationId\" = ? AND \"bankAccountId\" = ?").params(org, bankAccountId));
        // ADR-S073: a payment partly allocated to statement lines is outstanding for its open remainder only.
        Map<String, Object> open = SqlRows.one(jdbc.sql("SELECT COALESCE(SUM(o.open) FILTER (WHERE "
                + "o.direction ="
                + " 'inbound'), 0) AS inbound, COALESCE(SUM(o.open) FILTER (WHERE o.direction = 'outbound'), 0)"
                + " AS outbound, count(*) AS payments FROM (SELECT p.direction::text AS direction, "
                + BankMatchingService.accountAmountSql("p") + " - " + BankMatchingService.allocatedSql("p")
                + " AS open FROM \"Payment\" p WHERE p.\"organizationId\" = ?"
                + " AND p.\"accountId\" = ? AND p.status = 'posted' AND NOT EXISTS (SELECT 1 FROM "
                + "\"BankStatementLine\" b"
                + " WHERE b.\"organizationId\" = p.\"organizationId\" AND b.\"matchedPaymentId\" = p.id)) o"
                + " WHERE o.open > 0")
                .params(org, bankAccountId));
        return currencyFields(org, fx, body("bookBalance", JsNumbers.dec(book), "statementTotal", JsNumbers.dec(SqlRows.dec(lines, "total")),
                "matchedTotal", JsNumbers.dec(SqlRows.dec(lines, "matched")), "unmatchedTotal",
                JsNumbers.dec(SqlRows.dec(lines, "unmatched")), "unmatchedCount", lines.get("unmatchedCount"),
                "excludedTotal", JsNumbers.dec(SqlRows.dec(lines, "excluded")), "excludedCount",
                lines.get("excludedCount"), "outstandingInbound", JsNumbers.dec(SqlRows.dec(open, "inbound")),
                "outstandingOutbound", JsNumbers.dec(SqlRows.dec(open, "outbound")), "outstandingPayments",
                open.get("payments")));
    }

    /**
     * Ledger side of a reconciliation (R-8, ADR-S076): a designated foreign-currency account in its own currency (the
     * currency its statements, payments and transfers are in); every other account in the functional currency.
     */
    private BigDecimal ledgerBalance(String org, String accountId, ForeignCash.Denomination fx, java.sql.Timestamp upTo) {
        boolean own = fx != null && fx.designated();
        List<Object> params = new ArrayList<>(List.of(org, accountId));
        String sql = "SELECT coalesce(sum(" + (own ? "l.debit - l.credit" : "l.\"baseDebit\" - l.\"baseCredit\"")
                + "), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE"
                + " l.\"organizationId\" = ? AND l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed')";
        if (own) {
            sql += " AND upper(l.\"currencyId\") = ?";
            params.add(fx.currency());
        }
        if (upTo != null) {
            sql += " AND e.\"postingDate\" <= ?";
            params.add(upTo);
        }
        return jdbc.sql(sql).params(params).query(BigDecimal.class).single();
    }

    /** The currency the reconciliation amounts are in, and whether a foreign account still awaits designation. */
    private Map<String, Object> currencyFields(String org, ForeignCash.Denomination fx, Map<String, Object> out) {
        Map<String, Object> m = new LinkedHashMap<>(out);
        m.put("currencyCode", fx == null ? ForeignCash.functional(jdbc, org) : fx.currency());
        m.put("currencyState", fx == null ? "functional" : fx.designated() ? "designated" : "undesignated");
        return m;
    }

    /** Unmatched posted payments of the line's direction and size on its account, closest date first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> candidates(String lineId) {
        String org = TenantContext.organizationId();
        Map<String, Object> line = requireLine(lineId, org, false);
        BigDecimal amount = SqlRows.dec(line, "amount");
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT p.id, p.\"paymentNumber\", p.\"paymentDate\","
                + " p.amount, " + BankMatchingService.accountAmountSql("p") + " AS \"accountAmount\","
                + " p.direction::text AS direction, p.reference, pa.name AS \"partnerName\" FROM "
                + "\"Payment\" p"
                + " LEFT JOIN \"Partner\" pa ON pa.id = p.\"partnerId\" WHERE p.\"organizationId\" = ?"
                + " AND p.\"accountId\" = ? AND p.status = 'posted' AND p.direction::text = ? AND "
                + BankMatchingService.accountAmountSql("p") + " = ?"
                + " AND " + BankMatchingService.UNMATCHED_PAYMENT + " ORDER BY abs(extract(epoch FROM p.\"paymentDate\" - "
                + "?::timestamp)),"
                + " p.id LIMIT 20")
                .params(org, line.get("bankAccountId"), amount.signum() < 0 ? "outbound" : "inbound", amount.abs(),
                        FiscalPeriodControl.ts((Instant) line.get("postedAt"))));
        rows.forEach(r -> {
            r.put("amount", JsNumbers.dec(SqlRows.dec(r, "amount")));
            r.put("accountAmount", JsNumbers.dec(SqlRows.dec(r, "accountAmount")));
        });
        return rows;
    }

    /** Match one line to a payment by hand; the payment must be on the account, unmatched, same size and direction. */
    @Transactional
    public Map<String, Object> matchLine(String lineId, String paymentId) {
        String org = TenantContext.organizationId();
        Map<String, Object> line = requireLine(lineId, org, true);
        assertUnlocked(org, line);
        lockAccount(org, (String) line.get("bankAccountId"));
        if (!"unmatched".equals(line.get("status"))) {
            throw ApiException.badRequest("Only an unmatched line can be matched");
        }
        Map<String, Object> payment = SqlRows.one(jdbc.sql("SELECT p.id, " + BankMatchingService.accountAmountSql("p")
                + " AS \"accountAmount\", p.direction::text AS direction, p.status::text AS status, p.\"accountId\""
                + " FROM \"Payment\" p WHERE p.id = ? AND p.\"organizationId\" = ? FOR UPDATE OF p")
                .params(paymentId, org));
        if (payment == null) {
            throw ApiException.notFound("Payment not found");
        }
        BigDecimal amount = SqlRows.dec(line, "amount");
        String direction = amount.signum() < 0 ? "outbound" : "inbound";
        if (!"posted".equals(payment.get("status")) || !line.get("bankAccountId").equals(payment.get("accountId"))) {
            throw ApiException.badRequest("Payment is not a posted payment of this account");
        }
        // review R9: the payment's movement in the account's currency, the figure the statement shows
        if (!direction.equals(payment.get("direction")) || SqlRows.dec(payment,
                "accountAmount").compareTo(amount.abs()) != 0) {
            throw ApiException.badRequest("Payment amount and direction must equal the statement line");
        }
        long taken = jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ?"
                + " AND \"matchedPaymentId\" = ?").params(org, paymentId).query(Long.class).single()
                + jdbc.sql("SELECT count(*) FROM \"BankStatementMatch\" WHERE \"organizationId\" = ? AND \"paymentId\" = ?")
                        .params(org, paymentId).query(Long.class).single();
        if (taken > 0) {
            throw ApiException.conflict("Payment is already matched to another statement line");
        }
        jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'matched', \"matchedPaymentId\" = ?, \"matchedRunId\" = "
                + "NULL,"
                + " note = NULL WHERE id = ? AND \"organizationId\" = ?").params(paymentId, lineId, org).update();
        audit.recordInTx(AuditInput.of("BankStatementLine", lineId, "update", null, body("matchedPaymentId",
                paymentId)));
        return body("id", lineId, "status", "matched", "matchedPaymentId", paymentId);
    }

    /** Clear a line that has no payment behind it (bank charge, transfer already journalled), with the reason. */
    @Transactional
    public Map<String, Object> excludeLine(String lineId, String note) {
        String org = TenantContext.organizationId();
        Map<String, Object> line = requireLine(lineId, org, true);
        assertUnlocked(org, line);
        if (!"unmatched".equals(line.get("status"))) {
            throw ApiException.badRequest("Only an unmatched line can be excluded");
        }
        jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'excluded', note = ? WHERE id = ? AND \"organizationId\" "
                + "= ?")
                .params(note, lineId, org).update();
        audit.recordInTx(AuditInput.of("BankStatementLine", lineId, "update", null, body("status", "excluded", "note",
                note)));
        return body("id", lineId, "status", "excluded", "note", note);
    }

    private Map<String, Object> requireLine(String lineId, String org, boolean lock) {
        Map<String, Object> line = SqlRows.one(jdbc.sql("SELECT id, \"bankAccountId\", \"postedAt\", amount, status "
                + "FROM"
                + " \"BankStatementLine\" WHERE id = ? AND \"organizationId\" = ?" + (lock ? " FOR UPDATE" : ""))
                .params(lineId, org));
        if (line == null) {
            throw ApiException.notFound("Statement line not found");
        }
        return line;
    }

    /** Manually unmatch a single line (Nest throws a plain Error → 500 when the line is unknown). */
    @Transactional
    public Map<String, Object> unmatch(String lineId) {
        Map<String, Object> current = SqlRows.one(jdbc.sql("SELECT id, \"bankAccountId\", \"postedAt\" FROM"
                + " \"BankStatementLine\" WHERE id = ? AND \"organizationId\" = ?").params(lineId,
                TenantContext.organizationId()));
        if (current != null) {
            assertUnlocked(TenantContext.organizationId(), current);
            if (matching != null) {
                matching.undo(TenantContext.organizationId(), lineId);
            }
        }
        int n = jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'unmatched', \"matchedPaymentId\" = NULL, "
                + "\"matchedJournalLineId\" = NULL, \"matchedRunId\" = NULL, note = NULL"
                + " WHERE id = ? AND \"organizationId\" = ?").params(lineId, TenantContext.organizationId()).update();
        if (n == 0) {
            throw new IllegalStateException("Line not found");
        }
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ ACC-6: reconciliation statement + lock

    /** Latest locked statement date of an account, or null. */
    private Instant lockedThrough(String org, String bankAccountId) {
        return jdbc.sql("SELECT max(\"statementDate\") FROM \"BankReconciliationStatement\" WHERE \"organizationId\" = ?"
                        + " AND \"bankAccountId\" = ?").params(org, bankAccountId)
                .query((rs, n) -> rs.getTimestamp(1) == null ? null
                        : rs.getTimestamp(1).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC))
                .optional().orElse(null);
    }

    private void assertUnlocked(String org, String bankAccountId, Instant postedAt) {
        Instant locked = lockedThrough(org, bankAccountId);
        if (locked != null && postedAt != null && !postedAt.isAfter(locked)) {
            throw ApiException.badRequest("Bank reconciliation is locked through " + FiscalPeriodControl.day(locked)
                    + "; statement lines on or before that date cannot change");
        }
    }

    private void assertUnlocked(String org, Map<String, Object> line) {
        Object at = line.get("postedAt");
        Instant postedAt = at instanceof Instant i ? i : at instanceof java.sql.Timestamp t
                ? t.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC) : null;
        assertUnlocked(org, (String) line.get("bankAccountId"), postedAt);
    }

    /**
     * Reconciliation statement as of {@code asOf} for a statement closing balance (ACC-6). Proves
     * {@code closing + outstanding book items = ledger + statement items not in the books}; outstanding book items are
     * posted payments of the account up to {@code asOf} not matched to any statement line (inbound +, outbound −), at
     * their movement in the account's currency (review R9); statement-only items are unmatched, non-excluded lines up
     * to {@code asOf}. A line matched to a GL line (review R5) is in both the ledger and the statement, so like a
     * payment-matched line it is in neither adjustment. The ledger and the payments use the same org-local day end.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> statement(String bankAccountId, Instant asOf, BigDecimal closingBalance) {
        if (bankAccountId == null || bankAccountId.isEmpty()) {
            throw ApiException.badRequest("bankAccountId is required");
        }
        String org = TenantContext.organizationId();
        ForeignCash.Denomination fx = ForeignCash.of(jdbc, org, bankAccountId);
        // One cut-off for the book side (review: the payments used the raw instant while the ledger used the accounting
        // day): a payment counts by its journal's posting date — exactly when its cash line enters the ledger figure —
        // and by its payment date only when it has no journal.
        java.sql.Timestamp bookCutoff = FiscalPeriodControl.ts(AccountingDay.fromWallClock(asOf,
                AccountingDay.zone(jdbc)));
        BigDecimal ledger = ledgerBalance(org, bankAccountId, fx, bookCutoff);
        BigDecimal outstanding = jdbc.sql("SELECT coalesce(sum(CASE WHEN p.direction::text = 'outbound' THEN -1"
                        + " ELSE 1 END * (" + BankMatchingService.accountAmountSql("p") + " - "
                        + BankMatchingService.allocatedSql("p") + ")), 0) FROM \"Payment\" p WHERE p.\"organizationId\" = ?"
                        + " AND p.\"accountId\" = ? AND p.status = 'posted' AND coalesce((SELECT je.\"postingDate\" FROM"
                        + " \"JournalEntry\" je WHERE je.id = p.\"journalEntryId\"), p.\"paymentDate\") <= ? AND NOT EXISTS"
                        + " (SELECT 1 FROM \"BankStatementLine\" b WHERE b.\"organizationId\" = p.\"organizationId\" AND"
                        + " b.\"matchedPaymentId\" = p.id)")
                .params(org, bankAccountId, bookCutoff).query(BigDecimal.class).single();
        // ADR-S073: a partially allocated line is unrecorded for its unallocated remainder.
        BigDecimal unrecorded = jdbc.sql("SELECT coalesce(sum(CASE WHEN l.status = 'partial' THEN sign(l.amount) *"
                        + " (abs(l.amount) - coalesce((SELECT sum(m.amount) FROM \"BankStatementMatch\" m WHERE"
                        + " m.\"statementLineId\" = l.id), 0)) ELSE l.amount END), 0) FROM \"BankStatementLine\" l WHERE"
                        + " l.\"organizationId\" = ? AND l.\"bankAccountId\" = ? AND l.status IN ('unmatched', 'partial')"
                        + " AND l.\"postedAt\" <= ?")
                .params(org, bankAccountId, FiscalPeriodControl.ts(asOf)).query(BigDecimal.class).single();
        BigDecimal closing = closingBalance == null ? BigDecimal.ZERO : closingBalance;
        BigDecimal difference = closing.add(outstanding).subtract(ledger.add(unrecorded));
        Instant locked = lockedThrough(org, bankAccountId);
        return currencyFields(org, fx, body("bankAccountId", bankAccountId, "asOf", JsonConfig.toJsIso(asOf),
                "closingBalance", plain(closing), "ledgerBalance", plain(ledger), "outstandingBook", plain(outstanding),
                "unrecordedStatement", plain(unrecorded), "difference", plain(difference), "reconciled",
                difference.abs().compareTo(new BigDecimal("0.01")) <= 0,
                "lockedThrough", locked == null ? null : JsonConfig.toJsIso(locked)));
    }

    /** Lock a reconciled statement: refused unless the difference is zero and the date is after any earlier lock. */
    @Transactional
    public Map<String, Object> lockStatement(String bankAccountId, Instant asOf, BigDecimal closingBalance,
            String notes) {
        String org = TenantContext.organizationId();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(? || '|bankrec|' || ?, 0))").params(org, bankAccountId)
                .query().listOfRows();
        Instant locked = lockedThrough(org, bankAccountId);
        if (locked != null && !asOf.isAfter(locked)) {
            throw ApiException.badRequest("A reconciliation is already locked through " + FiscalPeriodControl.day(locked));
        }
        Map<String, Object> s = statement(bankAccountId, asOf, closingBalance);
        if (!Boolean.TRUE.equals(s.get("reconciled"))) {
            throw ApiException.badRequest("Statement does not reconcile: difference " + s.get("difference")
                    + " (closing " + s.get("closingBalance") + " + outstanding book items " + s.get("outstandingBook")
                    + " vs ledger " + s.get("ledgerBalance") + " + unrecorded statement items "
                    + s.get("unrecordedStatement") + ")");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"BankReconciliationStatement\" (id, \"organizationId\", \"bankAccountId\", \"statementDate\","
                        + " \"closingBalance\", \"ledgerBalance\", \"outstandingBook\", \"unrecordedStatement\", difference,"
                        + " notes, \"lockedBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org, bankAccountId, FiscalPeriodControl.ts(asOf), new BigDecimal((String) s.get("closingBalance")),
                        new BigDecimal((String) s.get("ledgerBalance")), new BigDecimal((String) s.get("outstandingBook")),
                        new BigDecimal((String) s.get("unrecordedStatement")), new BigDecimal((String) s.get("difference")),
                        notes, TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("BankReconciliationStatement", id, "create", null, s));
        Map<String, Object> out = new java.util.LinkedHashMap<>(s);
        out.put("id", id);
        out.put("lockedThrough", JsonConfig.toJsIso(asOf));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String bankAccountId) {
        String org = TenantContext.organizationId();
        long unmatched = jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND \"bankAccountId\" = ?"
                + " AND status = 'unmatched'").params(org, bankAccountId).query(Long.class).single();
        long matched = jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND \"bankAccountId\" = ?"
                + " AND status = 'matched'").params(org, bankAccountId).query(Long.class).single();
        long total = jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND \"bankAccountId\" = ?")
                .params(org, bankAccountId).query(Long.class).single();
        Map<String, Object> latest = SqlRows.one(jdbc.sql("SELECT \"startedAt\", matched, unmatched FROM \"BankReconciliationRun\""
                + " WHERE \"organizationId\" = ? AND \"bankAccountId\" = ? ORDER BY \"startedAt\" DESC LIMIT 1").params(org, bankAccountId));
        Map<String, Object> out = body("unmatched", unmatched, "matched", matched, "total", total);
        if (latest != null) {
            out.put("latestRun", latest);
        }
        return out;
    }
}
