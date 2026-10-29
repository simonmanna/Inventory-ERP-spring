package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.rental.domain.RentalUnit;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Utilization, revenue and fleet KPIs (port of {@code rental-reports.service.ts}). Utilization = hired unit-days /
 * (fleet × window days) as a percentage with one decimal; revenue sums the settlement columns of closed agreements.
 */
@Service
public class RentalReportsService {

    private static final double DAY = 86_400_000d;

    private final RentalRows rows;
    private final PosDb db;

    public RentalReportsService(RentalRows rows) {
        this.rows = rows;
        this.db = rows.db();
    }

    private static Instant from(Map<String, String> q) {
        return q.get("from") != null ? RentalRows.valid(RentalRows.date(q.get("from")))
                : Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofDays(30));
    }

    private static Instant to(Map<String, String> q) {
        return q.get("to") != null ? RentalRows.valid(RentalRows.date(q.get("to"))) : Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> utilization(Map<String, String> q) {
        String orgId = RentalRows.org();
        Instant from = from(q);
        Instant to = to(q);
        long fleet = db.count(RentalUnit.class, "\"organizationId\" = ? AND \"deletedAt\" IS NULL", orgId);
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("""
                        SELECT l."dueAt", l.quantity, a."startAt"
                        FROM "RentalAgreementLine" l JOIN "RentalAgreement" a ON a.id = l."agreementId"
                        WHERE l."organizationId" = ? AND l."dueAt" > ? AND a.status::text IN ('checked_out', 'returned', 'closed')
                          AND a."startAt" < ?""")
                .params(orgId, SqlRows.ts(from), SqlRows.ts(to)));
        double hired = 0;
        for (Map<String, Object> l : lines) {
            long start = ((Instant) l.get("startAt")).toEpochMilli();
            long due = ((Instant) l.get("dueAt")).toEpochMilli();
            double s = Math.max(start, from.toEpochMilli());
            double e = Math.min(due, to.toEpochMilli());
            double days = (e - s) / DAY;
            if (days > 0) {
                hired += days * PosSql.num(l.get("quantity"));
            }
        }
        double denominator = fleet > 0 ? (to.toEpochMilli() - from.toEpochMilli()) / DAY * fleet : 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("fleet", fleet);
        out.put("totalUnitDays", RentalRows.jsNum(denominator));
        out.put("hiredUnitDays", RentalRows.jsNum(hired));
        out.put("utilization", RentalRows.jsNum(denominator > 0 ? Math.floor(hired / denominator * 1000 + 0.5) / 10 : 0));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> revenue(Map<String, String> q) {
        Instant from = from(q);
        Instant to = to(q);
        List<Map<String, Object>> agreements = db.jdbc().sql("""
                        SELECT "settlementTotal", "lateFeeTotal", "damageTotal" FROM "RentalAgreement"
                        WHERE "organizationId" = ? AND status = 'closed' AND "closedAt" >= ? AND "closedAt" <= ?""")
                .params(RentalRows.org(), SqlRows.ts(from), SqlRows.ts(to)).query().listOfRows();
        double rental = 0;
        double late = 0;
        double damage = 0;
        for (Map<String, Object> a : agreements) {
            rental += PosSql.num(a.get("settlementTotal"));
            late += PosSql.num(a.get("lateFeeTotal"));
            damage += PosSql.num(a.get("damageTotal"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("closedAgreements", agreements.size());
        out.put("rentalIncome", RentalRows.jsNum(rental));
        out.put("lateFeeIncome", RentalRows.jsNum(late));
        out.put("damageRecovery", RentalRows.jsNum(damage));
        out.put("total", RentalRows.jsNum(rental + late + damage));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> fleet(Map<String, String> q) {
        String orgId = RentalRows.org();
        Map<String, Object> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> g : db.jdbc().sql("SELECT status::text AS status, count(id) AS n FROM \"RentalUnit\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL GROUP BY status")
                .params(orgId).query().listOfRows()) {
            byStatus.put((String) g.get("status"), ((Number) g.get("n")).longValue());
        }
        StringBuilder where = new StringBuilder("\"organizationId\" = ? AND \"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (RentalUnitService.notEmpty(q.get("status"))) {
            where.append(" AND status = ?");
            params.add(q.get("status"));
        }
        List<Map<String, Object>> items = db.list(RentalUnit.class, where + " ORDER BY \"unitCode\" ASC LIMIT 500",
                params.toArray());
        for (Map<String, Object> u : items) {
            u.put("product", rows.productBrief(u.get("productId")));
            u.put("agreementLines", SqlRows.list(db.jdbc().sql("""
                            SELECT l.id, l."dueAt" FROM "RentalAgreementLine" l
                            JOIN "RentalAgreement" a ON a.id = l."agreementId"
                            WHERE l."unitId" = ? AND a.status = 'checked_out'""").params(u.get("id"))));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byStatus", byStatus);
        out.put("items", items);
        return out;
    }
}
