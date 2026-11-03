package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fiscal years and the year-end close (roadmap 1.7, ADR-S023, extension). A year groups its periods. Profit and loss
 * reaches Retained Earnings through each period's closing journal ({@link PeriodCloseService}), so closing the year
 * posts nothing itself: it closes the periods that had no P&amp;L activity, checks that every other period is closed,
 * that P&amp;L nets to zero over the year and the trial balance balances, requires the manual sign-offs, snapshots the
 * balance-sheet balances as next year's opening position, optionally locks the periods, and opens the next year.
 */
@Service
public class FiscalYearService {

    static final Set<String> PROFIT_AND_LOSS = Set.of("revenue", "other_income", "contra_revenue", "operating_expense",
            "other_expense", "cogs");

    /** Manual year-end steps, in the order an accountant works through them. */
    private static final Map<String, String> SIGN_OFFS = new LinkedHashMap<>();

    static {
        SIGN_OFFS.put("stock_count", "Year-end stock count done and posted");
        SIGN_OFFS.put("fixed_assets", "Depreciation run and asset register reviewed");
        SIGN_OFFS.put("accountant_review", "Year-end accounts reviewed by the accountant");
        SIGN_OFFS.put("tax_computation", "Annual tax computation prepared");
    }

    private record Year(String id, String name, LocalDate start, LocalDate end, String periodicity, String status,
            boolean lockedPeriods) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final AccountResolverService accounts;

    public FiscalYearService(JdbcClient jdbc, AuditService audit, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.accounts = accounts;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static Timestamp ts(LocalDate d) {
        return Timestamp.valueOf(d.atStartOfDay());
    }

    /** The last instant of {@code d}: a period or year ends at the end of its last day. */
    private static Timestamp endTs(LocalDate d) {
        return Timestamp.valueOf(d.atTime(23, 59, 59, 999_000_000));
    }

    private Year year(String id, boolean forUpdate) {
        return jdbc.sql("SELECT id, name, \"startDate\", \"endDate\", periodicity, status, \"lockedPeriods\""
                        + " FROM \"FiscalYear\" WHERE id = ? AND \"organizationId\" = ?" + (forUpdate ? " FOR UPDATE" : ""))
                .params(id, org())
                .query((rs, n) -> new Year(rs.getString("id"), rs.getString("name"),
                        rs.getDate("startDate").toLocalDate(), rs.getDate("endDate").toLocalDate(),
                        rs.getString("periodicity"), rs.getString("status"), rs.getBoolean("lockedPeriods")))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Fiscal year not found"));
    }

    private List<Map<String, Object>> periods(String yearId) {
        return SqlRows.list(jdbc.sql("SELECT id, name, \"startDate\", \"endDate\", status::text AS status, \"closedAt\","
                + " \"lockedAt\" FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"fiscalYearId\" = ?"
                + " AND \"deletedAt\" IS NULL ORDER BY \"startDate\"").params(org(), yearId));
    }

    private Map<String, Object> view(Year y) {
        List<Map<String, Object>> periods = periods(y.id());
        Map<String, Object> out = jdbc.sql("SELECT * FROM \"FiscalYear\" WHERE id = ?").param(y.id()).query()
                .singleRow();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", y.id());
        view.put("name", y.name());
        view.put("startDate", y.start().toString());
        view.put("endDate", y.end().toString());
        view.put("periodicity", y.periodicity());
        view.put("status", y.status());
        view.put("lockedPeriods", y.lockedPeriods());
        view.put("closedAt", out.get("closedAt") == null ? null : ((Timestamp) out.get("closedAt")).toInstant().toString());
        view.put("closedBy", out.get("closedBy"));
        view.put("netIncome", out.get("netIncome") == null ? null : PostingService.plain((BigDecimal) out.get("netIncome")));
        view.put("periods", periods);
        view.put("openPeriods", periods.stream().filter(p -> "open".equals(p.get("status"))).count());
        return view;
    }

    /** The organization's fiscal years, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<String> ids = jdbc.sql("SELECT id FROM \"FiscalYear\" WHERE \"organizationId\" = ? ORDER BY \"startDate\""
                + " DESC").param(org()).query(String.class).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            out.add(view(year(id, false)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return view(year(id, false));
    }

    /**
     * Create a year from {@code start} (end defaults to one year later, less a day). Periods already inside the range
     * join the year; with {@code createPeriods} the gaps are filled with monthly, quarterly or one yearly period.
     */
    @Transactional
    public Map<String, Object> create(String name, LocalDate start, LocalDate end, String periodicity,
            boolean createPeriods) {
        LocalDate last = end != null ? end : start.plusYears(1).minusDays(1);
        if (!last.isAfter(start)) {
            throw ApiException.badRequest("The end date must be after the start date");
        }
        if (last.isAfter(start.plusMonths(18))) {
            throw ApiException.badRequest("A fiscal year cannot be longer than 18 months");
        }
        String every = periodicity == null ? "monthly" : periodicity;
        String label = name != null && !name.isBlank() ? name.trim()
                : start.getYear() == last.getYear() ? "FY" + start.getYear() : "FY" + start.getYear() + "/" + last.getYear();
        String clash = jdbc.sql("SELECT name FROM \"FiscalYear\" WHERE \"organizationId\" = ? AND \"startDate\" <= ?"
                        + " AND \"endDate\" >= ? LIMIT 1")
                .params(org(), Date.valueOf(last), Date.valueOf(start)).query(String.class).optional().orElse(null);
        if (clash != null) {
            throw ApiException.badRequest("The dates overlap fiscal year " + clash);
        }
        if (count("SELECT count(*) FROM \"FiscalYear\" WHERE \"organizationId\" = ? AND name = ?", org(), label) > 0) {
            throw ApiException.badRequest("A fiscal year named " + label + " already exists");
        }
        String straddling = jdbc.sql("SELECT name FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"deletedAt\""
                        + " IS NULL AND \"startDate\" <= ? AND \"endDate\" >= ? AND (\"startDate\" < ? OR \"endDate\" > ?)"
                        + " LIMIT 1")
                .params(org(), endTs(last), ts(start), ts(start), endTs(last)).query(String.class).optional().orElse(null);
        if (straddling != null) {
            throw ApiException.badRequest("Period " + straddling + " crosses the start or end of the year; change its"
                    + " dates first");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalYear\" (id, \"organizationId\", name, \"startDate\", \"endDate\", periodicity,"
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), label, Date.valueOf(start), Date.valueOf(last), every, TenantContext.userId())
                .update();
        jdbc.sql("UPDATE \"FiscalPeriod\" SET \"fiscalYearId\" = ?, \"updatedAt\" = now() WHERE \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL AND \"startDate\" >= ? AND \"endDate\" <= ?")
                .params(id, org(), ts(start), endTs(last)).update();
        if (createPeriods) {
            for (LocalDate[] slot : slots(start, last, every)) {
                boolean taken = count("SELECT count(*) FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL AND \"startDate\" <= ? AND \"endDate\" >= ?", org(), endTs(slot[1]),
                        ts(slot[0])) > 0;
                if (!taken) {
                    jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\","
                                    + " status, \"fiscalYearId\", \"createdBy\", \"updatedAt\") VALUES (?, ?, ?, ?, ?,"
                                    + " 'open', ?, ?, now())")
                            .params(UUID.randomUUID().toString(), org(), periodName(slot[0], every, label), ts(slot[0]),
                                    endTs(slot[1]), id, TenantContext.userId())
                            .update();
                }
            }
        }
        audit.recordInTx(AuditInput.of("FiscalYear", id, "create", null, Obj.of("name", label, "startDate",
                start.toString(), "endDate", last.toString(), "periodicity", every)));
        return view(year(id, false));
    }

    static List<LocalDate[]> slots(LocalDate start, LocalDate end, String periodicity) {
        List<LocalDate[]> out = new ArrayList<>();
        int months = switch (periodicity) {
            case "quarterly" -> 3;
            case "yearly" -> 0;
            default -> 1;
        };
        if (months == 0) {
            out.add(new LocalDate[] {start, end});
            return out;
        }
        LocalDate from = start;
        while (!from.isAfter(end)) {
            LocalDate to = from.plusMonths(months).minusDays(1);
            if (to.isAfter(end)) {
                to = end;
            }
            out.add(new LocalDate[] {from, to});
            from = to.plusDays(1);
        }
        return out;
    }

    static String periodName(LocalDate from, String periodicity, String yearName) {
        return switch (periodicity) {
            case "quarterly" -> "Q" + ((from.getMonthValue() - 1) / 3 + 1) + " " + from.getYear();
            case "yearly" -> yearName;
            default -> from.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + from.getYear();
        };
    }

    /** Net of each P&amp;L account over the year, closing entries included (zero once every period is closed). */
    private Map<String, BigDecimal> profitAndLossNet(Year y, boolean withClosing) {
        Map<String, BigDecimal> net = new HashMap<>();
        jdbc.sql("SELECT l.\"accountId\", COALESCE(SUM(l.\"baseCredit\"), 0) - COALESCE(SUM(l.\"baseDebit\"), 0) FROM \"JournalLine\" l"
                        + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ?"
                        + " AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ? AND e.\"postingDate\" <= ?"
                        + (withClosing ? "" : " AND COALESCE(e.\"postingKey\", '') NOT LIKE 'period_close:%'")
                        + " GROUP BY l.\"accountId\"")
                .params(org(), ts(y.start()), endTs(y.end()))
                .query((rs, n) -> net.put(rs.getString(1), rs.getBigDecimal(2)))
                .list();
        Map<String, AccountMeta> meta = accounts.meta(net.keySet());
        net.keySet().removeIf(id -> meta.get(id) == null || !PROFIT_AND_LOSS.contains(meta.get(id).reportSection()));
        return net;
    }

    /** Open periods of the year that carry posted P&amp;L activity (they need a period close of their own). */
    private long periodsWithActivity(Year y) {
        return count("SELECT count(*) FROM \"FiscalPeriod\" p WHERE p.\"organizationId\" = ? AND p.\"fiscalYearId\" = ?"
                + " AND p.\"deletedAt\" IS NULL AND p.status::text = 'open' AND EXISTS (SELECT 1 FROM \"JournalEntry\" e"
                + " WHERE e.\"organizationId\" = p.\"organizationId\" AND e.status::text IN ('posted', 'reversed')"
                + " AND e.\"postingDate\" >= p.\"startDate\" AND e.\"postingDate\" <= p.\"endDate\")", org(), y.id());
    }

    private static Map<String, Object> check(String key, String label, boolean blocking, long found) {
        return Obj.of("key", key, "label", label, "blocking", blocking, "count", found, "ok", found == 0);
    }

    /** What stands between the year and its close, computed now. */
    @Transactional(readOnly = true)
    public Map<String, Object> checklist(String id) {
        return checklist(year(id, false));
    }

    private Map<String, Object> checklist(Year y) {
        String org = org();
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("earlier_years_closed", "Earlier fiscal years closed", true,
                count("SELECT count(*) FROM \"FiscalYear\" WHERE \"organizationId\" = ? AND \"startDate\" < ? AND status"
                        + " = 'open'", org, Date.valueOf(y.start()))));
        checks.add(check("periods_closed", "Every period with postings closed", true, periodsWithActivity(y)));
        checks.add(check("profit_and_loss_closed", "Profit and loss closed to retained earnings", true,
                profitAndLossNet(y, true).values().stream().filter(v -> v.signum() != 0).count()));
        BigDecimal[] totals = jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\"), 0), COALESCE(SUM(l.\"baseCredit\"), 0) FROM \"JournalLine\""
                        + " l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                        + " e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ? AND e.\"postingDate\" <= ?")
                .params(org, ts(y.start()), endTs(y.end()))
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)})
                .single();
        checks.add(check("trial_balance", "Trial balance balances", true, totals[0].compareTo(totals[1]) == 0 ? 0 : 1));
        checks.add(check("draft_journals", "No draft journal entries in the year", true,
                count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND status::text = 'draft'"
                        + " AND \"postingDate\" >= ? AND \"postingDate\" <= ?", org, ts(y.start()), endTs(y.end()))));
        checks.add(check("retained_earnings", "Retained earnings account mapped", true,
                accounts.byMappingOptional("retained_earnings") == null ? 1 : 0));
        checks.add(check("unmatched_bank_lines", "No unmatched bank statement lines", false,
                count("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND status = 'unmatched'"
                        + " AND \"postedAt\" >= ? AND \"postedAt\" <= ?", org, ts(y.start()), endTs(y.end()))));

        Map<String, Map<String, Object>> signed = new HashMap<>();
        for (Map<String, Object> s : SqlRows.list(jdbc.sql("SELECT s.key, s.note, s.\"signedBy\", s.\"signedAt\","
                + " u.\"firstName\" AS \"signedByName\" FROM \"FiscalYearSignOff\" s LEFT JOIN \"User\" u ON u.id ="
                + " s.\"signedBy\" WHERE s.\"organizationId\" = ? AND s.\"fiscalYearId\" = ?").params(org, y.id()))) {
            signed.put((String) s.get("key"), s);
        }
        List<Map<String, Object>> signOffs = new ArrayList<>();
        SIGN_OFFS.forEach((key, label) -> {
            Map<String, Object> s = signed.get(key);
            signOffs.add(Obj.of("key", key, "label", label, "signed", s != null, "note", s == null ? null : s.get("note"),
                    "signedBy", s == null ? null : s.get("signedBy"), "signedByName",
                    s == null ? null : s.get("signedByName"), "signedAt", s == null ? null : s.get("signedAt")));
        });
        boolean gates = checks.stream().allMatch(c -> !Boolean.TRUE.equals(c.get("blocking"))
                || Boolean.TRUE.equals(c.get("ok")));
        boolean allSigned = signOffs.stream().allMatch(s -> Boolean.TRUE.equals(s.get("signed")));
        return Obj.of("fiscalYearId", y.id(), "name", y.name(), "status", y.status(), "startDate", y.start().toString(),
                "endDate", y.end().toString(), "checks", checks, "signOffs", signOffs, "readyToClose",
                gates && allSigned && "open".equals(y.status()), "complete",
                allSigned && checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok"))));
    }

    /** Sign a manual step, or take the signature back, while the year is open. */
    @Transactional
    public Map<String, Object> signOff(String id, String key, boolean signed, String note) {
        Year y = year(id, true);
        if (!SIGN_OFFS.containsKey(key)) {
            throw ApiException.badRequest("Unknown checklist step: " + key);
        }
        if (!"open".equals(y.status())) {
            throw ApiException.badRequest("Only an open fiscal year's checklist can be changed");
        }
        if (signed) {
            jdbc.sql("INSERT INTO \"FiscalYearSignOff\" (id, \"organizationId\", \"fiscalYearId\", key, note, \"signedBy\")"
                            + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"fiscalYearId\", key) DO UPDATE"
                            + " SET note = EXCLUDED.note, \"signedBy\" = EXCLUDED.\"signedBy\", \"signedAt\" ="
                            + " CURRENT_TIMESTAMP")
                    .params(UUID.randomUUID().toString(), org(), y.id(), key, note, TenantContext.userId()).update();
        } else {
            jdbc.sql("DELETE FROM \"FiscalYearSignOff\" WHERE \"organizationId\" = ? AND \"fiscalYearId\" = ? AND key = ?")
                    .params(org(), y.id(), key).update();
        }
        audit.recordInTx(AuditInput.of("FiscalYear", y.id(), "update", null, Obj.of("closeChecklist", key, "signed",
                signed, "note", note)));
        return checklist(y);
    }

    /**
     * Close the year. Periods without postings are closed here; every check that stops the close must pass and every
     * sign-off must be signed. Takes the balance-sheet snapshot, locks the periods when {@code lockPeriods}, and
     * creates the next year (same length and periodicity) unless one exists.
     */
    @Transactional
    public Map<String, Object> close(String id, boolean lockPeriods) {
        Year y = year(id, true);
        if (!"open".equals(y.status())) {
            throw ApiException.badRequest("Fiscal year " + y.name() + " is already closed");
        }
        int emptyClosed = jdbc.sql("UPDATE \"FiscalPeriod\" p SET status = 'closed', \"closedAt\" = now(), \"closedBy\" = ?,"
                        + " \"updatedAt\" = now() WHERE p.\"organizationId\" = ? AND p.\"fiscalYearId\" = ? AND p.\"deletedAt\""
                        + " IS NULL AND p.status::text = 'open' AND NOT EXISTS (SELECT 1 FROM \"JournalEntry\" e WHERE"
                        + " e.\"organizationId\" = p.\"organizationId\" AND e.status::text IN ('posted', 'reversed') AND"
                        + " e.\"postingDate\" >= p.\"startDate\" AND e.\"postingDate\" <= p.\"endDate\")")
                .params(TenantContext.userId(), org(), y.id()).update();
        Map<String, Object> list = checklist(y);
        List<String> failed = new ArrayList<>();
        for (Object c : (List<?>) list.get("checks")) {
            Map<?, ?> m = (Map<?, ?>) c;
            if (Boolean.TRUE.equals(m.get("blocking")) && !Boolean.TRUE.equals(m.get("ok"))) {
                failed.add((String) m.get("label"));
            }
        }
        for (Object s : (List<?>) list.get("signOffs")) {
            Map<?, ?> m = (Map<?, ?>) s;
            if (!Boolean.TRUE.equals(m.get("signed"))) {
                failed.add("Sign-off: " + m.get("label"));
            }
        }
        if (!failed.isEmpty()) {
            throw ApiException.badRequest("Cannot close " + y.name() + ": " + String.join("; ", failed));
        }

        BigDecimal netIncome = profitAndLossNet(y, false).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        snapshot(y);
        int locked = 0;
        if (lockPeriods) {
            locked = jdbc.sql("UPDATE \"FiscalPeriod\" SET status = 'locked', \"lockedAt\" = now(), \"updatedAt\" = now()"
                            + " WHERE \"organizationId\" = ? AND \"fiscalYearId\" = ? AND status::text = 'closed'")
                    .params(org(), y.id()).update();
        }
        jdbc.sql("UPDATE \"FiscalYear\" SET status = 'closed', \"closedAt\" = now(), \"closedBy\" = ?, \"netIncome\" = ?,"
                        + " \"lockedPeriods\" = ?, \"updatedAt\" = now() WHERE id = ?")
                .params(TenantContext.userId(), netIncome, lockPeriods, y.id()).update();
        String next = null;
        LocalDate nextStart = y.end().plusDays(1);
        if (count("SELECT count(*) FROM \"FiscalYear\" WHERE \"organizationId\" = ? AND \"startDate\" <= ? AND"
                + " \"endDate\" >= ?", org(), Date.valueOf(nextStart), Date.valueOf(nextStart)) == 0) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(y.start(), y.end());
            next = (String) create(null, nextStart, nextStart.plusDays(days), y.periodicity(), true).get("id");
        }
        audit.recordInTx(AuditInput.of("FiscalYear", y.id(), "update", Map.of("status", "open"), Obj.of("status",
                "closed", "netIncome", PostingService.plain(netIncome), "lockedPeriods", lockPeriods,
                "emptyPeriodsClosed", emptyClosed, "nextFiscalYearId", next)));
        Map<String, Object> out = view(year(y.id(), false));
        out.put("emptyPeriodsClosed", emptyClosed);
        out.put("periodsLocked", locked);
        out.put("nextFiscalYearId", next);
        return out;
    }

    private void snapshot(Year y) {
        jdbc.sql("DELETE FROM \"FiscalYearBalance\" WHERE \"organizationId\" = ? AND \"fiscalYearId\" = ?")
                .params(org(), y.id()).update();
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        jdbc.sql("SELECT l.\"accountId\", COALESCE(SUM(l.\"baseDebit\"), 0), COALESCE(SUM(l.\"baseCredit\"), 0) FROM \"JournalLine\" l"
                        + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                        + " e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" <= ? GROUP BY l.\"accountId\"")
                .params(org(), endTs(y.end()))
                .query((rs, n) -> sums.put(rs.getString(1), new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3)}))
                .list();
        Map<String, AccountMeta> meta = accounts.meta(sums.keySet());
        for (Map.Entry<String, BigDecimal[]> e : sums.entrySet()) {
            AccountMeta m = meta.get(e.getKey());
            if (m == null || PROFIT_AND_LOSS.contains(m.reportSection())) {
                continue;
            }
            BigDecimal debit = e.getValue()[0];
            BigDecimal credit = e.getValue()[1];
            BigDecimal balance = "credit".equals(m.normalBalance()) ? credit.subtract(debit) : debit.subtract(credit);
            jdbc.sql("INSERT INTO \"FiscalYearBalance\" (id, \"organizationId\", \"fiscalYearId\", \"accountId\", debit,"
                            + " credit, balance) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), y.id(), e.getKey(), debit, credit, balance).update();
        }
    }

    /** Balance-sheet balances taken when the year closed (the next year's opening position). */
    @Transactional(readOnly = true)
    public Map<String, Object> closingBalances(String id) {
        Year y = year(id, false);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT b.\"accountId\", a.code, a.name, b.debit, b.credit,"
                + " b.balance FROM \"FiscalYearBalance\" b JOIN \"Account\" a ON a.id = b.\"accountId\" WHERE"
                + " b.\"organizationId\" = ? AND b.\"fiscalYearId\" = ? ORDER BY a.code").params(org(), y.id()));
        return Obj.of("fiscalYearId", y.id(), "name", y.name(), "asOf", y.end().toString(), "status", y.status(),
                "accounts", rows);
    }

    /**
     * Reopen a closed year whose periods were not locked, while the next year has no closed period. The snapshot is
     * dropped; the periods stay closed (reopen each with the period reopen if needed).
     */
    @Transactional
    public Map<String, Object> reopen(String id) {
        Year y = year(id, true);
        if (!"closed".equals(y.status())) {
            throw ApiException.badRequest("Fiscal year " + y.name() + " is open");
        }
        if (y.lockedPeriods()) {
            throw ApiException.badRequest("Fiscal year " + y.name() + " locked its periods when it closed; it cannot be"
                    + " reopened");
        }
        long laterClosed = count("SELECT count(*) FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"deletedAt\""
                + " IS NULL AND \"startDate\" > ? AND status::text <> 'open'", org(), endTs(y.end()));
        if (laterClosed > 0) {
            throw ApiException.badRequest("Reopen the later periods first: " + laterClosed + " period(s) after "
                    + y.name() + " are closed");
        }
        jdbc.sql("DELETE FROM \"FiscalYearBalance\" WHERE \"organizationId\" = ? AND \"fiscalYearId\" = ?")
                .params(org(), y.id()).update();
        jdbc.sql("UPDATE \"FiscalYear\" SET status = 'open', \"closedAt\" = NULL, \"closedBy\" = NULL, \"netIncome\" ="
                + " NULL, \"updatedAt\" = now() WHERE id = ?").param(y.id()).update();
        audit.recordInTx(AuditInput.of("FiscalYear", y.id(), "update", Map.of("status", "closed"),
                Map.of("status", "open")));
        return view(year(y.id(), false));
    }
}
