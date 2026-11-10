package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Period close / reopen / lock (port of {@code period-close.service.ts}). Close runs in one transaction:
 * refuse while shifts are unreconciled or inventory postings are pending, zero every P&amp;L account into
 * Retained Earnings through the CLOSING journal ({@code postingKey period_close:<id>}), then flip the
 * period to {@code closed}. Reopen reverses the closing entry; a locked period is never reopened.
 */
@Service
public class PeriodCloseService {

    private record Period(String id, String name, String status, Timestamp startDate, Timestamp endDate,
            Timestamp closedAt) {}

    private record Line(String accountId, BigDecimal debit, BigDecimal credit) {}

    private final JdbcClient jdbc;
    private final EventBus events;
    private final AuditService audit;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AccountResolverService accounts;

    public PeriodCloseService(JdbcClient jdbc, EventBus events, AuditService audit, PostingService posting,
            AccountDeterminationService determination, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
        this.posting = posting;
        this.determination = determination;
        this.accounts = accounts;
    }

    private Period lockPeriod(String periodId, String organizationId) {
        jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(periodId, organizationId).query(String.class).list();
        return period(periodId, organizationId);
    }

    /**
     * The period with its bounds as real instants: stored bounds are org-local wall clock (ADR-S070), so every window
     * below (ledger, shifts, stock jobs, closing date) follows the organisation's calendar. Identity for UTC.
     */
    private Period period(String periodId, String organizationId) {
        java.time.ZoneId zone = AccountingDay.zone(jdbc, organizationId);
        Period p = jdbc.sql("SELECT id, name, status::text AS status, \"startDate\", \"endDate\", \"closedAt\""
                        + " FROM \"FiscalPeriod\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(periodId, organizationId)
                .query((rs, n) -> new Period(rs.getString("id"), rs.getString("name"), rs.getString("status"),
                        AccountingDay.fromWallClock(rs.getTimestamp("startDate"), zone),
                        AccountingDay.fromWallClock(rs.getTimestamp("endDate"), zone), rs.getTimestamp("closedAt")))
                .optional().orElse(null);
        if (p == null) {
            throw ApiException.notFound("Fiscal period not found");
        }
        return p;
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static Instant instant(Timestamp ts) {
        return ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    /** Close the period: post the closing journal, flip status to closed. */
    @Transactional
    public Map<String, Object> close(String periodId) {
        String organizationId = TenantContext.organizationId();
        Period period = lockPeriod(periodId, organizationId);
        if (!"open".equals(period.status())) {
            throw ApiException.badRequest("Fiscal period '" + period.name() + "' is " + period.status()
                    + "; only open periods can be closed");
        }
        long unresolvedShifts = count("SELECT count(*) FROM \"CashSession\" WHERE \"organizationId\" = ?"
                + " AND \"openedAt\" <= ? AND status::text <> 'reconciled'", organizationId, period.endDate());
        if (unresolvedShifts > 0) {
            throw ApiException.badRequest(
                    "Reconcile all register shifts through the period end before locking the accounting period");
        }
        long unpostedCogs = count("SELECT count(*) FROM \"StockPostingJob\" WHERE \"organizationId\" = ?"
                        + " AND status::text IN ('pending', 'processing', 'failed') AND \"createdAt\" >= ? AND \"createdAt\" <= ?",
                organizationId, period.startDate(), period.endDate());
        if (unpostedCogs > 0) {
            throw ApiException.badRequest("Cannot close '" + period.name() + "': " + unpostedCogs
                    + " stock-posting job(s) in this period have not posted COGS yet. Drain the stock-posting queue,"
                    + " and resolve any failed jobs in the Posting Monitor, before closing.");
        }
        String pending = " WHERE \"organizationId\" = ? AND status::text IN ('draft', 'pending', 'approved')";
        long stockOuts = count("SELECT count(*) FROM \"StockOut\"" + pending, organizationId);
        long waste = count("SELECT count(*) FROM \"WasteRecord\"" + pending, organizationId);
        long adjustments = count("SELECT count(*) FROM \"StockAdjustment\"" + pending, organizationId);
        long transfers = count("SELECT count(*) FROM \"StockTransfer\"" + pending, organizationId);
        long totalPending = stockOuts + waste + adjustments + transfers;
        if (totalPending > 0) {
            throw ApiException.badRequest("Cannot close '" + period.name() + "': " + totalPending
                    + " pending inventory mutation(s) (" + stockOuts + " stock-outs, " + waste + " waste, " + adjustments
                    + " adjustments, " + transfers + " transfers). Post or cancel them before closing.");
        }
        long openExceptions = count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ?"
                        + " AND status::text = 'open' AND \"createdAt\" >= ? AND \"createdAt\" <= ?",
                organizationId, period.startDate(), period.endDate());
        if (openExceptions > 0) {
            throw ApiException.badRequest("Cannot close '" + period.name() + "': " + openExceptions
                    + " open inventory exception(s) in this period — sale lines whose stock/COGS was not posted."
                    + " Resolve them in the Posting Monitor before closing.");
        }

        Map<String, BigDecimal[]> grouped = new LinkedHashMap<>();
        jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseDebit\") AS d, sum(l.\"baseCredit\") AS c FROM \"JournalLine\" l"
                        + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ?"
                        + " AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ? AND e.\"postingDate\" <= ?"
                        + " GROUP BY l.\"accountId\"")
                .params(organizationId, period.startDate(), period.endDate())
                .query((rs, n) -> grouped.put(rs.getString(1), new BigDecimal[] {
                    rs.getBigDecimal(2) == null ? BigDecimal.ZERO : rs.getBigDecimal(2),
                    rs.getBigDecimal(3) == null ? BigDecimal.ZERO : rs.getBigDecimal(3)}))
                .list();
        Map<String, AccountMeta> acctById = accounts.meta(grouped.keySet());

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalContraRevenue = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        BigDecimal totalCogs = BigDecimal.ZERO;
        List<Line> closingLines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> g : grouped.entrySet()) {
            AccountMeta acct = acctById.get(g.getKey());
            if (acct == null) {
                continue;
            }
            BigDecimal debit = g.getValue()[0];
            BigDecimal credit = g.getValue()[1];
            boolean creditNormal = "credit".equals(acct.normalBalance());
            BigDecimal bal = creditNormal ? credit.subtract(debit) : debit.subtract(credit);
            Line closing = creditNormal ? new Line(acct.id(), bal, BigDecimal.ZERO) : new Line(acct.id(), BigDecimal.ZERO, bal);
            String section = acct.reportSection() == null ? "" : acct.reportSection();
            switch (section) {
                case "revenue", "other_income" -> totalRevenue = totalRevenue.add(bal);
                case "contra_revenue" -> totalContraRevenue = totalContraRevenue.add(bal);
                case "operating_expense", "other_expense" -> totalExpense = totalExpense.add(bal);
                case "cogs" -> totalCogs = totalCogs.add(bal);
                default -> {
                    continue;
                }
            }
            if (bal.signum() != 0) {
                closingLines.add(closing);
            }
        }
        if (closingLines.isEmpty()) {
            throw ApiException.badRequest("Nothing to close for '" + period.name()
                    + "': no posted revenue or expense in this period");
        }
        BigDecimal netIncome = totalRevenue.subtract(totalContraRevenue).subtract(totalExpense).subtract(totalCogs);
        String retainedEarningsId;
        try {
            retainedEarningsId = determination.mapped("retained_earnings");
        } catch (ApiException e) {
            throw ApiException.badRequest("Cannot close '" + period.name() + "': no Retained Earnings account configured"
                    + " (set AccountMapping key 'retained_earnings' under Accounting > Account Mapping)");
        }
        if (netIncome.signum() >= 0) {
            closingLines.add(new Line(retainedEarningsId, BigDecimal.ZERO, netIncome));
        } else {
            closingLines.add(new Line(retainedEarningsId, netIncome.abs(), BigDecimal.ZERO));
        }
        BigDecimal totalDebit = closingLines.stream().map(Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = closingLines.stream().map(Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDebit.subtract(totalCredit).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw ApiException.badRequest("Closing entry unbalanced: debit " + PostingService.plain(totalDebit)
                    + " vs credit " + PostingService.plain(totalCredit));
        }

        boolean hasClosing = count("SELECT count(*) FROM \"Journal\" WHERE \"organizationId\" = ? AND code = 'CLOSING'"
                + " AND \"deletedAt\" IS NULL", organizationId) > 0;
        if (!hasClosing) {
            jdbc.sql("INSERT INTO \"Journal\" (id, \"organizationId\", code, name, \"journalType\", \"updatedAt\")"
                            + " VALUES (gen_random_uuid()::text, ?, 'CLOSING', 'Period Closing', 'closing', now())")
                    .param(organizationId).update();
        }
        List<PostingRequest.Line> lines = new ArrayList<>();
        for (int i = 0; i < closingLines.size(); i++) {
            Line l = closingLines.get(i);
            lines.add(new PostingRequest.Line(l.accountId(), PostingService.plain(l.debit()), PostingService.plain(l.credit()),
                    null, i == closingLines.size() - 1 ? "Net income / (loss)" : "Closing", null, null, null));
        }
        Map<String, Object> entry = posting.post(PostingRequest.of("CLOSING", instant(period.endDate()),
                "Closing of " + period.name(), "period_close", period.id(), "period_close:" + period.id(), lines));
        String entryId = (String) entry.get("id");

        jdbc.sql("UPDATE \"FiscalPeriod\" SET status = 'closed', \"closedAt\" = now(), \"closedBy\" = ?, \"updatedAt\" = now()"
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(TenantContext.userId(), period.id(), organizationId).update();
        String net = PostingService.plain(netIncome);
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("status", "closed");
        newValues.put("netIncome", net);
        newValues.put("closingEntryId", entryId);
        audit.recordInTx(AuditInput.of("FiscalPeriod", period.id(), "update", Map.of("status", "open"), newValues));
        AfterCommit.run(() -> events.publish("fiscal_period.closed", Map.of("organizationId", organizationId,
                "periodId", period.id(), "periodName", period.name(), "closingEntryId", entryId, "netIncome", net)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journalEntryId", entryId);
        out.put("netIncome", net);
        return out;
    }

    /** Reopen a closed period, reversing its closing entry. Locked periods are refused. */
    @Transactional
    public Map<String, Object> reopen(String periodId) {
        String organizationId = TenantContext.organizationId();
        Period period = lockPeriod(periodId, organizationId);
        if ("locked".equals(period.status())) {
            throw ApiException.badRequest("Fiscal period '" + period.name() + "' is locked; a locked period cannot be"
                    + " reopened. Unlock requires an explicit year-end unlock operation.");
        }
        if ("open".equals(period.status())) {
            return Map.of("reopened", true);
        }
        record Closing(String id, String status) {}
        Closing closing = jdbc.sql("SELECT id, status::text AS status FROM \"JournalEntry\" WHERE \"organizationId\" = ?"
                        + " AND \"sourceType\" = 'period_close'"
                        + (period.closedAt() != null ? " AND \"postingDate\" >= ? AND \"postingDate\" <= ?"
                                : " AND \"postingDate\" <= ?")
                        + " ORDER BY \"postingDate\" DESC LIMIT 1")
                .params(period.closedAt() != null
                        ? new Object[] {organizationId, period.startDate(), period.endDate()}
                        : new Object[] {organizationId, period.endDate()})
                .query((rs, n) -> new Closing(rs.getString("id"), rs.getString("status")))
                .optional().orElse(null);
        if (closing != null && "posted".equals(closing.status())) {
            posting.reverse(closing.id(), null, "Reopen " + period.name());
        }
        jdbc.sql("UPDATE \"FiscalPeriod\" SET status = 'open', \"closedAt\" = NULL, \"closedBy\" = NULL, \"lockedAt\" = NULL,"
                        + " \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?")
                .params(period.id(), organizationId).update();
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("status", "open");
        newValues.put("reversedClosingEntryId", closing == null ? null : closing.id());
        audit.recordInTx(AuditInput.of("FiscalPeriod", period.id(), "update", Map.of("status", period.status()), newValues));
        AfterCommit.run(() -> events.publish("fiscal_period.reopened", Map.of("organizationId", organizationId,
                "periodId", period.id(), "periodName", period.name())));
        return Map.of("reopened", true);
    }

    /** Lock a closed period: no more postings to its date range ever. */
    @Transactional
    public void lock(String periodId) {
        String organizationId = TenantContext.organizationId();
        Period period = period(periodId, organizationId);
        if ("open".equals(period.status())) {
            throw ApiException.badRequest("Fiscal period '" + period.name() + "' is open; close it before locking");
        }
        jdbc.sql("UPDATE \"FiscalPeriod\" SET status = 'locked', \"lockedAt\" = now(), \"updatedAt\" = now()"
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(period.id(), organizationId).update();
        audit.recordInTx(AuditInput.of("FiscalPeriod", period.id(), "update", Map.of("status", period.status()),
                Map.of("status", "locked")));
        AfterCommit.run(() -> events.publish("fiscal_period.locked", Map.of("organizationId", organizationId,
                "periodId", period.id(), "periodName", period.name())));
    }
}
