package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.lossprevention.application.LpCommands.ReportQuery;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dashboard and the reports. Every figure is a count of cases or a stated quantity; a confirmed variance is a
 * difference between records and a count, not a finding of loss or of anyone's conduct, and the report says so.
 */
@Service
public class LpReportService {

    public static final String DISCLAIMER = "A confirmed variance means the recorded and the physical quantities"
            + " differ. It is not a finding of financial loss or of misconduct. Exposure figures are estimates; each"
            + " is shown with the method used to calculate it.";

    private final JdbcClient jdbc;
    private final LpScope scope;

    public LpReportService(JdbcClient jdbc, LpScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    /** A period of business dates, both ends included. */
    record Period(LocalDate from, LocalDate to) {}

    /** The period of a query: the last 30 days unless from and to say otherwise. */
    static Period period(ReportQuery q) {
        LocalDate today = LocalDate.now(BusinessZone.get());
        LocalDate to = LpScope.has(q.to()) ? day(q.to(), "to") : today;
        LocalDate from = LpScope.has(q.from()) ? day(q.from(), "from") : to.minusDays(29);
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        return new Period(from, to);
    }

    private static LocalDate day(String v, String field) {
        LocalDate d = v.length() == 10 ? LpEventValidator.date(v) : null;
        if (d == null) {
            throw ApiException.badRequest(field + " must be an ISO date (YYYY-MM-DD)");
        }
        return d;
    }

    /** {@code WHERE} over {@code LpCase c} for a period of business dates and an optional branch. */
    private String where(Period p, String branchId, List<Object> params) {
        scope.assertBranch(branchId);
        StringBuilder w = new StringBuilder(scope.caseWhere("c", params))
                .append(" AND c.\"businessDate\" >= ? AND c.\"businessDate\" <= ?");
        params.add(java.sql.Date.valueOf(p.from()));
        params.add(java.sql.Date.valueOf(p.to()));
        if (LpScope.has(branchId)) {
            w.append(" AND c.\"branchId\" = ?");
            params.add(branchId);
        }
        return w.toString();
    }

    private List<Map<String, Object>> group(String select, String where, List<Object> params, String tail) {
        return SqlRows.list(jdbc.sql("SELECT " + select + " FROM \"LpCase\" c" + where + " " + tail).params(params));
    }

    /** What is open now, and how many cases each of the last 30 days produced. */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        List<Object> params = new ArrayList<>();
        String open = scope.caseWhere("c", params) + " AND c.state <> 'closed'";
        Map<String, Object> totals = SqlRows.one(jdbc.sql("SELECT count(*) AS open, count(*) FILTER (WHERE c.state ="
                + " 'open') AS \"notStarted\", count(*) FILTER (WHERE c.state = 'reviewing') AS reviewing, count(*)"
                + " FILTER (WHERE c.state = 'escalated') AS escalated, count(*) FILTER (WHERE c.severity = 'critical')"
                + " AS critical, coalesce(sum(c.\"exposureAmount\"), 0) AS exposure, min(c.\"openedAt\") AS"
                + " \"oldestOpenedAt\" FROM \"LpCase\" c" + open).params(params));
        List<Object> trendParams = new ArrayList<>();
        String trendWhere = scope.caseWhere("c", trendParams) + " AND c.\"businessDate\" >= ?";
        trendParams.add(java.sql.Date.valueOf(LocalDate.now(BusinessZone.get()).minusDays(29)));
        return Obj.of("totals", totals,
                "byEngine", group("c.engine, count(*) AS cases", open, params, "GROUP BY c.engine ORDER BY cases DESC"),
                "bySeverity", group("c.severity, count(*) AS cases", open, params,
                        "GROUP BY c.severity ORDER BY cases DESC"),
                "byRule", group("c.\"ruleCode\", min(c.title) AS title, count(*) AS cases", open, params,
                        "GROUP BY c.\"ruleCode\" ORDER BY cases DESC"),
                "byBranch", branches(open, params),
                "trend", group("c.\"businessDate\" AS day, count(*) AS cases, count(*) FILTER (WHERE c.outcome ="
                        + " 'confirmed_variance') AS confirmed", trendWhere, trendParams,
                        "GROUP BY c.\"businessDate\" ORDER BY c.\"businessDate\""),
                "disclaimer", DISCLAIMER);
    }

    private List<Map<String, Object>> branches(String where, List<Object> params) {
        return SqlRows.list(jdbc.sql("SELECT c.\"branchId\", b.name AS \"branchName\", count(*) AS cases, count(*)"
                + " FILTER (WHERE c.outcome = 'confirmed_variance') AS confirmed FROM \"LpCase\" c LEFT JOIN \"Branch\""
                + " b ON b.id = c.\"branchId\"" + where + " GROUP BY c.\"branchId\", b.name ORDER BY cases DESC")
                .params(params));
    }

    private Map<String, Object> summary(Period p, String branchId) {
        List<Object> params = new ArrayList<>();
        String w = where(p, branchId, params);
        return SqlRows.one(jdbc.sql("SELECT count(*) AS generated, count(*) FILTER (WHERE c.\"reviewStartedAt\" IS NOT"
                + " NULL) AS reviewed, count(*) FILTER (WHERE c.state = 'closed') AS closed, count(*) FILTER (WHERE"
                + " c.state = 'closed' AND c.\"closedBy\" IS NULL) AS \"closedAutomatically\", count(*) FILTER (WHERE"
                + " c.outcome = 'explained') AS explained, count(*) FILTER (WHERE c.outcome = 'confirmed_variance') AS"
                + " \"confirmedVariance\", count(*) FILTER (WHERE c.outcome = 'false_alarm') AS \"falseAlarm\","
                + " count(*) FILTER (WHERE c.outcome = 'inconclusive') AS inconclusive, count(*) FILTER (WHERE c.state"
                + " = 'escalated') AS escalated, percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM"
                + " (c.\"reviewStartedAt\" - c.\"openedAt\")) / 3600.0) FILTER (WHERE c.\"reviewStartedAt\" IS NOT NULL)"
                + " AS \"medianHoursToReview\", percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM"
                + " (c.\"closedAt\" - c.\"openedAt\")) / 3600.0) FILTER (WHERE c.\"closedAt\" IS NOT NULL AND"
                + " c.\"closedBy\" IS NOT NULL) AS \"medianHoursToClose\" FROM \"LpCase\" c" + w).params(params));
    }

    /**
     * The owner's report for a period (default: the last 30 days): cases generated, reviewed and closed, outcomes,
     * where they came from, how fast they were handled, the previous period for comparison, and exposure per rule.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> performance(ReportQuery q) {
        Period p = period(q);
        long days = p.to().toEpochDay() - p.from().toEpochDay() + 1;
        Period before = new Period(p.from().minusDays(days), p.from().minusDays(1));
        List<Object> params = new ArrayList<>();
        String w = where(p, q.branchId(), params);
        return Obj.of("from", p.from(), "to", p.to(), "branchId", LpScope.has(q.branchId()) ? q.branchId() : null,
                "summary", summary(p, q.branchId()),
                "previous", Obj.of("from", before.from(), "to", before.to(), "summary",
                        summary(before, q.branchId())),
                "byEngine", group("c.engine, count(*) AS cases, count(*) FILTER (WHERE c.outcome ="
                        + " 'confirmed_variance') AS confirmed, count(*) FILTER (WHERE c.outcome = 'false_alarm') AS"
                        + " \"falseAlarm\"", w, params, "GROUP BY c.engine ORDER BY cases DESC"),
                "byRule", group("c.\"ruleCode\", min(c.title) AS title, count(*) AS cases, count(*) FILTER (WHERE"
                        + " c.outcome = 'confirmed_variance') AS confirmed, count(*) FILTER (WHERE c.outcome ="
                        + " 'false_alarm') AS \"falseAlarm\", coalesce(sum(c.\"exposureAmount\") FILTER (WHERE"
                        + " c.outcome IS DISTINCT FROM 'false_alarm' AND c.outcome IS DISTINCT FROM 'explained'), 0)"
                        + " AS exposure, min(c.\"exposureMethod\") AS \"exposureMethod\"", w, params,
                        "GROUP BY c.\"ruleCode\" ORDER BY cases DESC"),
                "byBranch", branches(w, params),
                "byCause", group("c.\"causeCode\", count(*) AS cases", w + " AND c.\"causeCode\" IS NOT NULL", params,
                        "GROUP BY c.\"causeCode\" ORDER BY cases DESC"),
                "disclaimer", DISCLAIMER);
    }

    /** Cases per person in a period. The caller's own cases are never part of it. */
    @Transactional(readOnly = true)
    public Map<String, Object> byCashier(ReportQuery q) {
        Period p = period(q);
        List<Object> params = new ArrayList<>();
        String w = where(p, q.branchId(), params) + " AND c.\"subjectType\" = 'actor' AND c.\"subjectId\" IS NOT NULL";
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT c.\"subjectId\", coalesce(nullif(trim("
                + "coalesce(u.\"firstName\", '') || ' ' || coalesce(u.\"lastName\", '')), ''), u.email) AS"
                + " \"subjectName\", count(*) AS cases, count(*) FILTER (WHERE c.state <> 'closed') AS open, count(*)"
                + " FILTER (WHERE c.outcome = 'confirmed_variance') AS confirmed, count(*) FILTER (WHERE c.outcome ="
                + " 'explained') AS explained, count(*) FILTER (WHERE c.outcome = 'false_alarm') AS \"falseAlarm\","
                + " array_agg(DISTINCT c.\"ruleCode\") AS rules, coalesce(sum(c.\"exposureAmount\") FILTER (WHERE"
                + " c.outcome IS DISTINCT FROM 'false_alarm' AND c.outcome IS DISTINCT FROM 'explained'), 0) AS"
                + " exposure FROM \"LpCase\" c LEFT JOIN \"User\" u ON u.id = c.\"subjectUserId\"" + w
                + " GROUP BY c.\"subjectId\", u.\"firstName\", u.\"lastName\", u.email ORDER BY cases DESC LIMIT 500")
                .params(params));
        return Obj.of("from", p.from(), "to", p.to(), "rows", rows, "disclaimer", DISCLAIMER);
    }

    /** Bottle-count variance per product in a period, from the count lines in the ledger. */
    @Transactional(readOnly = true)
    public Map<String, Object> barVariance(ReportQuery q) {
        Period p = period(q);
        scope.assertBranch(q.branchId());
        List<Object> params = new ArrayList<>();
        // The ledger keeps a missing branch as NULL; the scope helper compares with '' for the other tables.
        StringBuilder w = new StringBuilder(scope.where("e", params).replace("e.\"branchId\" = ANY",
                "coalesce(e.\"branchId\", '') = ANY"))
                .append(" AND e.type = ? AND e.\"businessDate\" >= ? AND e.\"businessDate\" <= ?");
        params.add(LpEventTypes.STOCK_COUNT_LINE);
        params.add(java.sql.Date.valueOf(p.from()));
        params.add(java.sql.Date.valueOf(p.to()));
        if (LpScope.has(q.branchId())) {
            w.append(" AND e.\"branchId\" = ?");
            params.add(q.branchId());
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT e.payload->>'productId' AS \"productId\","
                + " min(e.payload->>'productName') AS \"productName\", count(*) AS lines,"
                + " coalesce(sum((e.payload->>'varianceMl')::numeric), 0) AS \"varianceMl\","
                + " coalesce(sum((e.payload->>'varianceMl')::numeric) FILTER (WHERE (e.payload->>'varianceMl')::numeric"
                + " < 0), 0) AS \"shortageMl\", coalesce(sum((e.payload->>'soldMl')::numeric), 0) AS \"soldMl\","
                + " count(*) FILTER (WHERE e.payload->>'reasonCode' IS NULL OR e.payload->>'reasonCode' IN"
                + " ('unexplained', 'unrecorded_sale')) AS \"withoutCause\" FROM \"LpEvent\" e" + w
                + " GROUP BY e.payload->>'productId' ORDER BY \"shortageMl\" ASC LIMIT 500").params(params));
        return Obj.of("from", p.from(), "to", p.to(), "rows", rows, "disclaimer", DISCLAIMER);
    }
}
