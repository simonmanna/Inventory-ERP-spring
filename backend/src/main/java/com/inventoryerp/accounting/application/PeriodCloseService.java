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
import com.inventoryerp.kernel.crud.SqlRows;
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
 * Retained Earnings through the CLOSING journal ({@code postingKey period_close:<id>}; none when the P&amp;L is zero on
 * every account, finance audit A3), then flip the period to {@code closed}. Reopen reverses the closing entry; a locked
 * period is never reopened.
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

    private InventoryValueChecks valueChecks;

    /** IPR GL-10: the live inventory value checks (absent when the inventory module is not on the classpath). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setValueChecks(InventoryValueChecks valueChecks) {
        this.valueChecks = valueChecks;
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

    /**
     * ADR-S083 S4: sync op types that move stock; their open dead letters block a period close. Kept equal to
     * {@code SyncOpCatalog.STOCK_TYPES} by {@code SyncOpCatalogTest} (accounting may not depend on sync).
     */
    public static final List<String> SYNC_STOCK_OP_TYPES = List.of("stock.in", "stock.out", "stock.count",
            "kitchen.void", "transfer.dispatch", "transfer.receive", "stock.adjust", "grn.receive");

    /**
     * Unposted stock-posting jobs of a period (finance audit A3, P2-13). A job belongs to the period of its sale: the
     * invoice's issue date, else the order's time, which is the date its stock and COGS post at
     * ({@code PosInvoiceService.datedForStock}). An offline sale of the 31st synced on the 2nd queues its job in the next
     * month and used to slip past the close. A job queued inside the period still counts, as before. Params:
     * organization, then the period start and end twice.
     */
    static final String UNPOSTED_STOCK_JOBS = "SELECT count(*) FROM \"StockPostingJob\" j"
            + " LEFT JOIN \"Invoice\" i ON i.id = j.\"invoiceId\" AND i.\"organizationId\" = j.\"organizationId\""
            + " LEFT JOIN \"Order\" o ON o.id = j.\"orderId\" AND o.\"organizationId\" = j.\"organizationId\""
            + " WHERE j.\"organizationId\" = ? AND j.status::text IN ('pending', 'processing', 'failed')"
            + " AND (COALESCE(i.\"issueDate\", o.\"createdAt\", j.\"createdAt\") BETWEEN ? AND ?"
            + " OR j.\"createdAt\" BETWEEN ? AND ?)";

    /**
     * Open sale-line inventory exceptions of a period: in the period of their sale when they name an invoice (as
     * {@link #UNPOSTED_STOCK_JOBS}), else in the period they were raised in. GL tie-out variances and inventory
     * integrity findings are not sale lines. Params: organization, tie-out kind, integrity kind prefix, then the period
     * start and end twice.
     */
    static final String OPEN_SALE_LINE_EXCEPTIONS = "SELECT count(*) FROM \"InventoryException\" x"
            + " LEFT JOIN \"Invoice\" i ON i.id = x.\"invoiceId\" AND i.\"organizationId\" = x.\"organizationId\""
            + " WHERE x.\"organizationId\" = ? AND x.status::text = 'open' AND x.kind IS DISTINCT FROM ?"
            + " AND NOT starts_with(COALESCE(x.kind, ''), ?)"
            // ADR-S088 / IPR review 1: the device-time flags are report-only and never hold a close.
            + " AND COALESCE(x.kind, '') NOT IN ('offline_clock_skew', 'offline_late_sync')"
            + " AND (COALESCE(i.\"issueDate\", x.\"createdAt\") BETWEEN ? AND ? OR x.\"createdAt\" BETWEEN ? AND ?)";

    /**
     * Close the period: post the closing journal, flip status to closed. A period whose profit and loss is zero on every
     * account closes without a closing entry (finance audit A3).
     */
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
        long unpostedCogs = count(UNPOSTED_STOCK_JOBS, organizationId, period.startDate(), period.endDate(),
                period.startDate(), period.endDate());
        if (unpostedCogs > 0) {
            throw ApiException.badRequest("Cannot close '" + period.name() + "': " + unpostedCogs
                    + " stock-posting job(s) in this period have not posted COGS yet. Drain the stock-posting queue,"
                    + " and resolve any failed jobs in the Posting Monitor, before closing.");
        }
        // Review 2026-11-17 (ACR-17; deviation: Nest counted every pending document of the organisation). A stock
        // document posts when it is approved, never into an earlier date, so only those raised up to the period end
        // belong to it — a draft stock-out raised on the 2nd no longer blocks closing the month before.
        String pending = " WHERE \"organizationId\" = ? AND status::text IN ('draft', 'pending', 'approved')"
                + " AND \"createdAt\" <= ?";
        long stockOuts = count("SELECT count(*) FROM \"StockOut\"" + pending, organizationId, period.endDate());
        long waste = count("SELECT count(*) FROM \"WasteRecord\"" + pending, organizationId, period.endDate());
        long adjustments = count("SELECT count(*) FROM \"StockAdjustment\"" + pending, organizationId,
                period.endDate());
        long transfers = count("SELECT count(*) FROM \"StockTransfer\"" + pending, organizationId, period.endDate());
        long totalPending = stockOuts + waste + adjustments + transfers;
        if (totalPending > 0) {
            throw ApiException.badRequest("Cannot close '" + period.name() + "': " + totalPending
                    + " pending inventory mutation(s) (" + stockOuts + " stock-outs, " + waste + " waste, " + adjustments
                    + " adjustments, " + transfers + " transfers). Post or cancel them before closing.");
        }
        // ADR-S083 S4/S5: stock work recorded offline that the server refused outright is a dead letter until a person
        // corrects or discards it. Until then the stock of its location is not final, so the period cannot close.
        List<Map<String, Object>> stockDeadLetters = SqlRows.list(jdbc.sql("SELECT COALESCE(l.name, d.payload->>'locationId',"
                        + " d.payload->>'fromLocationId', d.payload->>'warehouseId', '?') AS location, count(*) AS n"
                        + " FROM \"SyncOpDeadLetter\" d LEFT JOIN \"InventoryLocation\" l ON l.id = COALESCE("
                        + "d.payload->>'locationId', d.payload->>'fromLocationId', d.payload->>'warehouseId')"
                        + " WHERE d.\"organizationId\" = ? AND d.status IN ('open', 'investigating')"
                        + " AND d.\"opType\" = ANY(?::text[]) AND d.\"createdAt\" <= ? GROUP BY 1 ORDER BY 1")
                .params(organizationId, SYNC_STOCK_OP_TYPES.toArray(String[]::new), period.endDate()));
        if (!stockDeadLetters.isEmpty()) {
            List<String> where = new ArrayList<>();
            stockDeadLetters.forEach(r -> where.add(r.get("location") + " (" + r.get("n") + ")"));
            throw ApiException.badRequest("Cannot close '" + period.name() + "': stock work recorded offline was refused"
                    + " and is still open in Sync dead letters for " + String.join(", ", where)
                    + ". Correct or discard it before closing.");
        }
        // A GL tie-out variance (daily tie-out job) is not a sale line: it shows on the close checklist as its own
        // advisory item and does not block the close. Inventory integrity findings (R0) are not sale lines either:
        // the value ones are checked below, the quantity ones never hold the close.
        long openExceptions = count(OPEN_SALE_LINE_EXCEPTIONS, organizationId, InventoryTieOutMonitor.KIND,
                InventoryTieOutMonitor.INTEGRITY_KIND_PREFIX, period.startDate(), period.endDate(), period.startDate(),
                period.endDate());
        if (openExceptions > 0) {
            throw ApiException.badRequest("Cannot close '" + period.name() + "': " + openExceptions
                    + " open inventory exception(s) in this period — sale lines whose stock/COGS was not posted."
                    + " Resolve them in the Posting Monitor before closing.");
        }

        // Hardening R0: an open integrity finding on stock value (valuation vs stock ledger, valuation vs GL) means the
        // period's stock value is not final, so the period cannot close. IPR GL-10 (deviation): a finding raised before
        // the period started counts too (it counted for nothing), and the value checks are re-run at close time, so a
        // variance no daily run has raised yet holds the close too. IPR review 2 (R2-1): both as of the period end — a
        // finding raised after it, a later variance or a journal dated after it do not hold this period, and a variance
        // of the period that a later entry offsets still does.
        long valueFindings = count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ?"
                        + " AND status::text = 'open' AND kind = ANY(?::text[]) AND \"createdAt\" <= ?",
                organizationId, InventoryTieOutMonitor.INTEGRITY_VALUE_KINDS.toArray(String[]::new), period.endDate());
        if (valueChecks != null) {
            List<InventoryValueChecks.Failure> failing = valueChecks.failing(instant(period.endDate()));
            if (!failing.isEmpty()) {
                List<String> what = new ArrayList<>();
                failing.forEach(f -> what.add(f.key() + " (" + f.failures() + " row(s)"
                        + (f.note() != null ? "; " + f.note() : "") + ")"));
                throw ApiException.badRequest("Cannot close '" + period.name() + "': the inventory value check(s) "
                        + String.join(", ", what) + " fail as of the period end. Correct the stock value or the GL,"
                        + " then close.");
            }
        }
        if (valueFindings > 0) {
            throw ApiException.badRequest("Cannot close '" + period.name() + "': " + valueFindings
                    + " open inventory integrity finding(s) on stock value (valuation vs stock ledger or GL)."
                    + " Investigate and resolve them in the Posting Monitor before closing.");
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
        // A3 (P2-3): every account with postings, also one this instance has not cached yet
        Map<String, AccountMeta> acctById = accounts.ledgerMeta(grouped.keySet());

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalContraRevenue = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        BigDecimal totalCogs = BigDecimal.ZERO;
        List<Line> closingLines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> g : grouped.entrySet()) {
            AccountMeta acct = acctById.get(g.getKey());
            BigDecimal debit = g.getValue()[0];
            BigDecimal credit = g.getValue()[1];
            boolean creditNormal = "credit".equals(acct.normalBalance());
            BigDecimal bal = creditNormal ? credit.subtract(debit) : debit.subtract(credit);
            // IPR R2 defect 1: an account whose period balance sits on its abnormal side (an expense with a net credit,
            // a revenue account with a net debit) is closed on the opposite side, with a positive amount. A negative
            // closing line was refused ("Line amounts cannot be negative") and blocked the close.
            BigDecimal amt = bal.abs();
            boolean closeWithDebit = creditNormal == (bal.signum() >= 0);
            Line closing = closeWithDebit ? new Line(acct.id(), amt, BigDecimal.ZERO) : new Line(acct.id(), BigDecimal.ZERO, amt);
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
        BigDecimal netIncome = totalRevenue.subtract(totalContraRevenue).subtract(totalExpense).subtract(totalCogs);
        // Finance audit A3 (P2-1, owner-approved deviation; Nest refused "Nothing to close for '…': no posted revenue or
        // expense in this period"): profit and loss that is zero on every account (no postings, balance-sheet movements
        // only, or everything reversed) leaves nothing to post, and the period closes without a closing entry.
        String entryId = closingLines.isEmpty() ? null : postClosingEntry(period, organizationId, closingLines, netIncome);

        jdbc.sql("UPDATE \"FiscalPeriod\" SET status = 'closed', \"closedAt\" = now(), \"closedBy\" = ?, \"updatedAt\" = now()"
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(TenantContext.userId(), period.id(), organizationId).update();
        String net = PostingService.plain(netIncome);
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("status", "closed");
        newValues.put("netIncome", net);
        newValues.put("closingEntryId", entryId);
        audit.recordInTx(AuditInput.of("FiscalPeriod", period.id(), "update", Map.of("status", "open"), newValues));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("organizationId", organizationId);
        event.put("periodId", period.id());
        event.put("periodName", period.name());
        event.put("closingEntryId", entryId);
        event.put("netIncome", net);
        AfterCommit.run(() -> events.publish("fiscal_period.closed", event));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journalEntryId", entryId);
        out.put("netIncome", net);
        return out;
    }

    /**
     * Post the CLOSING journal: zero each profit and loss account, the net income to Retained Earnings. Break-even
     * closes the accounts against each other: a zero Retained Earnings line would be refused, so there is none and the
     * mapping is not needed (finance audit A3, P2-1). Returns the entry id.
     */
    private String postClosingEntry(Period period, String organizationId, List<Line> closingLines, BigDecimal netIncome) {
        List<Line> all = new ArrayList<>(closingLines);
        boolean toRetainedEarnings = netIncome.signum() != 0;
        if (toRetainedEarnings) {
            String retainedEarningsId;
            try {
                retainedEarningsId = determination.mapped("retained_earnings");
            } catch (ApiException e) {
                throw ApiException.badRequest("Cannot close '" + period.name() + "': no Retained Earnings account"
                        + " configured (set AccountMapping key 'retained_earnings' under Accounting > Account Mapping)");
            }
            all.add(netIncome.signum() > 0 ? new Line(retainedEarningsId, BigDecimal.ZERO, netIncome)
                    : new Line(retainedEarningsId, netIncome.abs(), BigDecimal.ZERO));
        }
        BigDecimal totalDebit = all.stream().map(Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = all.stream().map(Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
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
        for (int i = 0; i < all.size(); i++) {
            Line l = all.get(i);
            boolean net = toRetainedEarnings && i == all.size() - 1;
            lines.add(new PostingRequest.Line(l.accountId(), PostingService.plain(l.debit()), PostingService.plain(l.credit()),
                    null, net ? "Net income / (loss)" : "Closing", null, null, null));
        }
        Map<String, Object> entry = posting.post(PostingRequest.of("CLOSING", instant(period.endDate()),
                "Closing of " + period.name(), "period_close", period.id(), "period_close:" + period.id(), lines));
        return (String) entry.get("id");
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
        // a closed year's net income and carried-forward balances would go stale (review 2026-11-15, G-3)
        String closedYear = jdbc.sql("SELECT y.name FROM \"FiscalYear\" y JOIN \"FiscalPeriod\" p ON p.id = ?"
                        + " WHERE y.\"organizationId\" = ? AND y.status = 'closed' AND (p.\"fiscalYearId\" = y.id"
                        + " OR (p.\"startDate\"::date >= y.\"startDate\" AND p.\"endDate\"::date <= y.\"endDate\")) LIMIT 1")
                .params(period.id(), organizationId).query(String.class).optional().orElse(null);
        if (closedYear != null) {
            throw ApiException.badRequest("Fiscal period '" + period.name() + "' belongs to the closed fiscal year '"
                    + closedYear + "'; reopen the fiscal year first.");
        }
        record Closing(String id, String status) {}
        // Review 2026-11-17 (ACR-1): the closing entry still in force — this period's own (sourceId), posted. The old
        // lookup took the latest period_close entry by date with no status filter: after close → reopen → close the
        // first, already reversed, entry shares the period-end date and could be picked, so the second reopen skipped
        // the reversal and the closing amounts stayed in the books. The date window stays as the fallback for closing
        // entries written before the source id was kept, posted ones only.
        Closing closing = jdbc.sql("SELECT id, status::text AS status FROM \"JournalEntry\" WHERE \"organizationId\" = ?"
                        + " AND \"sourceType\" = 'period_close' AND \"sourceId\" = ? AND status::text = 'posted'"
                        + " ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(organizationId, period.id())
                .query((rs, n) -> new Closing(rs.getString("id"), rs.getString("status")))
                .optional().orElseGet(() -> jdbc.sql("SELECT id, status::text AS status FROM \"JournalEntry\" WHERE"
                        + " \"organizationId\" = ? AND \"sourceType\" = 'period_close' AND status::text = 'posted'"
                        + " AND (\"sourceId\" IS NULL OR \"sourceId\" NOT IN (SELECT id FROM \"FiscalPeriod\" WHERE"
                        + " \"organizationId\" = ?))"
                        + (period.closedAt() != null ? " AND \"postingDate\" >= ? AND \"postingDate\" <= ?"
                                : " AND \"postingDate\" <= ?")
                        + " ORDER BY \"postingDate\" DESC, \"createdAt\" DESC LIMIT 1")
                .params(period.closedAt() != null
                        ? new Object[] {organizationId, organizationId, period.startDate(), period.endDate()}
                        : new Object[] {organizationId, organizationId, period.endDate()})
                .query((rs, n) -> new Closing(rs.getString("id"), rs.getString("status")))
                .optional().orElse(null));
        jdbc.sql("UPDATE \"FiscalPeriod\" SET status = 'open', \"closedAt\" = NULL, \"closedBy\" = NULL, \"lockedAt\" = NULL,"
                        + " \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?")
                .params(period.id(), organizationId).update();
        if (closing != null && "posted".equals(closing.status())) {
            // reversed after the period is open, so the reversal keeps the closing entry's own date: the period nets
            // back to its activity and can be closed again, and no closing amounts leak into the current period (G-5;
            // Nest reversed first, dating it today)
            posting.reverse(closing.id(), null, "Reopen " + period.name());
        }
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("status", "open");
        newValues.put("reversedClosingEntryId", closing == null ? null : closing.id());
        audit.recordInTx(AuditInput.of("FiscalPeriod", period.id(), "update", Map.of("status", period.status()), newValues));
        AfterCommit.run(() -> events.publish("fiscal_period.reopened", Map.of("organizationId", organizationId,
                "periodId", period.id(), "periodName", period.name())));
        return Map.of("reopened", true);
    }

    /**
     * Lock a closed period: no more postings to its date range ever. Under the period row lock, as close and reopen: a
     * reopen committing while the lock read the status was overwritten and the reopened period ended up locked
     * (finance audit A3).
     */
    @Transactional
    public void lock(String periodId) {
        String organizationId = TenantContext.organizationId();
        Period period = lockPeriod(periodId, organizationId);
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
