package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.accounting.application.GlAutomationCommands.DeferralFromLineInput;
import com.inventoryerp.accounting.application.GlAutomationCommands.DeferralInput;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deferred revenue and prepaid expense schedules (ADR-S073; Nest has no counterpart). A schedule releases a balance
 * that sits on a balance-sheet account ({@code sourceAccountId}: deferred revenue / prepaid) to a P&amp;L account
 * ({@code targetAccountId}) in planned slices ({@link GlSchedules#deferralPlan}). Each slice posts on its period end
 * with the key {@code deferral:<scheduleId>:<periodIndex>}, so the daily job and a manual release never book a slice
 * twice. Revenue: Dr deferred revenue / Cr revenue. Expense: Dr expense / Cr prepaid. Cancelling releases nothing
 * further; what is left stays on the deferral account and shows in the remaining-balance report.
 */
@Service
public class DeferralService {

    private static final Logger log = LoggerFactory.getLogger(DeferralService.class);

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AuditService audit;
    private final TransactionTemplate isolated;

    public DeferralService(JdbcClient jdbc, PostingService posting, AccountResolverService accounts, AuditService audit,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.accounts = accounts;
        this.audit = audit;
        this.isolated = new TransactionTemplate(txm);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        String where = "";
        if (status != null && !status.isEmpty()) {
            where = " AND s.status = ?";
            params.add(status);
        }
        return SqlRows.list(jdbc.sql("SELECT s.*, sa.code AS \"sourceAccountCode\", sa.name AS \"sourceAccountName\","
                + " ta.code AS \"targetAccountCode\", ta.name AS \"targetAccountName\" FROM \"DeferralSchedule\" s"
                + " LEFT JOIN \"Account\" sa ON sa.id = s.\"sourceAccountId\" LEFT JOIN \"Account\" ta ON ta.id ="
                + " s.\"targetAccountId\" WHERE s.\"organizationId\" = ?" + where + " ORDER BY s.\"createdAt\" DESC")
                .params(params)).stream().map(DeferralService::render).toList();
    }

    /** A schedule with its planned / posted slices. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> out = render(require(id, false));
        List<Map<String, Object>> releases = SqlRows.list(jdbc.sql("SELECT r.id, r.\"periodIndex\", r.\"periodStart\","
                + " r.\"periodEnd\", r.amount, r.status, r.\"journalEntryId\", r.\"postedAt\", e.\"entryNumber\" FROM"
                + " \"DeferralRelease\" r LEFT JOIN \"JournalEntry\" e ON e.id = r.\"journalEntryId\" WHERE"
                + " r.\"organizationId\" = ? AND r.\"scheduleId\" = ? ORDER BY r.\"periodIndex\"")
                .params(TenantContext.organizationId(), id));
        releases.forEach(r -> {
            r.put("periodStart", String.valueOf(r.get("periodStart")));
            r.put("periodEnd", String.valueOf(r.get("periodEnd")));
            r.put("amount", plain(SqlRows.dec(r, "amount")));
        });
        out.put("releases", releases);
        return out;
    }

    /**
     * Remaining balance as of {@code asOf}: per schedule total, released (posted slices dated on or before
     * {@code asOf}) and remaining; per deferral account the schedules' remaining against the account's GL balance in
     * its natural sign, so the register reconciles to the ledger.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> report(String asOf) {
        String org = TenantContext.organizationId();
        LocalDate day = asOf == null || asOf.isEmpty() ? LocalDate.now(BusinessZone.get())
                : RecurringJournalService.day(asOf);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT s.id, s.name, s.kind, s.status,"
                + " s.\"sourceAccountId\", a.code AS \"sourceAccountCode\", a.name AS \"sourceAccountName\","
                + " s.\"totalAmount\", s.\"startDate\", s.months, coalesce((SELECT sum(r.amount) FROM \"DeferralRelease\" r"
                + " WHERE r.\"scheduleId\" = s.id AND r.status = 'posted' AND r.\"periodEnd\" <= ?), 0) AS released"
                + " FROM \"DeferralSchedule\" s LEFT JOIN \"Account\" a ON a.id = s.\"sourceAccountId\" WHERE"
                + " s.\"organizationId\" = ? AND s.\"startDate\" <= ? ORDER BY a.code, s.\"createdAt\"")
                .params(Date.valueOf(day), org, Date.valueOf(day)));
        Map<String, Map<String, Object>> byAccount = new LinkedHashMap<>();
        BigDecimal totalRemaining = BigDecimal.ZERO;
        List<Map<String, Object>> schedules = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal total = SqlRows.dec(r, "totalAmount");
            BigDecimal released = SqlRows.dec(r, "released");
            BigDecimal remaining = total.subtract(released);
            totalRemaining = totalRemaining.add(remaining);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", r.get("id"));
            s.put("name", r.get("name"));
            s.put("kind", r.get("kind"));
            s.put("status", r.get("status"));
            s.put("sourceAccountId", r.get("sourceAccountId"));
            s.put("sourceAccountCode", r.get("sourceAccountCode"));
            s.put("startDate", String.valueOf(r.get("startDate")));
            s.put("months", r.get("months"));
            s.put("totalAmount", plain(total));
            s.put("released", plain(released));
            s.put("remaining", plain(remaining));
            schedules.add(s);
            String accountId = (String) r.get("sourceAccountId");
            Map<String, Object> acc = byAccount.computeIfAbsent(accountId, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("accountId", accountId);
                m.put("accountCode", r.get("sourceAccountCode"));
                m.put("accountName", r.get("sourceAccountName"));
                m.put("kind", r.get("kind"));
                m.put("scheduleRemaining", BigDecimal.ZERO);
                return m;
            });
            acc.put("scheduleRemaining", ((BigDecimal) acc.get("scheduleRemaining")).add(remaining));
        }
        List<Map<String, Object>> accountsOut = new ArrayList<>();
        for (Map<String, Object> acc : byAccount.values()) {
            BigDecimal net = jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l"
                    + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                    + " l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" < ?")
                    .params(org, acc.get("accountId"), FiscalPeriodControl.ts(day.plusDays(1)
                            .atStartOfDay(ZoneOffset.UTC).toInstant()))
                    .query(BigDecimal.class).single();
            BigDecimal gl = "revenue".equals(acc.get("kind")) ? net.negate() : net;
            BigDecimal remaining = (BigDecimal) acc.get("scheduleRemaining");
            acc.put("scheduleRemaining", plain(remaining));
            acc.put("glBalance", plain(gl));
            acc.put("difference", plain(gl.subtract(remaining)));
            accountsOut.add(acc);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", day.toString());
        out.put("totalRemaining", plain(totalRemaining));
        out.put("accounts", accountsOut);
        out.put("schedules", schedules);
        return out;
    }

    // ------------------------------------------------------------------ commands

    /** A schedule over a balance already on the deferral account; nothing is posted until the first release. */
    @Transactional
    public Map<String, Object> create(DeferralInput in) {
        String source = in.sourceAccountId() != null ? in.sourceAccountId() : defaultSource(in.kind());
        assertAccounts(source, in.targetAccountId());
        String id = insert(in.name(), in.kind(), source, in.targetAccountId(), in.totalAmount(),
                RecurringJournalService.day(in.startDate()), in.months(), in.method(), in.journalCode(), null, null,
                in.partnerId(), in.branchId(), in.costCenterId(), in.notes());
        Map<String, Object> out = get(id);
        audit.recordInTx(AuditInput.of("DeferralSchedule", id, "create", null, render(require(id, false))));
        return out;
    }

    /**
     * Defer (part of) a posted journal line, typically an invoice or bill line. A credit line is revenue, a debit line
     * an expense. On a P&amp;L account the deferred amount is first moved to the deferral account (posting key
     * {@code deferral:<id>:initial}, on the line's date while open, else today) and released back over time; on a
     * balance-sheet account the line itself is the balance and {@code targetAccountId} is required.
     */
    @Transactional
    public Map<String, Object> createFromLine(DeferralFromLineInput in) {
        String org = TenantContext.organizationId();
        Map<String, Object> line = SqlRows.one(jdbc.sql("SELECT l.id, l.\"accountId\", l.\"baseDebit\", l.\"baseCredit\","
                + " l.\"partnerId\", l.\"branchId\", l.\"costCenterId\", l.description, e.id AS \"entryId\","
                + " e.\"entryNumber\", e.\"postingDate\", e.status::text AS status FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.id = ? AND l.\"organizationId\" = ?")
                .params(in.journalLineId(), org));
        if (line == null) {
            throw ApiException.notFound("Journal line not found");
        }
        if (!"posted".equals(line.get("status"))) {
            throw ApiException.badRequest("Only a line of a posted entry can be deferred");
        }
        long taken = jdbc.sql("SELECT count(*) FROM \"DeferralSchedule\" WHERE \"organizationId\" = ? AND"
                + " \"journalLineId\" = ? AND status <> 'cancelled'").params(org, in.journalLineId()).query(Long.class)
                .single();
        if (taken > 0) {
            throw ApiException.conflict("Journal line is already deferred");
        }
        BigDecimal credit = SqlRows.dec(line, "baseCredit");
        BigDecimal debit = SqlRows.dec(line, "baseDebit");
        String kind = credit.signum() > 0 ? "revenue" : "expense";
        BigDecimal lineAmount = credit.signum() > 0 ? credit : debit;
        BigDecimal amount = in.amount() != null ? in.amount() : lineAmount;
        if (amount.compareTo(lineAmount) > 0) {
            throw ApiException.badRequest("Deferred amount " + plain(amount) + " exceeds the line amount "
                    + plain(lineAmount));
        }
        String lineAccount = (String) line.get("accountId");
        AccountMeta meta = accounts.metaById(lineAccount);
        boolean pnl = AccountClassification.isProfitAndLoss(meta.classification());
        String source;
        String target;
        if (pnl) {
            target = lineAccount;
            source = in.sourceAccountId() != null ? in.sourceAccountId() : defaultSource(kind);
        } else {
            if (in.targetAccountId() == null) {
                throw ApiException.badRequest("targetAccountId is required when the line is on a balance-sheet account");
            }
            source = lineAccount;
            target = in.targetAccountId();
        }
        assertAccounts(source, target);
        String name = in.name() != null && !in.name().isBlank() ? in.name()
                : "Deferral of " + line.get("entryNumber") + (line.get("description") == null ? ""
                        : " — " + line.get("description"));
        String id = insert(name, kind, source, target, amount, RecurringJournalService.day(in.startDate()),
                in.months(), in.method(), null, in.journalLineId(), (String) line.get("entryId"),
                (String) line.get("partnerId"), (String) line.get("branchId"), (String) line.get("costCenterId"),
                in.notes());
        if (pnl) {
            String value = plain(amount);
            String partner = (String) line.get("partnerId");
            Line drLine = "revenue".equals(kind)
                    ? new Line(target, value, null, partner, "Deferred to " + meta.code(), null, null, null)
                    : new Line(source, value, null, partner, "Prepaid from " + meta.code(), null, null, null);
            Line crLine = "revenue".equals(kind)
                    ? new Line(source, null, value, partner, "Deferred revenue", null, null, null)
                    : new Line(target, null, value, partner, "Expense deferred", null, null, null);
            Map<String, Object> entry = posting.post(new PostingRequest("GEN",
                    posting.openDateOrToday((Instant) line.get("postingDate")), "Deferral of " + line.get("entryNumber"),
                    null, null, "deferral", id, null, "deferral:" + id + ":initial", (String) line.get("branchId"),
                    (String) line.get("costCenterId"), null, List.of(drLine, crLine)));
            jdbc.sql("UPDATE \"DeferralSchedule\" SET \"reclassEntryId\" = ? WHERE id = ?").params(entry.get("id"), id)
                    .update();
        }
        audit.recordInTx(AuditInput.of("DeferralSchedule", id, "create", null, render(require(id, false))));
        return get(id);
    }

    /** Release this schedule's slices due up to {@code asOf} (default today) in one transaction. */
    @Transactional
    public Map<String, Object> release(String id, String asOf) {
        Map<String, Object> s = require(id, true);
        if (!"active".equals(s.get("status"))) {
            throw ApiException.badRequest("Only an active deferral schedule can be released");
        }
        LocalDate day = asOf == null || asOf.isEmpty() ? LocalDate.now(BusinessZone.get())
                : RecurringJournalService.day(asOf);
        int released = 0;
        for (String releaseId : due(id, day)) {
            postRelease(s, releaseId);
            released++;
        }
        completeIfDone(id);
        Map<String, Object> out = get(id);
        out.put("releasedNow", released);
        return out;
    }

    /**
     * Job body (and {@code POST /deferrals/run}) for the current organization: every planned slice of an active
     * schedule whose period ended on or before {@code asOf}, each in its own transaction, in period order; a failed
     * slice (e.g. closed period) stops that schedule until the next run.
     */
    public Map<String, Object> releaseDue(LocalDate asOf) {
        String org = TenantContext.organizationId();
        List<String> scheduleIds = isolated.execute(s -> jdbc.sql("SELECT DISTINCT s.id FROM \"DeferralSchedule\" s"
                + " JOIN \"DeferralRelease\" r ON r.\"scheduleId\" = s.id WHERE s.\"organizationId\" = ? AND s.status ="
                + " 'active' AND r.status = 'planned' AND r.\"periodEnd\" <= ?").params(org, Date.valueOf(asOf))
                .query(String.class).list());
        int released = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (String id : scheduleIds) {
            for (String releaseId : isolated.execute(s -> due(id, asOf))) {
                try {
                    isolated.executeWithoutResult(st -> {
                        Map<String, Object> s = require(id, true);
                        if ("active".equals(s.get("status"))) {
                            postRelease(s, releaseId);
                            completeIfDone(id);
                        }
                    });
                    released++;
                } catch (RuntimeException e) {
                    log.warn("[deferrals] schedule {} slice {} failed: {}", id, releaseId, e.getMessage());
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("scheduleId", id);
                    f.put("releaseId", releaseId);
                    f.put("error", e.getMessage());
                    failures.add(f);
                    break;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", asOf.toString());
        out.put("schedules", scheduleIds.size());
        out.put("released", released);
        out.put("failures", failures);
        return out;
    }

    /** Cancel: planned slices are cancelled, posted ones stay; the rest of the balance stays on the deferral account. */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        Map<String, Object> s = require(id, true);
        if (!"active".equals(s.get("status"))) {
            throw ApiException.badRequest("Only an active deferral schedule can be cancelled");
        }
        jdbc.sql("UPDATE \"DeferralRelease\" SET status = 'cancelled' WHERE \"scheduleId\" = ? AND status = 'planned'")
                .param(id).update();
        jdbc.sql("UPDATE \"DeferralSchedule\" SET status = 'cancelled', \"cancelledAt\" = now(), \"cancelledBy\" = ?,"
                + " \"cancelReason\" = ?, \"updatedAt\" = now() WHERE id = ?").params(TenantContext.userId(), reason, id)
                .update();
        audit.recordInTx(AuditInput.of("DeferralSchedule", id, "update", null, Map.of("status", "cancelled")));
        return get(id);
    }

    // ------------------------------------------------------------------ internals

    private List<String> due(String scheduleId, LocalDate asOf) {
        return jdbc.sql("SELECT id FROM \"DeferralRelease\" WHERE \"organizationId\" = ? AND \"scheduleId\" = ? AND"
                + " status = 'planned' AND \"periodEnd\" <= ? ORDER BY \"periodIndex\"")
                .params(TenantContext.organizationId(), scheduleId, Date.valueOf(asOf)).query(String.class).list();
    }

    private void postRelease(Map<String, Object> s, String releaseId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"DeferralRelease\" WHERE id = ? AND status ="
                + " 'planned' FOR UPDATE").param(releaseId));
        if (r == null) {
            return;
        }
        String id = (String) s.get("id");
        BigDecimal amount = SqlRows.dec(r, "amount");
        int index = ((Number) r.get("periodIndex")).intValue();
        LocalDate end = (LocalDate) r.get("periodEnd");
        String value = plain(amount);
        String source = (String) s.get("sourceAccountId");
        String target = (String) s.get("targetAccountId");
        String partner = (String) s.get("partnerId");
        boolean revenue = "revenue".equals(s.get("kind"));
        String label = s.get("name") + " " + index + "/" + s.get("months");
        List<Line> lines = revenue
                ? List.of(new Line(source, value, null, partner, "Deferred revenue released", null, null, null),
                        new Line(target, null, value, partner, "Revenue recognised", null, null, null))
                : List.of(new Line(target, value, null, partner, "Prepaid expense recognised", null, null, null),
                        new Line(source, null, value, partner, "Prepaid released", null, null, null));
        Map<String, Object> entry = posting.post(new PostingRequest((String) s.get("journalCode"),
                end.atStartOfDay(ZoneOffset.UTC).toInstant(), "Deferral release " + label, null, null,
                "deferral_release", id, null, "deferral:" + id + ":" + index, (String) s.get("branchId"),
                (String) s.get("costCenterId"), null, lines));
        jdbc.sql("UPDATE \"DeferralRelease\" SET status = 'posted', \"journalEntryId\" = ?, \"postedAt\" = now() WHERE"
                + " id = ?").params(entry.get("id"), releaseId).update();
        jdbc.sql("UPDATE \"DeferralSchedule\" SET \"releasedAmount\" = \"releasedAmount\" + ?, \"updatedAt\" = now()"
                + " WHERE id = ?").params(amount, id).update();
    }

    private void completeIfDone(String id) {
        jdbc.sql("UPDATE \"DeferralSchedule\" SET status = 'completed', \"updatedAt\" = now() WHERE id = ? AND status ="
                + " 'active' AND NOT EXISTS (SELECT 1 FROM \"DeferralRelease\" WHERE \"scheduleId\" = ? AND status ="
                + " 'planned')").params(id, id).update();
    }

    private String insert(String name, String kind, String source, String target, BigDecimal total, LocalDate start,
            int months, String method, String journalCode, String journalLineId, String sourceEntryId, String partnerId,
            String branchId, String costCenterId, String notes) {
        String org = TenantContext.organizationId();
        String m = method == null ? "monthly" : method;
        String journal = journalCode == null || journalCode.isBlank() ? "GEN" : journalCode;
        if (jdbc.sql("SELECT count(*) FROM \"Journal\" WHERE \"organizationId\" = ? AND code = ?").params(org, journal)
                .query(Long.class).single() == 0) {
            throw ApiException.badRequest("Journal '" + journal + "' not found");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"DeferralSchedule\" (id, \"organizationId\", name, kind, \"sourceAccountId\","
                        + " \"targetAccountId\", \"totalAmount\", \"startDate\", months, method, \"journalCode\","
                        + " \"journalLineId\", \"sourceEntryId\", \"partnerId\", \"branchId\", \"costCenterId\", notes,"
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org, name, kind, source, target, total, Date.valueOf(start), months, m, journal,
                        journalLineId, sourceEntryId, partnerId, branchId, costCenterId, notes, TenantContext.userId())
                .update();
        for (GlSchedules.Slice slice : GlSchedules.deferralPlan(total, start, months, m)) {
            jdbc.sql("INSERT INTO \"DeferralRelease\" (id, \"organizationId\", \"scheduleId\", \"periodIndex\","
                            + " \"periodStart\", \"periodEnd\", amount) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org, id, slice.index(), Date.valueOf(slice.periodStart()),
                            Date.valueOf(slice.periodEnd()), slice.amount())
                    .update();
        }
        return id;
    }

    /** Default deferral account: {@code unearned_revenue} for revenue, {@code prepaid_expense} for expenses. */
    private String defaultSource(String kind) {
        return "revenue".equals(kind)
                ? accounts.ensureByCode("DEFERRED-REVENUE", "Deferred Revenue", "deferred_revenue", "unearned_revenue")
                : accounts.ensureByCode("PREPAID-EXPENSE", "Prepaid Expenses", "prepaid_expense", "prepaid_expense");
    }

    private void assertAccounts(String source, String target) {
        AccountMeta s = accounts.metaById(source);
        AccountMeta t = accounts.metaById(target);
        if (AccountClassification.isProfitAndLoss(s.classification())) {
            throw ApiException.badRequest("The deferral account " + s.code() + " must be a balance-sheet account");
        }
        if (!AccountClassification.isProfitAndLoss(t.classification())) {
            throw ApiException.badRequest("The target account " + t.code() + " must be a revenue or expense account");
        }
    }

    private Map<String, Object> require(String id, boolean lock) {
        Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT * FROM \"DeferralSchedule\" WHERE id = ? AND"
                + " \"organizationId\" = ?" + (lock ? " FOR UPDATE" : "")).params(id, TenantContext.organizationId()));
        if (s == null) {
            throw ApiException.notFound("Deferral schedule not found");
        }
        return s;
    }

    private static Map<String, Object> render(Map<String, Object> s) {
        Map<String, Object> out = new LinkedHashMap<>(s);
        out.remove("organizationId");
        out.put("startDate", String.valueOf(s.get("startDate")));
        BigDecimal total = SqlRows.dec(s, "totalAmount");
        BigDecimal released = SqlRows.dec(s, "releasedAmount");
        out.put("totalAmount", plain(total));
        out.put("releasedAmount", plain(released));
        out.put("remainingAmount", plain(total.subtract(released)));
        return out;
    }
}
