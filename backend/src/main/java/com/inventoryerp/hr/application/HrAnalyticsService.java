package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.truthy;

import com.inventoryerp.accounting.domain.CashSession;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.invoicing.domain.Invoice;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.domain.User;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.pos.domain.PosRefund;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * What an employee did on the floor (port of {@code hr-analytics.service.ts}): read-only aggregates over invoices,
 * refunds and cash sessions by the linked user id. Sale statuses match the POS reports.
 */
@Service
public class HrAnalyticsService {

    static final String SALE_STATUSES = "('posted', 'paid', 'refunded')";

    private final HrDb hr;

    public HrAnalyticsService(HrDb hr) {
        this.hr = hr;
    }

    /** Inclusive-start, exclusive-end window (server-local = UTC days); defaults to the last 30 days. */
    static Instant[] range(Map<String, String> query) {
        Instant toBase = truthy(query.get("to")) ? HrDb.jsDate(query.get("to")) : Instant.now();
        Instant to = toBase.atOffset(ZoneOffset.UTC).toLocalDate().atTime(LocalTime.of(23, 59, 59, 999_000_000))
                .toInstant(ZoneOffset.UTC);
        Instant fromBase = truthy(query.get("from")) ? HrDb.jsDate(query.get("from"))
                : to.minusMillis(30L * 86_400_000L);
        Instant from = fromBase.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return new Instant[] {from, to};
    }

    private Map<String, Object> agg(Class<?> entity, String sumCol, String where, Object... params) {
        HrDb.Where w = hr.where(entity).and(where, params);
        return SqlRows.one(hr.jdbc().sql("SELECT count(*) AS n, sum(" + sumCol + ") AS s FROM \"" + entity.getSimpleName()
                + "\" WHERE " + w.sql()).params(w.args()));
    }

    /** One employee's POS footprint over a window. */
    public Map<String, Object> posActivity(String employeeId, Map<String, String> query) {
        Map<String, Object> row = hr.byId(HrEmployee.class, employeeId);
        if (row == null) {
            throw ApiException.notFound("Employee not found");
        }
        Map<String, Object> employee = HrDb.pick(row, "id", "employeeCode", "firstName", "lastName", "userId",
                "employmentStatus");
        if (employee.get("userId") == null) {
            return Obj.of("employee", employee, "linked", false, "range", null, "sales", Obj.of("count", 0, "gross", 0),
                    "served", Obj.of("count", 0), "refunds", Obj.of("count", 0, "amount", 0),
                    "cashSessions", Obj.of("count", 0, "open", 0), "discountsApplied", 0,
                    "message", "This employee has no linked user account, so no POS activity can be attributed to them.");
        }
        Instant[] r = range(query);
        Object from = SqlRows.ts(r[0]);
        Object to = SqlRows.ts(r[1]);
        Object userId = employee.get("userId");
        Map<String, Object> settled = agg(Invoice.class, "\"totalAmount\"", "\"settledBy\" = ? AND status::text IN "
                + SALE_STATUSES + " AND \"createdAt\" >= ? AND \"createdAt\" < ?", userId, from, to);
        long served = hr.where(Invoice.class).and("\"waiterId\" = ? AND status::text IN " + SALE_STATUSES
                + " AND \"createdAt\" >= ? AND \"createdAt\" < ?", userId, from, to).count();
        Map<String, Object> refunds = agg(PosRefund.class, "amount", "\"approvedById\" = ? AND \"createdAt\" >= ?"
                + " AND \"createdAt\" < ?", userId, from, to);
        long sessions = hr.where(CashSession.class).and("\"userId\" = ? AND \"openedAt\" >= ? AND \"openedAt\" < ?",
                userId, from, to).count();
        long open = hr.where(CashSession.class).and("\"userId\" = ? AND status::text = 'open'", userId).count();
        long discounts = hr.where(Invoice.class).and("\"discountAppliedBy\" = ? AND \"createdAt\" >= ?"
                + " AND \"createdAt\" < ?", userId, from, to).count();
        return Obj.of("employee", employee, "linked", true, "range", Obj.of("from", r[0], "to", r[1]),
                "sales", Obj.of("count", ((Number) settled.get("n")).longValue(), "gross", PosSql.js(nz0(settled.get("s")))),
                "served", Obj.of("count", served),
                "refunds", Obj.of("count", ((Number) refunds.get("n")).longValue(),
                        "amount", PosSql.js(nz0(refunds.get("s")))),
                "cashSessions", Obj.of("count", sessions, "open", open), "discountsApplied", discounts);
    }

    private static Object nz0(Object v) {
        return v == null ? 0 : v;
    }

    /** Linked employees' sales over the window, busiest first. */
    public Map<String, Object> workforceSales(Map<String, String> query) {
        Instant[] r = range(query);
        Map<String, Object> range = Obj.of("from", r[0], "to", r[1]);
        List<Map<String, Object>> employees = hr.where(HrEmployee.class).and("\"userId\" IS NOT NULL").list(null);
        if (employees.isEmpty()) {
            return Obj.of("rows", List.of(), "total", 0, "range", range);
        }
        List<Object> userIds = new ArrayList<>();
        employees.forEach(e -> userIds.add(e.get("userId")));
        HrDb.Where w = hr.where(Invoice.class).in("settledBy", userIds).and("status::text IN " + SALE_STATUSES)
                .and("\"createdAt\" >= ? AND \"createdAt\" < ?", SqlRows.ts(r[0]), SqlRows.ts(r[1]));
        Map<Object, Map<String, Object>> byUser = new HashMap<>();
        for (Map<String, Object> g : SqlRows.list(hr.jdbc().sql("SELECT \"settledBy\", count(*) AS n,"
                + " sum(\"totalAmount\") AS s FROM \"Invoice\" WHERE " + w.sql() + " GROUP BY \"settledBy\"")
                .params(w.args()))) {
            byUser.put(g.get("settledBy"), g);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> e : employees) {
            Map<String, Object> g = byUser.get(e.get("userId"));
            rows.add(Obj.of("employeeId", e.get("id"), "employeeCode", e.get("employeeCode"),
                    "firstName", e.get("firstName"), "lastName", e.get("lastName"),
                    "salesCount", g == null ? 0L : ((Number) g.get("n")).longValue(),
                    "salesGross", PosSql.js(g == null ? 0 : nz0(g.get("s")))));
        }
        rows.sort(Comparator.comparingDouble((Map<String, Object> m) -> -PosSql.num(m.get("salesGross"))));
        return Obj.of("rows", rows, "total", rows.size(), "range", range);
    }

    /** Headcount by lifecycle state and how much of the workforce has system access. */
    public Map<String, Object> workforceAccess() {
        HrDb.Where all = hr.where(HrEmployee.class);
        List<Map<String, Object>> byStatus = SqlRows.list(hr.jdbc().sql("SELECT \"employmentStatus\"::text AS status,"
                + " count(*) AS n FROM \"HrEmployee\" WHERE " + all.sql() + " GROUP BY \"employmentStatus\"")
                .params(all.args()));
        long total = all.count();
        long linked = hr.where(HrEmployee.class).and("\"userId\" IS NOT NULL").count();
        long withPos = hr.where(User.class).eq("isActive", true)
                .and("EXISTS (SELECT 1 FROM \"HrEmployee\" e WHERE e.\"userId\" = \"User\".id)")
                .and("EXISTS (SELECT 1 FROM \"_UserRoles\" ur WHERE ur.\"B\" = \"User\".id)").count();
        List<Map<String, Object>> statuses = new ArrayList<>();
        byStatus.forEach(s -> statuses.add(Obj.of("status", s.get("status"), "count", ((Number) s.get("n")).longValue())));
        return Obj.of("total", total, "linked", linked, "unlinked", total - linked, "accountsActive", withPos,
                "byStatus", statuses);
    }
}
